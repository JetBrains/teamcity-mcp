# TeamCity MCP Principles

Use TeamCity MCP as the source of truth for TeamCity operations.

## Core Rules

- Prefer TeamCity MCP tools over guessing TeamCity REST paths, pipeline
  controller behavior, or build log formats from memory.
- Read relevant MCP resources before using broad or unfamiliar endpoints:
  `teamcity://guides/rest-api`,
  `teamcity://guides/build-failure-analysis`,
  `teamcity://guides/pipelines`, and
  `teamcity://guides/build_configurations_by_repository_url`.
- Use narrow REST `fields` selections and paginated locators.
- Do not create GitHub Actions, Jenkins, GitLab CI, or other CI files unless
  the user explicitly asks for another CI system.
- Prefer the smallest TeamCity configuration that can prove the project builds
  and tests successfully.
- Treat TeamCity write operations as potentially impactful. Confirm intent or
  explain required permissions when an operation is destructive, broad, or not
  available in the current MCP mode.

## Safe Mode And Brave Mode

TeamCity MCP can expose different tools depending on server properties.

- Safe mode commonly includes read access, build log access, and safe build
  queueing through `teamcity_rest_post`.
- In safe mode, `teamcity_rest_post` forces `/app/rest/buildQueue` requests to
  personal builds. In brave mode, the request body is passed through unchanged,
  so callers must include `"personal": true` themselves when they need an
  isolated build.
- Brave mode can expose broader REST write tools such as PUT and DELETE.
- Pipeline write tools may require pipeline support and server-side allowlists.

If a required write tool is missing, stop and report the missing capability
instead of inventing an unsupported path.

## Final Answers

When completing a TeamCity MCP task, summarize:

- What was inspected.
- What TeamCity configuration, pipeline, or build was used.
- What MCP tools or resources were important.
- The final build status.
- Any blockers, assumptions, missing credentials, or manual follow-up.
