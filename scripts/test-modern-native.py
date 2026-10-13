#!/usr/bin/env python3
"""Run Cargo/Git LFS/Composer/Go/Swift/Terraform clients against Nexus fixtures."""
import json,os,pathlib,shutil,subprocess,sys,tempfile,urllib.request
from native_test_support import NexusTest
work=pathlib.Path(os.environ.get('MODERN_TEST_WORK','build/modern-work')).resolve();state=json.loads((work/'state.json').read_text());n=NexusTest(state['url'],state['container']);repo=state['repositories'];native=work/'native'
if native.exists():
 for directory,dirs,files in os.walk(native):
  os.chmod(directory,0o700)
  for name in dirs:os.chmod(pathlib.Path(directory)/name,0o700)
  for name in files:os.chmod(pathlib.Path(directory)/name,0o600)
 shutil.rmtree(native)
native.mkdir();credentials=(work/'basic-token').read_text()
# All credentials here belong only to our disposable test Nexus.
def run(args,cwd=None,env=None):return n.run(args,cwd=cwd,env=env)
def write(name,data):
 p=native/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(data);return p
internal='http://'+state['container']+':8081'
script=r'''import base64,hashlib,json,os,pathlib,subprocess,time
root=pathlib.Path('/work');state=json.loads((root.parent/'state.json').read_text());repos=state['repositories'];basic=(root.parent/'basic-token').read_text();password=(root.parent/'admin-password').read_text();url='http://'+state['container']+':8081'
def run(cmd,cwd,env=None):
 result=subprocess.run(cmd,cwd=cwd,env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=120)
 output=result.stdout.replace(password,'[redacted]').replace(basic,'[redacted]')
 if result.returncode:raise AssertionError(cmd[0]+' failed: '+output)
 return output
def write(path,text):p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);return p
cargo=root/'cargo';cargo.mkdir(exist_ok=True);version='1.0.'+str(time.time_ns()%1000000000000)
write('cargo/.cargo/config.toml',f'[registries.nexus]\nindex="sparse+{url}/repository/{repos["cargo"]["hosted"]}/"\ncredential-provider="cargo:token"\n[registry]\nglobal-credential-providers=["cargo:token"]\n')
write('cargo/Cargo.toml',f'[package]\nname="native_cargo_fixture"\nversion="{version}"\nedition="2021"\nlicense="MIT"\ndescription="Native Nexus acceptance"\n')
write('cargo/src/lib.rs','pub fn hello() -> u32 { 42 }\n')
env=os.environ.copy();env['CARGO_HOME']='/tmp/cargo-native';env['CARGO_REGISTRIES_NEXUS_TOKEN']='Basic '+basic
run(['cargo','publish','--registry','nexus','--allow-dirty','--no-verify'],cargo,env)
consumer=root/'cargo-consumer';consumer.mkdir(exist_ok=True)
write('cargo-consumer/.cargo/config.toml',f'[registries.nexus]\nindex="sparse+{url}/repository/{repos["cargo"]["group"]}/"\ncredential-provider="cargo:token"\n[registry]\nglobal-credential-providers=["cargo:token"]\n')
write('cargo-consumer/Cargo.toml',f'[package]\nname="nexus_consumer"\nversion="1.0.0"\nedition="2021"\n[dependencies]\nnative_cargo_fixture={{{{version="={version}",registry="nexus"}}}}\n'.replace('{{','{').replace('}}','}'))
write('cargo-consumer/src/main.rs','fn main() { assert_eq!(native_cargo_fixture::hello(),42); }\n');run(['cargo','run','--quiet'],consumer,env)
print('PASS native Cargo 1.85: publish hosted, sparse group resolve, checksum verification, compile/run')
lfs=root/'lfs';lfs.mkdir(exist_ok=True);run(['git','init'],lfs);run(['git','config','user.email','local@example.invalid'],lfs);run(['git','config','user.name','Nexus acceptance'],lfs);run(['git','lfs','install','--local'],lfs);run(['git','remote','add','origin',url+'/repository/'+repos['gitlfs']['hosted']+'/unused.git'],lfs);run(['git','config','lfs.url',url+'/repository/'+repos['gitlfs']['hosted']+'/info/lfs'],lfs);run(['git','config','http.extraHeader','Authorization: Basic '+basic],lfs);run(['git','lfs','track','*.bin'],lfs)
artifact=b'native git-lfs fixture'+bytes(range(256))*100;(lfs/'large.bin').write_bytes(artifact);run(['git','add','.'],lfs);run(['git','commit','-m','fixture'],lfs);run(['git','lfs','push','--all','origin'],lfs)
import shutil
shutil.rmtree(lfs/'.git/lfs/objects');pointer=run(['git','show','HEAD:large.bin'],lfs);(lfs/'large.bin').write_text(pointer);run(['git','lfs','pull','origin'],lfs);assert(lfs/'large.bin').read_bytes()==artifact
print('PASS native Git LFS: track, push, batch, fetch/pull and SHA256 object verification')
composer=root/'composer';composer.mkdir(exist_ok=True);write('composer/composer.json',json.dumps({'repositories':{'nexus':{'type':'composer','url':url+'/repository/'+repos['composer']['proxy']+'/'},'packagist.org':False},'require':{'fixture/hello':'1.0.0'},'config':{'secure-http':False},'minimum-stability':'stable'}));env=os.environ.copy();env['COMPOSER_AUTH']=json.dumps({'http-basic':{state['container']:{'username':'admin','password':password},state['container']+':8081':{'username':'admin','password':password}}});env['COMPOSER_HOME']='/tmp/composer-home';env['COMPOSER_ALLOW_SUPERUSER']='1'
run(['composer','install','--no-interaction','--no-plugins','--no-scripts','--prefer-dist'],composer,env);assert(composer/'vendor/fixture/hello/src/Hello.php').exists();assert run(['php','-r',"require 'vendor/autoload.php'; echo Fixture\\Hello::text();"],composer)=='nexus'
print('PASS native Composer: v2 metadata resolution, dist ZIP download, install and autoload')
'''
write('docker-native.py',script)
print(run(['docker','run','--rm','--platform','linux/amd64','--network',os.environ.get('MODERN_TEST_NETWORK','nexus-open_default'),'-v',str(work)+':/work-parent','-v',str(native)+':/work','-v',str(work/'state.json')+':/state.json:ro','-v',str(work/'basic-token')+':/basic-token:ro','-v',str(work/'admin-password')+':/admin-password:ro','nexus-modern-clients:debian13','python3','/work/docker-native.py']))
# Existing anonymous read role is enabled only on this disposable fixture, so Terraform's unsigned discovery uses public reads.
_,raw=n.request('/service/rest/v1/security/anonymous');anonymous=json.loads(raw);updated=dict(anonymous);updated['enabled']=True;n.request('/service/rest/v1/security/anonymous','PUT',updated)
try:
 go=native/'go';go.mkdir(exist_ok=True);write('go/go.mod','module native.test/consumer\n\ngo 1.20\n');env=os.environ.copy();env['GOPROXY']=state['url']+'/repository/'+repo['go']['group'];env['GOSUMDB']='off';env['GOPATH']=str(native/'go-cache');env['GOINSECURE']='127.0.0.1';result=run(['go','mod','download','-json','example.test/hello@v0.2.0'],go,env);assert json.loads(result)['Version']=='v0.2.0'
 print('PASS native Go 1.26: GOPROXY group list/info/mod/zip, extraction and module checksum')
 swift=native/'swift-consumer';swift.mkdir(exist_ok=True);write('swift-consumer/Package.swift','// swift-tools-version: 5.9\nimport PackageDescription\nlet package = Package(name:"Consumer", dependencies:[.package(id:"fixture.hello", from:"1.0.0")], targets:[.executableTarget(name:"Consumer", dependencies:[.product(name:"Hello",package:"fixture.hello")])])\n');write('swift-consumer/Sources/Consumer/main.swift','import Hello\nprint(hello())\n')
 run(['swift','package-registry','--disable-keychain','--disable-sandbox','set','--allow-insecure-http',state['url']+'/repository/'+repo['swift']['group']],swift)
 result=run(['swift','package','--disable-keychain','--disable-sandbox','--cache-path',str(native/'swift-cache'),'--security-path',str(native/'swift-security'),'resolve'],swift);assert (swift/'Package.resolved').exists()
 print('PASS native SwiftPM: registry release resolution, manifest, ZIP SHA256 and source extraction')
 terraform=native/'terraform';terraform.mkdir(exist_ok=True);host=state['url'].split('://')[1];tfurl=state['url']+'/repository/'+repo['terraform']['group']
 cfg=write('terraform.tfrc',f'host "{host}" {{\n services = {{\n "providers.v1" = "{tfurl}/v1/providers/"\n "modules.v1" = "{tfurl}/v1/modules/"\n }}\n}}\n')
 write('terraform/main.tf',f'terraform {{\n required_providers {{\n fixture = {{ source = "{host}/fixture/fixture", version = "1.0.0" }}\n }}\n}}\nmodule "hello" {{\n source = "{host}/fixture/hello/local"\n version = "1.0.0"\n}}\n')
 env=os.environ.copy();env['TF_CLI_CONFIG_FILE']=str(cfg);result=run(['terraform','init','-backend=false','-input=false','-no-color'],terraform,env);assert '.terraform.lock.hcl' in str(list(terraform.iterdir()));assert (terraform/'.terraform/modules/hello/main.tf').exists();print(result)
 print('PASS native Terraform: private provider/module registry, package checksum + real PGP signature authentication, installation')
finally:n.request('/service/rest/v1/security/anonymous','PUT',anonymous)
