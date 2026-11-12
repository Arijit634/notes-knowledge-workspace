#!/usr/bin/env python3
"""Read selected runtime ELF/JNI imports; never execute copied native libraries.

This is evidence about call-site reachability, not a claim libc is absent/fixed.
The image is an unstarted qualification artifact without runtime credentials.
"""
import hashlib,io,json,pathlib,re,shlex,subprocess,zipfile

REGEX={'regcomp','regexec','regfree','re_compile_pattern','re_compile_fastmap',
       're_search','re_search_2','re_match','re_match_2','re_set_registers'}
WIDE=re.compile(r'^(?:__[^ ]*)?(?:v?swscanf|v?fwscanf|v?wscanf)(?:_chk)?$')

def references(output):
    regex=set();wide=set()
    for line in output.splitlines():
        found=re.search(r'\bUND\s+(\S+)',line)
        if not found:continue
        name=found[1].split('@',1)[0]
        if name in REGEX or name.removeprefix('__') in REGEX:regex.add(name)
        if WIDE.fullmatch(name):wide.add(name)
    return dict(regex=sorted(regex),wideScanf=sorted(wide))

def command(*args):
    result=subprocess.run(args,capture_output=True,timeout=90)
    if result.returncode:raise ValueError('runtime_symbol_audit_command_failed')
    if len(result.stdout)>16*1024*1024:raise ValueError('runtime_symbol_output_exceeded')
    return result.stdout

def audit(image,container,jar,directory,architecture,full,inspect):
    if architecture not in ('amd64','arm64'):raise ValueError('symbol_audit_architecture')
    candidates=inspect('''for f in /jdk/bin/java /jdk/lib/jspawnhelper /jdk/lib/*.so /jdk/lib/*/*.so 
      /opt/pdf/helper /opt/video/helper /opt/operations/health-probe /opt/operations/service-boundary
      /opt/pdf/runtime/bin/java /opt/pdf/runtime/lib/jspawnhelper /opt/pdf/runtime/lib/*.so /opt/pdf/runtime/lib/*/*.so
      /opt/video/runtime/bin/java /opt/video/runtime/lib/*.so /opt/video/runtime/lib/*/*.so
      /opt/video/tools/bin/ffmpeg /opt/video/tools/bin/ffprobe /opt/video/tools/*.so*
      /usr/lib/*-linux-gnu/libfontconfig.so.1 /usr/lib/*-linux-gnu/libfreetype.so.6; do
      test ! -f "$f" || printf '%s\\n' "$f"; done'''.replace('\n',' '))
    paths=set(candidates.splitlines())
    if full:paths.update(('/usr/sbin/nginx','/usr/local/bin/redis-server','/bin/bash'))
    supervisor_matches=False
    if full:
        source=pathlib.Path(__file__).resolve().parents[2]/'.github/deploy/managed-preview/start-full.sh'
        actual=inspect('cat /app/start-full.sh').encode()
        if actual!=source.read_bytes().rstrip(b'\n'):raise ValueError('supervisor_source_binding_failed')
        supervisor_matches=True
    if len(paths)<10 or len(paths)>256 or any(not re.fullmatch(r'/[A-Za-z0-9_./+\-]+',p) for p in paths):raise ValueError('runtime_symbol_paths_invalid')
    linkage=inspect('LD_LIBRARY_PATH=/jdk/lib:/jdk/lib/server:/opt/pdf/runtime/lib:/opt/pdf/runtime/lib/server:/opt/video/runtime/lib:/opt/video/runtime/lib/server:/opt/video/tools ldd '
        +' '.join(shlex.quote(path) for path in sorted(paths)))
    missing=sorted(set(re.findall(r'^\s*(\S+)\s+=>\s+not found\s*$',linkage,re.M)))
    # jlink ships optional desktop/device backends too. Their absent X11/ALSA
    # dependencies are retained as facts; the existing core/native linkage gate
    # remains unchanged. No unrecognized missing dependency is accepted here.
    optional={'libX11.so.6','libXrender.so.1','libXtst.so.6','libXi.so.6','libXext.so.6',
        'libxcb.so.1','libXau.so.6','libXdmcp.so.6','libasound.so.2'}
    if not set(missing).issubset(optional):raise ValueError('runtime_symbol_linkage_incomplete')
    for line in linkage.splitlines():
        for path in re.findall(r'(?:=>\s+|^\s*)(/[A-Za-z0-9_./+\-]+)(?:\s|$)',line):paths.add(path)
    if len(paths)>512:raise ValueError('runtime_symbol_dependency_bound')
    expected_machine=62 if architecture=='amd64' else 183
    root=pathlib.Path(directory)/'symbol-audit';root.mkdir()
    records=[];cache={};total=0;embedded=0
    def record(path,label,foreign_ok=False):
        nonlocal total
        size=path.stat().st_size
        if not 64<=size<=128*1024*1024:raise ValueError('runtime_symbol_file_bound')
        with path.open('rb') as stream:header=stream.read(64)
        if header[:4]!=b'\x7fELF' or header[5] not in (1,2):raise ValueError('runtime_symbol_elf_required')
        if header[4:6]!=b'\x02\x01' or int.from_bytes(header[18:20],'little' if header[5]==1 else 'big')!=expected_machine:
            if foreign_ok:return
            raise ValueError('runtime_symbol_machine_mismatch')
        total+=size
        if total>1024*1024*1024 or len(records)>=512:raise ValueError('runtime_symbol_total_bound')
        with path.open('rb') as input:digest=hashlib.file_digest(input,'sha256').hexdigest()
        if digest not in cache:cache[digest]=references(command('readelf','--dyn-syms','--wide',str(path)).decode('utf-8',errors='strict'))
        records.append(dict(path=label,sha256=digest,**cache[digest]))
    for index,path in enumerate(sorted(paths)):
        target=root/('elf-'+str(index))
        command('docker','cp','--follow-link',container+':'+path,str(target))
        record(target,path)
        target.unlink()
    # Include same-architecture JNI assets dynamically extracted from nested
    # application dependencies (e.g. JNA/Argon2), not only ldd's startup closure.
    with zipfile.ZipFile(jar) as outer:
        for entry in outer.infolist():
            if not entry.filename.startswith('BOOT-INF/lib/') or not entry.filename.endswith('.jar'):continue
            if entry.file_size>64*1024*1024:raise ValueError('runtime_symbol_nested_jar_bound')
            with zipfile.ZipFile(io.BytesIO(outer.read(entry))) as inner:
                for native in inner.infolist():
                    if not native.filename.endswith('.so'):continue
                    if not 64<=native.file_size<=64*1024*1024:raise ValueError('runtime_symbol_jni_bound')
                    target=root/'jni-elf';target.write_bytes(inner.read(native))
                    before=len(records);record(target,entry.filename+'!/'+native.filename,True)
                    embedded+=int(len(records)>before);target.unlink()
    if not any(path.endswith('/libc.so.6') for path in paths) or len(records)<10:raise ValueError('runtime_symbol_coverage_incomplete')
    return dict(schemaVersion=1,image=image,architecture=architecture,selectedRuntimeAndLinkageInspected=True,
        inspectedElfFiles=len(records),sameArchitectureJniFiles=embedded,vulnerableLibcPresent=True,
        regexConsumers=[r for r in records if r['regex']],wideScanfConsumers=[r for r in records if r['wideScanf']],
        supervisorScriptMatchesSource=supervisor_matches,
        absentOptionalDesktopDependencies=missing,
        inspectedSourceBytes=total,inventorySha256=hashlib.sha256(json.dumps(records,sort_keys=True,separators=(',',':')).encode()).hexdigest(),
        copiedNativeCodeExecuted=False,runtimeCredentialsUsed=False,glibcDeclaredFixed=False)
