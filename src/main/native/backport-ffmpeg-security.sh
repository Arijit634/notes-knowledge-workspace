#!/bin/sh
# Complete functional upstream PR24593 backport to the selected static 9.0.2 build.
set -eu
test "$(pwd)" = /codec
mkdir -p /provenance/ffmpeg-security /native-diagnostics
# Retain an executable negative control against this exact unpatched library.
make -j2 libavutil/libavutil.a
gcc -std=c11 -O2 -DNKW_PATCHED_APPEND=0 -I. /native/ffmpeg-encryption-regression.c \
  libavutil/libavutil.a -lm -pthread -o /native-diagnostics/ffmpeg-encryption-before
set +e
(ulimit -c 0; ulimit -s 256; /native-diagnostics/ffmpeg-encryption-before >/dev/null 2>&1)
negative=$?
set -e
test "$negative" = 139
printf '{"unpatchedCleanupExit":139,"expectedStackExhaustion":true,"coreDumpEnabled":false}\n' > /provenance/ffmpeg-security/negative-control.json

for pin in \
  969c7eb091989a664cb7bf66a1f02051cc46cb52:594a0cbca9527bc1edab7f76679af5a73cee23aadc878e2cc6a9001e22742c45 \
  d1434518fbd03789e3e5dc0657a7a9f1123ce074:1fd3c8a2b37bb24fe4c8500422090453e18a06634e2647d8fb50216beb5332b8 \
  71403a21b02a92b925e26e609e9bd3e48df451cf:af4bf01465777b2b8d159e8cd8a2185f155a0db39baa7fc2c0e6acf4d5f734b8; do
  commit=${pin%%:*}; digest=${pin#*:}
  file="/provenance/ffmpeg-security/$commit.patch"
  curl --fail --location --retry 0 --max-time 90 --max-filesize 200000 --proto '=https' --tlsv1.2 \
    "https://github.com/FFmpeg/FFmpeg/commit/$commit.patch" -o "$file"
  printf '%s  %s\n' "$digest" "$file" | sha256sum --check --strict -
  # Keep every functional and test hunk. Only main-branch release bookkeeping
  # is excluded: this is a security backport, not a new shared-library API release.
  awk '
    BEGIN {
      allowed["b/libavutil/encryption_info.c"]=1;
      allowed["b/libavutil/encryption_info.h"]=1;
      allowed["b/libavutil/tests/encryption_info.c"]=1;
      allowed["b/libavformat/isom.h"]=1;
      allowed["b/libavformat/mov.c"]=1;
    }
    /^diff --git/ {
      if ($4 in allowed) keep=1;
      else if ($4=="b/doc/APIchanges" || $4=="b/libavutil/version.h") keep=0;
      else exit 99;
    }
    keep {print}
  ' "$file" > "$file.backport"
  test -s "$file.backport"
  patch --batch --fuzz=0 -p1 < "$file.backport"
done
make -j2 fate-encryption-info
printf '{"upstreamEncryptionInfoFatePassed":true}\n' > /provenance/ffmpeg-security/upstream-tests.json
make -j2 libavutil/libavutil.a
gcc -std=c11 -O2 -DNKW_PATCHED_APPEND=1 -I. /native/ffmpeg-encryption-regression.c \
  libavutil/libavutil.a -lm -pthread -o /native-diagnostics/ffmpeg-encryption-regression
(ulimit -c 0; ulimit -s 256; /native-diagnostics/ffmpeg-encryption-regression) > /provenance/ffmpeg-security/regression.json
sha256sum /provenance/ffmpeg-security/*.patch /provenance/ffmpeg-security/*.backport > /provenance/ffmpeg-security/patches.sha256
printf 'Upstream PR24593 functional code and tests; doc/APIchanges and libavutil/version.h bookkeeping excluded for static 9.0.2 security backport.\n' > /provenance/ffmpeg-security/backport-scope.txt
