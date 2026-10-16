FROM debian:bookworm-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl ca-certificates git && \
    rm -rf /var/lib/apt/lists/*

RUN useradd -m -s /bin/bash agent
USER agent
WORKDIR /home/agent

# Fail the build if the installer download fails: without pipefail, /bin/sh -c only
# checks the exit status of the final `bash`, which succeeds on empty input.
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
ARG INSTALL_VERSION=
RUN echo "Installing Claude Code ${INSTALL_VERSION:-stable}" && \
    curl -fsSL https://claude.ai/install.sh | bash -s ${INSTALL_VERSION:-stable}
ENV PATH="/home/agent/.local/bin:${PATH}"

RUN claude --version

# Pre-create a git repo so Claude Code doesn't complain about missing repo
RUN mkdir -p /home/agent/project && \
    cd /home/agent/project && \
    git init && \
    git config user.email "test@test.com" && \
    git config user.name "Test Agent"

CMD ["sleep", "infinity"]
