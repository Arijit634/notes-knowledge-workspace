"""Add the verified source-built Redis component without rewriting scanner output."""
import json
import os
from pathlib import Path


def complete(source, destination):
    if source.resolve() == destination.resolve():
        raise ValueError('Scanner input and final SBOM must be separate files')
    document = json.loads(source.read_text())
    document.setdefault('components', []).append(dict(type='application', name='redis-server', version='8.10.2',
        properties=[dict(name='nkw:unmodified-source-sha256',
            value='ae6973de9b6ad8e6cf21b1e764abccab59200689af6aabce10d4cf6bbb2e253f')]))
    with destination.open('x') as output:
        output.write(json.dumps(document))


if __name__ == '__main__':
    root = Path(os.environ['RUNNER_TEMP'])
    complete(root / 'preview-raw.cdx.json', root / 'preview.cdx.json')
