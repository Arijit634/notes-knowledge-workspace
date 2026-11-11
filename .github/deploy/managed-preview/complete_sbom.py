"""Add the verified source-built Redis component without rewriting scanner output."""
import json
import os
from pathlib import Path
import re


def complete(source, destination):
    if source.resolve() == destination.resolve():
        raise ValueError('Scanner input and final SBOM must be separate files')
    document = json.loads(source.read_text())
    document.setdefault('components', []).append(dict(type='application', name='redis-server', version='8.10.2',
        properties=[dict(name='nkw:unmodified-source-sha256',
            value='ae6973de9b6ad8e6cf21b1e764abccab59200689af6aabce10d4cf6bbb2e253f')]))
    with destination.open('x') as output:
        output.write(json.dumps(document))


def complete_full(source, destination, shipped):
    """Retain scanner components and bind the two source-built bundle artifacts."""
    if source.resolve() == destination.resolve():
        raise ValueError('Scanner input and final SBOM must be separate files')
    codec_paths=[p for p in shipped if re.fullmatch(
        r'/usr/lib/(x86_64|aarch64)-linux-gnu/libopenh264\.so\.2\.6\.0',p)]
    if (len(codec_paths)!=1 or set(shipped)!={'/usr/local/bin/redis-server',codec_paths[0]}
            or any(not re.fullmatch(r'[0-9a-f]{64}',digest) for digest in shipped.values())):
        raise ValueError('Exact source-built artifact hashes required')
    document=json.loads(source.read_text())
    root=document['metadata']['component']['bom-ref']
    dependencies=document.setdefault('dependencies',[])
    parent=next((item for item in dependencies if item['ref']==root),None)
    if parent is None:
        parent=dict(ref=root,dependsOn=[]);dependencies.append(parent)
    for path,name,version,source_hash in (
        ('/usr/local/bin/redis-server','redis-server','8.10.3',
            '5e0c00ed77f789fb1a4a30f1fbb58a793cc88c41c0f11f0e38e1b4f192991188'),
        (codec_paths[0],'openh264-noble-source-build','2.6.0+dfsg-2nkw1',
            '52fc65775e03abe70f3f453c3c57fa7b31c71fe240de84da53d75f2ddc7dd519')):
        reference='urn:nkw:artifact:'+name+':'+shipped[path]
        document.setdefault('components',[]).append(dict(type='application' if name=='redis-server' else 'library',
            name=name,version=version,**{'bom-ref':reference},hashes=[dict(alg='SHA-256',content=shipped[path])],
            properties=[dict(name='nkw:shipped:path',value=path),dict(name='nkw:source:sha256',value=source_hash),
                dict(name='nkw:scanner:coverage',value='explicit binary/source inventory; not invented vulnerability coverage')]))
        parent.setdefault('dependsOn',[]).append(reference)
    refs=[item.get('bom-ref') for item in document['components']]
    if len(refs)!=len(set(refs)):raise ValueError('Duplicate SBOM component identity')
    with destination.open('x') as output:output.write(json.dumps(document))


if __name__ == '__main__':
    root = Path(os.environ['RUNNER_TEMP'])
    complete(root / 'preview-raw.cdx.json', root / 'preview.cdx.json')
