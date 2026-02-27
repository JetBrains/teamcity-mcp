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

This guide teaches you how to use the `teamcity_rest_get` tool to query TeamCity.

## Tool Parameters

| Parameter | Required | Description |
|-----------|----------|-------------|
| `path` | Yes | REST endpoint, must start with `/app/rest/` |
| `query` | No | Query string without leading `?` |

## Automatic Behaviors

- **Pagination enforced**: If you omit pagination, `start=0&count=10` is added. Max `count` is 100.
- **Response format**: The tool always returns a structured JSON envelope with `meta`, `contentType`, and payload fields (`body` or `bodyText`).
- **Response notes**: Hints about pagination, truncation, and missing fields are in `meta.notes` (array of strings).
- **Truncation**: Very large responses are truncated. If you see a truncation warning, use narrower `fields` or smaller `count`.
- **Don't URL-encode**: Pass parameters as plain text — the server handles encoding.

**When in doubt, use `${'$'}help`**: `path=/app/rest/builds/${'$'}help` is the best self-service reference for locator dimensions and endpoint-specific syntax.

## Response Format

The tool output is a structured JSON envelope:

```json
{"meta":{"url":"/app/rest/builds?start=0&count=10&fields=build(id,status)","statusCode":200,"truncated":false,"hasNextHref":true,"notes":["More results available. Use the 'nextHref' value from the response to fetch the next page."]},"contentType":"application/json","body":{"count":2,"nextHref":"/app/rest/builds?start=10&count=10","build":[{"id":123,"status":"SUCCESS"},{"id":124,"status":"FAILURE"}]}}
```

- Use `meta.notes` for warnings/guidance.
- If `contentType=application/json`, parse payload from `body`.
- If `contentType=text/plain` (for example `/log`), read payload from `bodyText`.

## Fields Parameter

For JSON endpoints, **always specify `fields`** to control response size. Without it, responses can be huge.  
For plain-text endpoints like `/log`, `fields` is not applicable.

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

Default: 10 items from offset 0. Maximum page size: 100.

**Strategy:**
1. Check total: `fields=count` with your locator
2. Fetch page: add `count:100` to your locator, or `count=100` as query param
3. If response contains `nextHref`, extract path and query for the next call

**Parsing `nextHref`**: The value is a relative URL like `/app/rest/builds?locator=...,start:10,count:10&fields=...`. Split on `?` to get the `path` and `query` for your next tool call.

**If a query returns 0 results unexpectedly**, the server may have stopped scanning. Try increasing `lookupLimit` (e.g., `lookupLimit:50000`).

## Common Task Patterns

### Worked example: "Why did build 48231 fail?"

**Step 1** — Get build overview:
```
path: /app/rest/builds/id:48231
query: fields=id,number,status,statusText
```
Response: `{"id":48231,"number":"153","status":"FAILURE","statusText":"Tests failed: 3 (2 new)"}`

**Step 2** — Check test failures (count first):
```
path: /app/rest/builds/id:48231/testOccurrences
query: locator=status:FAILURE&fields=count
```
Response: `{"count":3}`

**Step 3** — Get failure details:
```
path: /app/rest/builds/id:48231/testOccurrences
query: locator=status:FAILURE&fields=testOccurrence(name,details)
```
Response: `{"count":3,"testOccurrence":[{"name":"com.example.AuthTest.testLogin","details":"Expected 200 but got 401"},...]}`

**Step 4** — If still unclear, check build problems and log:
```
path: /app/rest/builds/id:48231/problemOccurrences
query: fields=problemOccurrence(type,details)
```
```
path: /app/rest/builds/id:48231/log
```

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
query: locator=currentlyFailing:true,affectedProject:(id:PROJECT_ID)&fields=testOccurrence(name,details,build(buildType(id)))
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
# Test failures in a build
path: /app/rest/builds/id:BUILD_ID/testOccurrences
query: locator=status:FAILURE&fields=testOccurrence(name,status,details,duration)

# Test history
path: /app/rest/testOccurrences
query: locator=test:(name:com.example.MyTest),count:20&fields=testOccurrence(name,status,duration,build(id,number))
```

### Build Problems
```
path: /app/rest/builds/id:BUILD_ID/problemOccurrences
query: fields=problemOccurrence(type,identity,details)
```

### Build Log
```
# Returns plain text, not JSON
path: /app/rest/builds/id:BUILD_ID/log
```

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
        """.trimIndent()
    }

    override val uri = "teamcity://guides/rest-api"

    override val name = "TeamCity REST API Guide"

    override val shortName = SETTINGS_NAME

    override val description =
        "Comprehensive guide for AI agents on using the teamcity_rest_get tool to query the TeamCity REST API. " +
        "Covers endpoints, locators, field selection, pagination, and common workflows with examples."

    override val mimeType = "text/markdown"

    override fun read(): String = CONTENT
}