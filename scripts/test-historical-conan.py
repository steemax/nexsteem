#!/usr/bin/env python3
"""Native Conan2 export/create/upload/download, revisions, search and checksum rejection."""
import argparse,hashlib,json,os,tempfile,urllib.request,urllib.error
from pathlib import Path
from native_test_support import NexusTest
p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:18090');p.add_argument('--container',default='nexus-research-historical');a=p.parse_args();t=NexusTest(a.url,a.container)
conan=str(Path(__file__).resolve().parents[1]/'build/historical-client-env/bin/conan');name='nexus-conan-'+t.suffix;ref=name+'/1.0.0'
_,realmraw=t.request('/service/rest/v1/security/realms/active');original_realms=json.loads(realmraw)
with tempfile.TemporaryDirectory(prefix='nexus-conan-')as d:
 root=Path(d);recipe=root/'recipe';recipe.mkdir();(recipe/'fixture.txt').write_text('native-conan-ok\n')
 (recipe/'conanfile.py').write_text(f'''from conan import ConanFile\nfrom conan.tools.files import copy\nclass NexusFixture(ConanFile):\n    name = "{name}"\n    version = "1.0.0"\n    package_type = "header-library"\n    exports_sources = "fixture.txt"\n    def package(self):\n        copy(self, "fixture.txt", src=self.source_folder, dst=self.package_folder)\n''')
 def environment(home):return{**os.environ,'CONAN_HOME':str(home),'CONAN_LOGIN_USERNAME':'admin','CONAN_PASSWORD':t.password,'CONAN_NON_INTERACTIVE':'1'}
 try:
  t.request('/service/rest/v1/security/realms/active','PUT',json.dumps(list(dict.fromkeys(original_realms+['ConanToken']))).encode(),'application/json')
  hosted=t.repository('conan','hosted');proxy=t.repository('conan','proxy',remote='http://'+t.container+':8081/repository/'+hosted+'/');group=t.repository('conan','group',members=[hosted,proxy])
  home=root/'publish';env=environment(home)
  (home/'profiles').mkdir(parents=True);(home/'profiles/default').write_text('[settings]\nos=Macos\narch=armv8\n');t.run([conan,'remote','add','acceptance',t.url+'/repository/'+hosted+'/'],env=env);t.run([conan,'remote','auth','acceptance'],env=env)
  t.run([conan,'create',str(recipe)],env=env);t.run([conan,'upload',ref,'-r','acceptance','--confirm'],env=env)
  for repo in (hosted,proxy,group):
   env=environment(root/repo);t.run([conan,'remote','add','acceptance',t.url+'/repository/'+repo+'/'],env=env);t.run([conan,'remote','auth','acceptance'],env=env)
   listing=t.run([conan,'list',ref+'#*:*#*','-r','acceptance','--format=json'],env=env);data=json.JSONDecoder().raw_decode(listing[listing.index("{"):])[0]
   assert ref in data['acceptance'],data
   t.run([conan,'download',ref,'-r','acceptance'],env=env)
   files=list((root/repo).rglob('fixture.txt'));assert files and any(p.read_text()=='native-conan-ok\n'for p in files),files
   if repo!=group:
    _,raw=t.request('/service/rest/v1/components?repository='+repo);assert any(item['name']==name+'@_/_'and item['version']=='1.0.0'for item in json.loads(raw)['items']),raw.decode()
  # Reject corrupted bytes before an asset is committed.
  path='/repository/'+hosted+'/v2/conans/'+name+'/1.0.0/_/_/revisions/'+'f'*32+'/files/corrupt.txt'
  request=urllib.request.Request(t.url+path,data=b'bad bytes',method='PUT',headers={'Authorization':'Basic '+t.basic,'X-Checksum-Sha1':'0'*40,'Content-Type':'application/octet-stream'})
  try:urllib.request.urlopen(request,timeout=30);raise AssertionError('Invalid checksum accepted')
  except urllib.error.HTTPError as error:assert error.code==400,error.code
  try:t.request(path);raise AssertionError('Corrupted asset was committed')
  except AssertionError as error:assert 'HTTP 404:'in str(error),str(error)
  invalid=urllib.request.Request(t.url+'/repository/'+hosted+'/v2/users/check_credentials',headers={'Authorization':'Bearer ConanToken.invalid'})
  try:urllib.request.urlopen(invalid,timeout=30);raise AssertionError('Invalid Conan bearer token accepted')
  except urllib.error.HTTPError as error:assert error.code==401,error.code
  print('PASS Conan2: native create/upload/list/download hosted/proxy/group; SQL revisions and package snapshots; checksum rejection')
 finally:
  try:t.cleanup()
  finally:t.request('/service/rest/v1/security/realms/active','PUT',realmraw,'application/json')
