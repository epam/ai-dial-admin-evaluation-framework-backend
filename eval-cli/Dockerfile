# Build stage
FROM gradle:9.6.0-jdk25-alpine AS builder
WORKDIR /build-workspace

COPY build.gradle .
COPY settings.gradle .
COPY gradle.properties .
COPY lombok.config .
COPY src/ src/
COPY evaluation-runner-core/ evaluation-runner-core/
COPY eval-cli/ eval-cli/

RUN gradle --no-daemon :eval-cli:clean :eval-cli:bootJar

# Runtime stage
FROM amazoncorretto:25-alpine AS runtime
WORKDIR /app

RUN adduser -u 1001 --disabled-password --gecos "" appuser && \
    mkdir -p /app/eval-cli-work && \
    chown -R appuser:appuser /app

RUN apk add --no-cache bash

COPY --from=builder --chown=appuser:appuser /build-workspace/eval-cli/build/libs/eval-cli*.jar ./eval-cli.jar
COPY --chown=appuser:appuser eval-cli/docker-entrypoint.sh /usr/local/bin/

RUN chmod +x /usr/local/bin/docker-entrypoint.sh

USER appuser

ENTRYPOINT ["docker-entrypoint.sh"]
