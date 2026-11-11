FROM debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f AS compiler
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev binutils
COPY probe.c /probe.c
RUN gcc -std=c11 -O2 -Wall -Wextra -Werror -ffreestanding -fno-builtin -fno-stack-protector -nostdlib -static -no-pie -o /kernel-probe /probe.c \
    && ! readelf -l /kernel-probe | grep -q INTERP \
    && ! readelf -d /kernel-probe | grep -q NEEDED \
    && printf allowed > /allowed && printf denied > /denied
FROM scratch
ARG SOURCE_SHA
LABEL org.opencontainers.image.source="https://github.com/Arijit634/notes-knowledge-workspace" org.opencontainers.image.revision="$SOURCE_SHA"
COPY --from=compiler --chmod=555 /kernel-probe /probe/kernel-probe
COPY --from=compiler --chmod=444 /allowed /probe/allowed
COPY --from=compiler --chmod=444 /denied /probe/denied
USER 65533:65533
EXPOSE 10000
ENTRYPOINT ["/probe/kernel-probe"]
