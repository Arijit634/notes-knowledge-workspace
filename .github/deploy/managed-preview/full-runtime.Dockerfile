# Build on the actual target architecture. This builds a qualification/deployment
# candidate; neither construction nor an OCI digest grants codec patent rights.
FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce AS public-trust

FROM ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852 AS build
COPY --from=public-trust /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
USER root
# HTTPS mirror of the SAME Ubuntu archive; the pinned base's Ubuntu signing
# keyring, package pins and APT authentication remain authoritative.
# Both build and deployment use the same Noble ABI as the approved Nginx edge.
RUN sed -i -e 's|http://|https://|g' \
    -e 's|https://archive.ubuntu.com/ubuntu|https://mirrors.ocf.berkeley.edu/ubuntu|g' \
    -e 's|https://security.ubuntu.com/ubuntu|https://mirrors.ocf.berkeley.edu/ubuntu|g' \
    -e 's|https://ports.ubuntu.com/ubuntu-ports|https://mirrors.ocf.berkeley.edu/ubuntu-ports|g' \
    /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::Retries=0 -o APT::Update::Error-Mode=any update && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends \
       build-essential dpkg-dev pkg-config nasm xz-utils patch curl ca-certificates \
       libbrotli-dev libbz2-dev libpng-dev zlib1g-dev libfreetype6 libexpat1 libfontconfig1 fonts-dejavu-core \
    && mkdir -p /provenance
COPY managed/build-openh264.sh /native/build-openh264.sh
RUN /bin/sh /native/build-openh264.sh
COPY jdk/ /jdk/
COPY ffmpeg-9.0.2.tar.xz /source.tar.xz
COPY native/ /native/
RUN /bin/sh /native/build-media-libraries.sh
RUN echo '8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e  /source.tar.xz' | sha256sum -c - \
    && mkdir /codec && tar -xJf /source.tar.xz -C /codec --strip-components=1 \
    && cd /codec && patch --fuzz=0 -p1 < /native/empty-metadata.patch \
    && ./configure --prefix=/opt/video/tools --extra-version=nkw-security24593 --disable-autodetect --disable-iconv --disable-gpl --disable-nonfree \
       --disable-doc --disable-debug --disable-network --disable-everything \
       --enable-ffmpeg --enable-ffprobe --enable-libopenh264 --enable-protocol=file,pipe \
       --enable-demuxer=mov --enable-muxer=mp4,segment,null,wav --enable-decoder=h264,wrapped_avframe,aac \
       --enable-parser=h264,aac --enable-encoder=libopenh264,pcm_s16le --enable-bsf=h264_mp4toannexb \
       --enable-filter=format,null,scale,setsar,setpts,trim,testsrc,color,aresample,apad,atrim,asetpts,aformat --enable-indev=lavfi \
    && /bin/sh /native/backport-ffmpeg-security.sh \
    && make -j2 && make install
RUN mkdir -p /opt/pdf /opt/video /opt/operations /fixtures /provenance /native-diagnostics \
    && gcc -std=c11 -O2 -Wall -Wextra -Werror=implicit-function-declaration -o /opt/pdf/helper /native/pdf-sandbox.c \
    && gcc -std=c11 -O2 -Wall -Wextra -Werror=implicit-function-declaration -o /opt/video/helper /native/video-sandbox.c \
    && gcc -std=c11 -O2 -Wall -Wextra -DPDF_SANDBOX_DIAGNOSTIC -o /native-diagnostics/pdf-diagnostic-helper /native/pdf-sandbox.c \
    && gcc -std=c11 -O2 -Wall -Wextra -Werror -o /opt/operations/health-probe /native/health-probe.c \
    && g++ -std=c++17 -O2 -Wall -Wextra -Werror -o /native-diagnostics/allocation-overflow-probe /native/allocation-overflow-probe.cpp \
    && /native-diagnostics/allocation-overflow-probe \
    && /jdk/bin/jlink --add-modules java.base,java.desktop,java.logging,java.xml \
       --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output /tmp/pdf-linked-runtime \
    && cp -rL /tmp/pdf-linked-runtime /opt/pdf/runtime \
    && cp -a /opt/pdf/runtime /opt/video/runtime \
    && /jdk/bin/jlink --add-modules java.se,jdk.crypto.ec,jdk.unsupported,jdk.management,jdk.management.agent,jdk.naming.dns,jdk.localedata,jdk.charsets,jdk.zipfs,jdk.security.auth,jdk.jfr,jdk.net \
       --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output /service-runtime \
    && cp /usr/lib/$(gcc -dumpmachine)/libopenh264.so.2.6.0 /opt/video/tools/libopenh264.so.8 \
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
COPY native/audio-preflight.mp4 /opt/video/tools/audio-preflight.mp4
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
RUN chown -R 0:0 /opt/pdf /opt/video /opt/operations /qualification /fixtures /provenance \
    && chmod -R go-w /opt/pdf /opt/video /opt/operations /qualification /fixtures /provenance \
    && find /opt/pdf /opt/video /opt/operations -type f -exec sha256sum {} \; > /provenance/runtime-files.sha256
RUN /jdk/bin/java -cp '/qualification:/opt/pdf/artifacts/classes:/opt/pdf/artifacts/libraries/*' \
    org.notesknowledge.notes.MediaRuntimeImageDescriptor /provenance/runtime-selection.properties
FROM ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852 AS redis-build
COPY --from=public-trust /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
RUN sed -i -e 's|http://|https://|g' \
    -e 's|https://archive.ubuntu.com/ubuntu|https://mirrors.ocf.berkeley.edu/ubuntu|g' \
    -e 's|https://security.ubuntu.com/ubuntu|https://mirrors.ocf.berkeley.edu/ubuntu|g' \
    -e 's|https://ports.ubuntu.com/ubuntu-ports|https://mirrors.ocf.berkeley.edu/ubuntu-ports|g' \
    /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::Retries=0 -o APT::Update::Error-Mode=any update \
    && apt-get install -y --no-install-recommends gcc make libc6-dev curl ca-certificates \
    && mkdir /build && cd /build \
    && curl --fail --silent --show-error --location --retry 0 https://github.com/redis/redis/releases/download/8.10.3/redis-full.tar.gz -o redis-full.tar.gz \
    && echo '5e0c00ed77f789fb1a4a30f1fbb58a793cc88c41c0f11f0e38e1b4f192991188  redis-full.tar.gz' | sha256sum --check - \
    && mkdir redis && tar -xzf redis-full.tar.gz -C redis --strip-components=1 \
    && make -C redis -j2 MALLOC=libc BUILD_TLS=no redis-server && strip redis/src/redis-server

# A separate runtime image omits the compiler/build tree. Qualification adds
# only synthetic fixtures/controllers to this same runtime filesystem profile.
FROM ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852 AS deployment
COPY --from=public-trust /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
ARG SOURCE_SHA=unbound
LABEL org.opencontainers.image.source="https://github.com/Arijit634/notes-knowledge-workspace" \
      org.opencontainers.image.revision="$SOURCE_SHA"
USER root
RUN sed -i -e 's|http://|https://|g' \
    -e 's|https://archive.ubuntu.com/ubuntu|https://mirrors.ocf.berkeley.edu/ubuntu|g' \
    -e 's|https://security.ubuntu.com/ubuntu|https://mirrors.ocf.berkeley.edu/ubuntu|g' \
    -e 's|https://ports.ubuntu.com/ubuntu-ports|https://mirrors.ocf.berkeley.edu/ubuntu-ports|g' \
    /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::Retries=0 -o APT::Update::Error-Mode=any update && apt-get upgrade -y --no-install-recommends \
    && printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d && chmod 755 /usr/sbin/policy-rc.d \
    && apt-get install -y --no-install-recommends ca-certificates bash \
       libstdc++6 libfreetype6 libexpat1 libfontconfig1 fonts-dejavu-core \
       libbrotli1 libbz2-1.0 libpng16-16t64 zlib1g \
       nginx=1.24.0-2ubuntu7.19 nginx-common=1.24.0-2ubuntu7.19 \
    && rm /usr/sbin/policy-rc.d && rm -rf /var/lib/apt/lists/*
COPY --from=build /openh264-build/libopenh264-8.deb /tmp/libopenh264-8.deb
RUN dpkg -i /tmp/libopenh264-8.deb && ldconfig && rm /tmp/libopenh264-8.deb
COPY --from=build /service-runtime /jdk
COPY --from=build /opt/pdf /opt/pdf
COPY --from=build /opt/video /opt/video
COPY --from=build /opt/operations /opt/operations
COPY --from=build /provenance /provenance
COPY --from=build /source.tar.xz /provenance/ffmpeg-9.0.2.tar.xz
COPY --from=build /native/empty-metadata.patch /provenance/empty-metadata.patch
COPY --from=build /application.jar /application.jar
COPY --from=build /media-library-packages /tmp/controlled-media-libraries
RUN dpkg -i /tmp/controlled-media-libraries/libexpat1.deb /tmp/controlled-media-libraries/libfreetype6.deb \
    && ldconfig && rm -rf /tmp/controlled-media-libraries
COPY --from=build /native/minimize-runtime-tools.sh /tmp/minimize-runtime-tools.sh
# The service never needs setuid/setgid utilities. Remove their privilege bits
# as image-level defense in depth, independently of runtime no-new-privileges.
RUN find /usr /opt /jdk -xdev -type f -perm /6000 -exec chmod a-s {} +
RUN /bin/sh /tmp/minimize-runtime-tools.sh && rm /tmp/minimize-runtime-tools.sh
COPY --from=redis-build /build/redis/src/redis-server /usr/local/bin/redis-server
COPY --from=redis-build /build/redis-full.tar.gz /provenance/redis-8.10.3-source.tar.gz
COPY --chmod=755 managed/start-full.sh /app/start-full.sh
COPY managed/nginx.conf /app/nginx.conf
RUN ln -s /application.jar /app/application.jar && nginx -v && redis-server --version
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=UTC APP_ENVIRONMENT=private-preview
EXPOSE 10000
USER 65533:65533
HEALTHCHECK --interval=30s --start-period=15m --start-interval=30s --timeout=4s --retries=3 CMD ["/opt/operations/health-probe"]
ENTRYPOINT ["/app/start-full.sh"]
# No registry publication is performed. A release must separately reconcile
# redistribution, source availability/notice obligations AND codec patent rights.
FROM deployment AS qualification
# Compiler/controller tools exist ONLY in the qualification target.
COPY --from=build /jdk /jdk
COPY --from=build /qualification /qualification
COPY --from=build /fixtures /fixtures
COPY --from=build /native-diagnostics/pdf-diagnostic-helper /qualification/pdf-diagnostic-helper
COPY --from=build /native-diagnostics/media-library-versions /qualification/media-library-versions
COPY --from=build /native-diagnostics/allocation-overflow-probe /qualification/allocation-overflow-probe
# Fixture ownership changes are a qualification-only capability, not a runtime tool.
COPY --from=build /usr/bin/chown /qualification/bin/chown
USER 65533:65533
ENTRYPOINT ["/jdk/bin/java"]
