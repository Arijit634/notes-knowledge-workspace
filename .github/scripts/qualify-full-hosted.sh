#!/usr/bin/env bash
# Native candidate evidence only. No publication, credentials or live provider calls.
set -euo pipefail
test "$(uname -m)" = x86_64
source_sha="$(git rev-parse HEAD)"
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]]
context="$RUNNER_TEMP/full-hosted-context"
evidence="$RUNNER_TEMP/full-hosted-candidate"
test ! -e "$context" && test ! -e "$evidence"
mkdir -p "$context/artifacts/classes" "$context/artifacts/libraries" "$context/controller" "$context/native" "$context/managed" "$evidence"
cp -a "$JAVA_HOME" "$context/jdk"
cp src/main/native/*.c src/main/native/empty-metadata.patch src/main/native/allocation-overflow-probe.cpp \
  src/main/native/expat-2.10-regression-compat.patch src/main/native/build-media-libraries.sh \
  src/main/native/minimize-runtime-tools.sh "$context/native/"
cp src/main/native/backport-ffmpeg-security.sh "$context/native/"
cp .github/deploy/managed-preview/{build-openh264.sh,start-full.sh,nginx.conf} "$context/managed/"
cp src/test/resources/attachments/synthetic-aac-video.mp4 "$context/native/audio-preflight.mp4"
cp target/notes-knowledge-workspace-0.0.1-SNAPSHOT.jar "$context/application.jar"
(cd "$context" && "$JAVA_HOME/bin/jar" xf application.jar BOOT-INF)
cp -a "$context/BOOT-INF/classes/org" "$context/artifacts/classes/"
cp "$context"/BOOT-INF/lib/{pdfbox-*,fontbox-*,commons-logging-*,spring-web-*}.jar "$context/artifacts/libraries/"
cp -a target/test-classes/org "$context/controller/"
cp -a "$context/BOOT-INF/lib" "$context/controller/libraries"
mkdir "$context/controller/sandbox"
cp target/test-classes/sandbox/PdfPageRasterWorker.java "$context/controller/sandbox/"
curl --fail --location --retry 0 --proto '=https' --tlsv1.2 https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz --output "$context/ffmpeg-9.0.2.tar.xz"
printf '8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e  %s/ffmpeg-9.0.2.tar.xz\n' "$context" | sha256sum --check --strict -
docker build --build-arg "SOURCE_SHA=$source_sha" --file .github/deploy/managed-preview/full-runtime.Dockerfile --target qualification --tag nkw-full-hosted-tools "$context"
qualification="$(docker image inspect --format '{{.Id}}' nkw-full-hosted-tools)"
docker build --quiet --build-arg "SOURCE_SHA=$source_sha" --file .github/deploy/managed-preview/full-runtime.Dockerfile --target deployment "$context" > "$RUNNER_TEMP/full-hosted-image"
deployment="$(tail -n 1 "$RUNNER_TEMP/full-hosted-image")"
for image in "$deployment" "$qualification"; do
  [[ "$image" =~ ^sha256:[0-9a-f]{64}$ ]]
  test "$(docker image inspect --format '{{.Architecture}}' "$image")" = amd64
  test "$(docker image inspect --format '{{.Config.User}}' "$image")" = '65533:65533'
  test "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")" = "$source_sha"
done
docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges \
  --entrypoint /bin/sh "$deployment" -c \
  'test ! -e /qualification && test ! -e /fixtures && test ! -e /jdk/bin/javac && test ! -e /jdk/bin/jlink && ! command -v gcc && test -z "$(find /usr /opt /jdk -xdev -type f -perm /6000 -print -quit)" && nginx -v && redis-server --version'
common=(run --rm --init --network none --user 65533:65533 --cap-drop ALL --security-opt no-new-privileges --read-only --memory=1g --cpus=2 --pids-limit=300 --tmpfs /tmp:rw,noexec,nosuid,nodev,size=192m)
cp='/qualification:/opt/pdf/artifacts/classes:/qualification/libraries/*'
docker "${common[@]}" --entrypoint /qualification/media-library-versions "$qualification"
docker "${common[@]}" --entrypoint /qualification/allocation-overflow-probe "$qualification"
docker "${common[@]}" "$qualification" -Xmx192m -cp "$cp" org.notesknowledge.notes.NativePdfStartupDiagnostic /opt/pdf
helper_hash="$(docker "${common[@]}" --entrypoint sha256sum "$qualification" /opt/pdf/helper | cut -d ' ' -f 1)"
docker "${common[@]}" "$qualification" -Xmx192m -cp "$cp" org.notesknowledge.notes.PdfSandboxQualification /opt/pdf/helper /opt/pdf/runtime amd64 "$helper_hash"
docker "${common[@]}" "$qualification" -Xmx192m -cp "$cp" org.notesknowledge.notes.SupervisedPdfRuntimeQualification /opt/pdf/helper /opt/pdf/runtime /opt/pdf/artifacts
mkdir "$evidence/clips" && chmod 0777 "$evidence/clips"
docker "${common[@]}" -v "$evidence/clips:/output" "$qualification" -Xmx192m -cp "$cp" org.notesknowledge.notes.SupervisedVideoRuntimeQualification /opt/video /fixtures/long.mp4 /fixtures/vfr.mp4 /output /fixtures/broken.mp4 unused
docker "${common[@]}" "$qualification" -Xmx192m -cp "$cp" org.notesknowledge.notes.VideoSoundtrackRuntimeQualification /opt/video
decoded=0
for file in "$evidence"/clips/*.mp4; do
  frames="$(docker "${common[@]}" -v "$file:/clip.mp4:ro" --entrypoint /opt/video/tools/bin/ffprobe "$qualification" -v error -err_detect explode -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 /clip.mp4)"
  [[ "$frames" =~ ^[0-9]+$ ]]
  decoded=$((decoded+frames))
done
test "$decoded" = 2000
docker "${common[@]}" "$qualification" -Xmx192m -Dloader.path=/qualification -Dloader.main=org.notesknowledge.notes.ConfiguredMediaRuntimeQualification -cp /application.jar org.springframework.boot.loader.launch.PropertiesLauncher /opt/pdf /opt/video
bash .github/scripts/scan-deployment.sh "$deployment" "$evidence/security" "$RUNNER_TEMP/full-hosted-trivy-cache"
python3 .github/scripts/native-image-facts.py "$deployment" "$qualification" "$source_sha" "$evidence/security/native-artifact-facts.json"
docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges \
  --entrypoint sha256sum "$deployment" /usr/local/bin/redis-server /usr/lib/x86_64-linux-gnu/libopenh264.so.2.6.0 > "$evidence/security/full-bundle-artifacts.sha256"
python3 - "$evidence/security" <<'PY'
import importlib.util,pathlib,sys
path=pathlib.Path('.github/deploy/managed-preview/complete_sbom.py')
spec=importlib.util.spec_from_file_location('full_sbom',path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
root=pathlib.Path(sys.argv[1]);shipped={row.split()[1]:row.split()[0] for row in (root/'full-bundle-artifacts.sha256').read_text().splitlines()}
module.complete_full(root/'deployment.cdx.json',root/'full-runtime.cdx.json',shipped)
PY
# Scans retain every raw finding. A collected scan is not an applicability or
# release clearance; those remain separate exact-image gates.
python3 .github/deploy/managed-preview/smoke.py "$deployment" --full-hosted | tee "$evidence/managed-smoke.jsonl"
python3 - "$evidence" <<'PY'
import json,pathlib,sys
root=pathlib.Path(sys.argv[1]);lines=(root/'managed-smoke.jsonl').read_text().splitlines()
smoke=json.loads(lines[-1])
assert smoke['passed'] is True and smoke['productionJar'] is True and smoke['fullHostedNativeWorkload'] is True
assert smoke['liveProviderCalls']==0 and smoke['releaseEligible'] is False
(root/'combined-runtime.json').write_text(json.dumps(smoke,indent=2)+'\n')
PY
python3 - "$source_sha" "$deployment" "$qualification" "$evidence" <<'PY'
import json,pathlib,sys
source,deployment,qualification,evidence=sys.argv[1:]
pathlib.Path(evidence,'candidate.json').write_text(json.dumps(dict(source=source,image=deployment,
    qualificationImage=qualification,nativeConfinementPassed=True,independentlyDecodedFrames=2000,
    productionTestPayloadAbsent=True,liveProviderCalls=0,registryPublished=False,
    combinedNativeCoreResourceQualified=True,liveProviderQualified=False,releaseEligible=False),indent=2)+'\n')
PY
