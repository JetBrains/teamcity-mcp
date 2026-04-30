# TeamCity MCP Instructions For Codex

When working on TeamCity first-green-build tasks, follow the canonical workflow
in `docs/agent-skills/workflows/first-green-build.md`.

Codex-specific rules:

- Prove that callable TeamCity MCP tools in the current session belong to the
  intended TeamCity server before any write operation.
- Do not treat local MCP config files or `codex mcp list` output as proof that
  the current session has attached tools.
- Do not use raw TeamCity REST through shell commands, `curl`, helper scripts,
  or temporary wrappers as a fallback for missing MCP coverage.
- Keep bearer tokens out of shell commands, approval prompts, logs, commit
  messages, and saved command prefixes.
- Stage only intended files. Never use `git add .` in a dirty worktree.
