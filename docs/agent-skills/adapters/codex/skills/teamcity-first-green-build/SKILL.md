---
name: "teamcity-first-green-build"
description: "Use when the user wants Codex to create or set up a TeamCity project or pipeline, then drive the setup to the first successful build. Best fit for TeamCity Pipelines/YAML flows; avoid defaulting to Kotlin DSL migrations."
---

# TeamCity First Green Build For Codex

Use this Codex-specific skill with the canonical workflow:
`../../../../workflows/first-green-build.md`.

This file contains Codex runtime rules only. Keep reusable TeamCity behavior in
the canonical workflow and shared guidance.

## Default Stance

- Prefer TeamCity MCP first for discovery, permissions, schema inspection,
  create/update operations, build queueing, and diagnosis.
- Prefer TeamCity Pipelines or YAML over Kotlin DSL unless the repository
  already uses Kotlin DSL successfully or the user explicitly asks for Kotlin
  DSL.
- Do not use raw TeamCity REST through `exec_command`, `curl`, temporary helper
  scripts, or ad hoc shell wrappers as a fallback.
- Do not ask for sandbox permissions to bypass missing TeamCity MCP coverage.
- Do not claim success after merely creating a project, creating a pipeline, or
  queueing a build. The goal is a green build or a proven blocker.

## Authentication And Target Intake

Before the first TeamCity write request, resolve the target in one compact
exchange when it is not already known:

- TeamCity server URL.
- Authentication path, discovered by Codex: first check for an exact target MCP
  attachment, then a verified client-side MCP OAuth or interactive auth path.
  Ask the user for a TeamCity token only if those are unavailable.
- Target parent project ID. This is required for TeamCity writes; `_Root` is a
  valid ID. If the user gives only a project name, resolve it to an ID and
  confirm the exact ID before writing.
- TeamCity object create/update strategy is discovered by Codex from the server
  after parent project ID is known. Do not ask the user whether to create new or
  reuse existing before checking existing projects, pipelines, build types, VCS
  roots, and repo/YAML matches under the target parent.
- TeamCity object location follows from the required parent project ID and
  server-side discovery. Do not ask for "project location: repo root or
  subdirectory" during initial intake.
- Repository build root is discovered from the local checkout. Ask about a
  repository subdirectory only if local inspection shows multiple plausible
  build roots or the user has already hinted at a nested project.

Discover repository visibility and TeamCity VCS access before asking the user
about them. If the repository appears private or TeamCity cannot access it,
treat VCS authentication as a separate prerequisite from the TeamCity token.

Prefer TeamCity authentication strategies in this order:

1. An already working MCP attachment for the exact target server.
2. A verified MCP-side OAuth or interactive auth flow exposed by the current
   Codex client/runtime for that server.
3. A user-provided TeamCity token.

Treat OAuth support as a property of the current MCP client and runtime, not as
something guaranteed by TeamCity MCP. Do not claim OAuth is available from
memory alone.

## MCP Attachment Gate

There is a critical difference between:

- A server being present in local Codex MCP config.
- Callable MCP tools for that server being attached in the current session.

Before the first MCP request to TeamCity, prove that callable TeamCity MCP
access in this session corresponds to the exact target server.

Run a compact attachment diagnostic before stopping on this gate:

- Check whether the target appears in `codex mcp list`.
- Check whether the current session exposes TeamCity MCP tools through the
  available tool discovery surface.
- Check whether MCP resources/templates for the current session include the
  target TeamCity server.
- Check whether the configured bearer token environment variable is visible to
  the process doing the attachment. In Codex Desktop, the GUI app runtime may
  not inherit variables that are visible in an interactive shell, and a shell
  spawned by Codex may also lack variables that were added after the app
  started.

When the target server is present in config but no callable TeamCity tools or
resources are attached, do not just say "restart Codex." Report the diagnosis in
plain language:

- The configured MCP server name and URL that were found.
- Whether the expected token environment variable was visible or missing.
- That the missing piece is the current session's callable MCP attachment, not
  necessarily the local MCP config.
- The likely next action, such as starting Codex from an environment where the
  token variable is set, reconnecting the MCP server, or restarting the Desktop
  app after setting the token.

Acceptable evidence:

- The current session exposes TeamCity MCP tool calls that clearly correspond
  to the requested server.
- A fresh reconnect or restart occurred after MCP registration and the target
  server is now the one used by callable TeamCity tools.

Not acceptable as proof on its own:

- Seeing the server in `codex mcp list`.
- Seeing the server in config files.
- Seeing the server in `.mcp.json`.
- Seeing generic TeamCity resources when callable tools still point at another
  TeamCity server.

If the target server is configured locally but the current session exposes
TeamCity tools for another server:

- Stop immediately.
- Tell the user that the requested TeamCity MCP server is configured but not
  attached to this session.
- Require reconnect or restart before any TeamCity MCP action.
- Do not substitute another TeamCity server.
- Do not use raw REST, `curl`, helper scripts, or CLI transport to continue.

On macOS or other GUI-launched sessions, also distinguish between a token being
visible in the shell and the token being visible to the Codex app runtime that
attached MCP tools for this chat.

## Server Targeting Rules

- If multiple TeamCity servers are visible, do not pick one implicitly for
  write operations.
- If a local config points to one server but the user names another, the user
  instruction wins.
- After the user names a target server, treat that server as the only source of
  truth for create/update/queue operations.
- If the user changes the server mid-session, discard cached assumptions about
  parent project, pipeline or project ID, repository URL, VCS connection, and
  helper files tied to the previous server.
- Do not read guides, list resources, inspect pipelines, or reuse helper
  scripts from another TeamCity server as if they belonged to the active target.

## Token Handling

Treat the TeamCity token as a secret at every step.

- Prefer MCP configuration or environment variables.
- Do not copy literal tokens into shell commands, helper scripts, approval
  prompts, saved command prefixes, comments, or logs.
- Never print the token back to the user.
- Never store the token as a plain TeamCity project parameter.
- If authentication fails, report that the token may be invalid, expired, or
  insufficiently scoped, but do not echo it for debugging.

## Execution Surface

Allowed TeamCity operations in this skill are:

- MCP tool calls, for example:
  - `teamcity_rest_get`
  - `teamcity_rest_post`
  - `teamcity_pipeline_get`
  - `teamcity_pipeline_post`
  - `teamcity_build_log`
- Official first-class `teamcity` CLI commands only when MCP research has
  already made the target state clear and the CLI is available or can be
  installed safely.

The MCP tools above are still MCP tools even if their names contain `rest`.

Use MCP first to clarify server attachment, permissions, parent project,
repository state, schema, and the intended TeamCity object. After MCP research
has made the target state clear, prefer official first-class `teamcity` CLI
commands for supported execution steps. If the first-class CLI command surface
is not enough for a required supported operation, use the TeamCity MCP tools
rather than raw REST or shell wrappers.

To install the official TeamCity CLI on macOS with Homebrew, use:

```bash
brew install jetbrains/utils/teamcity
```

To install the TeamCity CLI skill, use:

```bash
teamcity skill install
```

Do not use:

- raw REST through shell commands
- `curl`
- temporary helper scripts
- `teamcity api` as the normal execution path
- historical approved command prefixes as permission to bypass MCP

If the official first-class `teamcity` CLI does not expose the required
operation after MCP research has made the target state clear:

1. Check whether TeamCity MCP exposes the operation.
2. If yes, use MCP instead of raw REST.
3. If no, stop and report the missing supported capability.

## Repository And Project Targeting

Treat the local checkout as the primary source of repository truth.

- Prefer the current Git remote over stale values from previous sessions.
- If the local repository URL conflicts with prior TeamCity session notes, stop
  and resolve the conflict before creating anything.
- Require a target parent project ID before TeamCity writes. If only a project
  name is known, resolve and confirm the exact ID first.
- Derive TeamCity project or pipeline placement from the target parent project
  ID and server-side discovery.
- Discover whether to update an existing TeamCity object or create a new one by
  inspecting existing objects and VCS bindings under the parent project. Ask the
  user only if multiple plausible matches exist or the operation would overwrite
  a user-owned object ambiguously.
- Derive the repository build root from the local checkout. If the repository is
  a monorepo or the user hinted at a nested project, ask which repository
  subdirectory TeamCity should build from before generating TeamCity YAML or
  pipeline configuration.
- Check repository visibility and existing TeamCity VCS/provider access before
  asking the user whether the repository is public or private.
- Inspect checked-in TeamCity YAML such as `.teamcity.yml` and preserve its job
  topology unless the user explicitly asks for a reduced first pass.

## MCP Request Bundles

Group MCP calls by phase. Avoid scattered one-off reads when they belong to the
same decision.

- Viability bundle: confirm exact target server attachment, read server info,
  read current user, confirm parent project.
- Target discovery bundle: inspect existing pipelines or build types, VCS or
  provider metadata, and only the guide/schema material needed for the current
  project model.
- Authoring bundle: inspect schema/capabilities, check VCS connection strategy,
  create or update the TeamCity object, and run compatibility checks.
- Run diagnosis bundle: inspect queued/running build, problem occurrences,
  failed tests, focused build logs, and queue/agent state if it changes the
  diagnosis.

Reuse cached MCP results only while the target server, parent project,
repository URL, project root, and relevant server-side objects have not changed.
Invalidate cache after writes, queued builds, target changes, or explicit user
requests to re-check live state.

## Build And Pipeline Rules

- Follow `docs/agent-skills/shared/build-step-selection.md`.
- Prefer dedicated TeamCity runner or pipeline step types for primary
  Gradle, Maven, Node.js, .NET, Docker, and other supported operations.
- Do not model Maven, Gradle, Docker, or other well-supported build actions as
  generic `script` steps by default when the active server exposes a dedicated
  step type.
- Do not collapse a repo-owned multi-job pipeline into a single verifier unless
  the user explicitly asks for a smaller pipeline.
- If Docker validation is present in the repository, include it unless the user
  asks for a minimal non-container pipeline or compatibility checks prove Docker
  is unavailable.

## Failure Patterns

### VCS Authentication

If a build fails with messages like:

- `Unable to collect changes`
- `Anonymous authentication has failed`
- `No user tokens found for connection`

then the likely blocker is VCS authentication, not the build script itself.
Verify whether the repository is private and whether TeamCity has the correct
GitHub App, OAuth, SSH key, or other VCS connection.

#### Remote Run Without Private Repo Access

For private repositories where TeamCity cannot collect changes, try a
TeamCity remote run / patch-based personal build before declaring the setup
blocked. This is a deferred VCS authorization fallback, not a replacement for a
working VCS root: TeamCity personal builds use current VCS repository sources
plus the uploaded local changes, so checkout/change collection may still fail
if the server or agent cannot read the base repository at all.

Prefer the official TeamCity CLI remote run surface when available:

```bash
teamcity run start <BUILD_TYPE_ID> --local-changes --no-push
teamcity run start <BUILD_TYPE_ID> --local-changes changes.patch --no-push
git diff --binary | teamcity run start <BUILD_TYPE_ID> --local-changes - --no-push
```

Use direct patch upload only as this specific documented exception to the
general no-raw-REST rule, and only with tokens supplied through environment
variables or MCP configuration:

```bash
git diff --binary > /tmp/tc-remote-run.patch

CHANGE_ID=$(curl -sS -X POST \
  -H "Authorization: Bearer $TEAMCITY_TOKEN" \
  -H "Content-Type: text/text" \
  --data-binary @/tmp/tc-remote-run.patch \
  "$TEAMCITY_URL/uploadDiffChanges.html?description=IJ%20IDEA%20Remote%20Run&commitType=0")

curl -sS -X POST \
  -H "Authorization: Bearer $TEAMCITY_TOKEN" \
  -H "Accept: application/json" \
  -H "Content-Type: application/json" \
  "$TEAMCITY_URL/app/rest/buildQueue" \
  -d "{\"buildType\":{\"id\":\"$BUILD_TYPE_ID\"},\"lastChanges\":{\"change\":[{\"id\":\"$CHANGE_ID\",\"personal\":true}]}}"
```

Direct patch upload is best for Git-generated unified diffs. If
`git diff --binary` produces a patch TeamCity rejects, retry with a plain
unified diff for text-only changes or report the binary patch as unsupported by
this fallback.

If patch-based remote run fails with the same VCS access error, try to create a
usable authenticated VCS root through the TeamCity CLI before sending the user
to the UI. Discover inherited provider connections first:

```bash
teamcity project connection list --project <PROJECT_ID> --json
teamcity project connection list --project _Root --json
teamcity project vcs create --project <PROJECT_ID> \
  --url <REPOSITORY_URL> \
  --auth token \
  --connection-id <GITHUB_OR_OTHER_PROVIDER_CONNECTION_ID> \
  --branch refs/heads/<DEFAULT_BRANCH> \
  --branch-spec '+:refs/heads/*'
```

For PAT or SSH credentials supplied by the user, prefer the corresponding
first-class CLI forms instead of storing secrets in project parameters:

```bash
teamcity project vcs create --project <PROJECT_ID> \
  --url <REPOSITORY_URL> \
  --auth password \
  --username oauth2 \
  --stdin

teamcity project vcs create --project <PROJECT_ID> \
  --url git@github.com:<OWNER>/<REPO>.git \
  --auth ssh-key \
  --ssh-key-name <UPLOADED_KEY_NAME>
```

If CLI authentication cannot produce a VCS root that passes both
`teamcity project vcs test <VCS_ROOT_ID>` and a real build change-collection
attempt, tell the user to authorize the repository in the TeamCity UI. Provide
the exact project or VCS root URL when known, for example:
`$TEAMCITY_URL/admin/editProject.html?projectId=<PROJECT_ID>` or the
`webUrl` returned for the failing VCS root/build configuration.

### Agent Availability

If TeamCity reports no idle compatible agents or no compatible agents, stop
retrying and report it as an infrastructure blocker.

### Versioned Settings Or Read-Only Objects

If the project or VCS root is read-only because of versioned settings or Kotlin
DSL, do not repeatedly mutate the same server-side object. Inspect the active
configuration model and prefer returning to the stable YAML or pipeline path
unless the user explicitly wants Kotlin DSL recovery.

## Polling And Loop Control

Do not use open-ended sleep loops.

Use bounded polling, for example:

- 5 seconds
- 10 seconds
- 20 seconds
- 30 seconds

Stop early if:

- The build reaches a terminal state.
- The queue or build reason already explains the blocker.
- The same failure repeats twice with no new signal.

Follow the smallest useful object: pipeline head or queued build, then child job
build, then problem occurrences or focused log lines.

## Git Hygiene

- Stage only intended repository files.
- Never use `git add .` in a dirty worktree.
- Do not push speculative fixes to the default branch in a loop.
- Keep repository-owned TeamCity YAML or pipeline files as the source of truth
  when the setup is YAML-driven.

## Reporting Requirements

Report only facts that were actually checked. Do not write placeholder lines
such as "TeamCity object created/updated: none" or "no failed or successful
build yet" before checking the TeamCity server.

After server-side discovery or execution, report:

1. What existing TeamCity object was found, created, updated, or selected.
2. Which TeamCity server and parent project ID were used.
3. Which repository URL and project root were used.
4. Which MCP actions were important.
5. Which exact MCP or CLI limitation blocked progress, if any.
6. The first failed build ID and root cause, only after a server-side build
   lookup or queued build produced that evidence.
7. The first successful build ID, only after a server-side build lookup or
   queued build produced that evidence.
8. Any manual prerequisite the user still needs to complete.

Keep the report short and evidence-based so another engineer can review the
flow.
