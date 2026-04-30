# TeamCity MCP Agent Skills

This directory contains canonical, agent-neutral workflows for using the
TeamCity MCP server. The files are plain Markdown so they can be read directly
from GitHub, copied into user projects, or reused later as MCP resources,
MCP prompts, and agent-specific adapters.

These documents are the source of truth for task behavior. Agent adapters should
stay thin and point back to these workflows instead of maintaining separate
logic per agent.

## Workflows

- [Use TeamCity MCP](workflows/use-teamcity-mcp.md) - install, connect,
  authenticate, discover tools and resources, and use safe defaults.
- [Create Build Configuration](workflows/create-build-configuration.md) -
  create or update a single TeamCity build configuration for a project.
- [Create Pipeline](workflows/create-pipeline.md) - create TeamCity Pipelines
  or build chains for multi-stage CI flows.
- [First Green Build](workflows/first-green-build.md) - inspect a project,
  configure TeamCity, run a build, debug failures, and iterate until green.
- [Debug Failed Build](workflows/debug-failed-build.md) - diagnose and fix an
  existing failed TeamCity build.

## Shared Guidance

- [TeamCity MCP Principles](shared/teamcity-mcp-principles.md)
- [Token Safety](shared/token-safety.md)
- [Project Inspection](shared/project-inspection.md)
- [Build Step Selection](shared/build-step-selection.md)
- [Build Log Debugging](shared/build-log-debugging.md)

## Adapters

The [generic AGENTS.md](adapters/generic/AGENTS.md) adapter is a small,
cross-agent instruction file that can be copied into a user project. Future
adapters can package the same workflows for Claude Code skills, Gemini context
files, GitHub Copilot instructions, Cursor rules, Windsurf rules, and Junie
instructions.

The [Codex adapter](adapters/codex/README.md) contains Codex-specific packaging
and runtime rules. It keeps Codex session mechanics out of the canonical
workflows.

If an agent supports MCP resources or prompts, prefer the workflows exposed by
the connected TeamCity MCP server. If it can read GitHub files, use this
directory directly.
