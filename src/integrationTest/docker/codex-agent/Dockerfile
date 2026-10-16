FROM debian:bookworm-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl ca-certificates git && \
    rm -rf /var/lib/apt/lists/*

RUN useradd -m -s /bin/bash agent
USER agent
WORKDIR /home/agent

ARG INSTALL_VERSION=
RUN echo "Installing Codex ${INSTALL_VERSION:-latest}" && \
    curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_RELEASE="${INSTALL_VERSION}" bash
ENV PATH="/home/agent/.local/bin:${PATH}"

# Pre-create a git repo so Codex doesn't complain (even with --skip-git-repo-check)
RUN mkdir -p /home/agent/project && \
    cd /home/agent/project && \
    git init && \
    git config user.email "test@test.com" && \
    git config user.name "Test Agent"

CMD ["sleep", "infinity"]
