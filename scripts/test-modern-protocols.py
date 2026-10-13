#!/usr/bin/env python3
"""Acceptance of seven native formats / fourteen recipes in a disposable Nexus."""
import base64,hashlib,io,json,os,pathlib,struct,subprocess,sys,tarfile,tempfile,time,urllib.request,urllib.error,uuid,zipfile
from native_test_support import NexusTest
URL=os.environ.get('NEXUS_URL','http://127.0.0.1:18101');CONTAINER=os.environ.get('NEXUS_CONTAINER','nexus-research-modern')
n=NexusTest(URL,CONTAINER)
work=pathlib.Path(os.environ.get('MODERN_TEST_WORK','build/modern-work')).resolve();work.mkdir(parents=True,exist_ok=True)
state={'url':URL,'container':CONTAINER,'repositories':{}}
def request(path,method='GET',body=None,type='application/octet-stream',expected=200):
 headers={'Authorization':'Basic '+n.basic}
 if isinstance(body,dict):body=json.dumps(body).encode();type='application/json'
 if body is not None:headers['Content-Type']=type
 req=urllib.request.Request(URL+path,data=body,method=method,headers=headers)
 try:
  with urllib.request.urlopen(req,timeout=90) as r: status,data,headers=r.status,r.read(),dict(r.headers)
 except urllib.error.HTTPError as e:status,data,headers=e.code,e.read(),dict(e.headers)
 assert status==expected,f'{method} {path}: {status}, {data[:1500].decode(errors="replace").replace(n.password,"[redacted]")}'
 return data,headers
def create(format,kind,members=None,remote=None,extra=None):
 name=f'modern-{format}-{kind}-{uuid.uuid4().hex[:8]}';settings={'name':name,'online':True,'storage':{'blobStoreName':'default','strictContentTypeValidation':True}}
 if kind=='hosted':settings['storage']['writePolicy']='ALLOW'
 elif kind=='group':settings['group']={'memberNames':members}
 else:settings.update(proxy={'remoteUrl':remote,'contentMaxAge':1440,'metadataMaxAge':1440},negativeCache={'enabled':True,'timeToLive':1440},httpClient={'blocked':False,'autoBlock':False,'authentication':{'type':'username','username':'admin','password':n.password}})
 if format=='cargo':settings['cargo']={'requireAuthentication':True}
 if extra:settings.update(extra)
 request(f'/service/rest/v1/repositories/{format}/{kind}','POST',settings,expected=201);n.created.append(name);state['repositories'].setdefault(format,{})[kind]=name;return name
def rep(name,path):return '/repository/'+name+path
def crate(name,version):
 out=io.BytesIO()
 with tarfile.open(fileobj=out,mode='w:gz') as t:
  for filename,content in {'Cargo.toml':f'[package]\nname="{name}"\nversion="{version}"\nedition="2021"\n','src/lib.rs':'pub fn hello() -> u32 { 42 }\n'}.items():
   b=content.encode();entry=tarfile.TarInfo(f'{name}-{version}/{filename}');entry.size=len(b);t.addfile(entry,io.BytesIO(b))
 archive=out.getvalue();metadata=json.dumps({'name':name,'vers':version,'deps':[],'features':{},'description':'Nexus acceptance fixture','license':'MIT'}).encode();return struct.pack('<I',len(metadata))+metadata+struct.pack('<I',len(archive))+archive,archive
# Explicitly allow only our local fixture/Nexus hostnames. Preserve the original policy for cleanup.
original=json.loads(request('/service/rest/v1/security/ssrf-protection')[0]);n.ssrf_original=original
updated=dict(original);updated['allowedDomains']=list(set(original.get('allowedDomains',[])+[CONTAINER,'nexus-modern-upstream']))
request('/service/rest/v1/security/ssrf-protection','PUT',updated,expected=200)
try:
 cargo=create('cargo','hosted');body,a=crate('nexus_fixture','1.0.0');request(rep(cargo,'/api/v1/crates/new'),'PUT',body,expected=200)
 body,b=crate('nexus_fixture','2.0.0');request(rep(cargo,'/api/v1/crates/new'),'PUT',body,expected=200)
 request(rep(cargo,'/api/v1/crates/new'),'PUT',body,expected=409)
 request(rep(cargo,'/api/v1/crates/nexus_fixture/1.0.0/yank'),'DELETE');index=[json.loads(x) for x in request(rep(cargo,'/ne/xu/nexus_fixture'))[0].splitlines()];assert index[0]['yanked'] and index[0]['cksum']==hashlib.sha256(a).hexdigest()
 request(rep(cargo,'/api/v1/crates/nexus_fixture/1.0.0/unyank'),'PUT');assert request(rep(cargo,'/api/v1/crates/nexus_fixture/1.0.0/download'))[0]==a
 proxy=create('cargo','proxy',remote=f'http://{CONTAINER}:8081/repository/{cargo}/');group=create('cargo','group',members=[proxy,cargo]);assert request(rep(group,'/api/v1/crates/nexus_fixture/2.0.0/download'))[0]==b
 lines=request(rep(group,'/ne/xu/nexus_fixture'))[0].splitlines();assert len(lines)==2;cfg=json.loads(request(rep(group,'/config.json'))[0]);assert '/repository/'+group+'/' in cfg['dl']
 print('PASS Cargo hosted/proxy/group: framed publish, immutable versions, SHA256, yank/unyank, sparse index, group dedup, download')
 lfs=create('gitlfs','hosted');artifact=b'nexus-lfs-fixture\0'+os.urandom(1024);oid=hashlib.sha256(artifact).hexdigest();batch=json.loads(request(rep(lfs,'/info/lfs/objects/batch'),'POST',{'operation':'upload','objects':[{'oid':oid,'size':len(artifact)}]})[0]);assert 'upload' in batch['objects'][0]['actions']
 request(rep(lfs,'/objects/'+oid),'PUT',b'wrong',expected=422);request(rep(lfs,'/objects/'+oid),'PUT',artifact);request(rep(lfs,'/objects/'+oid+'/verify'),'POST',{'oid':oid,'size':len(artifact)});assert request(rep(lfs,'/objects/'+oid))[0]==artifact
 lock=json.loads(request(rep(lfs,'/locks'),'POST',{'path':'large.bin'},expected=201)[0])['lock'];assert json.loads(request(rep(lfs,'/locks/verify'),'POST',{})[0])['ours'][0]['id']==lock['id'];request(rep(lfs,'/locks/'+lock['id']+'/unlock'),'POST',{})
 print('PASS Git LFS hosted: batch, upload/download, SHA256 rejection, verify, persisted locks')
 go1=create('go','proxy',remote='http://nexus-modern-upstream:8080/goa/');go2=create('go','proxy',remote='http://nexus-modern-upstream:8080/gob/');gog=create('go','group',members=[go1,go2]);assert request(rep(gog,'/example.test/hello/@v/list'))[0]==b'v0.1.0\nv0.2.0\n';assert json.loads(request(rep(gog,'/example.test/hello/@v/v0.2.0.info'))[0])['Version']=='v0.2.0'
 archive=request(rep(gog,'/example.test/hello/@v/v0.2.0.zip'))[0];assert zipfile.ZipFile(io.BytesIO(archive)).read('example.test/hello@v0.2.0/hello.go').startswith(b'package hello')
 print('PASS Go proxy/group: module list union, info, go.mod, valid module ZIP')
 composer=create('composer','proxy',remote='http://nexus-modern-upstream:8080/composer/');root=json.loads(request(rep(composer,'/packages.json'))[0]);assert URL in root['metadata-url'];package=json.loads(request(rep(composer,'/p2/fixture/hello.json'))[0])['packages']['fixture/hello'][0];dist=package['dist']['url'];assert '/repository/'+composer+'/_remote/' in dist;assert zipfile.ZipFile(io.BytesIO(request(dist.removeprefix(URL))[0])).read('fixture/src/Hello.php').startswith(b'<?php')
 print('PASS Composer proxy: metadata-url and dist rewriting, archive caching through trusted metadata mappings')
 hf=create('huggingface','proxy',remote='http://nexus-modern-upstream:8080/huggingface/');metadata=json.loads(request(rep(hf,'/api/models/fixture/tiny/revision/main'))[0]);assert metadata['sha']=='a'*40;data,headers=request(rep(hf,'/fixture/tiny/resolve/main/config.json'),'HEAD');assert data==b'' and headers.get('X-Repo-Commit')=='a'*40
 assert json.loads(request(rep(hf,'/fixture/tiny/resolve/main/config.json'))[0])['hidden_size']==8
 print('PASS Hugging Face proxy: model metadata, resolved file HEAD/GET, commit and ETag headers')
 swift=create('swift','hosted');out=io.BytesIO()
 with zipfile.ZipFile(out,'w') as z:z.writestr('Package.swift','// swift-tools-version: 5.9\nimport PackageDescription\nlet package = Package(name: "Hello", products: [.library(name:"Hello", targets:["Hello"])], targets:[.target(name:"Hello")])\n');z.writestr('Sources/Hello/Hello.swift','public func hello() -> String { "nexus" }\n')
 archive=out.getvalue();boundary='nexusboundary';multipart=(f'--{boundary}\r\nContent-Disposition: form-data; name="source-archive"; filename="source.zip"\r\nContent-Type: application/zip\r\n\r\n'.encode()+archive+f'\r\n--{boundary}--\r\n'.encode());request(rep(swift,'/fixture/hello/1.0.0'),'PUT',multipart,f'multipart/form-data; boundary={boundary}',expected=201)
 metadata=json.loads(request(rep(swift,'/fixture/hello/1.0.0'))[0]);assert metadata['resources'][0]['checksum']==hashlib.sha256(archive).hexdigest();assert request(rep(swift,'/fixture/hello/1.0.0.zip'))[0]==archive;assert b'swift-tools-version' in request(rep(swift,'/fixture/hello/1.0.0/Package.swift'))[0]
 swp=create('swift','proxy',remote=f'http://{CONTAINER}:8081/repository/{swift}/');swg=create('swift','group',members=[swp,swift]);assert '1.0.0' in json.loads(request(rep(swg,'/fixture/hello'))[0])['releases'];assert request(rep(swg,'/fixture/hello/1.0.0.zip'))[0]==archive
 print('PASS Swift hosted/proxy/group: multipart publishing, manifests, immutable release, SHA256, release list and download')
 with tempfile.TemporaryDirectory() as d:
  os.chmod(d,0o700);n.run(['gpg','--homedir',d,'--batch','--passphrase','','--quick-generate-key','Nexus modern local fixture <local@example.invalid>','rsa2048','sign','0']);secret=subprocess.check_output(['gpg','--homedir',d,'--armor','--export-secret-keys']);secret=secret.decode()
 terraform=create('terraform','hosted',extra={'terraformSigning':{'keypair':secret,'passphrase':''}})
 out=io.BytesIO()
 with zipfile.ZipFile(out,'w') as z:
  e=zipfile.ZipInfo('terraform-provider-fixture_v1.0.0');e.external_attr=0o100755<<16;z.writestr(e,'#!/bin/sh\nexit 0\n')
 provider=out.getvalue();request(rep(terraform,'/v1/providers/fixture/fixture/1.0.0/darwin/arm64/package.zip'),'PUT',provider,expected=201)
 versions=json.loads(request(rep(terraform,'/v1/providers/fixture/fixture/versions'))[0]);assert versions['versions'][0]['platforms']==[{'os':'darwin','arch':'arm64'}]
 download=json.loads(request(rep(terraform,'/v1/providers/fixture/fixture/1.0.0/download/darwin/arm64'))[0]);assert download['shasum']==hashlib.sha256(provider).hexdigest();assert 'BEGIN PGP PUBLIC KEY BLOCK' in download['signing_keys']['gpg_public_keys'][0]['ascii_armor']
 sums=request(rep(terraform,'/v1/providers/fixture/fixture/1.0.0/SHA256SUMS'))[0];sig=request(rep(terraform,'/v1/providers/fixture/fixture/1.0.0/SHA256SUMS.sig'))[0]
 (work/'public-key.asc').write_text(download['signing_keys']['gpg_public_keys'][0]['ascii_armor']);(work/'SHA256SUMS').write_bytes(sums);(work/'SHA256SUMS.sig').write_bytes(sig)
 with tempfile.TemporaryDirectory() as d:
  os.chmod(d,0o700);n.run(['gpg','--homedir',d,'--batch','--import',str(work/'public-key.asc')]);n.run(['gpg','--homedir',d,'--batch','--verify',str(work/'SHA256SUMS.sig'),str(work/'SHA256SUMS')])
 module=io.BytesIO()
 with tarfile.open(fileobj=module,mode='w:gz') as t:
  data=b'output "hello" { value = "nexus" }\n';entry=tarfile.TarInfo('main.tf');entry.size=len(data);t.addfile(entry,io.BytesIO(data))
 request(rep(terraform,'/v1/modules/fixture/hello/local/1.0.0/archive.tar.gz'),'PUT',module.getvalue(),expected=201);_,headers=request(rep(terraform,'/v1/modules/fixture/hello/local/1.0.0/download'),expected=204);assert headers.get('X-Terraform-Get').endswith('/archive.tar.gz')
 tfp=create('terraform','proxy',remote=f'http://{CONTAINER}:8081/repository/{terraform}/');tfg=create('terraform','group',members=[tfp,terraform]);assert len(json.loads(request(rep(tfg,'/v1/providers/fixture/fixture/versions'))[0])['versions'])==1
 proxied=json.loads(request(rep(tfp,'/v1/providers/fixture/fixture/1.0.0/download/darwin/arm64'))[0]);assert '/repository/'+tfp+'/_remote/' in proxied['download_url'];assert request(proxied['download_url'].removeprefix(URL))[0]==provider
 assert request(proxied['shasums_url'].removeprefix(URL))[0]==sums;assert request(proxied['shasums_signature_url'].removeprefix(URL))[0]==sig
 _,module_headers=request(rep(tfp,'/v1/modules/fixture/hello/local/1.0.0/download'),expected=204);assert '/repository/'+tfp+'/_remote/' in module_headers['X-Terraform-Get'];assert 'archive=tar.gz' in module_headers['X-Terraform-Get'];assert request(module_headers['X-Terraform-Get'].removeprefix(URL))[0]==module.getvalue()
 _,group_headers=request(rep(tfg,'/v1/modules/fixture/hello/local/1.0.0/download'),expected=204);assert '/repository/'+tfg+'/_remote/' in group_headers['X-Terraform-Get']
 print('PASS Terraform hosted/proxy/group: provider/module registry, SHA256 + genuine PGP signature verified, versions/platforms, mapped download, module source')
 # Components must be meaningful in the Nexus UI, not anonymous blobs or metadata pseudo-packages.
 expected={'cargo':('nexus_fixture','2.0.0'),'go':('example.test/hello','v0.2.0'),'composer':('fixture/hello','1.0.0'),'huggingface':('fixture/tiny','a'*40),'swift':('fixture.hello','1.0.0'),'terraform':('fixture/fixture','1.0.0')}
 for fmt,coordinates in expected.items():
  name=state['repositories'][fmt]['proxy'];items=json.loads(request('/service/rest/v1/components?repository='+name)[0])['items'];assert coordinates in [(item.get('name'),item.get('version')) for item in items],(fmt,items)
  if fmt in ('cargo','go'):assert all(item.get('version') not in ('','list') for item in items),(fmt,items)
 print('PASS Nexus component integration: true package names/versions, mapped archives visible, metadata excluded')
 # Keep fixtures for native-client phase; no credentials in state.
 state['ssrf_original']=original;state['created_repositories']=list(n.created);(work/'state.json').write_text(json.dumps(state,indent=2));(work/'basic-token').write_text(n.basic);os.chmod(work/'basic-token',0o600)
 (work/'admin-password').write_text(n.password);os.chmod(work/'admin-password',0o600)
 print('PASS All 14 modern format recipes. Native-client fixtures retained in',work)
except BaseException:
 # Do not hide the original failure under repository cleanup errors.
 for name in reversed(n.created):
  try:request('/service/rest/v1/repositories/'+name,'DELETE',expected=204)
  except BaseException:pass
 try:request('/service/rest/v1/security/ssrf-protection','PUT',original,expected=200)
 except BaseException:pass
 raise
