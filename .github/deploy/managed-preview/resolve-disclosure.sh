#!/bin/bash
# Resolve an operator-owned secret projection; never change its bytes or approval.
set -euo pipefail
unavailable() { printf '%s\n' 'preview_disclosure_configuration_unavailable' >&2; exit 2; }
[[ $# == 1 && "$1" == /* && ${#1} -le 4096 ]] || unavailable
root=$(readlink -e -- "$1" 2>/dev/null) || unavailable
snapshot=$(readlink -e -- "$1/processing-disclosure.json" 2>/dev/null) || unavailable
[[ "$root" != / && "$snapshot" == "$root/"* && ${#snapshot} -le 4096
    && "$root$snapshot" != *$'\n'* && "$root$snapshot" != *$'\r'*
    && -d "$root" && -f "$snapshot" && -r "$snapshot" && ! -L "$snapshot" ]] || unavailable
# A writable ancestor could let the application replace an otherwise read-only
# file. Canonical paths and every ancestor must remain outside its write authority.
parent=$snapshot
while :; do
    [[ ! -w "$parent" && ! -L "$parent" ]] || unavailable
    [[ "$parent" == / ]] && break
    parent=${parent%/*}
    [[ -n "$parent" ]] || parent=/
done
printf '%s\n' "$snapshot"
