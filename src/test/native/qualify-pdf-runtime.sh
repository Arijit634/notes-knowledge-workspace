#!/bin/sh
# Explicit operator qualification, never application startup or mandatory CI.
# Verify actual native hardware independently; uname/JVM properties alone do not
# distinguish native ARM64 from emulation. No cloud resources are provisioned.
set -eu
if [ "$#" -ne 3 ]; then
    echo 'Usage: qualify-pdf-runtime.sh PINNED_JDK_HOME JLINK_RUNTIME TEST_CLASSPATH' >&2
    exit 2
fi
: "${PDF_SANDBOX_CC:?Set the absolute reviewed compiler path}"
: "${PDF_SANDBOX_CC_SHA256:?Set the reviewed compiler SHA-256}"
case "$PDF_SANDBOX_CC" in /*) ;; *) exit 2 ;; esac
case "$(uname -m)" in aarch64) architecture=aarch64 ;; x86_64) architecture=amd64 ;; *) exit 2 ;; esac
compiler_hash=$(sha256sum "$PDF_SANDBOX_CC" | cut -d ' ' -f 1)
if [ "$compiler_hash" != "$PDF_SANDBOX_CC_SHA256" ]; then exit 2; fi
root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
umask 077
owned=$(mktemp -d "${TMPDIR:-/tmp}/nkw-pdf-qualification.XXXXXXXX")
# Only this script's exact freshly-created directory is removed. No globs or
# user-supplied cleanup target; the worker cannot write in its parent directory.
trap 'rm -f -- "$owned/pdf-sandbox"; rmdir -- "$owned"' EXIT HUP INT TERM
"$PDF_SANDBOX_CC" -std=c11 -Wall -Wextra -Werror -O2 -fstack-protector-strong \
    -D_FORTIFY_SOURCE=3 -fPIE -pie -Wl,-z,relro,-z,now \
    "$root/src/main/native/pdf-sandbox.c" -o "$owned/pdf-sandbox"
helper_hash=$(sha256sum "$owned/pdf-sandbox" | cut -d ' ' -f 1)
"$1/bin/java" --enable-native-access=ALL-UNNAMED -cp "$3" \
    org.notesknowledge.notes.PdfSandboxQualification "$owned/pdf-sandbox" "$2" "$architecture" "$helper_hash"
