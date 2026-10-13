#!/usr/bin/env python3
"""Native curl/Maven/APT acceptance for all eight recipes from public Core, on a disposable Nexus."""
import argparse,hashlib,io,json,os,tempfile,zipfile
from pathlib import Path
from urllib.parse import urlsplit
from native_test_support import NexusTest
p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:18090');p.add_argument('--container',default='nexus-research-historical');p.add_argument('--only',choices=['raw','maven','apt']);a=p.parse_args();t=NexusTest(a.url,a.container)
def repository(format_name,kind,members=None,remote=None,extra=None):
 name='core-'+format_name+'-'+kind+'-'+t.suffix;cfg={'name':name,'online':True,'storage':{'blobStoreName':'default','strictContentTypeValidation':True}}
 if kind=='hosted':cfg['storage']['writePolicy']='ALLOW'
 elif kind=='group':cfg['group']={'memberNames':members}
 else:
  host=urlsplit(remote).hostname
  assert host==t.container and (host.startswith('nexus-research-')or host=='nexus-ha-dc1-1'),'Fixture proxy must remain on our explicitly authorized test container'
  _,raw=t.request('/service/rest/v1/security/ssrf-protection');ssrf=json.loads(raw)
  if host not in ssrf.get('allowedDomains',[]):
   if t.ssrf_original is None:t.ssrf_original=dict(ssrf)
   ssrf['allowedDomains']=ssrf.get('allowedDomains',[])+[host];t.request('/service/rest/v1/security/ssrf-protection','PUT',ssrf)
  cfg.update({'proxy':{'remoteUrl':remote,'contentMaxAge':1440,'metadataMaxAge':1440},'negativeCache':{'enabled':True,'timeToLive':1440},'httpClient':{'blocked':False,'autoBlock':False,'authentication':{'type':'username','username':'admin','password':t.password}}})
 if extra:cfg.update(extra)
 t.request('/service/rest/v1/repositories/'+format_name+'/'+kind,'POST',cfg);t.created.append(name);return name
with tempfile.TemporaryDirectory(prefix='core-native-',dir=Path(__file__).resolve().parents[1]/'build')as d:
 root=Path(d);curlcfg=root/'curl.conf';curlcfg.write_text('user = "admin:'+t.password.replace('\\','\\\\').replace('"','\\"')+'"\n');curlcfg.chmod(0o600)
 try:
  if a.only in(None,'raw'):
   hosted=repository('raw','hosted');proxy=repository('raw','proxy',remote='http://'+t.container+':8081/repository/'+hosted+'/');group=repository('raw','group',members=[hosted,proxy]);rawzip=io.BytesIO()
   with zipfile.ZipFile(rawzip,'w',zipfile.ZIP_DEFLATED)as archive:archive.writestr('native.txt','native-raw-ok\n')
   blob=rawzip.getvalue();artifact=root/'raw.zip';artifact.write_bytes(blob)
   t.run(['curl','-fsS','--config',str(curlcfg),'--header','Content-Type: application/zip','--upload-file',str(artifact),t.url+'/repository/'+hosted+'/data/probe.zip'])
   for repo in(hosted,proxy,group):
    downloaded=root/(repo+'.bin');t.run(['curl','-fsS','--config',str(curlcfg),'--output',str(downloaded),t.url+'/repository/'+repo+'/data/probe.zip']);assert downloaded.read_bytes()==blob
    part=root/(repo+'.partial');t.run(['curl','-fsS','--config',str(curlcfg),'--range','1-4','--output',str(part),t.url+'/repository/'+repo+'/data/probe.zip']);assert part.read_bytes()==blob[1:5]
   print('PASS Core raw: native curl PUT/GET/Range hosted/proxy/group; SHA256',hashlib.sha256(blob).hexdigest(),flush=True)
  if a.only in(None,'maven'):
   settings={'maven':{'versionPolicy':'RELEASE','layoutPolicy':'STRICT','contentDisposition':'INLINE'}}
   hosted=repository('maven','hosted',extra=settings);proxy=repository('maven','proxy',remote='http://'+t.container+':8081/repository/'+hosted+'/',extra=settings);group=repository('maven','group',members=[hosted,proxy],extra=settings)
   artifact='nexus-core-'+t.suffix;coordinates='org.nexus.acceptance:'+artifact+':1.0.0';out=io.BytesIO()
   with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED)as jar:jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\n\n');jar.writestr('native.txt','native-maven-ok\n')
   jarbytes=out.getvalue();versions=['1.0.0','1.0.1','1.0.2']
   for version in versions:
    base='/repository/'+hosted+'/org/nexus/acceptance/'+artifact+'/'+version+'/'+artifact+'-'+version
    pom=f'<project><modelVersion>4.0.0</modelVersion><groupId>org.nexus.acceptance</groupId><artifactId>{artifact}</artifactId><version>{version}</version></project>'.encode()
    t.request(base+'.jar','PUT',jarbytes,'application/java-archive');t.request(base+'.pom','PUT',pom,'application/xml')
    for extension,value in[('.jar',jarbytes),('.pom',pom)]:t.request(base+extension+'.sha1','PUT',hashlib.sha1(value).hexdigest().encode(),'text/plain')
   for repo,version in zip((hosted,proxy,group),versions):
    work=root/repo;work.mkdir();url='http://'+t.container+':8081/repository/'+repo+'/'
    work.joinpath('settings.xml').write_text(f'<settings><mirrors><mirror><id>nexus</id><mirrorOf>nexus</mirrorOf><url>{url}</url></mirror></mirrors><servers><server><id>nexus</id><username>admin</username><password>{t.password}</password></server></servers></settings>');work.joinpath('settings.xml').chmod(0o600)
    work.joinpath('pom.xml').write_text(f'<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>acceptance</groupId><artifactId>resolver</artifactId><version>1.0</version><repositories><repository><id>nexus</id><url>{url}</url><releases><checksumPolicy>fail</checksumPolicy></releases></repository></repositories></project>')
    coordinates='org.nexus.acceptance:'+artifact+':'+version
    t.run(['docker','run','--rm','--platform','linux/amd64','--network','nexus-open_default','-v',str(work)+':/fixture','-v','nexus-historical-core-maven-cache:/root/.m2','--entrypoint','mvn','nexus-extension-builder:3.96.4-01','-B','-ntp','-s','/fixture/settings.xml','-f','/fixture/pom.xml','org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy','-Dartifact='+coordinates,'-DoutputDirectory=/fixture/download'])
    downloaded=work/'download'/(artifact+'-'+version+'.jar');assert downloaded.read_bytes()==jarbytes
   print('PASS Core maven2: native Maven resolver hosted/proxy/group; strict checksum policy and JAR bytes',flush=True)
  if a.only in(None,'apt'):
   gpg=root/'gpg';gpg.mkdir(mode=0o700);identity='Nexus Native Acceptance <'+t.suffix+'@example.invalid>'
   t.run(['gpg','--homedir',str(gpg),'--batch','--pinentry-mode','loopback','--passphrase','','--quick-generate-key',identity,'rsa2048','sign','1d'])
   t.run(['gpg','--homedir',str(gpg),'--batch','--armor','--output',str(root/'private.asc'),'--export-secret-keys',identity]);root.joinpath('private.asc').chmod(0o600);private=root.joinpath('private.asc').read_text()
   # Binary export is kept out of stdout/logs; apt validates the signed Release using this temporary keyring.
   t.run(['gpg','--homedir',str(gpg),'--batch','--output',str(root/'keyring.gpg'),'--export',identity])
   package='nexus-core-native-'+t.suffix;pkg=root/'pkg';pkg.joinpath('DEBIAN').mkdir(parents=True);pkg.joinpath('DEBIAN/control').write_text(f'Package: {package}\nVersion: 1.0.0\nArchitecture: amd64\nMaintainer: Nexus Test <test@example.invalid>\nDescription: Native Core APT acceptance fixture\n')
   payload=b'native-apt-ok\n';datafile=pkg/'usr/share/nexus-acceptance/data.txt';datafile.parent.mkdir(parents=True);datafile.write_bytes(payload)
   filename=package+'_1.0.0_amd64.deb';t.run(['docker','run','--rm','--platform','linux/amd64','-v',str(root)+':/fixture','nexus-historical-apt-client','dpkg-deb','--build','--root-owner-group','/fixture/pkg','/fixture/'+filename]);deb=root.joinpath(filename).read_bytes()
   apt={'distribution':'bookworm','flat':False};hosted=repository('apt','hosted',extra={'apt':apt,'aptSigning':{'keypair':private,'passphrase':''}});proxy=repository('apt','proxy',remote='http://'+t.container+':8081/repository/'+hosted+'/',extra={'apt':apt})
   t.request('/repository/'+hosted+'/','POST',deb);t.wait_content('/repository/'+hosted+'/dists/bookworm/Release',lambda b:b'SHA256:'in b)
   for repo in(hosted,proxy):
    work=root/repo;work.mkdir();sourceurl='http://admin:'+t.password+'@'+t.container+':8081/repository/'+repo+'/'
    root.joinpath(repo+'.list').write_text(f'deb [signed-by=/fixture/keyring.gpg] {sourceurl} bookworm main\n');root.joinpath(repo+'.list').chmod(0o600)
    command='mkdir -p /tmp/lists/partial /tmp/archives/partial && apt-get -o APT::Sandbox::User=root -o Dir::Etc::sourcelist=/fixture/'+repo+'.list -o Dir::Etc::sourceparts=- -o Dir::State::lists=/tmp/lists -o Dir::Cache::archives=/tmp/archives update && apt-get -o APT::Sandbox::User=root -o Dir::Etc::sourcelist=/fixture/'+repo+'.list -o Dir::Etc::sourceparts=- -o Dir::State::lists=/tmp/lists download '+package+'=1.0.0'
    t.run(['docker','run','--rm','--platform','linux/amd64','--network','nexus-open_default','-v',str(root)+':/fixture','-w','/fixture/'+repo,'nexus-historical-apt-client','sh','-c',command]);assert work.joinpath(filename).read_bytes()==deb
   print('PASS Core apt: native apt-get update/download hosted/proxy; GPG signed Release and .deb SHA256',flush=True)
 finally:t.cleanup()
