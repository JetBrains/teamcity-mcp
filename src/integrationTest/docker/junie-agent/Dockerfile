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

# Install the latest Junie release via the official installer. The npm package lags
# behind the release channel, so it is not used. JUNIE_LATEST is only a cache key:
# junie-agent.sh sets it to the newest released version, so Docker's layer cache is
# invalidated whenever a new Junie release is published.
# (Don't name it JUNIE_VERSION — both the installer and the shim read that variable.)
ARG JUNIE_LATEST=unknown
RUN echo "Installing Junie (latest: ${JUNIE_LATEST})" && \
    curl -fsSL https://junie.jetbrains.com/install.sh | bash
ENV PATH="/home/agent/.local/bin:${PATH}"

RUN junie --version

# Pre-create a git repo so Junie doesn't complain
RUN mkdir -p /home/agent/project && \
    cd /home/agent/project && \
    git init && \
    git config user.email "test@test.com" && \
    git config user.name "Test Agent"

CMD ["sleep", "infinity"]
