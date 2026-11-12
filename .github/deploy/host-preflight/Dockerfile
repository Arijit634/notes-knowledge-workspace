# Diagnostic derivative of the exact attested full runtime. Never a release.
FROM ghcr.io/arijit634/notes-knowledge-workspace-private-preview@sha256:e40b0a9551773440989e669b0fcff900df5d98788f00beab8624c3f724435b62
ARG SOURCE_SHA
LABEL org.opencontainers.image.revision="$SOURCE_SHA"
COPY --chmod=444 classes/ /host-probe/
USER 65533:65533
HEALTHCHECK NONE
ENTRYPOINT ["/jdk/bin/java", "-Xmx32m", "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC", "-cp", "/host-probe:/opt/pdf/artifacts/classes:/opt/pdf/artifacts/libraries/*", "org.notesknowledge.notes.HostPreflight"]
