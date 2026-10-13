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
docker run --rm --init --user 65533:65533 --group-add 0 --cap-drop ALL --security-opt no-new-privileges --read-only \
  --memory=2g --cpus=2 --pids-limit=512 --tmpfs /tmp:rw,nosuid,nodev,size=512m \
  --add-host host.docker.internal:host-gateway -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$testspace/source:/workspace" -v "$testspace/maven:/maven:ro" \
  -v "$testspace/repository:/repository" -v "$testspace/home:/home/test" -w /workspace \
  -e JAVA_HOME=/jdk -e HOME=/home/test -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  --entrypoint /maven/bin/mvn "$image" -o -Dmaven.repo.local=/repository \
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
exit "$result"
