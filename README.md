# TeamCity MCP Plugin

A [TeamCity](https://www.jetbrains.com/teamcity/) plugin that turns a TeamCity
server into a [Model Context Protocol](https://modelcontextprotocol.io) (MCP)
server, so AI agents — Claude, Junie, Gemini, OpenAI Codex, and any other
MCP-compatible client — can read build state, inspect logs, run REST API calls,
and manage pipelines on your behalf.

The plugin exposes a `streamable-http` MCP endpoint at `/app/mcp` on the
TeamCity server.

## MCP capabilities

The plugin exposes the following capabilities to connected AI agents over MCP:

- **REST API access** — `teamcity_rest_get`, `teamcity_rest_post`,
  `teamcity_rest_put`, `teamcity_rest_delete` tools wrap the full
  [TeamCity REST API](https://www.jetbrains.com/help/teamcity/rest/teamcity-rest-api-documentation.html).
- **Build logs** — `teamcity_build_log` tool retrieves and paginates build logs.
- **Pipeline management** — read, create, and delete TeamCity pipelines.
- **Operation modes**:
  - **Safe (default)** — read-only and curated, safe-by-default write paths.
  - **Brave** — full read/write access, including DELETE and arbitrary POST.
- **Per-tool allowlists** — restrict which REST and pipeline paths the AI
  agent can hit.
- **Auth-aware** — supports TeamCity bearer tokens and 2FA-equipped accounts.

## Requirements

- TeamCity server 2026.1 or later.
- JDK 17+ on the TeamCity server (the plugin runs in TeamCity's JVM).
- An MCP-compatible client (Claude Code, Junie, the official MCP CLI, etc.).

## Installation

1. Download the latest `mcp.zip` from
   [Releases](https://github.com/JetBrains/teamcity-mcp/releases) (or build it
   yourself — see [Building from source](#building-from-source)).
2. In TeamCity, go to **Administration → Plugins → Upload plugin zip** and
   upload `mcp.zip`.
3. Enable the plugin and restart the TeamCity server (the plugin descriptor
   declares `allow-runtime-reload="true"`, but a restart is recommended on
   first install).
4. Verify by visiting `https://<your-teamcity>/app/mcp` — you should see an
   MCP transport response.

## Configuration

Configuration is done via TeamCity internal properties
(`<DATA_DIR>/config/internal.properties`):

| Property | Default | Description |
| --- | --- | --- |
| `teamcity.ai.mcp.enabled` | `true` | Master switch for the MCP server. |
| `teamcity.ai.mcp.braveMode.enabled` | `false` | Enable destructive operations (PUT, DELETE, arbitrary POST). |
| `teamcity.ai.mcp.pipeline.enabled` | `false` | Enable pipeline tools. |
| `teamcity.ai.mcp.tools.enabled` | *(see source)* | Comma-separated allowlist of tool names. Empty value disables all tools. |
| `teamcity.ai.mcp.resources.enabled` | *(see source)* | Comma-separated allowlist of resource names. |
| `teamcity.ai.mcp.rest.post.allowed.paths` | unset | Comma-separated REST API path prefixes the POST tool may call. |
| `teamcity.ai.mcp.pipeline.post.allowed.paths` | unset | Comma-separated path prefixes for the pipeline POST tool. |

## Connecting an AI agent

The MCP endpoint is `https://<your-teamcity>/app/mcp` and requires a TeamCity
bearer token in the `Authorization: Bearer <token>` header. Generate a token
under **Profile → Access Tokens** in TeamCity.

### Claude Code

```bash
claude mcp add --transport http teamcity \
  https://teamcity.example.com/app/mcp \
  --header "Authorization: Bearer <your-token>"
```

### Junie

Junie autodetects MCP servers from your TeamCity profile when the plugin is
installed. Otherwise, add the endpoint manually in Junie's MCP settings.

### Other MCP clients

The endpoint speaks the standard MCP `streamable-http` transport, so any
compliant client works. Point it at `/app/mcp` and supply the bearer token.

## Building from source

> **Note:** The build pulls TeamCity SDK artifacts from a JetBrains Maven
> repository that currently requires credentials. See
> [CONTRIBUTING.md](CONTRIBUTING.md#building-the-plugin) for setup details if
> you hit auth errors.

```bash
./gradlew serverPlugin
```

The plugin zip lands in `build/distributions/mcp.zip`.

To run the full integration + e2e suite against a fresh TeamCity instance:

```bash
TC_DIST=~/Downloads/TeamCity-2026.1.tar.gz ./run_integration_tests.sh
```

## Contributing

We welcome contributions — bug reports, feature requests, questions, and pull
requests are all tracked on the
[GitHub issue tracker](https://github.com/JetBrains/teamcity-mcp/issues).
See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and the PR
process, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community standards.

Security issues: please **do not** open a public issue — see
[SECURITY.md](SECURITY.md).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
