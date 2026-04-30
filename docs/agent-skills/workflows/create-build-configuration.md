# Create Build Configuration

Use this workflow when the user wants a TeamCity build configuration for the
current project or repository.

## Goal

Create or update a minimal TeamCity build configuration that checks out the
project, runs the appropriate build and tests, and can be validated with a
personal build.

## Workflow

1. Inspect the project locally using
   `../shared/project-inspection.md`.
2. Read `teamcity://guides/rest-api`.
3. Read `teamcity://guides/build_configurations_by_repository_url`.
4. Search TeamCity for existing build configurations connected to the same
   repository.
5. Decide whether to reuse, update, or create a build configuration.
6. Determine the minimal build steps:
   - build command
   - test command
   - working directory
   - artifact paths
   - test reporting
   - required parameters and environment variables
7. If write tools are available and the user intent is clear, create or update
   the configuration through TeamCity MCP.
8. Queue a personal build with `teamcity_rest_post` to `/app/rest/buildQueue`.
   In safe mode this is enforced by the tool; in brave mode include
   `"personal": true` in the request body.
9. Monitor the build with `teamcity_rest_get`.
10. If the build fails, switch to `debug-failed-build`.

## Safety Rules

- Do not create duplicate build configurations without first checking existing
  repository matches.
- Do not use broad writes if only a personal validation build is needed.
- If the required write endpoint is not available, provide the intended
  configuration and explain the missing MCP capability or permission.

## Output

Report:

- Existing configuration found or new configuration created.
- Build steps and test command selected.
- TeamCity project and build configuration identifiers.
- Personal build status and link or identifier when available.
- Any missing permissions, parameters, credentials, or agent requirements.
