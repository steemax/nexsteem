#!/usr/bin/env python3
"""Deterministic public-protocol upstreams for Nexus modern-format acceptance."""
import io,json,zipfile,hashlib,urllib.parse,struct,base64,threading
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
credential_seen=False
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def do_HEAD(self): self.respond(False)
 def do_GET(self): self.respond(True)
 def respond(self,body):
  global credential_seen
  p=urllib.parse.urlsplit(self.path).path; parts=p.split('/'); branch=parts[1] if len(parts)>1 else ''; rel='/'+'/'.join(parts[2:]);base=f'http://nexus-modern-upstream:8080/{branch}'
  if branch=='credential-check':
   credential_seen=credential_seen or self.headers.get('Authorization') is not None
   self.send_response(401);self.send_header('WWW-Authenticate','Basic realm="foreign-artifact"');self.send_header('Content-Length','0');self.end_headers();return
  if branch=='private-huggingface' and self.headers.get('Authorization')!='Bearer fixture-upstream-token':
   self.send_response(401);self.send_header('WWW-Authenticate','Bearer realm="private-hub"');self.send_header('Content-Length','0');self.end_headers();return
  if branch.startswith('private-') and branch!='private-huggingface' and self.headers.get('Authorization')!='Basic '+base64.b64encode(b'fixture:fixture-password').decode():
   self.send_response(401);self.send_header('WWW-Authenticate','Basic realm="private-upstream"');self.send_header('Content-Length','0');self.end_headers();return
  data=None;ctype='application/json';headers={}
  if branch=='credential-state':data=json.dumps({'foreignAuthorizationSeen':credential_seen}).encode()
  if branch in ('goa','gob','private-go'):
   version='v0.1.0' if branch=='goa' else 'v0.2.0';module='example.test/hello'
   if rel==f'/{module}/@v/list':data=(version+'\n').encode();ctype='text/plain'
   if rel==f'/{module}/@latest' or rel==f'/{module}/@v/{version}.info':data=json.dumps({'Version':version,'Time':'2026-10-11T00:00:00Z'}).encode()
   if rel==f'/{module}/@v/{version}.mod':data=f'module {module}\n\ngo 1.20\n'.encode();ctype='text/plain'
   if rel==f'/{module}/@v/{version}.zip':
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w') as z:z.writestr(f'{module}@{version}/go.mod',f'module {module}\n\ngo 1.20\n');z.writestr(f'{module}@{version}/hello.go','package hello\nfunc Hello() string { return "nexus" }\n')
    data=out.getvalue();ctype='application/zip'
  if branch in ('composer','private-composer'):
   package={'name':'fixture/hello','version':'1.0.0','type':'library','dist':{'type':'zip','url':base+'/archive.zip','reference':'a'*40,'shasum':''},'autoload':{'psr-4':{'Fixture\\':'src/'}}}
   if branch=='private-composer':package['dist']['url']='http://nexus-modern-upstream:8082/credential-check/archive.zip'
   if rel=='/packages.json':data=json.dumps({'packages':{},'metadata-url':base+'/p2/%package%.json','available-packages':['fixture/hello']}).encode()
   if rel in ['/p2/fixture/hello.json','/p2/fixture/hello~dev.json']:data=json.dumps({'packages':{'fixture/hello':[] if '~dev' in rel else [package]}}).encode()
   if rel=='/archive.zip':
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w') as z:z.writestr('fixture/composer.json',json.dumps(package));z.writestr('fixture/src/Hello.php','<?php namespace Fixture; class Hello { public static function text(){return "nexus";} }')
    data=out.getvalue();ctype='application/zip'
  if branch in ('huggingface','private-huggingface'):
   commit='a'*40;config=b'{"model_type":"bert","hidden_size":8}\n';tensor_header=json.dumps({'weight':{'dtype':'F32','shape':[2],'data_offsets':[0,8]}},separators=(',',':')).encode();tensor_header+=b' '*((-len(tensor_header))%8);tensor=struct.pack('<Q',len(tensor_header))+tensor_header+struct.pack('<ff',1.0,2.0);files={'config.json':config,'model.safetensors':tensor}
   if '/tree/' in rel and rel.startswith('/api/models/fixture/tiny'):
    entries=[{'type':'file','path':name,'size':len(value),'oid':hashlib.sha1(b'blob '+str(len(value)).encode()+b'\0'+value).hexdigest()}for name,value in files.items()];page2=urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get('cursor')==['second-page'];data=json.dumps(entries[1:]if page2 else entries[:1]).encode()
    if not page2:headers['Link']='<'+base+rel+'?cursor=second-page>; rel="next"'
   elif rel.startswith('/api/models/fixture/tiny'):
    data=json.dumps({'id':'fixture/tiny','sha':commit,'private':False,'siblings':[{'rfilename':name}for name in files]}).encode()
   for name,value in files.items():
    if rel in ['/fixture/tiny/resolve/main/'+name,f'/fixture/tiny/resolve/{commit}/'+name]:data=value;ctype='application/json'if name.endswith('.json')else'application/octet-stream'
   if data is not None:headers.update({'X-Repo-Commit':commit,'ETag':'"'+hashlib.sha256(data).hexdigest()+'"'})
  if data is None:self.send_error(404);return
  self.send_response(200);self.send_header('Content-Type',ctype);self.send_header('Content-Length',str(len(data)));self.send_header('ETag',headers.pop('ETag','"'+hashlib.sha256(data).hexdigest()+'"'))
  for k,v in headers.items():self.send_header(k,v)
  self.end_headers()
  if body:self.wfile.write(data)
threading.Thread(target=ThreadingHTTPServer(('0.0.0.0',8082),Handler).serve_forever,daemon=True).start()
ThreadingHTTPServer(('0.0.0.0',8080),Handler).serve_forever()
