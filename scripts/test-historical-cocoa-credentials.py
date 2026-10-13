#!/usr/bin/env python3
"""Challenge a pod source on another origin; repository credentials must never be sent there."""
import argparse,base64,hashlib,json,subprocess,urllib.request,urllib.error
from native_test_support import NexusTest
p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:18090');p.add_argument('--container',default='nexus-research-historical');a=p.parse_args();t=NexusTest(a.url,a.container)
fixture_name='nexus-historical-source-check-'+t.suffix
server='''from http.server import BaseHTTPRequestHandler,HTTPServer
import json
class Handler(BaseHTTPRequestHandler):
 def do_GET(self):
  present=bool(self.headers.get('Authorization'))
  print(json.dumps({'authorization_seen':present}),flush=True)
  self.send_response(200 if present else 401)
  if not present:self.send_header('WWW-Authenticate','Basic realm="foreign-source"')
  self.end_headers();self.wfile.write(b'credential-leak'if present else b'authentication-required')
 def log_message(self,*args):pass
HTTPServer(('0.0.0.0',8080),Handler).serve_forever()
'''
_,ssrfraw=t.request('/service/rest/v1/security/ssrf-protection');ssrf=json.loads(ssrfraw)
try:
 t.run(['docker','run','-d','--rm','--name',fixture_name,'--platform','linux/amd64','--network','nexus-open_default','--entrypoint','python3','nexus-extension-builder:3.96.4-01','-u','-c',server])
 fixture=t.repository('raw','hosted');pod='NexusCredentialGuard'+t.suffix;source='http://'+fixture_name+':8080/source.tar.gz';spec={'name':pod,'version':'1.0.0','source':{'http':source},'summary':'credential boundary test'}
 specpath='Specs/'+pod+'.podspec.json';t.request('/repository/'+fixture+'/'+specpath,'PUT',json.dumps(spec).encode(),'application/json')
 proxy=t.repository('cocoapods','proxy',remote='http://'+t.container+':8081/repository/'+fixture+'/')
 _,raw=t.request('/service/rest/v1/security/ssrf-protection');allowed=json.loads(raw);allowed['allowedDomains']=list(dict.fromkeys(allowed.get('allowedDomains',[])+[fixture_name]));t.request('/service/rest/v1/security/ssrf-protection','PUT',allowed)
 _,body=t.request('/repository/'+proxy+'/'+specpath);rewritten=json.loads(body);url=rewritten['source']['http'];request=urllib.request.Request(url,headers={'Authorization':'Basic '+t.basic})
 try:urllib.request.urlopen(request,timeout=30);raise AssertionError('Foreign source accepted repository credentials')
 except urllib.error.HTTPError as error:assert error.code in (401,404,502,503,500),error.code
 output=t.run(['docker','logs',fixture_name]);records=[json.loads(line)for line in output.splitlines()if line.startswith('{')];assert records and not any(r['authorization_seen']for r in records),records
 print('PASS CocoaPods credentials: challenged foreign origin received no repository Authorization header')
finally:
 try:t.cleanup()
 finally:
  t.request('/service/rest/v1/security/ssrf-protection','PUT',ssrfraw,'application/json')
  subprocess.run(['docker','stop',fixture_name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
