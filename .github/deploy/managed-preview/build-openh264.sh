#!/bin/sh
# Build the already-selected codec against Noble's libc; never copy Trixie ELFs.
set -eu
test "$(id -u)" = 0
mkdir -p /openh264-build /provenance/openh264-source /codec-package/DEBIAN
cd /provenance/openh264-source
for item in \
  openh264_2.6.0+dfsg.orig.tar.xz:52fc65775e03abe70f3f453c3c57fa7b31c71fe240de84da53d75f2ddc7dd519 \
  openh264_2.6.0+dfsg-2.debian.tar.xz:9d57036c560394682df042748f419a43452447ae5bb2059003b7a9faef24af04 \
  openh264_2.6.0+dfsg-2.dsc:65320b4c8cd6c56626effe323037dad1b2fc68985f4761a5eefb5441f19750b3; do
  file=${item%%:*}; digest=${item#*:}
  curl --fail --location --retry 0 --max-time 90 --max-filesize 10000000 --proto '=https' --tlsv1.2 \
    "https://deb.debian.org/debian/pool/main/o/openh264/$file" -o "$file"
  printf '%s  %s\n' "$digest" "$file" | sha256sum --check --strict -
done
sha256sum ./* > /provenance/openh264-source.sha256
# Authentication comes from the three committed hashes above. Apply the same
# Debian source patches as the qualified 2.6.0+dfsg-2 package, not a moving branch.
dpkg-source --no-check -x openh264_2.6.0+dfsg-2.dsc /openh264-build/source
cd /openh264-build/source
triplet=$(gcc -dumpmachine)
architecture=$(dpkg --print-architecture)
case "$architecture:$triplet" in amd64:x86_64-linux-gnu|arm64:aarch64-linux-gnu) ;; *) exit 1 ;; esac
make -j2 libraries PREFIX=/usr LIBDIR_NAME="lib/$triplet"
make DESTDIR=/openh264-build/stage PREFIX=/usr LIBDIR_NAME="lib/$triplet" install-shared
cp -a /openh264-build/stage/usr/. /usr/
ldconfig
mkdir -p "/codec-package/usr/lib/$triplet" /codec-package/usr/share/doc/libopenh264-8
cp -a "/usr/lib/$triplet/"libopenh264.so.2.6.0 "/usr/lib/$triplet/"libopenh264.so.8 "/codec-package/usr/lib/$triplet/"
cp debian/copyright /provenance/openh264-copyright
cp debian/copyright /codec-package/usr/share/doc/libopenh264-8/copyright
printf 'Package: libopenh264-8\nSource: openh264\nVersion: 2.6.0+dfsg-2nkw1\nArchitecture: %s\nMaintainer: NKW Build <build@example.test>\nDepends: libc6, libstdc++6\nDescription: Existing OpenH264 codec rebuilt from pinned source for Noble\n' "$architecture" > /codec-package/DEBIAN/control
dpkg-deb --root-owner-group --build /codec-package /openh264-build/libopenh264-8.deb
