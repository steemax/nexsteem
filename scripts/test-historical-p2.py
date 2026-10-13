#!/usr/bin/env python3
"""Use Eclipse Tycho's real p2 mirror application through Nexus; verify mirrored bundle bytes."""
import argparse,hashlib,io,zipfile,tempfile
from pathlib import Path
from native_test_support import NexusTest
p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:18090');p.add_argument('--container',default='nexus-research-historical');a=p.parse_args();t=NexusTest(a.url,a.container)
bundle='nexus.acceptance.'+t.suffix;version='1.0.0';filename=bundle+'_'+version+'.jar';out=io.BytesIO()
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
 z.writestr('META-INF/MANIFEST.MF',f'Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: {bundle}\nBundle-Version: {version}\nBundle-Name: Nexus acceptance fixture\n\n')
 z.writestr('nexus-acceptance.txt','native-p2-ok\n')
jar=out.getvalue()
content=f'''<?xml version="1.0" encoding="UTF-8"?>
<?metadataRepository version="1.1.0"?>
<repository name="Nexus acceptance" type="org.eclipse.equinox.internal.p2.metadata.repository.LocalMetadataRepository" version="1.0.0">
<properties size="1"><property name="p2.timestamp" value="1760000000000"/></properties>
<units size="1"><unit id="{bundle}" version="{version}" singleton="false"><update id="{bundle}" range="[0.0.0,{version})" severity="0"/>
<properties size="1"><property name="org.eclipse.equinox.p2.name" value="Nexus acceptance"/></properties>
<provides size="2"><provided namespace="org.eclipse.equinox.p2.iu" name="{bundle}" version="{version}"/><provided namespace="osgi.bundle" name="{bundle}" version="{version}"/></provides>
<artifacts size="1"><artifact classifier="osgi.bundle" id="{bundle}" version="{version}"/></artifacts>
<touchpoint id="org.eclipse.equinox.p2.osgi" version="1.0.0"/>
<touchpointData size="1"><instructions size="1"><instruction key="manifest">Bundle-SymbolicName: {bundle}\nBundle-Version: {version}</instruction></instructions></touchpointData>
</unit></units></repository>'''
artifacts=f'''<?xml version="1.0" encoding="UTF-8"?>
<?artifactRepository version="1.1.0"?>
<repository name="Nexus acceptance" type="org.eclipse.equinox.p2.artifact.repository.simpleRepository" version="1.0.0">
<properties size="1"><property name="p2.timestamp" value="1760000000000"/></properties>
<mappings size="1"><rule filter="(classifier=osgi.bundle)" output="${{repoUrl}}/plugins/${{id}}_${{version}}.jar"/></mappings>
<artifacts size="1"><artifact classifier="osgi.bundle" id="{bundle}" version="{version}"><properties size="4">
<property name="artifact.size" value="{len(jar)}"/><property name="download.size" value="{len(jar)}"/><property name="download.md5" value="{hashlib.md5(jar).hexdigest()}"/><property name="download.checksum.sha-256" value="{hashlib.sha256(jar).hexdigest()}"/>
</properties></artifact></artifacts></repository>'''
try:
 fixture=t.repository('raw','hosted')
 for path,bytes_,kind in [('content.xml',content.encode(),'application/xml'),('artifacts.xml',artifacts.encode(),'application/xml'),('p2.index',b'version = 1\nmetadata.repository.factory.order = content.xml,\\!\nartifact.repository.factory.order = artifacts.xml,\\!\n','text/plain'),('plugins/'+filename,jar,'application/java-archive')]:t.request('/repository/'+fixture+'/'+path,'PUT',bytes_,kind)
 proxy=t.repository('p2','proxy',remote='http://'+t.container+':8081/repository/'+fixture+'/')
 # Priming also validates Nexus's format-specific metadata parsing and component extraction.
 for path in ['p2.index','content.xml','artifacts.xml','plugins/'+filename]:t.request('/repository/'+proxy+'/'+path)
 with tempfile.TemporaryDirectory(prefix='historical-p2-',dir=Path(__file__).resolve().parents[1]/'build') as d:
  root=Path(d);url='http://'+t.container+':8081/repository/'+proxy+'/'
  root.joinpath('settings.xml').write_text(f'<settings><servers><server><id>nexus</id><username>admin</username><password>{t.password}</password></server></servers></settings>');root.joinpath('settings.xml').chmod(0o600)
  root.joinpath('p2-mirror.target').write_text(f'''<?xml version="1.0" encoding="UTF-8"?><target name="Nexus private P2"><locations><location includeAllPlatforms="true" includeConfigurePhase="false" includeMode="slicer" includeSource="false" type="InstallableUnit"><repository location="{url}" id="nexus"/><unit id="{bundle}" version="{version}"/></location></locations></target>''')
  root.joinpath('pom.xml').write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>acceptance</groupId><artifactId>p2-mirror</artifactId><version>1.0</version><packaging>eclipse-target-definition</packaging><build><plugins><plugin><groupId>org.eclipse.tycho</groupId><artifactId>tycho-maven-plugin</artifactId><version>4.0.13</version><extensions>true</extensions></plugin><plugin><groupId>org.eclipse.tycho</groupId><artifactId>target-platform-configuration</artifactId><version>4.0.13</version><configuration><target><file>/fixture/p2-mirror.target</file></target><destination>/fixture/mirror</destination></configuration></plugin></plugins></build></project>''')
  t.run(['docker','run','--rm','--platform','linux/amd64','--network','nexus-open_default','-v',str(root)+':/fixture','-v','nexus-historical-p2-maven-cache:/root/.m2','--entrypoint','mvn','nexus-extension-builder:3.96.4-01','-B','-ntp','-s','/fixture/settings.xml','-f','/fixture/pom.xml','org.eclipse.tycho:target-platform-configuration:4.0.13:mirror-target-platform'])
  mirrored=root/'mirror/plugins'/filename;assert mirrored.exists(),'Tycho did not mirror the bundle';assert hashlib.sha256(mirrored.read_bytes()).digest()==hashlib.sha256(jar).digest()
 print('PASS P2: real Eclipse Tycho p2 mirror; parsed metadata and downloaded bundle SHA256 verified')
finally:t.cleanup()
