#!/usr/bin/env python3
"""Read-only artifact facts for review. No risk acceptance or release verdict."""
import argparse,hashlib,json,os,pathlib,platform,re,subprocess,tempfile,uuid,zipfile

ROOT=pathlib.Path(__file__).resolve().parents[2]

def require(condition,code):
    if not condition:raise ValueError(code)

def command(*args):
    result=subprocess.run(args,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=90)
    require(result.returncode==0,'artifact_fact_command_failed')
    return result.stdout

def package_inventory(packages):
    versions={}
    for row in packages.splitlines():
        name,version,source=row.split('\t')
        name=name.split(':')[0]
        require(name not in versions,'duplicate_package_identity')
        versions[name]=dict(version=version,source=source)
    require(versions.get('libfreetype6',{}).get('version')=='2.15.0-0nkw1'
        and versions.get('libexpat1',{}).get('version')=='2.10.0-0nkw2','security_library_version_mismatch')
    return versions

def validate_images(metadata,source):
    require(len(metadata)==2,'two_artifacts_required')
    architecture=metadata[0].get('Architecture')
    require(architecture in ('amd64','arm64'),'qualified_architecture_required')
    require(all(m.get('Architecture')==architecture and m.get('Config',{}).get('User')=='65533:65533'
        and m.get('Config',{}).get('Labels',{}).get('org.opencontainers.image.revision')==source
        for m in metadata),'artifact_binding_failed')
    return architecture

def production_fingerprint():
    paths=['src/main','pom.xml','frontend','.github/scripts','.github/deploy','.github/workflows']
    rows=command('git','ls-tree','-r','-z','HEAD','--',*paths).split(b'\0')
    entries=[]
    for row in rows:
        if not row:continue
        metadata,path=row.split(b'\t',1);mode,kind,blob=metadata.split()
        require(kind==b'blob','unexpected_production_tree_entry')
        entries.append((path,blob))
    raw=subprocess.run(['git','cat-file','--batch'],cwd=ROOT,input=b''.join(blob+b'\n' for _,blob in entries),
        stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,check=True,timeout=90).stdout
    digest=hashlib.sha256();offset=0
    for path,blob in entries:
        end=raw.index(b'\n',offset);header=raw[offset:end].split()
        require(header[:2]==[blob,b'blob'],'source_blob_mismatch')
        size=int(header[2]);data=raw[end+1:end+1+size];offset=end+2+size
        require(len(data)==size,'source_blob_truncated')
        digest.update(path+b'\0'+size.to_bytes(8,'big')+data)
    require(offset==len(raw),'unexpected_source_blob_output')
    return dict(sha256=digest.hexdigest(),files=len(entries),scope=paths,
        policyDirectoryExcluded=True,internalFilesExcluded=True)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('deployment');parser.add_argument('qualification');parser.add_argument('source');parser.add_argument('output')
    args=parser.parse_args()
    require(platform.system()=='Linux','native_linux_required')
    for image in (args.deployment,args.qualification):require(re.fullmatch(r'sha256:[0-9a-f]{64}',image),'exact_image_required')
    require(re.fullmatch(r'[0-9a-f]{40}',args.source),'exact_source_required')
    require(command('git','rev-parse','HEAD').decode().strip()==args.source
        and not command('git','status','--porcelain').strip(),'clean_source_required')
    output=pathlib.Path(args.output);require(not output.exists(),'existing_evidence_must_be_preserved')
    metadata=[json.loads(command('docker','image','inspect',image))[0] for image in (args.deployment,args.qualification)]
    architecture=validate_images(metadata,args.source)
    common=['docker','run','--rm','--network','none','--user','65533:65533','--cap-drop','ALL',
        '--security-opt','no-new-privileges','--read-only','--memory','256m','--cpus','1','--pids-limit','64']
    def inspect(script):return command(*common,'--entrypoint','/bin/sh',args.deployment,'-c',script).decode().strip()
    packages=inspect("dpkg-query -W -f='${binary:Package}\\t${Version}\\t${source:Package}\\n'")
    versions=package_inventory(packages)
    abi=json.loads(command(*common,'--entrypoint','/qualification/media-library-versions',args.qualification))
    require(abi==dict(mediaLibraryVersions=True,freetype='2.15.0',expat='2.10.0',abiProbePassed=True),'actual_library_abi_failed')
    allocation=json.loads(command(*common,'--entrypoint','/qualification/allocation-overflow-probe',args.qualification))
    require(allocation==dict(alignedAllocationOverflowRejected=True,cases=30),'aligned_allocation_check_failed')
    backport={
        'bf26290d2f021111cf806753dcb87d165f53571b':'56b71deecec296aba42cd80ebde30215527aec9fa5c3ea183d0a056f2bb1520e',
        '3d77567612994172f9d09b46f31bb604e16fb08d':'42caed50fae3f88186155b0c4280025a89913a83aed2a02986b545e0a8d1e7ed',
        '05075975dc246f5f6c0757d532f22f518850e7af':'89d6e66a1c346ffcb57abfaf5fc6b251b351d6dcc778270e09ea513ab9e4eb89'}
    for commit,digest in backport.items():
        require(inspect('sha256sum /provenance/media-libraries/expat-'+commit+'.patch').split()[0]==digest,
            'expat_backport_provenance_mismatch')
    components={}
    probes={
        'infocmp':'/usr/bin/infocmp','tic':'/usr/bin/tic','aptKey':'/usr/bin/apt-key',
        'perl':'/usr/bin/perl','tar':'/usr/bin/tar','mount':'/usr/bin/mount','nsenter':'/usr/bin/nsenter',
        'login':'/usr/bin/login','chown':'/usr/bin/chown','chgrp':'/usr/bin/chgrp','diff3':'/usr/bin/diff3',
        'sort':'/usr/bin/sort','uniq':'/usr/bin/uniq','unexpand':'/usr/bin/unexpand',
        'systemdHomed':'/usr/lib/systemd/systemd-homed','systemdOomd':'/usr/lib/systemd/systemd-oomd',
        'systemdJournald':'/usr/lib/systemd/systemd-journald','nscd':'/usr/sbin/nscd',
        'pngimage':'/usr/bin/pngimage','pnm2png':'/usr/bin/pnm2png','bzip2recover':'/usr/bin/bzip2recover'}
    for name,path in probes.items():
        # Paths are the fixed developer-owned constants above, never caller input.
        value=inspect(f"if test -e {path} || test -L {path}; then printf present; else printf absent; fi")
        require(value in ('present','absent'),'unexpected_component_fact')
        components[name]=dict(path=path,absent=value=='absent')
    privileged=inspect("find /usr /opt /jdk -xdev -type f -perm /6000 -print -quit")
    fonts=inspect("find /etc/fonts /usr/share/fonts /usr/share/fontconfig -type f \\( ! -uid 0 -o -perm /022 \\) -print -quit")
    linkage=inspect('cat /provenance/runtime-linkage.txt')
    removals=inspect('cat /provenance/removed-runtime-tools.sha256')
    locales={key:None for key in ('LANG','LC_ALL','LANGUAGE','LOCALDOMAIN')}
    for entry in metadata[0]['Config'].get('Env',[]):
        key,_,value=entry.partition('=')
        if key in locales:locales[key]=value
    locale_safe=all(locales[key] in (None,'','C','C.UTF-8','C.utf8','POSIX') for key in ('LANG','LC_ALL'))
    locale_safe=locale_safe and not locales['LANGUAGE'] and not locales['LOCALDOMAIN']
    name='native-facts-'+uuid.uuid4().hex
    temporary_parent=pathlib.Path(os.environ['RUNNER_TEMP']).resolve()
    with tempfile.TemporaryDirectory(prefix='native-facts-',dir=temporary_parent) as directory:
        jar=pathlib.Path(directory)/'application.jar'
        created=False
        try:
            size=int(inspect('stat -c %s /application.jar'));require(0<size<=256*1024*1024,'application_size_out_of_bounds')
            command('docker','create','--name',name,'--network','none','--read-only',args.deployment);created=True
            command('docker','cp',name+':/application.jar',str(jar))
            require(jar.stat().st_size==size,'copied_artifact_size_mismatch')
            with zipfile.ZipFile(jar) as archive:
                dependencies=sorted(n for n in archive.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar'))
                require(len(dependencies)>=200,'packaged_dependencies_missing')
                sqlite=any('sqlite' in n.casefold() for n in dependencies)
            jar_sha=hashlib.sha256(jar.read_bytes()).hexdigest()
        finally:
            if created:command('docker','rm',name)
    receipt=dict(schemaVersion=1,source=args.source,deploymentImage=args.deployment,qualificationImage=args.qualification,
        architecture=architecture,imageUser='65533:65533',productionSourceFingerprint=production_fingerprint(),
        packageInventorySha256=hashlib.sha256(packages.encode()).hexdigest(),packages=versions,
        mediaLibraryAbi=abi,expatBackportPatchSha256=backport,alignedAllocationCheck=allocation,
        components=components,setuidSetgidFilesAbsent=not privileged,
        fontConfigurationRootOwnedAndNotWritable=not fonts,defaultLocaleInputsRestricted=locale_safe,
        nativeLinkageMentionsSqlite='sqlite' in linkage.casefold(),sqliteApplicationDependencyPresent=sqlite,
        applicationJarSha256=jar_sha,packagedJavaDependencies=len(dependencies),
        runtimeToolRemovalInventorySha256=hashlib.sha256(removals.encode()).hexdigest(),
        healthCheck=metadata[0]['Config'].get('Healthcheck',{}),
        liveDataOrProviderAccess=False,rawLogsOrEnvironmentValuesRetained=False,
        riskDispositionsApproved=False,releaseGatePassed=False)
    output.parent.mkdir(parents=True,exist_ok=True)
    with output.open('x') as file:file.write(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(dict(nativeArtifactFacts=True,source=args.source,image=args.deployment,
        architecture=architecture,riskDispositionsApproved=False,releaseGatePassed=False)))

if __name__=='__main__':main()
