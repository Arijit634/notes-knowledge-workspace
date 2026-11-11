#!/usr/bin/env python3
"""FAST controlled scanner regression; generated fixtures have no real authority."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[2]
IMAGE='ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f'


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--scanner',type=Path);args=parser.parse_args()
    if args.scanner:
        version=subprocess.run([str(args.scanner),'version'],capture_output=True,text=True,check=True,timeout=10)
        if version.stdout.strip()!='8.30.1':raise ValueError('Pinned scanner required')
    code=next(line for line in (ROOT/'.github/scripts/test-operations.py').read_text().splitlines()
              if line.strip().startswith('required=[') and 'native64BitAbi' in line)+'\n'
    # Deliberately generated and never used with any provider or account.
    positive='linkedin'+'_client_id = "'+'86'+''.join('abcdefgh')+'1234"\n'
    cases=[]
    with tempfile.TemporaryDirectory() as temporary:
        baseline=Path(temporary)/'baseline.toml';baseline.write_text('[extend]\nuseDefault=true\n')
        for name,payload,original,expected in (
            ('original-control-list',code,True,1),('exact-control-list',code,False,0),
            ('synthetic-client-id',positive,False,1),('control-list-with-extra-id',code.rstrip()+' '+positive,False,1)):
            if args.scanner:
                command=[str(args.scanner),'stdin','--config='+str(baseline if original else ROOT/'.gitleaks.toml')]
            else:
                command=['docker','run','--rm','-i','--network','none','--read-only','--cap-drop','ALL',
                    '--security-opt','no-new-privileges','-v',str(ROOT)+':/repo:ro',
                    '-v',str(baseline)+':/baseline.toml:ro',IMAGE,'stdin',
                    '--config=/baseline.toml' if original else '--config=/repo/.gitleaks.toml']
            result=subprocess.run(command+['--redact','--no-banner'],input=payload.encode(),capture_output=True,timeout=45)
            if result.returncode!=expected:raise ValueError('Scanner contract failed: '+name)
            cases.append(dict(case=name,exitCode=result.returncode,expected=expected))
    print(json.dumps(dict(passed=True,checks=len(cases),cases=cases,realCredentialUsed=False,
        positiveDetectionPreserved=True,networkAccessFromScanner=False)))


if __name__=='__main__':main()
