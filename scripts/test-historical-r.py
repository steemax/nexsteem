#!/usr/bin/env python3
"""Verify real R install.packages against hosted, proxy, and merged group PACKAGES metadata."""
import argparse,io,tarfile,tempfile,json
from pathlib import Path
from native_test_support import NexusTest
p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:18090');p.add_argument('--container',default='nexus-research-historical');a=p.parse_args();t=NexusTest(a.url,a.container)
name='nexusacceptance'+t.suffix
def package_archive(version):
 out=io.BytesIO()
 with tarfile.open(fileobj=out,mode='w:gz') as archive:
  files={'DESCRIPTION':f'Package: {name}\nType: Package\nTitle: Nexus Acceptance\nVersion: {version}\nAuthor: Nexus Test\nMaintainer: Nexus Test <test@example.invalid>\nDescription: A deterministic repository client acceptance fixture.\nLicense: MIT\nEncoding: UTF-8\nNeedsCompilation: no\n','NAMESPACE':'export(nexus_value)\n','R/value.R':'nexus_value <- function() "native-r-ok"\n'}
  for relative,data in files.items():
   blob=data.encode();info=tarfile.TarInfo(name+'/'+relative);info.size=len(blob);info.mode=0o644;archive.addfile(info,io.BytesIO(blob))
 return out.getvalue()
try:
 hosted=t.repository('r','hosted')
 for version in ('1.0.0','1.2.0'):t.request('/repository/'+hosted+'/src/contrib/'+name+'_'+version+'.tar.gz','PUT',package_archive(version),'application/x-gzip')
 proxy=t.repository('r','proxy',remote='http://'+t.container+':8081/repository/'+hosted+'/');group=t.repository('r','group',members=[hosted,proxy])
 for repo in (hosted,proxy,group):
  _,index=t.request('/repository/'+repo+'/src/contrib/PACKAGES');assert name.encode()in index and b'Version: 1.2.0' in index and b'Version: 1.0.0' not in index,index
  code=f'options(repos=c(nexus="http://admin:{t.password}@{t.container}:8081/repository/{repo}/"));dir.create("/tmp/r-lib");install.packages("{name}",lib="/tmp/r-lib",type="source");library("{name}",lib.loc="/tmp/r-lib",character.only=TRUE);stopifnot(nexus_value()=="native-r-ok", as.character(packageVersion("{name}",lib.loc="/tmp/r-lib"))=="1.2.0")'
  t.run(['docker','run','--rm','--platform','linux/amd64','--network','nexus-open_default','nexus-historical-r-client','Rscript','--vanilla','-e',code])
 print('PASS R: native R install.packages and exported function hosted/proxy/group; latest-version selection; plain and gzip PACKAGES')
finally:t.cleanup()
