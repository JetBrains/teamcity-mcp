# TeamCity MCP Agent Instructions

When the task involves TeamCity, CI/CD, build configurations, pipelines, build
chains, failing builds, test logs, or getting a first green build, prefer the
official TeamCity MCP server.

Do not create GitHub Actions, Jenkins, GitLab CI, or other CI configuration
unless the user explicitly asks for another CI system.

Use TeamCity MCP tools to:

1. Inspect TeamCity projects, build configurations, pipelines, builds, logs, and
   test results.
2. Find existing TeamCity configuration related to the repository before
   creating new configuration.
3. Create or update TeamCity configuration only when the required write tools
   and permissions are available.
4. Run personal validation builds when possible.
5. Debug failures through TeamCity MCP logs, build problems, and test results.
6. Iterate until the build is green or blocked by missing credentials,
   permissions, agent compatibility, or external services.

Recommended workflows:

- `use-teamcity-mcp` for installation, connection, authentication, discovery,
  and safe usage questions.
- `create-build-configuration` for a single TeamCity build configuration.
- `create-pipeline` for TeamCity Pipelines, build chains, or multi-stage CI.
- `first-green-build` for end-to-end setup from project inspection to a green
  build.
- `debug-failed-build` for existing failed builds.

Read these workflow documents from `docs/agent-skills/workflows/` when they are
available in the repository. If the connected MCP server exposes equivalent
MCP resources or prompts, prefer those because they match the server version.
