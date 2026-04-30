# Build Log Debugging

Use TeamCity MCP logs and REST data to move from symptoms to the smallest
useful fix.

## Debugging Order

1. Get the build overview: status, status text, branch, build type, agent, and
   whether the build failed to start.
2. Check build problems with details and log anchors.
3. Check failed tests with details and log anchors.
4. Read the log using `teamcity_build_log` with `filter=errors`.
5. Use log anchors to read a small window before and after the failure.
6. Check recent changes and compare with the last successful build when
   available.
7. Decide whether the fix belongs in project code, build configuration,
   pipeline definition, parameters, credentials, or agent requirements.

## Log Reading Rules

- Start narrow. Use `filter=errors` before reading large log ranges.
- Use pagination. Do not request huge logs in one call.
- If a log anchor is available, start slightly before it to capture context.
- Distinguish test failures from build step failures, failed-to-start builds,
  canceled builds, and dependency-chain failures.
- Do not hide uncertainty. If the logs do not identify a single cause, state
  the likely causes and the next verification step.
