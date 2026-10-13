#!/usr/bin/env python3
"""Check private upstream auth and credentials scoping on a disposable Nexus."""
import argparse,json,pathlib,urllib.request,urllib.error
from native_test_support import NexusTest
p=argparse.ArgumentParser();p.add_argument('--state',default='build/modern-work17/state.json');args=p.parse_args()
s=json.loads(pathlib.Path(args.state).read_text());n=NexusTest(s['url'],s['container'])
def repository(format,remote):
 name=f'private-{format}-{n.suffix}'
 cfg={'name':name,'online':True,'storage':{'blobStoreName':'default','strictContentTypeValidation':True},'proxy':{'remoteUrl':remote,'contentMaxAge':1440,'metadataMaxAge':1440},'negativeCache':{'enabled':True,'timeToLive':1440},'httpClient':{'autoBlock':False,'blocked':False,'authentication':{'type':'username','username':'fixture','password':'fixture-password'}}}
 n.request(f'/service/rest/v1/repositories/{format}/proxy','POST',cfg);n.created.append(name);return name
try:
 go=repository('go','http://nexus-modern-upstream:8080/private-go/');_,mod=n.request(f'/repository/{go}/example.test/hello/@v/v0.2.0.mod');assert mod==b'module example.test/hello\n\ngo 1.20\n'
 composer=repository('composer','http://nexus-modern-upstream:8080/private-composer/');_,metadata=n.request(f'/repository/{composer}/packages.json');assert json.loads(metadata)['metadata-url'].startswith(n.url+'/repository/'+composer)
 _,metadata=n.request(f'/repository/{composer}/p2/fixture/hello.json');archive=json.loads(metadata)['packages']['fixture/hello'][0]['dist']['url'];assert archive.startswith(n.url+'/repository/'+composer+'/_remote/')
 try:n.request(archive[len(n.url):]);raise AssertionError('Foreign credential challenge must not return an artifact')
 except AssertionError as error:assert 'HTTP 503:' in str(error),str(error)
 with urllib.request.urlopen('http://127.0.0.1:18102/credential-state',timeout=10)as response:assert not json.load(response)['foreignAuthorizationSeen']
 print('PASS Go and Composer private Basic upstreams; artifact host challenge cannot obtain repository credentials')
finally:n.cleanup()
