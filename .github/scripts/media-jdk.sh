#!/usr/bin/env bash
set -euo pipefail
case "$(uname -m)" in
  x86_64) arch=x64; expected=d3b07dd6fd096353d6834e62a6f32117eb610d0bee24cb70acb8c82832043b68 ;;
  aarch64) arch=aarch64; expected=7c9eb1828d479dc9f546ba918a4c98593462bdb03fee404f9a63611ffffe546c ;;
  *) exit 1 ;;
esac
test ! -e "$RUNNER_TEMP/media-jdk"
mkdir "$RUNNER_TEMP/media-jdk"
archive="$RUNNER_TEMP/microsoft-jdk.tar.gz"
curl --fail --location --retry 2 --proto '=https' --tlsv1.2 \
  "https://aka.ms/download-jdk/microsoft-jdk-25.0.4.1-linux-$arch.tar.gz" --output "$archive"
printf '%s  %s\n' "$expected" "$archive" | sha256sum --check --strict -
tar -xzf "$archive" --strip-components=1 -C "$RUNNER_TEMP/media-jdk"
printf 'JAVA_HOME=%s/media-jdk\n' "$RUNNER_TEMP" >> "$GITHUB_ENV"
printf '%s/media-jdk/bin\n' "$RUNNER_TEMP" >> "$GITHUB_PATH"
