#!/usr/bin/env python3
"""Run native pod install through CDN proxy, verify pod source archive and rewritten URL."""
import argparse,hashlib,io,json,os,tempfile,tarfile
from pathlib import Path
from native_test_support import NexusTest
p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:18090');p.add_argument('--container',default='nexus-research-historical');a=p.parse_args();t=NexusTest(a.url,a.container)
name='NexusAcceptance'+t.suffix;digest=hashlib.md5(name.encode()).hexdigest();chunk='_'.join(digest[:3]);specpath='Specs/'+'/'.join(digest[:3])+'/'+name+'/1.0.0/'+name+'.podspec.json'
archive=io.BytesIO()
with tarfile.open(fileobj=archive,mode='w:gz')as tar:
 data=b'const char *nexus_acceptance = "native-cocoapods-ok";\n';info=tarfile.TarInfo(name+'/Nexus.m');info.mode=0o644;info.size=len(data);tar.addfile(info,io.BytesIO(data))
blob=archive.getvalue()
try:
 fixture=t.repository('raw','hosted');remote='http://'+t.container+':8081/repository/'+fixture+'/'
 spec={'name':name,'version':'1.0.0','summary':'Nexus acceptance fixture','description':'A native CocoaPods repository acceptance fixture.','homepage':'https://example.invalid/','license':{'type':'MIT'},'authors':{'Nexus':'test@example.invalid'},'source':{'http':remote+'archives/'+name+'.tar.gz','sha256':hashlib.sha256(blob).hexdigest()},'platforms':{'ios':'12.0'},'source_files':'Nexus.m'}
 datafiles={'CocoaPods-version.yml':b'---\nmin: 1.0.0\nlast: 1.16.2\nprefix_lengths: [1, 1, 1]\n','all_pods.txt':(name+'\n').encode(),'all_pods_versions_'+chunk+'.txt':(name+'/1.0.0\n').encode(),specpath:json.dumps(spec).encode(),'archives/'+name+'.tar.gz':blob}
 for path,data in datafiles.items():t.request('/repository/'+fixture+'/'+path,'PUT',data,'application/json' if path.endswith('.json') else 'application/octet-stream')
 proxy=t.repository('cocoapods','proxy',remote=remote)
 with tempfile.TemporaryDirectory(prefix='historical-cocoa-',dir=Path(__file__).resolve().parents[1]/'build')as d:
  root=Path(d);root.joinpath('Podfile').write_text(f'''source ENV.fetch('NEXUS_SOURCE')\ninstall! 'cocoapods', :integrate_targets => false\nplatform :ios, '12.0'\ntarget 'Demo' do\n  pod '{name}', '1.0.0'\nend\n''')
  home=root/'home';home.mkdir();home.joinpath('.netrc').write_text(f'machine {t.container}\nlogin admin\npassword {t.password}\n');home.joinpath('.netrc').chmod(0o600)
  source='http://'+t.container+':8081/repository/'+proxy+'/'
  t.run(['docker','run','--rm','--platform','linux/amd64','--network','nexus-open_default','-v',str(root)+':/fixture','-w','/fixture','-e','HOME=/fixture/home','-e','NEXUS_SOURCE='+source,'-e','COCOAPODS_DISABLE_STATS=true','nexus-historical-cocoa-client','pod','install','--allow-root','--verbose'])
  installed=root/'Pods'/name/'Nexus.m';assert installed.exists();assert installed.read_bytes()==b'const char *nexus_acceptance = "native-cocoapods-ok";\n'
  assert name in root.joinpath('Podfile.lock').read_text()
 # Metadata must render URLs for the current caller, not whichever host primed the cache.
 _,metadata=t.request('/repository/'+proxy+'/'+specpath);rewritten=json.loads(metadata);assert '/repository/'+proxy+'/_source/'in rewritten['source']['http'];assert rewritten['source']['sha256']==hashlib.sha256(blob).hexdigest()
 _,cached=t.request('/repository/'+proxy+'/_source/'+hashlib.sha256(spec['source']['http'].encode()).hexdigest());assert hashlib.sha256(cached).digest()==hashlib.sha256(blob).digest()
 print('PASS CocoaPods: native pod install through CDN proxy; podspec source rewritten and archive SHA256 verified')
finally:t.cleanup()
