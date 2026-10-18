# Build on the actual target architecture. This builds a qualification/deployment
# candidate; neither construction nor an OCI digest grants codec patent rights.
FROM debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f AS build
USER root
# Only this distribution's signed main/updates/security indexes are used.
# Retain the exact Debian codec source, notices and installed-package inventory.
RUN sed -i 's/^Types: deb$/Types: deb deb-src/' /etc/apt/sources.list.d/debian.sources \
    && apt-get update && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends \
    build-essential pkg-config nasm xz-utils patch perl-base=5.40.1-6+deb13u1 \
    libopenh264-dev=2.6.0+dfsg-2 \
    libfreetype6=2.13.3+dfsg-1+deb13u1 libfontconfig1=2.15.0-2.3 fonts-dejavu-core=2.37-8 \
    && mkdir -p /provenance/openh264-source \
    && cd /provenance/openh264-source && apt-get source --download-only openh264=2.6.0+dfsg-2 \
    && sha256sum ./* > /provenance/openh264-source.sha256
COPY jdk/ /jdk/
COPY ffmpeg-9.0.2.tar.xz /source.tar.xz
COPY native/ /native/
RUN echo '8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e  /source.tar.xz' | sha256sum -c - \
    && mkdir /codec && tar -xJf /source.tar.xz -C /codec --strip-components=1 \
    && cd /codec && patch --fuzz=0 -p1 < /native/empty-metadata.patch \
    && ./configure --prefix=/opt/video/tools --disable-autodetect --disable-gpl --disable-nonfree \
       --disable-doc --disable-debug --disable-network --disable-everything \
       --enable-ffmpeg --enable-ffprobe --enable-libopenh264 --enable-protocol=file,pipe \
       --enable-demuxer=mov --enable-muxer=mp4,segment,null --enable-decoder=h264,wrapped_avframe \
       --enable-parser=h264 --enable-encoder=libopenh264 --enable-bsf=h264_mp4toannexb \
       --enable-filter=format,null,scale,setsar,setpts,trim,testsrc,color --enable-indev=lavfi \
    && make -j2 && make install
RUN mkdir -p /opt/pdf /opt/video /fixtures /provenance /native-diagnostics \
    && gcc -std=c11 -O2 -Wall -Wextra -Werror=implicit-function-declaration -o /opt/pdf/helper /native/pdf-sandbox.c \
    && gcc -std=c11 -O2 -Wall -Wextra -Werror=implicit-function-declaration -o /opt/video/helper /native/video-sandbox.c \
    && gcc -std=c11 -O2 -Wall -Wextra -DPDF_SANDBOX_DIAGNOSTIC -o /native-diagnostics/pdf-diagnostic-helper /native/pdf-sandbox.c \
    && /jdk/bin/jlink --add-modules java.base,java.desktop,java.logging,java.xml \
       --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output /tmp/pdf-linked-runtime \
    && cp -rL /tmp/pdf-linked-runtime /opt/pdf/runtime \
    && cp -a /opt/pdf/runtime /opt/video/runtime \
    && /jdk/bin/jlink --add-modules java.se,jdk.crypto.ec,jdk.unsupported,jdk.management,jdk.management.agent,jdk.naming.dns,jdk.localedata,jdk.charsets,jdk.zipfs,jdk.security.auth,jdk.jfr,jdk.net \
       --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output /service-runtime \
    && cp /usr/lib/$(gcc -dumpmachine)/libopenh264.so.2.6.0 /opt/video/tools/libopenh264.so.8 \
    && cp /usr/share/doc/libopenh264-8/copyright /provenance/openh264-copyright \
    && cp /codec/COPYING.LGPLv2.1 /provenance/ffmpeg-LGPLv2.1 \
    && cp /codec/ffbuild/config.mak /provenance/ffmpeg-config.mak \
    && dpkg-query -W > /provenance/os-build-packages.txt \
    && /jdk/bin/java -version 2> /provenance/jdk-version.txt \
    && /opt/video/tools/bin/ffmpeg -buildconf > /provenance/ffmpeg-buildconf.txt 2>&1 \
    && ldd /opt/video/tools/bin/ffmpeg /opt/video/tools/bin/ffprobe /jdk/lib/server/libjvm.so > /provenance/runtime-linkage.txt \
    && ! grep -q 'not found' /provenance/runtime-linkage.txt
# jlink's trusted legal-notice aliases are materialized as regular files above.
# Runtime identity/custody still reject every symlink; that check is not relaxed.
COPY artifacts/ /opt/pdf/artifacts/
RUN cp -a /opt/pdf/artifacts /opt/video/artifacts
RUN /opt/video/tools/bin/ffmpeg -hide_banner -loglevel error -nostdin -y -f lavfi \
    -i 'color=c=blue:duration=1:size=64x64:rate=25' -vf 'format=yuv420p,setsar=0' \
    -c:v libopenh264 -profile:v constrained_baseline -threads 1 -g 25 -bf 0 -b:v 600k \
    -map_metadata -1 -map_chapters -1 -video_track_timescale 1000 -movie_timescale 1000 \
    -use_editlist 0 -movflags +faststart -brand mp42 -write_btrt 0 -fflags +bitexact -flags:v +bitexact /opt/video/tools/preflight.mp4
RUN for kind in long vfr; do \
    if [ "$kind" = vfr ]; then timing='setpts=(N+floor(N/2))/(25*TB),'; else timing=''; fi; \
    /opt/video/tools/bin/ffmpeg -hide_banner -loglevel error -nostdin -y -f lavfi \
    -i 'testsrc=duration=40:size=128x96:rate=25' -vf "${timing}format=yuv420p,setsar=0" -fps_mode passthrough \
    -c:v libopenh264 -profile:v constrained_baseline -threads 1 -g 1500 -bf 0 -b:v 600k \
    -map_metadata -1 -map_chapters -1 -video_track_timescale 1000 -movie_timescale 1000 \
    -use_editlist 0 -movflags +faststart -brand mp42 -write_btrt 0 -fflags +bitexact -flags:v +bitexact /fixtures/$kind.mp4; done \
    && printf '\000\000\000\010ftyp' > /fixtures/broken.mp4
COPY application.jar /application.jar
COPY controller/ /qualification/
# Analyze production classes/dependencies, not the controller classes. Optional
# external integrations can have absent classes, but every discovered JDK module
# must be present in the linked service runtime before an image can be qualified.
RUN /jdk/bin/jdeps -J-Xmx256m --multi-release 25 --ignore-missing-deps --recursive --print-module-deps \
      --class-path '/qualification/libraries/*' /opt/pdf/artifacts/classes > /provenance/service-required-modules.txt \
    && /service-runtime/bin/java --list-modules > /provenance/service-modules.txt \
    && for module in $(tr ',' ' ' < /provenance/service-required-modules.txt); do \
         grep -q "^${module}@" /provenance/service-modules.txt || exit 1; done
RUN chown -R 0:0 /opt/pdf /opt/video /qualification /fixtures /provenance \
    && chmod -R go-w /opt/pdf /opt/video /qualification /fixtures /provenance \
    && find /opt/pdf /opt/video -type f -exec sha256sum {} \; > /provenance/runtime-files.sha256
RUN /jdk/bin/java -cp '/qualification:/opt/pdf/artifacts/classes:/opt/pdf/artifacts/libraries/*' \
    org.notesknowledge.notes.MediaRuntimeImageDescriptor /provenance/runtime-selection.properties
# A separate runtime image omits the compiler/build tree. Qualification adds
# only synthetic fixtures/controllers to this same runtime filesystem profile.
FROM debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f AS deployment
ARG SOURCE_SHA=unbound
LABEL org.opencontainers.image.source="https://github.com/Arijit634/notes-knowledge-workspace" \
      org.opencontainers.image.revision="$SOURCE_SHA"
USER root
RUN apt-get update && apt-get upgrade -y --no-install-recommends && apt-get install -y --no-install-recommends \
    perl-base=5.40.1-6+deb13u1 libpcre2-8-0=10.46-1~deb13u3 \
    libopenh264-8=2.6.0+dfsg-2 libfreetype6=2.13.3+dfsg-1+deb13u1 \
    libfontconfig1=2.15.0-2.3 fonts-dejavu-core=2.37-8 && rm -rf /var/lib/apt/lists/*
COPY --from=build /service-runtime /jdk
COPY --from=build /opt/pdf /opt/pdf
COPY --from=build /opt/video /opt/video
COPY --from=build /provenance /provenance
COPY --from=build /source.tar.xz /provenance/ffmpeg-9.0.2.tar.xz
COPY --from=build /native/empty-metadata.patch /provenance/empty-metadata.patch
COPY --from=build /application.jar /application.jar
# The service never needs setuid/setgid utilities. Remove their privilege bits
# as image-level defense in depth, independently of runtime no-new-privileges.
RUN find /usr /opt /jdk -xdev -type f -perm /6000 -exec chmod a-s {} +
USER 65533:65533
ENTRYPOINT ["/jdk/bin/java", "-jar", "/application.jar"]
# No registry publication is performed. A release must separately reconcile
# redistribution, source availability/notice obligations AND codec patent rights.
FROM deployment AS qualification
# Compiler/controller tools exist ONLY in the qualification target.
COPY --from=build /jdk /jdk
COPY --from=build /qualification /qualification
COPY --from=build /fixtures /fixtures
COPY --from=build /native-diagnostics/pdf-diagnostic-helper /qualification/pdf-diagnostic-helper
USER 65533:65533
ENTRYPOINT ["/jdk/bin/java"]
