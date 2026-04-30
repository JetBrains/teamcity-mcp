# Debug Failed Build

Use this workflow when a TeamCity build or build chain already exists and is
failing.

## Goal

Find the root cause of the failure, apply the smallest safe fix when possible,
and rerun the build to verify.

## Workflow

1. Read `teamcity://guides/build-failure-analysis`.
2. Identify the build:
   - build ID
   - build configuration ID
   - branch
   - whether it is part of a chain
3. Get the build overview with `teamcity_rest_get`.
4. If the build is composite or part of a chain, inspect dependency builds and
   find the first failed upstream build.
5. Get build problems with details and log anchors.
6. Get failed tests with details and log anchors.
7. Read focused log ranges using `teamcity_build_log`.
8. Check recent changes and compare with the last successful build when useful.
9. Decide the smallest likely fix:
   - project code
   - tests
   - build step command
   - TeamCity parameters
   - agent requirements
   - missing credentials or services
10. Apply the fix only when it is within the user's request and available
    permissions.
11. Queue a personal build or rerun validation.

## Safety Rules

- Do not mask failing tests without explicit user approval.
- Do not delete build configurations, pipelines, or history as a debugging
  shortcut.
- Do not expose secrets from logs.
- If the root cause is uncertain, state the evidence and run the next smallest
  verification step.

## Output

Report:

- Root cause or most likely cause.
- Evidence from TeamCity status, problems, tests, and logs.
- Fix applied or proposed.
- Rerun build status.
- Remaining risks or blockers.
