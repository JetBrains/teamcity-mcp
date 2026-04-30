# Codex Adapter

This adapter packages the canonical TeamCity MCP workflows for Codex.

Use the canonical workflows in `../../workflows/` for task behavior. The files
under this directory should contain only Codex-specific packaging and runtime
guardrails, such as MCP attachment checks, Codex session behavior, shell
restrictions, and commit hygiene.

## Skills

- [teamcity-first-green-build](skills/teamcity-first-green-build/SKILL.md)
