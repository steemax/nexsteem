#!/usr/bin/env python3
"""Runtime regression for auth/cookie scoping on RubyGems and Yum proxy redirects."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from native_test_support import NexusTest

ROOT = Path(__file__).resolve().parents[1]
NETWORK = 'nexus-ha_default'
CLIENT = 'nexus-ha-ruby-yum-client'
BODY = b'nexus-proxy-origin-scoping-proof\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:18125')
    parser.add_argument('--container', default='nexus-ha-ruby-yum')
    parser.add_argument('--forward-proxy', action='store_true', help='Also require authenticated Core outbound proxy HTTP 407')
    args = parser.parse_args()
    if not args.container.startswith('nexus-ha-'):
        raise ValueError('Use only the isolated test-suite Nexus fixture')
    test = NexusTest(args.url, args.container)
    fixture = 'nexus-ha-proxy-auth-' + test.suffix
    basic = 'Basic ' + base64.b64encode(('fixture:' + test.suffix).encode()).decode()
    bearer = 'fixture-bearer-' + test.suffix
    proxy_auth = 'Basic ' + base64.b64encode(('transport:' + test.suffix).encode()).decode()
    _, raw = test.request('/service/rest/v1/security/ssrf-protection')
    original = json.loads(raw)
    started = False
    settings_original = None
    settings_modified = False
    def direct(method, data):
        _, raw = test.request('/service/extdirect', 'POST', {'action': 'coreui_HttpSettings', 'method': method,
                                                          'data': data, 'type': 'rpc', 'tid': 1})
        response = json.loads(raw)
        if isinstance(response, list): response = response[0]
        assert response.get('type') == 'rpc', 'HTTP settings Direct API failed'
        result = response.get('result')
        if isinstance(result, dict) and 'success' in result:
            assert result['success'] is True
            return result.get('data')
        return result
    try:
        with tempfile.TemporaryDirectory(prefix='nexus-proxy-auth-') as directory:
            env = Path(directory) / 'fixture.env'
            env.write_text(f'FIXTURE_BASIC={basic}\nFIXTURE_BEARER={bearer}\nFIXTURE_PROXY_AUTH={proxy_auth}\nFIXTURE_HOST={fixture}\n')
            env.chmod(0o600)
            subprocess.run(['docker', 'run', '-d', '--name', fixture, '--network', NETWORK,
                            '--env-file', str(env), '--mount', f'type=bind,src={ROOT / "scripts/proxy-auth-fixture.py"},dst=/fixture.py,readonly',
                            CLIENT, 'python3', '/fixture.py'], check=True, stdout=subprocess.DEVNULL)
            started = True
            for attempt in range(30):
                result = subprocess.run(['docker', 'exec', fixture, 'curl', '--fail', '--silent', '--max-time', '1',
                                         f'http://{fixture}:8090/health'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                if result.returncode == 0: break
                if attempt == 29: raise AssertionError('Private fixture did not start')
                time.sleep(1)
            allowed = dict(original)
            allowed['allowedDomains'] = list(original.get('allowedDomains', [])) + [fixture]
            test.request('/service/rest/v1/security/ssrf-protection', 'PUT', allowed)
            if args.forward_proxy:
                settings_original = direct('read', [])
                assert not settings_original.get('httpEnabled') and not settings_original.get('httpsEnabled'), 'Refuse to overwrite an existing configured proxy'
                updated = dict(settings_original)
                updated.update({'httpEnabled': True, 'httpHost': fixture, 'httpPort': 8092,
                                'httpAuthEnabled': True, 'httpAuthUsername': 'transport', 'httpAuthPassword': test.suffix})
                settings_modified = True
                direct('update', [updated])
            results = []
            for format_name, extension in [('rubygems', 'gem'), ('yum', 'rpm')]:
                for auth_kind in ('basic', 'bearer'):
                    name = f'proxy-auth-{format_name}-{auth_kind}-{test.suffix}'
                    auth = ({'type': 'username', 'username': 'fixture', 'password': test.suffix}
                            if auth_kind == 'basic' else {'type': 'bearerToken', 'bearerToken': bearer})
                    if args.forward_proxy and auth_kind == 'basic': auth['preemptive'] = True
                    settings = {'name': name, 'online': True,
                                'storage': {'blobStoreName': 'default', 'strictContentTypeValidation': False},
                                'proxy': {'remoteUrl': f'http://{fixture}:8090/{auth_kind}/', 'contentMaxAge': 1440, 'metadataMaxAge': 1440},
                                'negativeCache': {'enabled': False, 'timeToLive': 1},
                                'httpClient': {'blocked': False, 'autoBlock': False, 'authentication': auth,
                                               'connection': {'enableCookies': True}}}
                    test.request(f'/service/rest/v1/repositories/{format_name}/proxy', 'POST', settings)
                    test.created.append(name)
                    def path(action):
                        return (f'/gems/{action}-proof.gem' if format_name == 'rubygems' else f'/{action}/proof.rpm')
                    for action in ('good', 'same'):
                        _, content = test.request(f'/repository/{name}' + path(action))
                        assert content == BODY
                    for action in ('trap', 'loop', 'blocked'):
                        request = urllib.request.Request(args.url + f'/repository/{name}' + path(action),
                                                         headers={'Authorization': 'Basic ' + test.basic})
                        try:
                            urllib.request.urlopen(request, timeout=30)
                            raise AssertionError('Unsafe/rejected upstream response was accepted: ' + action)
                        except urllib.error.HTTPError as error:
                            assert error.code in (400, 401, 403, 404, 500, 502, 503), error.code
                    results.append({'format': format_name, 'authentication': auth_kind, 'same_origin_authentication': True,
                                    'foreign_redirect_download_sha256': hashlib.sha256(content).hexdigest()})
            events = [json.loads(line) for line in subprocess.check_output(
                ['docker', 'exec', fixture, 'cat', '/tmp/proxy-auth-events.jsonl'], text=True).splitlines()]
            foreign = [event for event in events if event['port'] == 8091]
            if args.forward_proxy:
                transport = [event for event in events if event['port'] == 8092]
                assert any(not event['proxy_credentials_match'] for event in transport), '407 challenge was not exercised'
                assert any(event['proxy_credentials_match'] and event['target_port'] == 8091 for event in transport), 'Foreign CDN transport was not authenticated'
            assert len(foreign) == 8, len(foreign)
            assert all(not event['authorization_present'] and not event['cookie_present'] for event in foreign), foreign
            assert any(event['cookie_present'] for event in events if event['port'] == 8090), 'Origin cookies were not exercised'
            for kind in ('basic', 'bearer'):
                assert any(event[kind + '_matches'] for event in events if event['port'] == 8090)
            # Basic challenge adds one initial unauthenticated request; six authorized attempts are the maximum.
            for kind in ('basic', 'bearer'):
                loops = [event for event in events if event['port'] == 8090 and event['path'].startswith(f'/{kind}/') and event['action'] == 'loop'
                         and event[kind + '_matches']]
                assert len(loops) == 12, (kind, len(loops))
            image_id = subprocess.check_output(['docker', 'inspect', args.container, '--format', '{{.Image}}'], text=True).strip()
            provenance = json.loads(subprocess.check_output(['docker', 'exec', args.container, 'cat',
                                                             '/opt/nexus/EXTENSIONS-PROVENANCE.json'], text=True))
            report = {'image_id': image_id, 'application_sha256': provenance['application_sha256'],
                      'results': results, 'foreign_authorization_absent': True, 'foreign_cookie_absent': True,
                      'foreign_basic_challenge_never_retried_with_credentials': True, 'redirect_limit_five': True,
                      'private_address_redirect_rejected': True, 'events': events}
            if args.forward_proxy: report['configured_forward_proxy_407_and_foreign_401'] = True
            report_name = 'ruby-yum-proxy-auth-forward.json' if args.forward_proxy else 'ruby-yum-proxy-auth.json'
            (ROOT / 'build/logs' / report_name).write_text(json.dumps(report, indent=2) + '\n')
            print(json.dumps({key: value for key, value in report.items() if key != 'events'}, indent=2))
    finally:
        try:
            test.cleanup()
        finally:
            try:
                if settings_modified: direct('update', [settings_original])
            finally:
                try:
                    test.request('/service/rest/v1/security/ssrf-protection', 'PUT', original)
                finally:
                    if started:
                        subprocess.run(['docker', 'rm', '-f', fixture], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
