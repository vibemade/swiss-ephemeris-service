# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Runs a released build: downloads the release archive for this platform from GitHub, checks it against the
# release's SHA256SUMS and runs it on a JRE as a non-root user with a gRPC health check.
#
#   docker build -t swiss-ephemeris-service .                                   # latest release
#   docker build --build-arg RELEASE=download/v0.1.0 -t swiss-ephemeris-service .  # a pinned release
#
# BuildKit re-checks the remote files on every build, so a rebuild picks up a new release and reuses the cache
# otherwise. To build from the sources in this checkout instead, use Dockerfile.source.

ARG JRE_IMAGE=eclipse-temurin:25-jre-noble

FROM ${JRE_IMAGE} AS fetch
ARG REPOSITORY=vibemade/swiss-ephemeris-service
# "latest/download" for the newest release, or "download/<tag>" for a pinned one.
ARG RELEASE=latest/download
# Where the release files are; overridable to test an archive before it is published.
ARG BASE_URL=https://github.com/${REPOSITORY}/releases/${RELEASE}
ARG TARGETARCH
ADD ${BASE_URL}/SHA256SUMS /tmp/release/
ADD ${BASE_URL}/swiss-ephemeris-service-linux-${TARGETARCH}.tar.gz /tmp/release/
RUN cd /tmp/release \
 && sha256sum --check --ignore-missing --strict SHA256SUMS \
 && mkdir /out \
 && tar -xzf swiss-ephemeris-service-linux-${TARGETARCH}.tar.gz -C /out

FROM ${JRE_IMAGE}
LABEL org.opencontainers.image.title="swiss-ephemeris-service" \
      org.opencontainers.image.description="Stateless gRPC ephemeris service built on the Swiss Ephemeris" \
      org.opencontainers.image.source="https://github.com/vibemade/swiss-ephemeris-service" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later"

COPY --from=fetch /out/swiss-ephemeris-service/ /app/

ENV PORT=50051 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

USER 10001:10001
EXPOSE 50051
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD ["/app/bin/swiss-ephemeris-service", "healthcheck"]
ENTRYPOINT ["/app/bin/swiss-ephemeris-service"]
