# Token Safety

TeamCity MCP actions use the permissions of the token configured for the MCP
client. Handle that token as production infrastructure access.

## Rules

- Never print full tokens, secrets, passwords, private keys, or authorization
  headers.
- Prefer least-privilege TeamCity tokens scoped to the task.
- Use personal builds for validation when possible.
- Do not switch from a restricted token to an admin token unless the user
  explicitly asks and understands the impact.
- Avoid storing tokens in repository files.
- If a tool returns a response containing secrets, redact them before showing
  output to the user.

## When Credentials Are Missing

If credentials or permissions are missing:

1. State the exact operation that is blocked.
2. Name the TeamCity permission or token scope that appears required, if known.
3. Provide the next safe action the user can take.
4. Do not work around permission failures by using unrelated CI systems.
