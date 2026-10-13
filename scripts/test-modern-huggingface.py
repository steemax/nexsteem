#!/usr/bin/env python3
"""Real Hugging Face Hub SDK download/snapshot over a Nexus repository endpoint."""
import argparse
import hashlib
import json
import os
import pathlib
import struct
import tempfile
from native_test_support import NexusTest

os.environ['HF_HUB_DISABLE_PROGRESS_BARS'] = '1'
os.environ['HF_HUB_DISABLE_XET'] = '1'
os.environ['HF_HUB_DISABLE_IMPLICIT_TOKEN'] = '1'
from huggingface_hub import hf_hub_download, snapshot_download, __version__

parser = argparse.ArgumentParser()
parser.add_argument('--state', default='build/modern-work/state.json')
parser.add_argument('--public', action='store_true', help='Also exercise the real public Hub and its CDN.')
parser.add_argument('--private-bearer', action='store_true', help='Require a bearer token at the controlled Hub upstream.')
args = parser.parse_args()
assert not (args.public and args.private_bearer), 'Choose one upstream mode'
state = json.loads(pathlib.Path(args.state).read_text())
n = NexusTest(state['url'], state['container'])
if args.public or args.private_bearer:
    repository = ('public-hf-' if args.public else 'bearer-hf-') + n.suffix
    settings = {
        'name': repository, 'online': True,
        'storage': {'blobStoreName': 'default', 'strictContentTypeValidation': True},
        'proxy': {'remoteUrl': 'https://huggingface.co/' if args.public else 'http://nexus-modern-upstream:8080/private-huggingface/', 'contentMaxAge': 1440, 'metadataMaxAge': 1440},
        'negativeCache': {'enabled': True, 'timeToLive': 1440},
        'httpClient': {'blocked': False, 'autoBlock': False},
    }
    if args.private_bearer:
        settings['httpClient']['authentication'] = {'type': 'bearerToken', 'bearerToken': 'fixture-upstream-token'}
    n.request('/service/rest/v1/repositories/huggingface/proxy', 'POST', settings)
    n.created.append(repository)
    model = 'hf-internal-testing/tiny-random-bert' if args.public else 'fixture/tiny'
else:
    repository = n.repository('huggingface', 'proxy', remote='http://nexus-modern-upstream:8080/huggingface/')
    model = 'fixture/tiny'
endpoint = state['url'] + '/repository/' + repository
options = {'endpoint': endpoint, 'headers': {'Authorization': 'Basic ' + n.basic}, 'token': False}
try:
    with tempfile.TemporaryDirectory(dir=pathlib.Path(args.state).resolve().parent, prefix='hf-sdk-') as root:
        root = pathlib.Path(root)
        single = pathlib.Path(hf_hub_download(model, 'config.json', cache_dir=root / 'single', force_download=True, **options))
        config = single.read_bytes()
        assert json.loads(config)['model_type'] == 'bert'
        snapshot = pathlib.Path(snapshot_download(model, revision=single.parent.name, cache_dir=root / 'snapshot', force_download=True,
                                                max_workers=2, allow_patterns=['config.json', 'model.safetensors'], **options))
        assert (snapshot / 'config.json').read_bytes() == config
        tensor = (snapshot / 'model.safetensors').read_bytes()
        size = struct.unpack('<Q', tensor[:8])[0]
        metadata = json.loads(tensor[8:8 + size])
        assert isinstance(metadata, dict) and any(key != '__metadata__' for key in metadata)
        if not args.public:
            assert config == b'{"model_type":"bert","hidden_size":8}\n'
            assert snapshot.name == 'a' * 40
            assert metadata['weight']['shape'] == [2]
            assert struct.unpack('<ff', tensor[8 + size:]) == (1.0, 2.0)
        else:
            assert len(snapshot.name) == 40 and 0 < len(tensor) < 1024 * 1024
        offline = pathlib.Path(hf_hub_download(model, 'config.json', cache_dir=root / 'single', local_files_only=True, **options))
        assert offline.read_bytes() == config
        print('PASS huggingface_hub', __version__, 'hf_hub_download, paginated snapshot_download, config SHA256',
              hashlib.sha256(config).hexdigest(), 'safetensors SHA256', hashlib.sha256(tensor).hexdigest(),
              'commit', snapshot.name, 'and offline SDK cache through Nexus endpoint', 'public Hub/CDN' if args.public else '2-page bearer fixture' if args.private_bearer else '2-page fixture')
finally:
    n.cleanup()
