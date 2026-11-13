# Disposable, no-credential proof. Production uses the separate full build.
FROM ghcr.io/arijit634/notes-knowledge-workspace-private-preview@sha256:e40b0a9551773440989e669b0fcff900df5d98788f00beab8624c3f724435b62
ARG SOURCE_SHA
LABEL org.opencontainers.image.revision="$SOURCE_SHA"
USER 0:0
COPY --chmod=555 classes/ /qualification/
COPY --chmod=555 service-boundary-diagnostic /qualification/service-boundary-diagnostic
RUN printf x > /provenance/service-write-probe && chmod 666 /provenance/service-write-probe
USER 65533:65533
HEALTHCHECK NONE
ENTRYPOINT ["/qualification/service-boundary-diagnostic"]
