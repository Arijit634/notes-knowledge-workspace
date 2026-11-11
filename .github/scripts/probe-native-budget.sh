#!/usr/bin/env bash
# Isolated resource evidence, never image publication or production qualification.
set -euo pipefail
test "$(uname -m)" = x86_64
source_sha="$(git rev-parse HEAD)"
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]]
context="$RUNNER_TEMP/native-budget-context"
test ! -e "$context"
mkdir -p "$context/artifacts/classes" "$context/artifacts/libraries" "$context/controller" "$context/native"
cp -a "$JAVA_HOME" "$context/jdk"
cp src/main/native/*.c src/main/native/empty-metadata.patch "$context/native/"
cp src/main/native/allocation-overflow-probe.cpp "$context/native/"
cp src/main/native/expat-2.10-regression-compat.patch "$context/native/"
cp src/main/native/build-media-libraries.sh "$context/native/"
cp src/main/native/minimize-runtime-tools.sh "$context/native/"
cp src/test/resources/attachments/synthetic-aac-video.mp4 "$context/native/audio-preflight.mp4"
cp target/notes-knowledge-workspace-0.0.1-SNAPSHOT.jar "$context/application.jar"
"$JAVA_HOME/bin/jar" tf "$context/application.jar" | grep -Fx 'BOOT-INF/classes/static/index.html'
(cd "$context" && "$JAVA_HOME/bin/jar" xf application.jar BOOT-INF)
cp -a "$context/BOOT-INF/classes/org" "$context/artifacts/classes/"
cp "$context"/BOOT-INF/lib/{pdfbox-*,fontbox-*,commons-logging-*,spring-web-*}.jar "$context/artifacts/libraries/"
cp -a target/test-classes/org "$context/controller/"
cp -a "$context/BOOT-INF/lib" "$context/controller/libraries"
mkdir "$context/controller/sandbox"
cp target/test-classes/sandbox/PdfPageRasterWorker.java "$context/controller/sandbox/"
curl --fail --location --retry 0 --proto '=https' --tlsv1.2 https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz --output "$context/ffmpeg-9.0.2.tar.xz"
printf '8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e  %s/ffmpeg-9.0.2.tar.xz\n' "$context" | sha256sum --check --strict -
docker build --build-arg "SOURCE_SHA=$source_sha" --file src/main/native/media-runtime.Dockerfile --target qualification --tag nkw-native-budget-tools "$context"
qualification="$(docker image inspect --format '{{.Id}}' nkw-native-budget-tools)"
docker build --quiet --build-arg "SOURCE_SHA=$source_sha" --file src/main/native/media-runtime.Dockerfile --target deployment "$context" > "$RUNNER_TEMP/native-budget-image"
deployment="$(tail -n 1 "$RUNNER_TEMP/native-budget-image")"
for image in "$deployment" "$qualification"; do
  [[ "$image" =~ ^sha256:[0-9a-f]{64}$ ]]
  test "$(docker image inspect --format '{{.Architecture}}' "$image")" = amd64
  test "$(docker image inspect --format '{{.Config.User}}' "$image")" = '65533:65533'
  test "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")" = "$source_sha"
done
# The service gets only its production JAR; fixtures, DB migration authority and
# inspection helpers remain in separate disposable controller processes.
python3 .github/scripts/deployment-smoke.py "$deployment" "$qualification" "$RUNNER_TEMP/native-budget-startup" render-startup
