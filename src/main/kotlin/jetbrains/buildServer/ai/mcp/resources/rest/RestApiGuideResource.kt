package jetbrains.buildServer.ai.mcp.resources.rest

import jetbrains.buildServer.ai.mcp.BUILD_QUEUE_PATH
import jetbrains.buildServer.ai.mcp.resources.McpResource
import org.springframework.stereotype.Component

@Component
class RestApiGuideResource : McpResource {

    companion object {
        const val SETTINGS_NAME = "rest_api_guide"

        private val CONTENT = """
# TeamCity REST API Guide

This guide teaches you how to use the `teamcity_rest_get`, `teamcity_rest_post`, and `teamcity_build_log` tools to query and interact with TeamCity.

---

# Part 1: Reading Data (`teamcity_rest_get`)

## Tool Parameters

| Parameter | Required | Description |
|-----------|----------|-------------|
| `path` | Yes | REST endpoint, must start with `/app/rest/` |
| `query` | No | Query string without leading `?` |

## Automatic Behaviors

- **Pagination enforced**: If you omit pagination, `start:0,count:10` is added to the locator. Max `count` is 100. Always specify `start` and `count` inside the locator — top-level query parameters are deprecated.
- **Response format**: The tool always returns a structured JSON envelope with `meta`, `contentType`, and payload fields (`body` or `bodyText`).
- **Response notes**: Hints about pagination, truncation, and missing fields are in `meta.notes` (array of strings).
- **Truncation**: Very large responses are truncated. If you see a truncation warning, use narrower `fields` or smaller `count`.
- **Don't URL-encode**: Pass parameters as plain text — the server handles encoding.

**When in doubt, use `${'$'}help`**: `path=/app/rest/builds/${'$'}help` is the best self-service reference for locator dimensions and endpoint-specific syntax.

## Response Format

The tool output is a structured JSON envelope:

```json
{"meta":{"url":"/app/rest/builds?locator=start:0,count:10&fields=build(id,status)","statusCode":200,"truncated":false,"hasNextHref":true,"notes":["More results available. Use the 'nextHref' value from the response to fetch the next page."]},"contentType":"application/json","body":{"count":2,"nextHref":"/app/rest/builds?locator=start:10,count:10","build":[{"id":123,"status":"SUCCESS"},{"id":124,"status":"FAILURE"}]}}
```

- Use `meta.notes` for warnings/guidance.
- If `contentType=application/json`, parse payload from `body`.
- If `contentType=text/plain` (e.g. `/builds/aggregated/.../status`), read payload from `bodyText`.

## Fields Parameter

For JSON endpoints, **always specify `fields`** to control response size. Without it, responses can be huge.  
Some endpoints return plain text (e.g. `/builds/aggregated/.../status`), where `fields` is not applicable.

**List endpoints** — wrap in entity name: `fields=build(id,number,status)`
**Single-entity endpoints** — use bare names: `fields=id,number,status`

```
fields=build(id,number,status)                 # list of builds
fields=id,number,status,buildType(id,name)     # single build
fields=project(id,name,parentProjectId)        # list of projects
fields=count                                   # total count only
fields=build(id,buildType(id,name),agent(name)) # nested fields
```

**Tip**: Use `fields=count` first to check how many results exist before fetching full data.

## Locators

Locators filter results server-side using `dimension:value` syntax, comma-separated.

```
locator=status:SUCCESS,count:10
locator=buildType:(id:MyBuild),status:FAILURE
locator=project:(id:MyProject),state:finished
```

Use parentheses for nested locators:
```
locator=buildType:(id:MyBuild),branch:default:any
locator=build:(id:12345)
locator=affectedProject:(id:MyProject)
```

### Key Build Locator Dimensions

| Dimension | Values/Format | Description |
|-----------|--------------|-------------|
| `buildType` | `(id:BT_ID)` | Build configuration |
| `status` | `SUCCESS`, `FAILURE`, `UNKNOWN` | Build result |
| `state` | `finished`, `running`, `queued`, `any` | Build state |
| `project` | `(id:PROJ_ID)` | Direct parent project |
| `affectedProject` | `(id:PROJ_ID)` | Project including all subprojects |
| `branch` | `default:any` or `name:main` | Branch filter. Use `default:any` for all branches |
| `defaultFilter` | `true`/`false` | Exclude personal/canceled builds (default: true) |
| `tag` | tag name | Filter by tag |
| `agent` | `(name:AgentName)` | Agent that ran the build |
| `sinceDate` | `yyyyMMddTHHmmss+0000` | Builds after this date |
| `lookupLimit` | integer | Max items to scan (default 5000). Increase if searching far back in history |

Use `${'$'}help` to discover supported locator dimensions: `path=/app/rest/builds/${'$'}help`

### Date Format

Dates are formatted as `yyyyMMddTHHmmss+0000` where `+0000` is the UTC offset (e.g., `+0000` for UTC, `-0500` for EST).

Date-based filtering examples:
```
locator=buildType:(id:MyBuild),sinceDate:20260201T000000+0000
locator=buildType:(id:MyBuild),sinceDate:20260201T000000+0000,untilDate:20260215T235959+0000
```

## Pagination

Default: 10 items from offset 0. Maximum page size: 100. Always specify `start` and `count` inside the locator — do NOT use them as top-level query parameters (deprecated).

**Strategy:**
1. Check total: `fields=count` with your locator
2. Fetch page: add `count:100` to your locator (e.g., `locator=buildType:(id:BT1),count:100`)
3. If response contains `nextHref`, extract path and query for the next call

**Parsing `nextHref`**: The value is a relative URL like `/app/rest/builds?locator=...,start:10,count:10&fields=...`. Split on `?` to get the `path` and `query` for your next tool call.

**If a query returns 0 results unexpectedly**, the server may have stopped scanning. Try increasing `lookupLimit` (e.g., `lookupLimit:50000`).

## Common Task Patterns

### "Why did build 48231 fail?"

Quick overview → problems → tests → log. For a comprehensive methodology (build chains, failed-to-start, root cause analysis), see the **Build Failure Analysis Guide**.

```
# 1. Build overview — statusText often tells you what failed
path: /app/rest/builds/id:48231
query: fields=id,number,status,statusText

# 2. Build problems — always include details and logAnchor (excluded by default)
path: /app/rest/builds/id:48231/problemOccurrences
query: fields=problemOccurrence(id,type,details,logAnchor)

# 3. Log around the problem — start before logAnchor to see the lead-up
tool: teamcity_build_log
buildId: 48231, start: 310, count: 60

# 4. Test failures — include details, newFailure, logAnchor (excluded by default)
path: /app/rest/builds/id:48231/testOccurrences
query: locator=status:FAILURE&fields=testOccurrence(name,status,details,newFailure,logAnchor)

# 5. Log around a test — start before logAnchor for setup context
tool: teamcity_build_log
buildId: 48231, start: 490, count: 70
```

**Key**: `details`, `logAnchor`, and `newFailure` are **not returned by default** — always include them in `fields`. `newFailure: true` means the test was passing before — focus on these first.

### Finding entities by name

Project and build configuration IDs are human-readable strings (e.g., `MyProject`, `MyProject_Build`), not opaque numbers. You can often guess them from names, but to search:

```
# Find project by name
path: /app/rest/projects
query: locator=name:Frontend&fields=project(id,name)

# Find build config by name
path: /app/rest/buildTypes
query: locator=name:Deploy&fields=buildType(id,name,projectId)

# List configs in a project
path: /app/rest/buildTypes
query: locator=project:(id:MyProject)&fields=buildType(id,name)
```

### "What's the status of project X?"
1. List its build configs: `path=/app/rest/projects/id:PROJECT_ID` `query=fields=id,name,buildTypes(buildType(id,name))`
2. Get latest build per config: `path=/app/rest/builds` `query=locator=buildType:(id:BT_ID),defaultFilter:true,count:1&fields=build(id,number,status,finishOnAgentDate)`

### "Find failing tests"
```
path: /app/rest/testOccurrences
query: locator=currentlyFailing:true,affectedProject:(id:PROJECT_ID)&fields=testOccurrence(name,details,logAnchor,build(buildType(id)))
```

### "How long do builds take?"
```
path: /app/rest/builds
query: locator=buildType:(id:BT_ID),status:SUCCESS,defaultFilter:true,count:20&fields=build(id,number,startDate,finishOnAgentDate)
```

### "What changed in this build?"
```
path: /app/rest/changes
query: locator=build:(id:BUILD_ID)&fields=change(id,username,comment,date,files(file(file,changeType)))
```

### "Who triggered this build?"
```
path: /app/rest/builds/id:BUILD_ID
query: fields=id,triggered(type,user(username))
```
Trigger types: `user` (manual), `vcs` (VCS change), `schedule` (cron), `snapshotDependency` (build chain), `buildType` (dependency finish).

### "Get last successful build"
```
path: /app/rest/builds
query: locator=buildType:(id:BT_ID),status:SUCCESS,defaultFilter:true,count:1&fields=build(id,number,finishOnAgentDate)
```

## Key Endpoints Reference

### Server
```
path: /app/rest/server
query: fields=version,buildNumber,startTime
```

### Projects
```
path: /app/rest/projects
query: fields=project(id,name,parentProjectId)

path: /app/rest/projects/id:PROJECT_ID
query: fields=id,name,description,buildTypes(buildType(id,name))
```

### Build Configurations
```
path: /app/rest/buildTypes
query: locator=project:(id:PROJECT_ID)&fields=buildType(id,name,projectId)

path: /app/rest/buildTypes/id:BT_ID
query: fields=id,name,project(id,name),parameters(property(name,value))
```

### Builds
```
# Recent builds
path: /app/rest/builds
query: locator=buildType:(id:BT_ID),defaultFilter:true,count:10&fields=build(id,number,status,statusText,finishOnAgentDate)

# Specific build
path: /app/rest/builds/id:BUILD_ID
query: fields=id,number,status,statusText,state,buildType(id,name),agent(name),startDate,finishOnAgentDate,triggered(type,user(username))

# Failed builds
path: /app/rest/builds
query: locator=buildType:(id:BT_ID),status:FAILURE,defaultFilter:true,count:10&fields=build(id,number,statusText,finishOnAgentDate)

# Running builds
path: /app/rest/builds
query: locator=state:running&fields=build(id,number,buildType(id,name),agent(name),percentageComplete)

# Builds across all branches
path: /app/rest/builds
query: locator=buildType:(id:BT_ID),branch:default:any,count:20&fields=build(id,number,status,branchName)

# Failed builds in a project (includes subprojects)
path: /app/rest/builds
query: locator=affectedProject:(id:PROJECT_ID),status:FAILURE,defaultFilter:true,count:10&fields=build(id,number,buildType(id,name),statusText)
```

### Build Queue
```
path: $BUILD_QUEUE_PATH
query: fields=build(id,buildType(id,name),branchName,waitReason)
```

### Tests
```
# Test failures in a build — include details and log anchor
path: /app/rest/builds/id:BUILD_ID/testOccurrences
query: locator=status:FAILURE&fields=testOccurrence(name,status,details,newFailure,logAnchor)

# Test failures — short form (without stacktraces)
path: /app/rest/builds/id:BUILD_ID/testOccurrences
query: locator=status:FAILURE&fields=testOccurrence(name,status,duration)

# Test history
path: /app/rest/testOccurrences
query: locator=test:(name:com.example.MyTest),count:20&fields=testOccurrence(name,status,duration,build(id,number))
```

Key `testOccurrence` fields (must be explicitly requested):
- `details` — full test output: stacktrace, stdout, stderr
- `newFailure` — `true` if the test was passing in the previous build (investigate these first)
- `logAnchor` — build log index; use as `start` in `teamcity_build_log` to see log around the test
- `firstFailed` — the build where this test first started failing
- `nextFixed` — the build where this test was fixed

### Build Problems
```
# Build problems with details and log anchors
path: /app/rest/builds/id:BUILD_ID/problemOccurrences
query: fields=problemOccurrence(id,type,details,logAnchor)
```

Key `problemOccurrence` fields (must be explicitly requested):
- `details` — description of the problem
- `logAnchor` — build log index; use as `start` in `teamcity_build_log` to jump to the problem location
- `newFailure` — `true` if this is a new problem

Common problem types: `TC_EXIT_CODE` (non-zero exit), `TC_FAILED_TESTS` (test failures), `TC_COMPILATION_ERROR`, `TC_OOME` (out of memory).

### Changes
```
path: /app/rest/changes
query: locator=build:(id:BUILD_ID)&fields=change(id,version,username,date,comment,files(file(file,changeType)))

# Pending changes
path: /app/rest/changes
query: locator=buildType:(id:BT_ID),pending:true&fields=change(id,version,username,comment)
```

### Agents
```
path: /app/rest/agents
query: locator=connected:true,authorized:true&fields=agent(id,name,connected,enabled,pool(name))
```

### Build Statistics
```
path: /app/rest/builds/id:BUILD_ID/statistics
query: fields=property(name,value)
```
Common stat names: `BuildDuration`, `BuildDurationNetTime`, `TimeSpentInQueue`, `ArtifactsSize`.

### Build Artifacts
```
# List artifact files
path: /app/rest/builds/id:BUILD_ID/artifacts/children/
query: fields=file(name,size,modificationTime,href)
```

### Investigations
```
path: /app/rest/investigations
query: locator=buildType:(id:BT_ID)&fields=investigation(id,state,assignee(username),resolution(type))
```

### Other Endpoints
```
# VCS roots
path: /app/rest/vcs-roots
query: fields=vcs-root(id,name,vcsName,project(id))

# Agent pools
path: /app/rest/agentPools
query: fields=agentPool(id,name)

# Users
path: /app/rest/users
query: fields=user(id,username,name,email)

# Current user
path: /app/rest/users/current
query: fields=id,username,name,roles(role(roleId,scope))
```

## Error Recovery

| Status | Meaning | Action |
|--------|---------|--------|
| 400 | Malformed query | Check parentheses balance in locator, verify field names. Use `${'$'}help` to check syntax. |
| 403 | Access denied | Your token lacks permission for this endpoint. Try a different approach. |
| 404 | Not found | Entity doesn't exist. Verify the ID using a list endpoint first. |
| 405 | Method not allowed | This endpoint doesn't support GET (rare). |
| 5xx | Server error | Simplify the query and retry. |

**Common mistakes:**
- Missing parentheses: `buildType:BT1` should be `buildType:(id:BT1)`
- Wrong nesting: `fields=build(id,buildType(id))` is correct, `fields=build(id),buildType(id)` is wrong
- Forgetting `id:` prefix: `project:MyProject` should be `project:(id:MyProject)`

## Discovery

Use `${'$'}help` on endpoints to inspect supported locator dimensions and sub-resources:
```
path: /app/rest/builds/${'$'}help
path: /app/rest/projects/${'$'}help
path: /app/rest/testOccurrences/${'$'}help
```
Returns plain text listing dimensions and descriptions. For field/schema discovery, use `path=/app/rest/swagger.json` and validate with targeted `fields=...` requests.

API schema: `path=/app/rest/swagger.json`

## Tips

- **Start with `fields=count`** to gauge result size before fetching data.
- **Use `defaultFilter:true`** to exclude personal and canceled builds.
- **Use `branch:default:any`** to search across all branches.
- **Use `affectedProject` instead of `project`** to include subprojects.
- **Build IDs are globally unique**; build numbers are only unique within a configuration.
- **Use `state:any`** if you want queued, running, and finished builds together.
- **Follow `nextHref`** for pagination — don't construct page URLs manually.

---

# Part 2: Triggering Actions (`teamcity_rest_post`)

## Important Constraint: Personal Builds Only

All builds triggered through this tool are **personal builds**. The tool automatically enforces `"personal": true` in every request body. You cannot override this — any `"personal": false` in your body will be replaced with `true`.

Personal builds:
- Run in isolation and do not affect the main build chain
- Are visible only to the triggering user by default
- Are safe for experimentation and testing

## Tool Parameters

| Parameter | Required | Description |
|-----------|----------|-------------|
| `path` | Yes | REST endpoint, must start with `/app/rest/` and be in the allowed list |
| `body` | Yes | JSON object request body |
| `fields` | No | Field selection for the response (e.g., `id,number,status`) |

## Allowed Endpoints

By default, only `$BUILD_QUEUE_PATH` is allowed. The server administrator controls which POST endpoints are accessible.

## Request Body Format

The body must be a **JSON object** (not an array or primitive). The tool parses, validates, and modifies the body before sending.

### Trigger a Build

Minimal:
```json
{"buildType": {"id": "MyBuildConfig_Build"}}
```

With branch:
```json
{"buildType": {"id": "MyBuildConfig_Build"}, "branchName": "feature-branch"}
```

With comment:
```json
{"buildType": {"id": "MyBuildConfig_Build"}, "comment": {"text": "Triggered by AI agent"}}
```

With custom parameters:
```json
{"buildType": {"id": "MyBuildConfig_Build"}, "properties": {"property": [{"name": "env.MY_VAR", "value": "custom_value"}]}}
```

## Workflow: Trigger and Monitor a Build

**Step 1** — Find the build configuration ID:
```
tool: teamcity_rest_get
path: /app/rest/buildTypes
query: locator=project:(id:MyProject)&fields=buildType(id,name)
```

**Step 2** — Trigger the build:
```
tool: teamcity_rest_post
path: $BUILD_QUEUE_PATH
body: {"buildType": {"id": "MyProject_Build"}}
fields: id,number,status,personal,webUrl
```

**Step 3** — Monitor the build (use the id from step 2):
```
tool: teamcity_rest_get
path: /app/rest/builds/id:BUILD_ID
query: fields=id,number,status,state,statusText,percentageComplete
```

**Step 4** — If the build fails, investigate:
```
tool: teamcity_rest_get
path: /app/rest/builds/id:BUILD_ID/testOccurrences
query: locator=status:FAILURE&fields=testOccurrence(name,details,logAnchor)
```

## POST Response Format

Same JSON envelope as `teamcity_rest_get`:

```json
{"meta": {"url": "$BUILD_QUEUE_PATH", "statusCode": 200, "notes": ["\"personal\": true was enforced in the request body."]}, "contentType": "application/json", "body": {"id": 12345, "personal": true, "buildType": {"id": "MyProject_Build"}}}
```

- `meta.notes` will always include a reminder that personal mode was enforced.
- Use `fields` to control response size.

## POST Error Recovery

| Status | Meaning | Action |
|--------|---------|--------|
| 400 | Bad request body | Check JSON structure. Verify `buildType.id` exists. |
| 403 | Access denied | Your token may lack permission to queue builds for this configuration. |
| 404 | Not found | The build configuration ID may be wrong. Use `teamcity_rest_get` to verify it. |
| 409 | Conflict | The build may already be queued or there's a state conflict. |
| 5xx | Server error | Simplify the request and retry. |

## POST Tips

- **Always use `fields`** in your request to keep the response manageable.
- **Find the build config ID first** using `teamcity_rest_get` before triggering a build.
- **Use `branchName`** to target a specific branch. TeamCity picks up Kotlin DSL settings from that branch, so you can test build configuration changes without committing to main.
- **Add a `comment`** to document why the build was triggered.
- **Monitor after triggering** — use `teamcity_rest_get` with the returned build ID to track progress.

---

# Part 3: Viewing Build Logs (`teamcity_build_log`)

## Tool Parameters

| Parameter | Required | Description |
|-----------|----------|-------------|
| `buildId` | Yes | Numeric build ID (e.g. `"19501"`) |
| `filter` | No | `errors` (FAILURE/ERROR only), `warnings` (WARNING/FAILURE/ERROR), or omit for all |
| `start` | No | Starting message index for pagination (default: `0`) |
| `count` | No | Number of messages to retrieve (default: 100, max: 300) |

## Response Format

Plain text output with a single header line followed by log messages:

```
--- Build log: 51 messages, end of log ---

TeamCity server version is 2026.1 EAP (build 219599)
Collecting changes in 1 VCS root
[WARNING] Process exited with code 1
[ERROR] Process exited with code 1 (Step: Command Line)
[ERROR] Step Command Line failed
Build finished
```

- **Header**: shows message count and either `end of log` or `next page start: N`
- **Normal messages**: plain text, no prefix
- **Non-normal messages**: prefixed with `[WARNING]`, `[FAILURE]`, or `[ERROR]`
- **Notes**: lines starting with `#` provide hints about filtering and pagination

## Pagination

When the log is larger than `count`, the header shows `next page start: N`. Pass this value as `start` in the next call:

```
# Page 1
buildId: 19501, count: 100
→ header: "--- Build log: 100 messages, next page start: 120 ---"

# Page 2
buildId: 19501, start: 120, count: 100
→ header: "--- Build log: 80 messages, end of log ---"
```

## Common Patterns

### Quick scan for errors
```
buildId: 19501
filter: errors
```
Returns only FAILURE and ERROR messages — the fastest way to see what went wrong.

### Jump to a specific log location using logAnchor

Use `logAnchor` from `problemOccurrences` or `testOccurrences` as the `start` parameter. Start a bit before the anchor (30-50 messages) to see what led to the failure:
```
# logAnchor from problemOccurrences: "340"
buildId: 19501, start: 310, count: 60

# logAnchor from testOccurrences: "512"
buildId: 19501, start: 490, count: 70
```

For a full build failure investigation methodology, see the **Build Failure Analysis Guide**.

## Tips

- **Start with `filter=errors`** to quickly find what went wrong.
- **Use `logAnchor`** from problem/test occurrences to jump to the exact log location (pass as `start`).
- **Use `filter=warnings`** if errors alone don't explain the failure.
- **Omit the filter** to see the full build execution flow.
- **Use pagination** for long logs — don't request `count: 300` unless you need it.
- **Build IDs are numeric** — the same IDs used in `teamcity_rest_get` with `/app/rest/builds/id:BUILD_ID`.
        """.trimIndent()
    }

    override val uri = "teamcity://guides/rest-api"

    override val name = "TeamCity REST API Guide"

    override val shortName = SETTINGS_NAME

    override val description =
        "Comprehensive guide for AI agents on using the teamcity_rest_get, teamcity_rest_post, and teamcity_build_log tools. " +
        "Covers endpoints, locators, field selection, pagination, triggering builds, build log navigation, and common investigation workflows with examples."

    override val mimeType = "text/markdown"

    override fun read(): String = CONTENT
}