#!/usr/bin/env python3
"""Native dotnet pack/push/build via NuGet hosted/proxy/group, API key and V3 metadata contracts."""
import argparse,base64,hashlib,json,os,subprocess,tempfile,urllib.request,urllib.error
from pathlib import Path
from xml.sax.saxutils import escape
from native_test_support import NexusTest
p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:18103');p.add_argument('--container',default='nexus-research-nuget');p.add_argument('--sdk-image',default='mcr.microsoft.com/dotnet/sdk:8.0');p.add_argument('--public-upstream',action='store_true');a=p.parse_args();t=NexusTest(a.url,a.container)
_,realmsraw=t.request('/service/rest/v1/security/realms/active');realms=json.loads(realmsraw);apikey='';reader='';reader_password='';reader_role='';reader_created=False;role_created=False
def run(command,cwd=None):
 result=subprocess.run(command,cwd=cwd,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180);output=result.stdout.replace(t.password,'[redacted]').replace(t.basic,'[redacted]');output=output.replace(apikey,'[redacted]')if apikey else output
 if reader_password:output=output.replace(reader_password,'[redacted]')
 if result.returncode:raise AssertionError(f'{command[0]} failed: {output[-8000:]}')
 return output
with tempfile.TemporaryDirectory(prefix='native-nuget-',dir=Path(__file__).resolve().parents[1]/'build')as d:
 root=Path(d);package='Nexus.Nuget.'+t.suffix
 def sdk(args,work=None,cache=None,extra=None):
  command=['docker','run','--rm','--platform','linux/amd64','--network','nexus-open_default','-v',str(root)+':/work','-w','/work'+('/'+work if work else''),'-e','DOTNET_CLI_HOME=/tmp/dotnet','-e','DOTNET_SKIP_FIRST_TIME_EXPERIENCE=true','-e','DOTNET_CLI_TELEMETRY_OPTOUT=1','-e','NUGET_PACKAGES=/work/'+(cache or 'packages')]
  if extra:
   for key,value in extra.items():command+=['-e',key+'='+value]
  return run(command+[a.sdk_image]+args)
 try:
  t.request('/service/rest/v1/security/realms/active','PUT',json.dumps(list(dict.fromkeys(realms+['NuGetApiKey']))).encode(),'application/json');_,keyraw=t.request('/service/rest/v1/security/nuget-apikey');apikey=json.loads(keyraw)['apiKey'];assert apikey
  hosted=t.repository('nuget','hosted');proxy=t.repository('nuget','proxy',remote='http://'+t.container+':8081/repository/'+hosted+'/v3/index.json');group=t.repository('nuget','group',members=[hosted,proxy])
  producer=root/'producer';producer.mkdir();producer.joinpath('package.csproj').write_text(f'<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><TargetFramework>net8.0</TargetFramework><PackageId>{package}</PackageId><Version>1.0.0</Version><Authors>Nexus Test</Authors><Description>Native NuGet acceptance fixture</Description></PropertyGroup></Project>');producer.joinpath('Probe.cs').write_text('namespace NexusProbe; public static class Value { public static string Get() => "native-nuget-ok"; }\n');producer.joinpath('NuGet.Config').write_text('<configuration><packageSources><clear/></packageSources></configuration>')
  sdk(['dotnet','pack','package.csproj','--output','/work/out'],'producer');nupkg=root/'out'/(package+'.1.0.0.nupkg');assert nupkg.exists();expected=nupkg.read_bytes()
  for repo in(hosted,proxy,group):
   work=root/repo;work.mkdir();url='http://'+t.container+':8081/repository/'+repo+'/v3/index.json';work.joinpath('NuGet.Config').write_text(f'<configuration><packageSources><clear/><add key="nexus" value="{escape(url)}" allowInsecureConnections="true"/></packageSources><packageSourceCredentials><nexus><add key="Username" value="admin"/><add key="ClearTextPassword" value="{escape(t.password)}"/></nexus></packageSourceCredentials></configuration>');work.joinpath('NuGet.Config').chmod(0o600)
   work.joinpath('consumer.csproj').write_text(f'<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><TargetFramework>net8.0</TargetFramework><OutputType>Exe</OutputType></PropertyGroup><ItemGroup><PackageReference Include="{package}" Version="1.0.0"/></ItemGroup></Project>');work.joinpath('Program.cs').write_text('System.Console.WriteLine(NexusProbe.Value.Get());\n')
  output=sdk(['sh','-c','dotnet nuget push /work/out/*.nupkg --source "$NEXUS_NUGET_SOURCE" --api-key "$NEXUS_NUGET_API_KEY" --no-symbols'],hosted,extra={'NEXUS_NUGET_SOURCE':'http://'+t.container+':8081/repository/'+hosted+'/v3/index.json','NEXUS_NUGET_API_KEY':apikey})
  for repo in(hosted,proxy,group):
   output=sdk(['dotnet','run','--project','consumer.csproj'],repo,cache='cache-'+repo);assert 'native-nuget-ok'in output,output
   output=sdk(['dotnet','package','search',package,'--configfile','NuGet.Config','--format','json'],repo);assert package in output,output
   cached=root/('cache-'+repo)/package.lower()/'1.0.0'/(package.lower()+'.1.0.0.nupkg');assert cached.exists()and hashlib.sha256(cached.read_bytes()).digest()==hashlib.sha256(expected).digest()
   _,versions=t.request('/repository/'+repo+'/v3-flatcontainer/'+package.lower()+'/index.json');assert json.loads(versions)['versions']==['1.0.0']
   _,registration=t.request('/repository/'+repo+'/registration/'+package.lower()+'/index.json');assert json.loads(registration)['items'][0]['items'][0]['catalogEntry']['id'].lower()==package.lower()
   _,search=t.request('/repository/'+repo+'/query?q='+package+'&take=10');assert json.loads(search)['totalHits']==1
   if repo!=group:
    _,components=t.request('/service/rest/v1/components?repository='+repo);entries=json.loads(components)['items'];assert any(item['name'].lower()==package.lower()and item['version']=='1.0.0'for item in entries),entries
  digest=base64.b64encode(hashlib.sha512(expected).digest()).decode();_,checksum=t.request('/repository/'+hosted+'/v3-flatcontainer/'+package.lower()+'/1.0.0/'+package.lower()+'.1.0.0.nupkg.sha512');assert checksum.decode()==digest
  # Invalid API key must not be accepted even when a valid Basic header is also supplied.
  request=urllib.request.Request(t.url+'/repository/'+hosted+'/v2/package',data=expected,method='PUT',headers={'Authorization':'Basic '+t.basic,'X-NuGet-ApiKey':'invalid','Content-Type':'application/octet-stream'})
  try:urllib.request.urlopen(request,timeout=30);raise AssertionError('Invalid API key accepted')
  except urllib.error.HTTPError as error:assert error.code==401,error.code
  headers={'Authorization':'Basic '+t.basic,'X-NuGet-ApiKey':apikey}
  with urllib.request.urlopen(urllib.request.Request(t.url+'/repository/'+hosted+'/v2/package/'+package+'/1.0.0',method='DELETE',headers=headers),timeout=30)as result:assert result.status==204
  _,search=t.request('/repository/'+hosted+'/query?q='+package);assert json.loads(search)['totalHits']==0
  _,still=t.request('/repository/'+hosted+'/v3-flatcontainer/'+package.lower()+'/1.0.0/'+package.lower()+'.1.0.0.nupkg');assert still==expected
  with urllib.request.urlopen(urllib.request.Request(t.url+'/repository/'+hosted+'/v2/package/'+package+'/1.0.0',method='POST',headers=headers,data=b''),timeout=30)as result:assert result.status==200
  # A group reader must not require direct grants on its hosted/proxy members.
  reader_role='native-nuget-group-reader-'+t.suffix;reader=reader_role;reader_password=os.urandom(20).hex();t.request('/service/rest/v1/security/roles','POST',{'id':reader_role,'name':reader_role,'description':'Temporary NuGet group-only read acceptance role','privileges':['nx-repository-view-nuget-'+group+'-read','nx-repository-view-nuget-'+group+'-browse'],'roles':[]});role_created=True;t.request('/service/rest/v1/security/users','POST',{'userId':reader,'firstName':'NuGet','lastName':'Reader','emailAddress':'native-nuget@example.invalid','password':reader_password,'status':'active','roles':[reader_role]});reader_created=True
  reader_basic=base64.b64encode((reader+':'+reader_password).encode()).decode();request=urllib.request.Request(t.url+'/repository/'+hosted+'/v3/index.json',headers={'Authorization':'Basic '+reader_basic})
  try:urllib.request.urlopen(request,timeout=30);raise AssertionError('Group reader received direct hosted access')
  except urllib.error.HTTPError as error:assert error.code in(401,403),error.code
  work=root/'group-reader';work.mkdir();work.joinpath('consumer.csproj').write_text((root/group/'consumer.csproj').read_text());work.joinpath('Program.cs').write_text((root/group/'Program.cs').read_text());work.joinpath('NuGet.Config').write_text(f'<configuration><packageSources><clear/><add key="nexus" value="http://{t.container}:8081/repository/{group}/v3/index.json" allowInsecureConnections="true"/></packageSources><packageSourceCredentials><nexus><add key="Username" value="{reader}"/><add key="ClearTextPassword" value="{reader_password}"/></nexus></packageSourceCredentials></configuration>');work.joinpath('NuGet.Config').chmod(0o600);output=sdk(['dotnet','run','--project','consumer.csproj'],'group-reader',cache='cache-reader');assert 'native-nuget-ok'in output,output
  output=sdk(['dotnet','package','search',package,'--configfile','NuGet.Config','--format','json'],'group-reader');assert package in output,output
  print('PASS NuGet: real dotnet pack/push/restore/build hosted/proxy/group; V3 flatcontainer/registration/search; SHA512; invalid API key; unlist/relist')
  if a.public_upstream:
   t.suffix+='p';public=t.repository('nuget','proxy',remote='https://api.nuget.org/v3/index.json');public_group=t.repository('nuget','group',members=[public])
   with urllib.request.urlopen('https://api.nuget.org/v3-flatcontainer/newtonsoft.json/13.0.3/newtonsoft.json.13.0.3.nupkg',timeout=60)as response:origin=response.read()
   for repo in(public,public_group):
    work=root/repo;work.mkdir();url='http://'+t.container+':8081/repository/'+repo+'/v3/index.json';work.joinpath('NuGet.Config').write_text(f'<configuration><packageSources><clear/><add key="nexus" value="{escape(url)}" allowInsecureConnections="true"/></packageSources><packageSourceCredentials><nexus><add key="Username" value="admin"/><add key="ClearTextPassword" value="{escape(t.password)}"/></nexus></packageSourceCredentials></configuration>');work.joinpath('NuGet.Config').chmod(0o600)
    work.joinpath('consumer.csproj').write_text('<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><TargetFramework>net8.0</TargetFramework><OutputType>Exe</OutputType></PropertyGroup><ItemGroup><PackageReference Include="Newtonsoft.Json" Version="13.0.3"/></ItemGroup></Project>');work.joinpath('Program.cs').write_text('System.Console.WriteLine(Newtonsoft.Json.JsonConvert.SerializeObject(new[]{"native-nuget-ok"}));\n')
    output=sdk(['dotnet','run','--project','consumer.csproj'],repo,cache='cache-'+repo);assert '["native-nuget-ok"]'in output,output
    cached=root/('cache-'+repo)/'newtonsoft.json'/'13.0.3'/'newtonsoft.json.13.0.3.nupkg';assert hashlib.sha256(cached.read_bytes()).digest()==hashlib.sha256(origin).digest()
    output=sdk(['dotnet','package','search','Newtonsoft.Json','--exact-match','--configfile','NuGet.Config','--format','json'],repo);assert 'Newtonsoft.Json'in output,output
    _,registration=t.request('/repository/'+repo+'/registration/newtonsoft.json/index.json');assert len(json.loads(registration)['items'])>0
    _,browse=t.request('/repository/'+repo+'/query?take=5&semVerLevel=2.0.0');page=json.loads(browse);assert len(page['data'])==5 and page['totalHits']>5,page
   print('PASS NuGet public upstream: actual nuget.org Newtonsoft.Json13.0.3 restore/build/search through proxy/group; archive SHA256 matches origin; registration pages')
 finally:
  try:
   if reader_created:t.request('/service/rest/v1/security/users/'+reader,'DELETE')
   if role_created:t.request('/service/rest/v1/security/roles/'+reader_role,'DELETE')
  finally:
   try:t.cleanup()
   finally:t.request('/service/rest/v1/security/realms/active','PUT',realmsraw,'application/json')
