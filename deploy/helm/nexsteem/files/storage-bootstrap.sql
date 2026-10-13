-- SPDX-License-Identifier: EPL-1.0
-- Bootstrap once as PostgreSQL administrator after cluster-control.sql.
CREATE TABLE IF NOT EXISTS public.nx_cluster_blob_events (
  seq bigserial PRIMARY KEY, cluster_id text NOT NULL, store_name text NOT NULL, blob_id text NOT NULL,
  epoch bigint NOT NULL, owner_id text NOT NULL, committed boolean NOT NULL DEFAULT false,
  manifest jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS nx_cluster_blob_events_scan ON public.nx_cluster_blob_events(cluster_id,store_name,seq) WHERE committed;
CREATE TABLE IF NOT EXISTS public.nx_cluster_blob_heads (
  cluster_id text NOT NULL, store_name text NOT NULL, blob_id text NOT NULL,
  seq bigint NOT NULL REFERENCES public.nx_cluster_blob_events(seq), PRIMARY KEY(cluster_id,store_name,blob_id)
);
CREATE INDEX IF NOT EXISTS nx_cluster_blob_heads_scan ON public.nx_cluster_blob_heads(cluster_id,seq);
CREATE INDEX IF NOT EXISTS nx_cluster_blob_heads_store_scan ON public.nx_cluster_blob_heads(cluster_id,store_name,seq);
CREATE TABLE IF NOT EXISTS public.nx_cluster_blob_acks (
  seq bigint NOT NULL REFERENCES public.nx_cluster_blob_events(seq), node_id text NOT NULL,
  generation text NOT NULL, acknowledged_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(seq,node_id,generation)
);
GRANT SELECT,INSERT,UPDATE,DELETE ON public.nx_cluster_blob_events,public.nx_cluster_blob_heads,public.nx_cluster_blob_acks TO nexus,nxcontrol;
GRANT USAGE,SELECT ON SEQUENCE public.nx_cluster_blob_events_seq_seq TO nexus,nxcontrol;

-- Narrow read-only GC API: passive replicas never receive general access to application tables.
CREATE OR REPLACE FUNCTION public.nx_cluster_blob_is_referenced(p_store text,p_blob text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE t record; used boolean; ref text;
BEGIN
  ref:=p_store||'@'||p_blob;
  FOR t IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    JOIN pg_attribute a ON a.attrelid=c.oid AND a.attname='blob_ref' AND NOT a.attisdropped
    WHERE n.nspname='public' AND c.relkind IN ('r','p') AND c.relname ~ '^[a-zA-Z0-9_]+_asset_blob$'
  LOOP
    EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I WHERE blob_ref=$1 OR starts_with(blob_ref,$1||''@''))',t.relname)
      INTO used USING ref;
    IF used THEN RETURN TRUE;END IF;
  END LOOP;
  RETURN FALSE;
END $$;
REVOKE EXECUTE ON FUNCTION public.nx_cluster_blob_is_referenced(text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.nx_cluster_blob_is_referenced(text,text) TO nexus,nxcontrol;

-- An SQL attachment cannot race GC: it locks the committed head and the physical object until metadata commit.
CREATE OR REPLACE FUNCTION public.nx_cluster_blob_reference_guard()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE c text; store text; blob text; sha text; state text;
BEGIN
  IF TG_OP='UPDATE' AND NEW.blob_ref IS NOT DISTINCT FROM OLD.blob_ref THEN RETURN NEW;END IF;
  c:=current_setting('nexus.cluster.id',true);
  IF c IS NULL OR c='' THEN RAISE EXCEPTION 'NX_CLUSTER_BLOB_CONNECTION_UNBOUND' USING ERRCODE='55000';END IF;
  store:=split_part(NEW.blob_ref,'@',1);blob:=split_part(NEW.blob_ref,'@',2);
  SELECT e.manifest->>'sha256',e.manifest->>'state' INTO sha,state
    FROM public.nx_cluster_blob_heads h JOIN public.nx_cluster_blob_events e ON e.seq=h.seq
    WHERE h.cluster_id=c AND h.store_name=store AND h.blob_id=blob AND e.committed FOR SHARE OF h;
  IF sha IS NULL OR state <> 'LIVE' THEN RAISE EXCEPTION 'NX_CLUSTER_BLOB_NOT_COMMITTED' USING ERRCODE='55000';END IF;
  PERFORM pg_advisory_xact_lock_shared(hashtextextended('blobgc:'||c||':'||sha,1901));
  RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION public.nx_cluster_blob_attach_guard(p_table oid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE schema_name text; table_name text;
BEGIN
  SELECT n.nspname,c.relname INTO schema_name,table_name FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE c.oid=p_table AND c.relkind IN ('r','p');
  IF schema_name='public' AND table_name='blob_store_configuration'
    AND NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid=p_table AND tgname='nx_cluster_blob_type_guard')
  THEN EXECUTE 'CREATE TRIGGER nx_cluster_blob_type_guard BEFORE INSERT OR UPDATE ON public.blob_store_configuration FOR EACH ROW EXECUTE FUNCTION public.nx_cluster_blob_type_guard()';END IF;
  IF schema_name='public' AND table_name ~ '^[a-zA-Z0-9_]+_asset_blob$'
    AND EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid=p_table AND attname='blob_ref' AND NOT attisdropped)
    AND NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid=p_table AND tgname='nx_cluster_blob_reference_guard')
  THEN EXECUTE format('CREATE TRIGGER nx_cluster_blob_reference_guard BEFORE INSERT OR UPDATE ON %I.%I FOR EACH ROW EXECUTE FUNCTION public.nx_cluster_blob_reference_guard()',schema_name,table_name);END IF;
END $$;
CREATE OR REPLACE FUNCTION public.nx_cluster_blob_type_guard()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
BEGIN
  IF NEW.type <> 'ClusterLocal' THEN RAISE EXCEPTION 'Kubernetes cluster requires a replicated ClusterLocal blob store' USING ERRCODE='23514';END IF;
  RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION public.nx_cluster_blob_ddl_end()
RETURNS event_trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE command record;
BEGIN
  FOR command IN SELECT * FROM pg_event_trigger_ddl_commands() LOOP
    IF command.object_type IN ('table','table column') AND command.schema_name='public'
    THEN PERFORM public.nx_cluster_blob_attach_guard(command.objid);END IF;
  END LOOP;
END $$;
DROP EVENT TRIGGER IF EXISTS nx_cluster_blob_ddl_end;
CREATE EVENT TRIGGER nx_cluster_blob_ddl_end ON ddl_command_end EXECUTE FUNCTION public.nx_cluster_blob_ddl_end();
DO $$ DECLARE r record;BEGIN FOR r IN SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND c.relkind IN ('r','p') LOOP PERFORM public.nx_cluster_blob_attach_guard(r.oid);END LOOP;END $$;
DO $$ BEGIN
  IF to_regclass('public.blob_store_configuration') IS NOT NULL THEN
    IF EXISTS(SELECT 1 FROM public.blob_store_configuration WHERE type <> 'ClusterLocal') THEN
      RAISE EXCEPTION 'Cluster bootstrap refuses an existing unreplicated blob store; use a fresh cluster database' USING ERRCODE='23514';
    END IF;
  END IF;
END $$;
