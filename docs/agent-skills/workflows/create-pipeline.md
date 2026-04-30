# Create Pipeline

Use this workflow when the user wants a TeamCity Pipeline, build chain, or
multi-stage CI flow.

## Goal

Create a maintainable TeamCity pipeline or build chain that represents the
project's build, test, package, and optional deploy stages.

## Workflow

1. Inspect the project locally using
   `../shared/project-inspection.md`.
2. Read `teamcity://guides/pipelines`.
3. Check whether pipeline tools are available:
   - `teamcity_pipeline_get`
   - `teamcity_pipeline_post`
   - `teamcity_pipeline_delete` when destructive changes are explicitly needed
4. Inspect existing TeamCity pipelines or build chains related to the project.
5. Design the smallest useful stage graph:
   - build
   - test
   - package
   - deploy only when requested or clearly existing
6. Define parameters, secrets, agent requirements, artifacts, snapshot
   dependencies, and artifact dependencies.
7. Use pipeline helper endpoints for validation where available:
   - repository test connection
   - versioned settings check
   - parameter resolution
   - job descriptions
   - agent compatibility
8. Create or update the pipeline through TeamCity MCP if write tools and
   permissions are available.
9. Run the pipeline or its first validation build.
10. If it fails, switch to `debug-failed-build`.

## Safety Rules

- Do not delete pipelines unless the user explicitly asks.
- Do not invent deploy stages or production credentials.
- Prefer a small green pipeline before adding optimization or release stages.
- If pipeline write tools are missing, provide a proposed pipeline design and
  the exact MCP capability or permission that is blocked.

## Output

Report:

- Pipeline or build chain name and identifier.
- Stages, dependencies, and commands.
- Required parameters, secrets, and agent requirements.
- Validation build status.
- Remaining blockers or manual steps.
