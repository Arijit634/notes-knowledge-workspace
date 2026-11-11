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

FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce
ARG SOURCE_SHA
LABEL org.opencontainers.image.source="https://github.com/Arijit634/notes-knowledge-workspace" \
      org.opencontainers.image.revision="$SOURCE_SHA"
# CVE-2026-109518. HTTPS + no retries also protects the bootstrap update itself.
RUN sed -i -e 's|http://|https://|g' \
    -e 's|https://archive.ubuntu.com/ubuntu|https://mirrors.ocf.berkeley.edu/ubuntu|g' \
    -e 's|https://security.ubuntu.com/ubuntu|https://mirrors.ocf.berkeley.edu/ubuntu|g' \
    -e 's|https://ports.ubuntu.com/ubuntu-ports|https://mirrors.ocf.berkeley.edu/ubuntu-ports|g' \
    /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::Retries=0 -o APT::Update::Error-Mode=any update \
    && apt-get -o Acquire::Retries=0 install --no-install-recommends --only-upgrade -y apt=2.8.4ubuntu0.1 libapt-pkg6.0t64=2.8.4ubuntu0.1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=backend --chown=65533:65533 /application.jar /app/application.jar
USER 65533:65533
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=UTC
EXPOSE 8080
ENTRYPOINT ["java","-Xmx768m","-XX:MaxDirectMemorySize=128m","-XX:ActiveProcessorCount=2","-XX:-HeapDumpOnOutOfMemoryError","-XX:+ExitOnOutOfMemoryError","-Duser.timezone=UTC","-Djava.io.tmpdir=/tmp","-jar","/app/application.jar"]
