#!/bin/sh
# Controlled security updates of two existing runtime libraries, not new services.
set -eu
test "$(id -u)" = 0
test ! -e /media-library-build
mkdir /media-library-build /media-library-packages
cd /media-library-build
architecture="$(dpkg --print-architecture)"
triplet="$(gcc -dumpmachine)"
case "$architecture:$triplet" in amd64:x86_64-linux-gnu|arm64:aarch64-linux-gnu) ;; *) exit 1 ;; esac

curl --fail --location --retry 0 --max-time 90 --max-filesize 20000000 --proto '=https' --tlsv1.2 \
  https://downloads.sourceforge.net/project/freetype/freetype2/2.15.0/freetype-2.15.0.tar.xz -o freetype-2.15.0.tar.xz
printf '%s  %s\n' 93cd47eb719c325107f2d3cf087d06299e944c3b48a40b55537302304dea1dd5 freetype-2.15.0.tar.xz | sha256sum --check --strict -
curl --fail --location --retry 0 --max-time 90 --max-filesize 20000000 --proto '=https' --tlsv1.2 \
  https://github.com/libexpat/libexpat/releases/download/R_2_10_0/expat-2.10.0.tar.xz -o expat-2.10.0.tar.xz
printf '%s  %s\n' c624ba2998fc65df1163a49641ff3f9ab73138b11641d92c86bee0fb01b1bc7f expat-2.10.0.tar.xz | sha256sum --check --strict -

# The old distribution packages supply the same existing runtime dependency set.
# Local .deb ownership/version metadata accurately identifies replaced files;
# the old vulnerable shared-library files are removed by dpkg's normal upgrade.
freetype_dependencies="$(dpkg-query -W -f='${Depends}' libfreetype6)"
expat_dependencies="$(dpkg-query -W -f='${Depends}' libexpat1)"
case "$freetype_dependencies" in *libbz2-1.0*) ;; *) freetype_dependencies="$freetype_dependencies, libbz2-1.0" ;; esac
tar -xJf freetype-2.15.0.tar.xz
tar -xJf expat-2.10.0.tar.xz
(cd freetype-2.15.0 && ./configure --prefix=/usr --libdir="/usr/lib/$triplet" \
  --enable-shared --disable-static --with-harfbuzz=no --with-zlib=yes --with-bzip2=yes --with-png=yes --with-brotli=yes \
  && make -j2 && make DESTDIR=/media-library-build/freetype-stage install)
(cd expat-2.10.0 && ./configure --prefix=/usr --libdir="/usr/lib/$triplet" \
  --enable-shared --disable-static --without-docbook --without-examples --without-xmlwf \
  && make -j2 && make check && make DESTDIR=/media-library-build/expat-stage install)

package_library() {
  package="$1"; source_name="$2"; version="$3"; stage="$4"; stem="$5"; dependencies="$6"
  directory="/media-library-build/$package-package"
  mkdir -p "$directory/DEBIAN" "$directory/usr/lib/$triplet" "$directory/usr/share/doc/$package"
  cp -a "$stage/usr/lib/$triplet/$stem".so.* "$directory/usr/lib/$triplet/"
  printf 'Package: %s\nSource: %s\nVersion: %s-0nkw1\nArchitecture: %s\nMaintainer: NKW Build <build@example.test>\nDepends: %s\nDescription: Controlled upstream security update of the existing media library\n' \
    "$package" "$source_name" "$version" "$architecture" "$dependencies" > "$directory/DEBIAN/control"
  if [ "$package" = libfreetype6 ]; then
    cp freetype-2.15.0/docs/FTL.TXT freetype-2.15.0/docs/GPLv2.TXT "$directory/usr/share/doc/$package/"
  else
    cp expat-2.10.0/COPYING "$directory/usr/share/doc/$package/"
  fi
  dpkg-deb --root-owner-group --build "$directory" "/media-library-packages/$package.deb"
}
package_library libfreetype6 freetype 2.15.0 /media-library-build/freetype-stage libfreetype "$freetype_dependencies"
package_library libexpat1 expat 2.10.0 /media-library-build/expat-stage libexpat "$expat_dependencies"
dpkg -i /media-library-packages/libexpat1.deb /media-library-packages/libfreetype6.deb
ldconfig
mkdir -p /native-diagnostics
cat > /media-library-build/versions.c <<'C'
#include <stdio.h>
#include <string.h>
#include <ft2build.h>
#include FT_FREETYPE_H
#include <expat.h>
int main(void) {
    FT_Library library; FT_Int major,minor,patch;
    if (FT_Init_FreeType(&library)) return 1;
    FT_Library_Version(library,&major,&minor,&patch);
    FT_Done_FreeType(library);
    if (major!=2 || minor!=15 || patch!=0 || strcmp(XML_ExpatVersion(),"expat_2.10.0")) return 1;
    XML_Parser parser=XML_ParserCreate(NULL);
    if (!parser) return 1;
    int ok=XML_Parse(parser,"<nkw/>",6,1)==XML_STATUS_OK;
    XML_ParserFree(parser);
    if (!ok) return 1;
    puts("{\"mediaLibraryVersions\":true,\"freetype\":\"2.15.0\",\"expat\":\"2.10.0\",\"abiProbePassed\":true}");
    return 0;
}
C
gcc -std=c11 -O2 -Wall -Wextra -Werror \
  -I/media-library-build/freetype-stage/usr/include/freetype2 -I/media-library-build/expat-stage/usr/include \
  /media-library-build/versions.c "/usr/lib/$triplet/libfreetype.so.6" "/usr/lib/$triplet/libexpat.so.1" \
  -o /native-diagnostics/media-library-versions
mkdir -p /provenance/media-libraries
/native-diagnostics/media-library-versions > /provenance/media-libraries/abi-probe.json
cp freetype-2.15.0.tar.xz expat-2.10.0.tar.xz /provenance/media-libraries/
sha256sum /media-library-packages/*.deb /provenance/media-libraries/*.tar.xz > /provenance/media-libraries/build-inputs.sha256
dpkg-query -W libfreetype6 libexpat1 > /provenance/media-libraries/package-versions.txt
