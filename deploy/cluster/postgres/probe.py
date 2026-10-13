#!/opt/patroni/bin/python
import ssl
import sys
import urllib.request

context = ssl.create_default_context(cafile='/run/nexus-postgres/tls/ca.crt')
request = urllib.request.Request('https://localhost:8008/' + sys.argv[1])
with urllib.request.urlopen(request, context=context, timeout=3) as response:
    if response.status != 200:
        raise SystemExit(1)
