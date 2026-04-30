# Project Inspection

Before creating or changing TeamCity CI, inspect the project so the first
configuration is small, idiomatic, and likely to build.

## Inspect Locally

Identify:

- Repository root and primary modules.
- Languages and frameworks.
- Build files such as `build.gradle`, `build.gradle.kts`, `pom.xml`,
  `package.json`, `pnpm-lock.yaml`, `pyproject.toml`, `go.mod`, `Cargo.toml`,
  `.sln`, `Dockerfile`, and `docker-compose.yml`.
- Existing CI files.
- Test commands and artifact outputs.
- Required services, environment variables, caches, and credentials.

## Inspect TeamCity

Use TeamCity MCP to check:

- Existing projects and build configurations.
- Build configurations already connected to the same repository.
- Existing VCS roots and branch specifications.
- Recent builds, failures, and successful baseline builds.
- Available pipeline support and write tools.

## Avoid Duplicate CI

Before creating a new TeamCity configuration, search for existing build
configurations related to the same repository. Prefer updating or reusing an
existing configuration when it matches the user's intent.
