#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" != 0
case "$(uname -m)" in x86_64|aarch64) ;; *) exit 1 ;; esac
context="$RUNNER_TEMP/media-context"
test ! -e "$context"
mkdir -p "$context/artifacts/classes" "$context/artifacts/libraries" "$context/controller" "$context/native"
cp -a "$JAVA_HOME" "$context/jdk"
cp src/main/native/*.c src/main/native/empty-metadata.patch "$context/native/"
# Frozen synthetic AAC fixture, also exercised by the upload/decoder regressions.
cp src/test/resources/attachments/synthetic-aac-video.mp4 "$context/native/audio-preflight.mp4"
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
# Rebuild the prior deployment packaging with this identical JAR and compare
# both images against the same scanner/database snapshot; no old result reuse.
if [ "${NKW_CANDIDATE_ONLY:-false}" != true ]; then
prior=ffa878faeb6302f06e0a1fdde7619776af77a27c
comparison="$RUNNER_TEMP/media-comparison"
mkdir "$comparison"
git cat-file -e "$prior^{commit}" 2>/dev/null || git fetch --no-tags --depth=1 origin "$prior"
git show "$prior:src/main/native/media-runtime.Dockerfile" > "$comparison/Dockerfile"
docker build --file "$comparison/Dockerfile" --target deployment --tag nkw-media-before "$context"
before="$(docker image inspect --format '{{.Id}}' nkw-media-before)"
docker run --rm --network none --entrypoint /bin/sh "$before" -c 'dpkg-query -W' > "$RUNNER_TEMP/media-before-packages.txt"
fi
source_sha="$(git rev-parse HEAD)"
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]]
docker build --build-arg "SOURCE_SHA=$source_sha" --file src/main/native/media-runtime.Dockerfile --target qualification --tag nkw-media-qualification "$context"
image="$(docker image inspect --format '{{.Id}}' nkw-media-qualification)"
case "$(uname -m)" in x86_64) expected=amd64 ;; aarch64) expected=arm64 ;; esac
test "$(docker image inspect --format '{{.Architecture}}' "$image")" = "$expected"
printf 'Native architecture: %s\nCandidate image: %s\n' "$(uname -m)" "$image"
# Build the actual deployment target from the identical qualified inputs. This
# is local runner evidence, not registry publication or deployment approval.
docker build --quiet --build-arg "SOURCE_SHA=$source_sha" --file src/main/native/media-runtime.Dockerfile --target deployment "$context" > "$RUNNER_TEMP/media-deployment-image"
deployment="$(tail -n 1 "$RUNNER_TEMP/media-deployment-image")"
[[ "$deployment" =~ ^sha256:[0-9a-f]{64}$ ]]
test "$(docker image inspect --format '{{.Architecture}}' "$deployment")" = "$expected"
test "$(docker image inspect --format '{{.Config.User}}' "$deployment")" = '65533:65533'
docker run --rm --network none --user 65533:65533 --cap-drop ALL --security-opt no-new-privileges --read-only \
  --entrypoint /bin/sh "$deployment" -c 'test ! -e /qualification && test ! -e /fixtures && test ! -e /codec && test ! -e /native && test ! -e /jdk/bin/javac && test ! -e /jdk/bin/jlink && ! command -v gcc && test -z "$(find /usr /opt /jdk -xdev -type f -perm /6000 -print -quit)" && dpkg-query -W' \
  > "$RUNNER_TEMP/media-deployment-packages.txt"
docker run --rm --network none --user 65533:65533 --cap-drop ALL --security-opt no-new-privileges --read-only \
  --entrypoint sha256sum "$deployment" /provenance/runtime-files.sha256 > "$RUNNER_TEMP/media-runtime-manifest.sha256"
provenance="$RUNNER_TEMP/media-runtime-provenance"
mkdir "$provenance"
# Deliberate public build metadata only; no runtime properties or private inputs.
for file in runtime-files.sha256 runtime-linkage.txt jdk-version.txt openh264-source.sha256 openh264-copyright ffmpeg-LGPLv2.1 ffmpeg-buildconf.txt ffmpeg-config.mak os-build-packages.txt service-required-modules.txt service-modules.txt; do
  docker run --rm --network none --user 65533:65533 --cap-drop ALL --security-opt no-new-privileges --read-only \
    --entrypoint /bin/cat "$deployment" "/provenance/$file" > "$provenance/$file"
done
python3 - "$deployment" "$context/application.jar" "$RUNNER_TEMP/media-deployment-packages.txt" "$RUNNER_TEMP/media-runtime-manifest.sha256" <<'PY'
import hashlib,json,pathlib,sys,zipfile,subprocess
image,jar,packages,manifest=sys.argv[1:]
with zipfile.ZipFile(jar) as z:
    libraries=[dict(file=n.removeprefix('BOOT-INF/lib/'),sha256=hashlib.sha256(z.read(n)).hexdigest())
               for n in sorted(z.namelist()) if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')]
    assert not any(marker in n for n in z.namelist() for marker in
        ('ConfiguredMediaStorageFixtures','ControlledObjectStorageTransport','ControlledS3Transport',
         'ConfiguredAvatarStorageFixtures','ConfiguredPublicMediaStorageFixtures','ControlledRegistrationDelivery','ConfiguredObjectCustodyJourney'))
    assert not any(marker in n for n in z.namelist() for marker in
        ('ControlledS3Endpoint','DeploymentSmokeDatabase','DependencyUnavailableFilterTest','CoreReadinessDatabaseTest','WindowsPdfProcessTest'))
    assert not any(library['file'].startswith(('junit-','testcontainers-','spring-boot-test-','spring-security-test-')) for library in libraries)
    assert not any('sqlite' in library['file'].lower() for library in libraries)
os_packages=[dict(zip(('name','version'),line.split('\t'))) for line in pathlib.Path(packages).read_text().splitlines()]
package_versions={p['name'].split(':')[0]:p['version'] for p in os_packages}
assert package_versions['perl-base']=='5.40.1-6+deb13u1'
assert package_versions['libpcre2-8-0']=='10.46-1~deb13u3'
assert package_versions['libopenh264-8']=='2.6.0+dfsg-2'
assert not any(p.startswith(('nodejs','npm')) for p in package_versions)
# Preserve the raw scanner finding. This independently inspects the shipped
# binary for the specific contrib/minizip entry point described by Debian.
zlib_symbols=subprocess.check_output(['docker','run','--rm','--network','none','--user','65533:65533',
    '--cap-drop','ALL','--security-opt','no-new-privileges','--read-only','--entrypoint','/bin/sh',image,
    '-c',"test -e /lib/*/libz.so.1; if grep -aq 'zipOpenNewFileInZip4_64' /lib/*/libz.so.1; then printf present; else printf absent; fi"],text=True).strip()
assert zlib_symbols in ('present','absent')
print(json.dumps(dict(zlibApplicability=True,deploymentImage=image,cve='CVE-2023-45853',
    packageVersion=package_versions['zlib1g'],minizipPackagePresent=any('minizip' in p for p in package_versions),
    vulnerableEntryPointPresent=zlib_symbols=='present',scannerFindingSuppressed=False,
    distroAdvisory='https://security-tracker.debian.org/tracker/CVE-2023-45853',
    scope='This exact Trixie zlib binary; vendor marks Trixie fixed, old Bookworm contrib assessment is not inherited')))
print(json.dumps(dict(releaseInventory=True,deploymentImage=image,user='65533:65533',
    applicationSha256=hashlib.sha256(pathlib.Path(jar).read_bytes()).hexdigest(),libraries=libraries,osPackages=os_packages,
    runtimeManifestSha256=pathlib.Path(manifest).read_text().split()[0],
    baseImage='debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f',
    qualificationPayloadAbsent=True,testFixturesAbsent=True,testFrameworkDependenciesAbsent=True,
    sqliteApplicationDependencyAbsent=True,setuidSetgidFilesAbsent=True,registryPublished=False,vulnerabilityScanPerformed=False,
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
docker "${common[@]}" "$image" -Xmx192m -cp "$cp" org.notesknowledge.notes.VideoSoundtrackRuntimeQualification /opt/video
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
if [ "${NKW_CANDIDATE_ONLY:-false}" != true ]; then
bash .github/scripts/scan-deployment.sh "$before" "$RUNNER_TEMP/media-before-security" "$RUNNER_TEMP/media-security-cache"
fi
bash .github/scripts/scan-deployment.sh "$deployment" "$RUNNER_TEMP/media-security-readiness" "$RUNNER_TEMP/media-security-cache"
if [ "${NKW_CANDIDATE_ONLY:-false}" != true ]; then
python3 .github/scripts/compare-deployment.py "$before" "$deployment" "$RUNNER_TEMP"
fi
# Run the actual production process, with no test classes or Docker socket mounted.
# Scan evidence survives even if this independent operational gate fails.
python3 .github/scripts/deployment-smoke.py "$deployment" "$image" "$RUNNER_TEMP/media-deployment-smoke"
exit "$result"
