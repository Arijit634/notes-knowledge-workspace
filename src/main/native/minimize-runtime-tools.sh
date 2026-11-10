#!/bin/sh
# Remove unused administrative programs from this immutable application image.
# Keep package metadata and an exact removal inventory; do not hide scanner data.
set -eu
test "$(id -u)" = 0
test ! -e /provenance/removed-runtime-tools.sha256
: > /provenance/removed-runtime-tools.sha256
remove_tool() {
  case "$1" in /usr/bin/*|/usr/sbin/*|/bin/*|/sbin/*|/usr/libexec/rmt*) ;; *) exit 1 ;; esac
  if [ -f "$1" ] || [ -L "$1" ]; then
    if [ -f "$1" ]; then sha256sum "$1" >> /provenance/removed-runtime-tools.sha256; fi
    rm -f -- "$1"
  fi
}
for package in apt perl-base tar util-linux bsdutils login passwd ncurses-bin sysvinit-utils; do
  dpkg-query -L "$package" | while IFS= read -r path; do
    case "$path" in /usr/bin/*|/usr/sbin/*|/bin/*|/sbin/*|/usr/libexec/rmt*)
      if [ ! -d "$path" ]; then remove_tool "$path"; fi ;;
    esac
  done
done
# These packages also supply the fixed cat/du/hash/inspection tools used by the
# operational probes, so remove only their unused vulnerable command surfaces.
for path in /usr/bin/diff3 /usr/bin/sort /usr/bin/uniq /usr/bin/unexpand \
  /usr/bin/chown /usr/bin/chgrp; do remove_tool "$path"; done
test -x /bin/sh && test -x /usr/bin/cat && test -x /usr/bin/dpkg-query
