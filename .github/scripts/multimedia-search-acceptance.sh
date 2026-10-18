#!/usr/bin/env bash
# One backend feature acceptance scenario, not deployment/security requalification.
set -euo pipefail
test "$(id -u)" != 0
case "$(uname -m)" in x86_64|aarch64) ;; *) exit 1 ;; esac
context="$RUNNER_TEMP/multimedia-search-context"
test ! -e "$context"
mkdir -p "$context/artifacts/classes" "$context/artifacts/libraries" "$context/controller/libraries" "$context/native"
cp -a "$JAVA_HOME" "$context/jdk"
cp src/main/native/*.c src/main/native/empty-metadata.patch "$context/native/"
cp target/notes-knowledge-workspace-0.0.1-SNAPSHOT.jar "$context/application.jar"
(cd "$context" && "$JAVA_HOME/bin/jar" xf application.jar BOOT-INF)
cp -a "$context/BOOT-INF/classes/org" "$context/artifacts/classes/"
cp "$context"/BOOT-INF/lib/{pdfbox-*,fontbox-*,commons-logging-*,spring-web-*}.jar "$context/artifacts/libraries/"
cp -a target/test-classes/. "$context/controller/"
# External test transports/controllers need test-only dependencies. Neither they
# nor these libraries are added to the production JAR or deployment target.
tr ':' '\n' < target/qualification-classpath.txt | while IFS= read -r library || test -n "$library"; do
  cp "$library" "$context/controller/libraries/"
done
curl --fail --location --retry 2 --proto '=https' --tlsv1.2 https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz --output "$context/ffmpeg-9.0.2.tar.xz"
printf '8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e  %s/ffmpeg-9.0.2.tar.xz\n' "$context" | sha256sum --check --strict -
docker build --file src/main/native/media-runtime.Dockerfile --target qualification --tag nkw-multimedia-search "$context"
image="$(docker image inspect --format '{{.Id}}' nkw-multimedia-search)"
report="$RUNNER_TEMP/multimedia-search-report"
mkdir "$report"
docker run --rm --network none --user 0:0 --cap-drop ALL --cap-add CHOWN --read-only \
  -v "$report:/report" --entrypoint /bin/chown "$image" 65533:65533 /report
group="$(stat -c '%g' /var/run/docker.sock)"
[[ "$group" =~ ^[0-9]+$ ]]
docker run --rm --init --user 65533:65533 --group-add "$group" --cap-drop ALL --security-opt no-new-privileges --read-only \
  --memory=2g --cpus=2 --pids-limit=512 --tmpfs /tmp:rw,nosuid,nodev,size=512m \
  --add-host host.docker.internal:host-gateway -v /var/run/docker.sock:/var/run/docker.sock -v "$report:/report" \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --entrypoint /jdk/bin/java "$image" -Xmx512m \
  -Dloader.path=/qualification,/qualification/libraries -Dloader.main=org.notesknowledge.knowledge.PackagedMultimediaSearchAcceptance \
  -cp /application.jar org.springframework.boot.loader.launch.PropertiesLauncher /opt/pdf /opt/video /report/accepted
