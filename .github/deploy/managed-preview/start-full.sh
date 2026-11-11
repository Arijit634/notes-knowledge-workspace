#!/bin/bash
# One rootless preview container; PostgreSQL and object storage stay external.
set -euo pipefail
umask 077
[[ "${PORT:-10000}" == 10000 ]] || { echo 'preview_port_invalid' >&2; exit 2; }
for name in runtime.properties processing-disclosure.json preview-origin preview-password-hash redis.conf; do
    [[ -f "/etc/secrets/$name" && -r "/etc/secrets/$name" ]] || { echo 'preview_configuration_unavailable' >&2; exit 2; }
done
PREVIEW_ORIGIN=$(cat /etc/secrets/preview-origin)
[[ "$PREVIEW_ORIGIN" =~ ^https://[a-z0-9][a-z0-9.-]+\.[a-z]{2,}$ ]] || { echo 'preview_origin_invalid' >&2; exit 2; }
PREVIEW_HOST=${PREVIEW_ORIGIN#https://}
PREVIEW_PASSWORD_HASH=$(cat /etc/secrets/preview-password-hash)
[[ "$PREVIEW_PASSWORD_HASH" =~ ^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$ ]] || { echo 'preview_perimeter_unavailable' >&2; exit 2; }
export NKW_PREVIEW_SECRET_IMPORT=file:/etc/secrets/runtime.properties
mkdir -p /tmp/nkw-edge/body /tmp/nkw-edge/proxy /tmp/nkw-redis \
    /tmp/nkw-pdf-recovery /tmp/nkw-video-recovery /tmp/nkw-video-scratch
printf 'preview:%s\n' "$PREVIEW_PASSWORD_HASH" > /tmp/nkw-edge/htpasswd
template=$(cat /app/nginx.conf)
printf '%s\n' "${template//__PREVIEW_HOST__/$PREVIEW_HOST}" > /tmp/nkw-edge/nginx.conf
unset template PREVIEW_PASSWORD_HASH
nginx -e /dev/null -t -c /tmp/nkw-edge/nginx.conf >/dev/null 2>&1 || { echo 'preview_edge_configuration_invalid' >&2; exit 2; }
java_pid='';redis_pid='';edge_pid='';stopping=0
shutdown() {
    (( stopping == 0 )) || return 0
    stopping=1
    # Keep proxy/cache available while the application drains. Bound the whole
    # shutdown below the managed host's 30-second termination allowance.
    if [[ -n "$java_pid" ]]; then
        kill -TERM "$java_pid" 2>/dev/null || true
        for ((i=0;i<24;i++)); do
            kill -0 "$java_pid" 2>/dev/null || break
            sleep 1
        done
        kill -KILL "$java_pid" 2>/dev/null || true
        wait "$java_pid" 2>/dev/null || true
    fi
    for pid in "$edge_pid" "$redis_pid"; do [[ -z "$pid" ]] || kill -TERM "$pid" 2>/dev/null || true; done
    sleep 1
    for pid in "$edge_pid" "$redis_pid"; do
        if [[ -n "$pid" ]]; then kill -KILL "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
    done
}
trap 'shutdown; exit 0' TERM INT
trap shutdown EXIT
redis-server /etc/secrets/redis.conf >/dev/null 2>&1 & redis_pid=$!
nginx -e /dev/null -c /tmp/nkw-edge/nginx.conf & edge_pid=$!
# The perimeter credential is not exposed to application configuration.
/jdk/bin/java -Xmx128m -XX:MaxDirectMemorySize=16m -XX:MaxMetaspaceSize=192m \
    -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k \
    -XX:ActiveProcessorCount=1 -XX:-HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError \
    -Dnkw.attachment-parser.profile=compact-v1 \
    -Duser.timezone=UTC -Djava.io.tmpdir=/tmp -jar /app/application.jar \
    --spring.profiles.active=operations,private-preview,managed-preview,full-hosted \
    --spring.config.additional-location=file:/provenance/runtime-selection.properties \
    --notes.media-runtime.pdf.recovery-directory=/tmp/nkw-pdf-recovery \
    --notes.media-runtime.video.recovery-directory=/tmp/nkw-video-recovery \
    --notes.media-runtime.video.scratch-directory=/tmp/nkw-video-scratch \
    --knowledge.processing-disclosure.path=/etc/secrets/processing-disclosure.json & java_pid=$!
unset PREVIEW_HOST PREVIEW_ORIGIN
# Any unexpected constituent exit invalidates the entire instance. The hosting
# platform may restart it; no child is silently replaced or reported healthy.
set +e
wait -n "$java_pid" "$redis_pid" "$edge_pid"
echo 'preview_constituent_stopped' >&2
exit 1
