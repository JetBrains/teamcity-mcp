FROM debian:bookworm-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl ca-certificates gnupg git && \
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | \
        gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" \
        > /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && apt-get install -y nodejs && \
    apt-get purge -y --auto-remove gnupg && \
    rm -rf /var/lib/apt/lists/*

RUN npm install -g @openai/codex@0.104.0

RUN useradd -m -s /bin/bash agent
USER agent
WORKDIR /home/agent

# Pre-create a git repo so Codex doesn't complain (even with --skip-git-repo-check)
RUN mkdir -p /home/agent/project && \
    cd /home/agent/project && \
    git init && \
    git config user.email "test@test.com" && \
    git config user.name "Test Agent"

CMD ["sleep", "infinity"]
