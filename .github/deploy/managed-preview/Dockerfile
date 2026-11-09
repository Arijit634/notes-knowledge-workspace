FROM node:24.21.0-bookworm-slim@sha256:d6aa754f16b3197301076f047b5def2f02ea1dbbc2ca920407d46d7ec7f87b20 AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --ignore-scripts
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a AS backend
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
COPY --from=frontend /build/frontend/dist/ frontend/dist/
# Verification is a separate required gate on this exact source revision.
RUN chmod +x mvnw && ./mvnw --batch-mode --no-transfer-progress -Pwith-frontend -DskipTests package -q \
    && cp target/notes-knowledge-workspace-*.jar /application.jar

FROM eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a AS redis-build
# Same approved server, built unmodified against the runtime's libc. No modules,
# downloaded build scripts, third-party binary or compiler enters the runtime.
RUN sed -i 's|http://|https://|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::Retries=0 update \
    && apt-get -o Acquire::Retries=0 install --no-install-recommends -y gcc make libc6-dev curl ca-certificates \
    && mkdir /build && cd /build \
    && curl --fail --silent --show-error --location --retry 0 https://github.com/redis/redis/releases/download/8.10.2/redis-full.tar.gz -o redis-full.tar.gz \
    && echo 'ae6973de9b6ad8e6cf21b1e764abccab59200689af6aabce10d4cf6bbb2e253f  redis-full.tar.gz' | sha256sum --check - \
    && mkdir redis && tar -xzf redis-full.tar.gz -C redis --strip-components=1 \
    && make -C redis -j2 MALLOC=libc BUILD_TLS=no redis-server \
    && strip redis/src/redis-server

FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce
ARG SOURCE_SHA
LABEL org.opencontainers.image.source="https://github.com/Arijit634/notes-knowledge-workspace" \
      org.opencontainers.image.revision="$SOURCE_SHA"
# CVE-2026-109518. HTTPS + no retries also protects the bootstrap update itself.
RUN sed -i 's|http://|https://|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::Retries=0 update \
    && printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d && chmod 755 /usr/sbin/policy-rc.d \
    && apt-get -o Acquire::Retries=0 install --no-install-recommends -y apt=2.8.4ubuntu0.1 libapt-pkg6.0t64=2.8.4ubuntu0.1 nginx=1.24.0-2ubuntu7.19 nginx-common=1.24.0-2ubuntu7.19 \
    && rm /usr/sbin/policy-rc.d \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=backend --chown=0:0 /application.jar /app/application.jar
COPY --from=redis-build /build/redis/src/redis-server /usr/local/bin/redis-server
# Corresponding unmodified Redis source and license notices travel with the binary.
COPY --from=redis-build /build/redis-full.tar.gz /usr/share/nkw-preview/redis-8.10.2-source.tar.gz
COPY --chmod=755 .github/deploy/managed-preview/start.sh /app/start.sh
COPY .github/deploy/managed-preview/nginx.conf /app/nginx.conf
RUN redis-server --version && nginx -v
USER 65533:65533
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=UTC
ENV APP_ENVIRONMENT=private-preview
EXPOSE 10000
ENTRYPOINT ["/app/start.sh"]
