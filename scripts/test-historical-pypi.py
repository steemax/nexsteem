#!/usr/bin/env python3
"""Publish a real wheel with Twine; install it through hosted/proxy/group with pip."""
import argparse,base64,csv,hashlib,io,json,os,sys,tempfile,zipfile
from pathlib import Path
from native_test_support import NexusTest
p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:18090');p.add_argument('--container',default='nexus-research-historical');a=p.parse_args()
t=NexusTest(a.url,a.container)
with tempfile.TemporaryDirectory(prefix='nexus-pypi-') as d:
 root=Path(d); package='nexus_acceptance_'+t.suffix; distinfo=package+'-1.0.0.dist-info'; wheel=root/(package+'-1.0.0-py3-none-any.whl')
 entries={package+'/__init__.py':b'VALUE = "native-pypi-ok"\n',distinfo+'/METADATA':f'Metadata-Version: 2.1\nName: {package}\nVersion: 1.0.0\nSummary: Nexus native acceptance fixture\n\n'.encode(),distinfo+'/WHEEL':b'Wheel-Version: 1.0\nGenerator: Nexus open acceptance\nRoot-Is-Purelib: true\nTag: py3-none-any\n'}
 record=io.StringIO();writer=csv.writer(record,lineterminator='\n')
 for name,data in entries.items():writer.writerow([name,'sha256='+base64.urlsafe_b64encode(hashlib.sha256(data).digest()).decode().rstrip('='),len(data)])
 writer.writerow([distinfo+'/RECORD','','']);entries[distinfo+'/RECORD']=record.getvalue().encode()
 with zipfile.ZipFile(wheel,'w',zipfile.ZIP_DEFLATED) as z:
  for name,data in entries.items():z.writestr(name,data)
 env={**os.environ,'TWINE_USERNAME':'admin','TWINE_PASSWORD':t.password,'TWINE_NON_INTERACTIVE':'1','PIP_DISABLE_PIP_VERSION_CHECK':'1','PIP_NO_CACHE_DIR':'1'}
 try:
  hosted=t.repository('pypi','hosted');url=t.url+'/repository/'+hosted+'/'
  t.run([str(Path(__file__).resolve().parents[1]/'build/historical-client-env/bin/python'),'-m','twine','upload','--repository-url',url,str(wheel)],env=env)
  normalized=package.replace('_','-')
  _,index=t.request('/repository/'+hosted+'/simple/'+normalized+'/');assert wheel.name.encode() in index
  proxy=t.repository('pypi','proxy',remote='http://'+t.container+':8081/repository/'+hosted+'/')
  group=t.repository('pypi','group',members=[hosted,proxy])
  for repo in (hosted,proxy,group):
   target=root/repo;target.mkdir();authurl=t.url.replace('://','://admin:'+t.password+'@')+'/repository/'+repo+'/simple/'
   t.run([sys.executable,'-m','pip','install','--no-deps','--no-cache-dir','--index-url',authurl,'--target',str(target),package+'==1.0.0'],env=env)
   assert (target/package/'__init__.py').read_text().strip()=='VALUE = "native-pypi-ok"'
  # Actual XML-RPC search uses the stored components, not an empty placeholder.
  from xmlrpc.client import dumps,loads
  query=dumps(({'name':[t.suffix]},'or'),methodname='search').encode()
  _,response=t.request('/repository/'+hosted+'/pypi','POST',query,'text/xml');results=loads(response)[0][0];assert any(r['name']==package.replace('_','-') for r in results),results
  print('PASS PyPI: Twine wheel publish; pip hosted/proxy/group install; SQL-backed XML-RPC search')
 finally:t.cleanup()
