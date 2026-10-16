FROM debian:bookworm-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl ca-certificates git unzip && \
    rm -rf /var/lib/apt/lists/*

# Create non-root user before installing Junie — the installer puts the shim into
# ~/.local/bin/junie and the binary into ~/.local/share/junie
RUN useradd -m -s /bin/bash agent
USER agent
WORKDIR /home/agent

# Install Junie via the official installer. The npm package lags behind the release
# channel, so it is not used. junie-agent.sh sets INSTALL_VERSION to the newest released
# version: the installer installs exactly that version, and Docker's layer cache is
# invalidated whenever a new Junie release is published. Empty means "latest at build time".
# (Don't name the ARG JUNIE_VERSION — the shim reads that variable too, so it is set only
# for the installer.)
ARG INSTALL_VERSION=
RUN echo "Installing Junie ${INSTALL_VERSION:-latest}" && \
    curl -fsSL https://junie.jetbrains.com/install.sh | JUNIE_VERSION="${INSTALL_VERSION}" bash
ENV PATH="/home/agent/.local/bin:${PATH}"

RUN junie --version

# Pre-create a git repo so Junie doesn't complain
RUN mkdir -p /home/agent/project && \
    cd /home/agent/project && \
    git init && \
    git config user.email "test@test.com" && \
    git config user.name "Test Agent"

CMD ["sleep", "infinity"]
