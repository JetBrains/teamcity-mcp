# Use TeamCity MCP

Use this workflow when the user asks how to install, connect, authenticate,
discover, or safely use the TeamCity MCP server.

## Goal

Help the user connect an AI agent to TeamCity MCP and use the available tools
and resources safely.

## Workflow

1. Confirm that the agent has a TeamCity MCP server configured.
2. Verify that authentication is provided through a TeamCity bearer token or the
   agent's configured MCP authentication mechanism.
3. Discover available MCP tools.
4. Discover available MCP resources.
5. Read the resources that match the task:
   - `teamcity://guides/rest-api`
   - `teamcity://guides/build-failure-analysis`
   - `teamcity://guides/pipelines`
   - `teamcity://guides/build_configurations_by_repository_url`
6. Use safe tools first:
   - `teamcity_rest_get` for TeamCity REST reads.
   - `teamcity_rest_post` for safe build queueing.
   - `teamcity_build_log` for logs.
   - `teamcity_pipeline_get` when pipeline support is enabled.
7. Use write tools only when they are available and the user intent requires
   them.

## Important Tool Notes

- `teamcity_rest_get` should use `fields` and paginated locators.
- `teamcity_rest_post` may be limited by server allowlists and enforces
  personal builds for `/app/rest/buildQueue`.
- `teamcity_rest_put` and `teamcity_rest_delete` may only be available in brave
  mode.
- `teamcity_pipeline_post` and `teamcity_pipeline_delete` may require pipeline
  support and server-side allowlists.

## Output

When explaining setup or usage, include:

- Which MCP server endpoint or agent config is expected.
- Which token or permission is required.
- Which tools and resources are relevant.
- Any limitation caused by safe mode, brave mode, pipeline support, or missing
  permissions.
