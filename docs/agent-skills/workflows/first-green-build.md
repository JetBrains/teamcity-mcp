# First Green Build

Use this workflow when the user wants to set up TeamCity CI for a project and
iterate until the first successful build.

## Goal

Get one successful TeamCity build for the project with the smallest reasonable
configuration.

## Workflow

1. Inspect the project locally using
   `../shared/project-inspection.md`.
2. Confirm the target TeamCity server, parent project, repository URL, and
   project root before the first TeamCity write operation.
3. Discover TeamCity MCP tools and resources.
4. Read the relevant resources:
   - `teamcity://guides/rest-api`
   - `teamcity://guides/build_configurations_by_repository_url`
   - `teamcity://guides/pipelines` when pipeline support is needed
   - `teamcity://guides/build-failure-analysis` when a build fails
5. Search for existing TeamCity configurations connected to the repository.
6. Choose the smallest path:
   - reuse an existing configuration
   - update an existing configuration
   - create a new build configuration
   - create a pipeline only when the project needs multiple stages
7. Choose build steps using `../shared/build-step-selection.md`.
8. Queue a personal build when possible.
9. Monitor the build status.
10. If the build fails:
   - get build problems
   - get failed tests
   - read focused log ranges with `teamcity_build_log`
   - apply the smallest safe fix to project files or TeamCity configuration
   - rerun a personal build
11. Repeat until the build is green or blocked by missing credentials,
   permissions, incompatible agents, unavailable services, or unclear user
   intent.

## Decision Rules

- Prefer existing TeamCity configuration over creating duplicates.
- Prefer a single build configuration before a pipeline unless the project has
  clear multi-stage needs.
- Prefer TeamCity Pipelines or YAML when the repository already carries that
  intent. Do not migrate to Kotlin DSL unless the repository already uses it or
  the user explicitly asks.
- Prefer personal builds for validation.
- Prefer minimal fixes over broad refactors.
- Do not switch to another CI system unless the user explicitly asks.

## Stop Conditions

Stop when:

- A TeamCity build is successful.
- Required credentials or permissions are missing.
- The agent environment cannot access required services.
- A destructive action would be required and the user has not explicitly
  approved it.

## Output

Report:

- The final TeamCity project, build configuration, pipeline, or build ID.
- The TeamCity server, parent project, repository URL, and project root used.
- The commands and build steps used.
- The final build status.
- Failures investigated and fixes applied.
- Any blockers, assumptions, or manual follow-up.
