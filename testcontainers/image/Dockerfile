# syntax=docker/dockerfile:1
FROM debian:bookworm-slim

ARG TARGETARCH
ARG COPILOT_VERSION=1.0.105
LABEL org.opencontainers.image.source="https://github.com/brunoborges/copilot-sdk-integrations" \
      org.opencontainers.image.title="Copilot CLI for Testcontainers" \
      org.opencontainers.image.description="Copilot CLI and runtime harness for the Copilot SDK Testcontainers integration" \
      org.opencontainers.image.version="${COPILOT_VERSION}"
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git \
 && rm -rf /var/lib/apt/lists/*
RUN set -eu; \
    case "${TARGETARCH}" in \
      amd64) platform=linux-x64 ;; \
      arm64) platform=linux-arm64 ;; \
      *) echo "Unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    asset="github-copilot-${COPILOT_VERSION}-${platform}.tgz"; \
    base="https://github.com/github/copilot-cli/releases/download/v${COPILOT_VERSION}"; \
    mkdir -p /opt/download /opt/copilot; \
    cd /opt/download; \
    curl -fsSL -o "${asset}" "${base}/${asset}"; \
    curl -fsSL -o SHA256SUMS.txt "${base}/SHA256SUMS.txt"; \
    awk -v a="${asset}" '$2 == a || $2 == "*" a { print $1 "  " a; n++ } END { exit n != 1 }' SHA256SUMS.txt > archive.sha256; \
    sha256sum -c archive.sha256; \
    tar -xzf "${asset}" -C /opt/copilot --strip-components=1; \
    if [ -d "/opt/copilot/prebuilds/${platform}" ]; then cp -a "/opt/copilot/prebuilds/${platform}/." /opt/copilot/; fi; \
    chmod +x /opt/copilot/copilot-runtime; \
    cli_asset="copilot-${platform}.tar.gz"; \
    curl -fsSL -o "${cli_asset}" "${base}/${cli_asset}"; \
    awk -v a="${cli_asset}" '$2 == a || $2 == "*" a { print $1 "  " a; n++ } END { exit n != 1 }' SHA256SUMS.txt > archive.sha256; \
    sha256sum -c archive.sha256; \
    tar -xzf "${cli_asset}" -C /usr/local/bin copilot; \
    chmod +x /usr/local/bin/copilot; \
    test -s /opt/copilot/LICENSE.md; \
    rm -rf /opt/download

RUN useradd --create-home --uid 10001 copilot \
 && mkdir -p /home/copilot/workspace \
 && chown copilot:copilot /home/copilot/workspace
COPY --chmod=755 entrypoint.sh /usr/local/bin/copilot-server
USER copilot
ENV HOME=/home/copilot COPILOT_PORT=4321
WORKDIR /home/copilot/workspace
EXPOSE 4321
ENTRYPOINT ["/usr/local/bin/copilot-server"]
