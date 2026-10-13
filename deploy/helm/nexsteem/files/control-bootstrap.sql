-- SPDX-License-Identifier: EPL-1.0
-- Install in the dedicated Nexus PostgreSQL database as the bootstrap superuser.
-- Runtime nxcontrol is distinct from the trusted, non-superuser nexus Core role.
CREATE TABLE IF NOT EXISTS public.nx_cluster_leader (
  cluster_id text PRIMARY KEY, epoch bigint NOT NULL CHECK(epoch >= 0), leader_id text NOT NULL,
  lease_until timestamptz NOT NULL, endpoint text NOT NULL, phase text NOT NULL
    CHECK(phase IN ('FENCING','STARTING','READY'))
);
CREATE TABLE IF NOT EXISTS public.nx_cluster_lease (
  cluster_id text PRIMARY KEY REFERENCES public.nx_cluster_leader(cluster_id),
  epoch bigint NOT NULL, leader_id text NOT NULL, lease_until timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS public.nx_cluster_policy (
  cluster_id text PRIMARY KEY REFERENCES public.nx_cluster_leader(cluster_id), epoch bigint NOT NULL,
  required_copies integer NOT NULL CHECK(required_copies >= 1), require_remote_dc boolean NOT NULL,
  degraded boolean NOT NULL, proof_revision text NOT NULL,
  CHECK(NOT degraded OR (proof_revision <> '' AND NOT require_remote_dc))
);
CREATE TABLE IF NOT EXISTS public.nx_cluster_release (
  cluster_id text PRIMARY KEY, application_sha256 text NOT NULL CHECK(application_sha256 ~ '^[a-f0-9]{64}$'),
  version text NOT NULL
);
CREATE TABLE IF NOT EXISTS public.nx_cluster_tx_guard (
  tx_id bigint NOT NULL, cluster_id text NOT NULL, owner_id text NOT NULL, epoch bigint NOT NULL,
  PRIMARY KEY(tx_id, cluster_id)
);

CREATE OR REPLACE FUNCTION public.nx_cluster_lock_key(p_cluster text) RETURNS bigint
  LANGUAGE sql IMMUTABLE AS $$ SELECT hashtextextended(p_cluster, 5411429); $$;

CREATE OR REPLACE FUNCTION public.nx_cluster_assert_current(p_cluster text,p_owner text,p_epoch bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE valid boolean;
BEGIN
  SELECT TRUE INTO valid FROM public.nx_cluster_leader l
    JOIN public.nx_cluster_lease s USING(cluster_id)
    WHERE l.cluster_id=p_cluster AND l.epoch=p_epoch AND l.leader_id=p_owner
      AND s.epoch=p_epoch AND s.leader_id=p_owner AND s.lease_until > clock_timestamp();
  IF valid IS DISTINCT FROM TRUE OR pg_is_in_recovery() OR current_setting('transaction_read_only')='on'
  THEN RAISE EXCEPTION 'NX_CLUSTER_FENCED' USING ERRCODE='55000'; END IF;
END $$;

CREATE OR REPLACE FUNCTION public.nx_cluster_bind(p_cluster text,p_owner text,p_epoch bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
BEGIN
  PERFORM public.nx_cluster_assert_current(p_cluster,p_owner,p_epoch);
  PERFORM set_config('nexus.cluster.id',p_cluster,false);
  PERFORM set_config('nexus.cluster.owner',p_owner,false);
  PERFORM set_config('nexus.cluster.epoch',p_epoch::text,false);
  PERFORM set_config('application_name','nxcore:'||substr(md5(p_cluster),1,12)||':'||substr(md5(p_owner),1,16)||':'||p_epoch,false);
END $$;

CREATE OR REPLACE FUNCTION public.nx_cluster_begin_write(p_cluster text,p_owner text,p_epoch bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE state text;
BEGIN
  -- Xact locks drain before takeover; heartbeat deliberately uses another table.
  PERFORM pg_advisory_xact_lock_shared(public.nx_cluster_lock_key(p_cluster));
  SELECT phase INTO state FROM public.nx_cluster_leader
    WHERE cluster_id=p_cluster AND epoch=p_epoch AND leader_id=p_owner FOR SHARE;
  PERFORM public.nx_cluster_assert_current(p_cluster,p_owner,p_epoch);
  IF state IS NULL OR state NOT IN ('STARTING','READY')
  THEN RAISE EXCEPTION 'NX_CLUSTER_WRITER_NOT_STARTED' USING ERRCODE='55000'; END IF;
  INSERT INTO public.nx_cluster_tx_guard(tx_id,cluster_id,owner_id,epoch)
    VALUES(txid_current(),p_cluster,p_owner,p_epoch) ON CONFLICT DO NOTHING;
END $$;

CREATE OR REPLACE FUNCTION public.nx_cluster_commit_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
BEGIN
  PERFORM public.nx_cluster_assert_current(NEW.cluster_id,NEW.owner_id,NEW.epoch);
  -- No unbounded audit row accumulation; this insert and delete share the transaction.
  DELETE FROM public.nx_cluster_tx_guard WHERE tx_id=NEW.tx_id AND cluster_id=NEW.cluster_id;
  RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS nx_cluster_commit_guard ON public.nx_cluster_tx_guard;
CREATE CONSTRAINT TRIGGER nx_cluster_commit_guard AFTER INSERT ON public.nx_cluster_tx_guard
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.nx_cluster_commit_guard();

CREATE OR REPLACE FUNCTION public.nx_cluster_guard_bound() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE c text; o text; e text;
BEGIN
  c:=current_setting('nexus.cluster.id',true);o:=current_setting('nexus.cluster.owner',true);
  e:=current_setting('nexus.cluster.epoch',true);
  IF c IS NULL OR c='' OR o IS NULL OR o='' OR e IS NULL OR e !~ '^[0-9]+$'
  THEN RAISE EXCEPTION 'NX_CLUSTER_CONNECTION_UNBOUND' USING ERRCODE='55000'; END IF;
  PERFORM public.nx_cluster_begin_write(c,o,e::bigint);
END $$;

CREATE OR REPLACE FUNCTION public.nx_cluster_statement_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
BEGIN
  PERFORM public.nx_cluster_guard_bound();RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION public.nx_cluster_attach_guard(p_table oid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE schema_name text; table_name text;
BEGIN
  SELECT n.nspname,c.relname INTO schema_name,table_name FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE c.oid=p_table AND c.relkind IN ('r','p');
  IF schema_name='public' AND table_name NOT LIKE 'nx\_cluster\_%' ESCAPE '\'
    AND NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid=p_table AND tgname='nx_cluster_statement_guard')
  THEN EXECUTE format('CREATE TRIGGER nx_cluster_statement_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON %I.%I FOR EACH STATEMENT EXECUTE FUNCTION public.nx_cluster_statement_guard()',schema_name,table_name);END IF;
END $$;

-- DDL admission is checked before execution, including migrations and runtime DAO creation.
CREATE OR REPLACE FUNCTION public.nx_cluster_ddl_begin() RETURNS event_trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE c text; o text; e text; previous text; key bigint;
BEGIN
  IF session_user <> 'nexus' THEN RETURN; END IF;
  -- A previous failed utility command may not emit ddl_command_end. The next command
  -- on that same JDBC connection can discard its abandoned session lock safely.
  previous:=current_setting('nexus.cluster.ddl_lock_key',true);
  IF previous IS NOT NULL AND previous<>'' THEN
    PERFORM pg_advisory_unlock_shared(previous::bigint);
    PERFORM set_config('nexus.cluster.ddl_lock_key','',false);
  END IF;
  IF TG_TAG IN ('CREATE INDEX','DROP INDEX','REINDEX') THEN
    c:=current_setting('nexus.cluster.id',true);o:=current_setting('nexus.cluster.owner',true);e:=current_setting('nexus.cluster.epoch',true);
    IF c IS NULL OR c='' OR o IS NULL OR o='' OR e IS NULL OR e !~ '^[0-9]+$'
    THEN RAISE EXCEPTION 'NX_CLUSTER_CONNECTION_UNBOUND' USING ERRCODE='55000';END IF;
    key:=public.nx_cluster_lock_key(c);
    -- CONCURRENTLY uses internal commits and DROP refuses an already assigned XID.
    -- A readonly admission + session lock spans those commits without assigning XID.
    PERFORM pg_advisory_lock_shared(key);
    BEGIN
      PERFORM public.nx_cluster_assert_current(c,o,e::bigint);
      PERFORM set_config('nexus.cluster.ddl_lock_key',key::text,false);
    EXCEPTION WHEN OTHERS THEN
      PERFORM pg_advisory_unlock_shared(key);RAISE;
    END;
  ELSE
    PERFORM public.nx_cluster_guard_bound();
  END IF;
END $$;
CREATE OR REPLACE FUNCTION public.nx_cluster_ddl_end() RETURNS event_trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE command record; key text;
BEGIN
  IF session_user='nexus' THEN
    key:=current_setting('nexus.cluster.ddl_lock_key',true);
    IF key IS NOT NULL AND key<>'' THEN
      BEGIN
        -- Transfer protection from the session lock to the final xact/COMMIT guard.
        PERFORM public.nx_cluster_guard_bound();
        PERFORM pg_advisory_unlock_shared(key::bigint);
        PERFORM set_config('nexus.cluster.ddl_lock_key','',false);
      EXCEPTION WHEN OTHERS THEN
        PERFORM pg_advisory_unlock_shared(key::bigint);
        PERFORM set_config('nexus.cluster.ddl_lock_key','',false);RAISE;
      END;
    END IF;
  END IF;
  FOR command IN SELECT * FROM pg_event_trigger_ddl_commands() LOOP
    IF command.object_type IN ('table','table column') AND command.schema_name='public'
    THEN PERFORM public.nx_cluster_attach_guard(command.objid);END IF;
  END LOOP;
END $$;
DROP EVENT TRIGGER IF EXISTS nx_cluster_ddl_begin;
DROP EVENT TRIGGER IF EXISTS nx_cluster_ddl_end;
CREATE EVENT TRIGGER nx_cluster_ddl_begin ON ddl_command_start EXECUTE FUNCTION public.nx_cluster_ddl_begin();
CREATE EVENT TRIGGER nx_cluster_ddl_end ON ddl_command_end EXECUTE FUNCTION public.nx_cluster_ddl_end();
DO $$ DECLARE r record;BEGIN FOR r IN SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND c.relkind IN ('r','p') LOOP PERFORM public.nx_cluster_attach_guard(r.oid);END LOOP;END $$;

DROP FUNCTION IF EXISTS public.nx_cluster_try_acquire(text,text,text,text,timestamptz,integer);
CREATE OR REPLACE FUNCTION public.nx_cluster_try_acquire(p_cluster text,p_owner text,p_endpoint text,
  p_proof_revision text,p_proof_until timestamptz,p_lease_seconds integer DEFAULT 30,p_application_sha256 text DEFAULT NULL,p_version text DEFAULT NULL) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE term bigint; valid_until timestamptz;
BEGIN
  IF p_proof_revision IS NULL OR p_proof_revision='' OR p_proof_until IS NULL OR p_proof_until<=clock_timestamp() OR p_lease_seconds<3 OR p_lease_seconds>60
  THEN RAISE EXCEPTION 'NX_CLUSTER_AUTHORITY_PROOF_INVALID' USING ERRCODE='55000';END IF;
  PERFORM pg_advisory_xact_lock(public.nx_cluster_lock_key(p_cluster));
  IF p_proof_until<=clock_timestamp() THEN RAISE EXCEPTION 'NX_CLUSTER_AUTHORITY_PROOF_EXPIRED' USING ERRCODE='55000';END IF;
  IF p_application_sha256 IS NULL OR p_application_sha256 !~ '^[a-f0-9]{64}$' OR p_version IS NULL OR p_version=''
  THEN RAISE EXCEPTION 'NX_CLUSTER_RELEASE_IDENTITY_REQUIRED' USING ERRCODE='55000';END IF;
  INSERT INTO public.nx_cluster_release VALUES(p_cluster,p_application_sha256,p_version) ON CONFLICT DO NOTHING;
  IF NOT EXISTS(SELECT 1 FROM public.nx_cluster_release WHERE cluster_id=p_cluster AND application_sha256=p_application_sha256 AND version=p_version) THEN RETURN NULL;END IF;
  IF EXISTS(SELECT 1 FROM public.nx_cluster_lease WHERE cluster_id=p_cluster AND lease_until>clock_timestamp()) THEN RETURN NULL;END IF;
  SELECT epoch+1 INTO term FROM public.nx_cluster_leader WHERE cluster_id=p_cluster FOR UPDATE;
  term:=coalesce(term,1);valid_until:=least(p_proof_until,clock_timestamp()+make_interval(secs=>p_lease_seconds));
  INSERT INTO public.nx_cluster_leader VALUES(p_cluster,term,p_owner,valid_until,p_endpoint,'FENCING')
    ON CONFLICT(cluster_id) DO UPDATE SET epoch=EXCLUDED.epoch,leader_id=EXCLUDED.leader_id,
      lease_until=EXCLUDED.lease_until,endpoint=EXCLUDED.endpoint,phase=EXCLUDED.phase;
  INSERT INTO public.nx_cluster_lease VALUES(p_cluster,term,p_owner,valid_until)
    ON CONFLICT(cluster_id) DO UPDATE SET epoch=EXCLUDED.epoch,leader_id=EXCLUDED.leader_id,lease_until=EXCLUDED.lease_until;
  INSERT INTO public.nx_cluster_policy VALUES(p_cluster,term,2,true,false,p_proof_revision)
    ON CONFLICT(cluster_id) DO UPDATE SET epoch=EXCLUDED.epoch,required_copies=2,require_remote_dc=true,degraded=false,proof_revision=p_proof_revision;
  RETURN term;
END $$;

DROP FUNCTION IF EXISTS public.nx_cluster_renew(text,text,bigint,text,timestamptz,integer);
CREATE OR REPLACE FUNCTION public.nx_cluster_renew(p_cluster text,p_owner text,p_epoch bigint,
  p_proof_revision text,p_proof_until timestamptz,p_lease_seconds integer DEFAULT 30,p_application_sha256 text DEFAULT NULL) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE changed integer;
BEGIN
  IF p_proof_revision IS NULL OR p_proof_revision='' OR p_proof_until IS NULL OR p_proof_until<=clock_timestamp() OR p_lease_seconds<3 OR p_lease_seconds>60 THEN RETURN FALSE;END IF;
  UPDATE public.nx_cluster_lease s SET lease_until=least(p_proof_until,clock_timestamp()+make_interval(secs=>p_lease_seconds))
    WHERE s.cluster_id=p_cluster AND s.epoch=p_epoch AND s.leader_id=p_owner AND s.lease_until>clock_timestamp()
      AND EXISTS(SELECT 1 FROM public.nx_cluster_leader l WHERE l.cluster_id=p_cluster AND l.epoch=p_epoch AND l.leader_id=p_owner)
      AND EXISTS(SELECT 1 FROM public.nx_cluster_release r WHERE r.cluster_id=p_cluster AND r.application_sha256=p_application_sha256);
  GET DIAGNOSTICS changed=ROW_COUNT;RETURN changed=1;
END $$;

CREATE OR REPLACE FUNCTION public.nx_cluster_phase(p_cluster text,p_owner text,p_epoch bigint,p_phase text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
BEGIN
  PERFORM public.nx_cluster_assert_current(p_cluster,p_owner,p_epoch);
  UPDATE public.nx_cluster_leader SET phase=p_phase WHERE cluster_id=p_cluster AND epoch=p_epoch AND leader_id=p_owner;
END $$;

CREATE OR REPLACE FUNCTION public.nx_cluster_set_policy(p_cluster text,p_owner text,p_epoch bigint,
  p_copies integer,p_remote boolean,p_degraded boolean,p_proof_revision text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(public.nx_cluster_lock_key(p_cluster));
  PERFORM public.nx_cluster_assert_current(p_cluster,p_owner,p_epoch);
  IF p_proof_revision='' OR p_copies<1 OR (NOT p_degraded AND (p_copies<2 OR NOT p_remote)) OR (p_degraded AND p_remote)
  THEN RAISE EXCEPTION 'NX_CLUSTER_POLICY_INVALID' USING ERRCODE='55000';END IF;
  UPDATE public.nx_cluster_policy SET required_copies=p_copies,require_remote_dc=p_remote,
    degraded=p_degraded,proof_revision=p_proof_revision WHERE cluster_id=p_cluster AND epoch=p_epoch;
END $$;

CREATE OR REPLACE FUNCTION public.nx_cluster_fence_expired(p_cluster text) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE owner text; term bigint; killed integer:=0; r record;
BEGIN
  SELECT l.leader_id,l.epoch INTO owner,term FROM public.nx_cluster_leader l JOIN public.nx_cluster_lease s USING(cluster_id)
    WHERE l.cluster_id=p_cluster AND s.lease_until<=clock_timestamp();
  IF owner IS NULL THEN RETURN 0;END IF;
  FOR r IN SELECT pid FROM pg_stat_activity WHERE usename='nexus' AND pid<>pg_backend_pid()
    AND application_name='nxcore:'||substr(md5(p_cluster),1,12)||':'||substr(md5(owner),1,16)||':'||term
  LOOP IF pg_terminate_backend(r.pid) THEN killed:=killed+1;END IF;END LOOP;
  RETURN killed;
END $$;

REVOKE ALL ON public.nx_cluster_leader,public.nx_cluster_lease,public.nx_cluster_policy,public.nx_cluster_tx_guard,public.nx_cluster_release FROM PUBLIC;
GRANT SELECT ON public.nx_cluster_leader,public.nx_cluster_lease,public.nx_cluster_policy,public.nx_cluster_release TO nexus,nxcontrol;
-- Functions are SECURITY DEFINER; nxcontrol has no direct application DML or superuser rights.
REVOKE EXECUTE ON FUNCTION public.nx_cluster_try_acquire(text,text,text,text,timestamptz,integer,text,text),
  public.nx_cluster_renew(text,text,bigint,text,timestamptz,integer,text),
  public.nx_cluster_phase(text,text,bigint,text),public.nx_cluster_set_policy(text,text,bigint,integer,boolean,boolean,text),
  public.nx_cluster_fence_expired(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.nx_cluster_try_acquire(text,text,text,text,timestamptz,integer,text,text),
  public.nx_cluster_renew(text,text,bigint,text,timestamptz,integer,text),
  public.nx_cluster_phase(text,text,bigint,text),public.nx_cluster_set_policy(text,text,bigint,integer,boolean,boolean,text),
  public.nx_cluster_fence_expired(text) TO nxcontrol;

CREATE OR REPLACE FUNCTION public.nx_cluster_release(p_cluster text,p_owner text,p_epoch bigint)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE changed integer;
BEGIN
  UPDATE public.nx_cluster_lease SET lease_until=clock_timestamp()
    WHERE cluster_id=p_cluster AND epoch=p_epoch AND leader_id=p_owner;
  GET DIAGNOSTICS changed=ROW_COUNT;RETURN changed=1;
END $$;
REVOKE EXECUTE ON FUNCTION public.nx_cluster_release(text,text,bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.nx_cluster_release(text,text,bigint) TO nxcontrol;

-- Narrow diagnostic surface; nxcontrol does not receive pg_monitor or unrestricted operator privileges.
CREATE OR REPLACE FUNCTION public.nx_cluster_pg_status() RETURNS jsonb
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
SELECT jsonb_build_object('node',current_setting('nx_cluster.node_id'),'dc',current_setting('nx_cluster.dc'),
 'syncNames',current_setting('synchronous_standby_names'),'syncCommit',current_setting('synchronous_commit'),
 'peers',coalesce((SELECT jsonb_agg(jsonb_build_object('node',application_name,'state',state,'sync',sync_state))
   FROM pg_stat_replication),'[]'::jsonb));
$$;
REVOKE EXECUTE ON FUNCTION public.nx_cluster_pg_status() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.nx_cluster_pg_status() TO nxcontrol;

-- Upgrader invokes only after the new passive members have verified all committed heads.
-- Epoch/lease fencing is atomic with the release barrier; old code cannot run new migrations.
CREATE OR REPLACE FUNCTION public.nx_cluster_release_set(p_cluster text,p_expected text,p_next text,p_version text,
  p_proof_revision text,p_proof_until timestamptz) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
BEGIN
  IF p_next IS NULL OR p_next !~ '^[a-f0-9]{64}$' OR p_version IS NULL OR p_version='' OR p_proof_revision IS NULL OR p_proof_revision='' OR p_proof_until IS NULL OR p_proof_until<=clock_timestamp()
    OR pg_is_in_recovery() THEN RAISE EXCEPTION 'NX_CLUSTER_RELEASE_PROOF_INVALID' USING ERRCODE='55000';END IF;
  PERFORM pg_advisory_xact_lock(public.nx_cluster_lock_key(p_cluster));
  IF p_proof_until<=clock_timestamp() THEN RAISE EXCEPTION 'NX_CLUSTER_RELEASE_PROOF_EXPIRED' USING ERRCODE='55000';END IF;
  IF EXISTS(SELECT 1 FROM public.nx_cluster_release WHERE cluster_id=p_cluster AND application_sha256=p_next AND version=p_version) THEN RETURN;END IF;
  IF NOT EXISTS(SELECT 1 FROM public.nx_cluster_release WHERE cluster_id=p_cluster AND application_sha256=p_expected)
  THEN RAISE EXCEPTION 'NX_CLUSTER_RELEASE_CONFLICT' USING ERRCODE='55000';END IF;
  UPDATE public.nx_cluster_release SET application_sha256=p_next,version=p_version WHERE cluster_id=p_cluster;
  UPDATE public.nx_cluster_lease SET lease_until=clock_timestamp() WHERE cluster_id=p_cluster;
END $$;
REVOKE EXECUTE ON FUNCTION public.nx_cluster_release_set(text,text,text,text,text,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.nx_cluster_release_set(text,text,text,text,text,timestamptz) TO nxcontrol;
