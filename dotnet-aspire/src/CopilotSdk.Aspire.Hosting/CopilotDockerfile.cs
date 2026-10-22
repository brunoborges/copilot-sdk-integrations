namespace CopilotSdk.Aspire.Hosting;

/// <summary>
/// The Dockerfile used for the runtime container when no image is set with <c>WithImage</c>. It installs the
/// Copilot CLI release that <c>GitHub.Copilot.SDK</c> pins and verifies its checksum.
/// </summary>
internal static class CopilotDockerfile
{
    public static string Create(string cliVersion) => $$"""
        # syntax=docker/dockerfile:1
        FROM debian:bookworm-slim
        ARG TARGETARCH
        ARG COPILOT_VERSION={{cliVersion}}
        RUN apt-get update \
         && apt-get install -y --no-install-recommends ca-certificates curl git \
         && rm -rf /var/lib/apt/lists/*
        RUN set -eu; \
            case "${TARGETARCH:-amd64}" in \
              amd64) platform=linux-x64 ;; \
              arm64) platform=linux-arm64 ;; \
              *) echo "Unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
            esac; \
            asset="github-copilot-${COPILOT_VERSION}-${platform}.tgz"; \
            base="https://github.com/github/copilot-cli/releases/download/v${COPILOT_VERSION}"; \
            mkdir -p /opt/copilot-download /opt/copilot; \
            cd /opt/copilot-download; \
            curl -fsSL -o "${asset}" "${base}/${asset}"; \
            curl -fsSL -o SHA256SUMS.txt "${base}/SHA256SUMS.txt"; \
            awk -v a="${asset}" '$2 == a || $2 == "*" a { print $1 "  " a; n++ } END { exit n != 1 }' SHA256SUMS.txt | sha256sum -c -; \
            tar -xzf "${asset}" -C /opt/copilot --strip-components=1; \
            if [ -d "/opt/copilot/prebuilds/${platform}" ]; then cp -a "/opt/copilot/prebuilds/${platform}/." /opt/copilot/; fi; \
            rm -rf /opt/copilot-download; \
            chmod +x /opt/copilot/copilot-runtime
        RUN useradd --create-home --home-dir /home/copilot --uid 10001 copilot \
         && mkdir -p {{CopilotContainerResource.HomeDirectory}} \
         && chown -R copilot:copilot /home/copilot
        USER copilot
        ENV HOME=/home/copilot
        WORKDIR /home/copilot
        EXPOSE {{CopilotContainerResource.RuntimePort}}
        ENTRYPOINT ["/opt/copilot/copilot-runtime"]
        """;
}
