#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" != 0
case "$(uname -m)" in x86_64|aarch64) ;; *) exit 1 ;; esac
context="$RUNNER_TEMP/media-context"
test ! -e "$context"
mkdir -p "$context/artifacts/classes" "$context/artifacts/libraries" "$context/controller" "$context/native"
cp -a "$JAVA_HOME" "$context/jdk"
cp src/main/native/*.c src/main/native/empty-metadata.patch "$context/native/"
cp target/notes-knowledge-workspace-0.0.1-SNAPSHOT.jar "$context/application.jar"
# Use the actual packaged production classes and libraries, never recompile them.
(cd "$context" && "$JAVA_HOME/bin/jar" xf application.jar BOOT-INF)
cp -a "$context/BOOT-INF/classes/org" "$context/artifacts/classes/"
cp "$context"/BOOT-INF/lib/{pdfbox-*,fontbox-*,commons-logging-*,spring-web-*}.jar "$context/artifacts/libraries/"
cp -a target/test-classes/org "$context/controller/"
cp -a "$context/BOOT-INF/lib" "$context/controller/libraries"
mkdir "$context/controller/sandbox"
cp target/test-classes/sandbox/PdfPageRasterWorker.java "$context/controller/sandbox/"
curl --fail --location --retry 2 --proto '=https' --tlsv1.2 https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz --output "$context/ffmpeg-9.0.2.tar.xz"
printf '8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e  %s/ffmpeg-9.0.2.tar.xz\n' "$context" | sha256sum --check --strict -
docker build --file src/main/native/media-runtime.Dockerfile --target qualification --tag nkw-media-qualification "$context"
image="$(docker image inspect --format '{{.Id}}' nkw-media-qualification)"
case "$(uname -m)" in x86_64) expected=amd64 ;; aarch64) expected=arm64 ;; esac
test "$(docker image inspect --format '{{.Architecture}}' "$image")" = "$expected"
printf 'Native architecture: %s\nCandidate image: %s\n' "$(uname -m)" "$image"
# Build the actual deployment target from the identical qualified inputs. This
# is local runner evidence, not registry publication or deployment approval.
docker build --quiet --file src/main/native/media-runtime.Dockerfile --target deployment "$context" > "$RUNNER_TEMP/media-deployment-image"
deployment="$(tail -n 1 "$RUNNER_TEMP/media-deployment-image")"
[[ "$deployment" =~ ^sha256:[0-9a-f]{64}$ ]]
test "$(docker image inspect --format '{{.Architecture}}' "$deployment")" = "$expected"
test "$(docker image inspect --format '{{.Config.User}}' "$deployment")" = '65533:65533'
docker run --rm --network none --user 65533:65533 --cap-drop ALL --security-opt no-new-privileges --read-only \
  --entrypoint /bin/sh "$deployment" -c 'test ! -e /qualification && test ! -e /fixtures && test ! -e /codec && test ! -e /native && dpkg-query -W' \
  > "$RUNNER_TEMP/media-deployment-packages.txt"
docker run --rm --network none --user 65533:65533 --cap-drop ALL --security-opt no-new-privileges --read-only \
  --entrypoint sha256sum "$deployment" /provenance/runtime-files.sha256 > "$RUNNER_TEMP/media-runtime-manifest.sha256"
python3 - "$deployment" "$context/application.jar" "$RUNNER_TEMP/media-deployment-packages.txt" "$RUNNER_TEMP/media-runtime-manifest.sha256" <<'PY'
import hashlib,json,pathlib,sys,zipfile
image,jar,packages,manifest=sys.argv[1:]
with zipfile.ZipFile(jar) as z:
    libraries=[dict(file=n.removeprefix('BOOT-INF/lib/'),sha256=hashlib.sha256(z.read(n)).hexdigest())
               for n in sorted(z.namelist()) if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')]
    assert not any('ConfiguredMediaStorageFixtures' in n or 'ControlledObjectStorageTransport' in n for n in z.namelist())
os_packages=[dict(zip(('name','version'),line.split('\t'))) for line in pathlib.Path(packages).read_text().splitlines()]
print(json.dumps(dict(releaseInventory=True,deploymentImage=image,user='65533:65533',
    applicationSha256=hashlib.sha256(pathlib.Path(jar).read_bytes()).hexdigest(),libraries=libraries,osPackages=os_packages,
    runtimeManifestSha256=pathlib.Path(manifest).read_text().split()[0],
    baseImage='node:24.21.0-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6',
    qualificationPayloadAbsent=True,registryPublished=False,vulnerabilityScanPerformed=False,
    codecLegalClearance=False)))
PY
common=(run --rm --init --network none --user 65533:65533 --cap-drop ALL --security-opt no-new-privileges --read-only --memory=1g --cpus=2 --pids-limit=300 --tmpfs /tmp:rw,noexec,nosuid,nodev,size=192m)
cp='/qualification:/opt/pdf/artifacts/classes:/qualification/libraries/*'
docker "${common[@]}" "$image" -Xmx192m -cp "$cp" org.notesknowledge.notes.NativePdfStartupDiagnostic /opt/pdf
helper_hash="$(docker "${common[@]}" --entrypoint sha256sum "$image" /opt/pdf/helper | cut -d ' ' -f 1)"
case "$expected" in amd64) java_arch=amd64 ;; arm64) java_arch=aarch64 ;; esac
docker "${common[@]}" "$image" -Xmx192m -cp "$cp" org.notesknowledge.notes.PdfSandboxQualification /opt/pdf/helper /opt/pdf/runtime "$java_arch" "$helper_hash"
docker "${common[@]}" "$image" -Xmx192m -cp "$cp" org.notesknowledge.notes.SupervisedPdfRuntimeQualification /opt/pdf/helper /opt/pdf/runtime /opt/pdf/artifacts
mkdir "$RUNNER_TEMP/media-clips"
chmod 0777 "$RUNNER_TEMP/media-clips"
docker "${common[@]}" -v "$RUNNER_TEMP/media-clips:/output" "$image" -Xmx192m -cp "$cp" org.notesknowledge.notes.SupervisedVideoRuntimeQualification /opt/video /fixtures/long.mp4 /fixtures/vfr.mp4 /output /fixtures/broken.mp4 unused
# Decode every emitted frame independently. A protocol/metadata match is not
# evidence that clip bytes decode successfully.
decoded=0
for file in "$RUNNER_TEMP"/media-clips/*.mp4; do
  frames="$(docker "${common[@]}" -v "$file:/clip.mp4:ro" --entrypoint /opt/video/tools/bin/ffprobe "$image" -v error -err_detect explode -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 /clip.mp4)"
  [[ "$frames" =~ ^[0-9]+$ ]]
  decoded=$((decoded+frames))
done
test "$decoded" = 2000
printf 'Independently decoded frames: %s\n' "$decoded"
docker "${common[@]}" "$image" -Xmx192m -Dloader.path=/qualification -Dloader.main=org.notesknowledge.notes.ConfiguredMediaRuntimeQualification -cp /application.jar org.springframework.boot.loader.launch.PropertiesLauncher /opt/pdf /opt/video
# The daemon socket below is exclusively a Testcontainers harness capability.
# Neither production packaging nor either native worker gets a Docker API.
testspace="$RUNNER_TEMP/media-application-test"
mkdir -p "$testspace/source" "$testspace/home"
cp -a src pom.xml .mvn mvnw target "$testspace/source/"
maven_executable="$(find "$HOME/.m2/wrapper/dists" -type f -path '*apache-maven-3.9.16*/bin/mvn' -print -quit)"
test -n "$maven_executable"
maven="$(dirname "$(dirname "$maven_executable")")"
test -f "$maven/bin/mvn"
cp -a "$maven" "$testspace/maven"
cp -a "$HOME/.m2/repository" "$testspace/repository"
chmod -R a+rwX "$testspace"
# Only the disposable, explicitly copied test workspace/cache is reassigned.
# Production roots stay administrator-owned/read-only; the application and
# native workers still start non-root with ALL capabilities dropped.
docker run --rm --network none --user 0:0 --cap-drop ALL --cap-add CHOWN --security-opt no-new-privileges --read-only \
  -v "$testspace:/harness" --entrypoint /bin/chown "$image" --no-dereference --recursive 65533:65533 /harness
result=0
docker_group="$(stat -c '%g' /var/run/docker.sock)"
[[ "$docker_group" =~ ^[0-9]+$ ]]
docker run --rm --init --user 65533:65533 --group-add "$docker_group" --cap-drop ALL --security-opt no-new-privileges --read-only \
  --memory=2g --cpus=2 --pids-limit=512 --tmpfs /tmp:rw,nosuid,nodev,size=512m \
  --add-host host.docker.internal:host-gateway -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$testspace/source:/workspace" -v "$testspace/maven:/maven:ro" \
  -v "$testspace/repository:/repository" -v "$testspace/home:/home/test" -w /workspace \
  -e JAVA_HOME=/jdk -e HOME=/home/test -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  --entrypoint /maven/bin/mvn "$image" -o -Dmaven.repo.local=/repository \
  -DreuseForks=false \
  -Dtest=ConfiguredMultimodalApplicationTest,PrivateDerivationIntegrationTest \
  -Dnkw.test.pdf.nativeRoot=/opt/pdf -Dnkw.test.video.nativeRoot=/opt/video \
  -Dnkw.test.media.pdfRoot=/opt/pdf -Dnkw.test.media.videoRoot=/opt/video \
  -Dnkw.test.media.videoFixture=/fixtures/vfr.mp4 test -q || result=$?
python3 - "$testspace/source/target/surefire-reports" <<'PY'
import json,pathlib,sys,xml.etree.ElementTree as ET
for name in ('ConfiguredMultimodalApplicationTest','PrivateDerivationIntegrationTest'):
    path=pathlib.Path(sys.argv[1])/('TEST-org.notesknowledge.knowledge.'+name+'.xml')
    root=ET.parse(path).getroot()
    print(json.dumps({"nativeMedia":True,"testClass":name,**{key:int(root.attrib[key]) for key in ("tests","failures","errors","skipped")}}))
PY
# Qualification evidence, not release promotion. Preserve native-test results
# independently from vulnerability findings; any finding still blocks release.
security="$RUNNER_TEMP/media-security-readiness"
cache="$RUNNER_TEMP/media-security-cache"
mkdir -p "$security" "$cache"
scanner='ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa'
scan_result=0
docker run --rm --memory=2g --cpus=2 -v /var/run/docker.sock:/var/run/docker.sock:ro \
  -v "$security:/evidence" -v "$cache:/root/.cache/trivy" "$scanner" image \
  --timeout 5m --scanners vuln,secret,misconfig --image-config-scanners misconfig,secret \
  --list-all-pkgs --exit-code 1 --format json --output /evidence/raw-scan.json "$deployment" || scan_result=$?
SCAN_RESULT="$scan_result" python3 - "$security" "$deployment" "$scanner" "$cache" <<'PY'
import collections,hashlib,json,os,pathlib,sys
root=pathlib.Path(sys.argv[1]); raw=root/'raw-scan.json'
assert raw.is_file(), 'Scanner infrastructure failed; missing evidence is not zero findings'
data=json.loads(raw.read_text()); vulnerabilities=[]; secrets=[]; misconfigurations=[]; java_packages=0; os_packages=0
assert int(os.environ['SCAN_RESULT']) in (0,1) and data.get('Metadata'), 'Scanner execution failed'
for item in data.get('Results',[]):
    if item.get('Type')=='jar': java_packages+=len(item.get('Packages',[]))
    if item.get('Class')=='os-pkgs': os_packages+=len(item.get('Packages',[]))
    for v in item.get('Vulnerabilities',[]):
        vulnerabilities.append({k:v.get(k) for k in ('VulnerabilityID','PkgName','InstalledVersion','FixedVersion','Severity','Status','PrimaryURL')})
    for v in item.get('Secrets',[]):
        # Never publish Match, Code, source lines or secret-bearing metadata.
        secrets.append({k:v.get(k) for k in ('RuleID','Category','Severity')})
    for v in item.get('Misconfigurations',[]):
        misconfigurations.append({k:v.get(k) for k in ('ID','Severity','Status','PrimaryURL')})
assert java_packages>=200 and os_packages>0, 'Packaged Java/OS scan coverage missing; zero findings is not clearance'
db_path=pathlib.Path(sys.argv[4])/'db'/'metadata.json'
assert db_path.is_file(), 'Vulnerability database metadata missing'
database=json.loads(db_path.read_text())
receipt=dict(releaseSecurity=True,image=sys.argv[2],scanner=sys.argv[3],scanExit=int(os.environ['SCAN_RESULT']),
    database={k:database.get(k) for k in ('Version','UpdatedAt','NextUpdate','DownloadedAt')},
    javaPackagesAnalyzed=java_packages,osPackagesAnalyzed=os_packages,
    vulnerabilityCounts=dict(collections.Counter(v['Severity'] for v in vulnerabilities)),vulnerabilities=vulnerabilities,
    secretFindingCount=len(secrets),secretFindings=secrets,misconfigurations=misconfigurations,
    releaseGatePassed=not(vulnerabilities or secrets or misconfigurations) and os.environ['SCAN_RESULT']=='0',
    registryPublished=False,codecLegalClearance=False)
assert receipt['scanExit']==0 or vulnerabilities or secrets or misconfigurations, 'Scanner failed without a complete findings report'
(root/'security-readiness.json').write_text(json.dumps(receipt,indent=2)+'\n')
raw.unlink() # Raw matches never leave this controlled ephemeral scan boundary.
print(json.dumps(receipt))
assert not secrets, 'Potential secret finding: STOP AND REVIEW; no match values were published'
PY
docker run --rm --memory=2g --cpus=2 -v /var/run/docker.sock:/var/run/docker.sock:ro \
  -v "$security:/evidence" -v "$cache:/root/.cache/trivy" "$scanner" image \
  --timeout 5m --format cyclonedx --output /evidence/deployment.cdx.json "$deployment"
python3 - "$security" "$deployment" <<'PY'
import hashlib,json,pathlib,sys
root=pathlib.Path(sys.argv[1]); path=root/'deployment.cdx.json'; data=json.loads(path.read_text())
assert data.get('bomFormat')=='CycloneDX' and data.get('components')
assert sum(c.get('purl','').startswith('pkg:maven/') for c in data['components'])>=200, 'Java SBOM coverage missing'
receipt=dict(releaseSbom=True,image=sys.argv[2],format='CycloneDX',specVersion=data['specVersion'],
    components=len(data['components']),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),registryPublished=False,signedAttestation=False)
(root/'sbom-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
PY
exit "$result"
