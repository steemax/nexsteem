#!/usr/bin/env python3
"""Resolve and install a real noarch Conda package with micromamba through Nexus proxy."""
import argparse,io,tarfile,bz2,json,hashlib
from native_test_support import NexusTest
p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:18090');p.add_argument('--container',default='nexus-research-historical');a=p.parse_args();t=NexusTest(a.url,a.container)
name='nexus-conda-'+t.suffix;filename=name+'-1.0.0-0.tar.bz2';metadata={'name':name,'version':'1.0.0','build':'0','build_number':0,'subdir':'noarch','noarch':'generic','depends':[]}
out=io.BytesIO()
with tarfile.open(fileobj=out,mode='w:bz2') as archive:
 files={'info/index.json':json.dumps(metadata),'info/files':'share/nexus-acceptance.txt\n','share/nexus-acceptance.txt':'native-conda-ok\n'}
 for path,data in files.items():
  data=data.encode();info=tarfile.TarInfo(path);info.size=len(data);info.mode=0o644;archive.addfile(info,io.BytesIO(data))
package=out.getvalue();entry={**metadata,'size':len(package),'md5':hashlib.md5(package).hexdigest(),'sha256':hashlib.sha256(package).hexdigest()};repodata={'info':{'subdir':'noarch'},'packages':{filename:entry},'packages.conda':{},'repodata_version':1}
try:
 fixture=t.repository('raw','hosted')
 for arch,data in [('noarch',repodata),('linux-64',{'info':{'subdir':'linux-64'},'packages':{},'packages.conda':{}})]:
  t.request('/repository/'+fixture+'/'+arch+'/repodata.json','PUT',json.dumps(data).encode(),'application/json')
 t.request('/repository/'+fixture+'/noarch/'+filename,'PUT',package,'application/x-bzip2')
 proxy=t.repository('conda','proxy',remote='http://'+t.container+':8081/repository/'+fixture+'/')
 _,cached=t.request('/repository/'+proxy+'/noarch/'+filename);assert hashlib.sha256(cached).hexdigest()==entry['sha256']
 channel='http://admin:'+t.password+'@'+t.container+':8081/repository/'+proxy+'/'
 code='micromamba create -y -p /tmp/acceptance --override-channels -c "$NEXUS_CHANNEL" "$NEXUS_PACKAGE" && test "$(cat /tmp/acceptance/share/nexus-acceptance.txt)" = native-conda-ok'
 t.run(['docker','run','--rm','--platform','linux/amd64','--network','nexus-open_default','-e','NEXUS_CHANNEL='+channel,'-e','NEXUS_PACKAGE='+name+'=1.0.0','--entrypoint','sh','mambaorg/micromamba:2.3.2','-c',code])
 print('PASS Conda: native micromamba solve/install noarch package; SHA256 verified')
finally:t.cleanup()
