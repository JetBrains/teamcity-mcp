package jetbrains.buildServer.ai.mcp.resources.rest

import jetbrains.buildServer.ai.mcp.BUILD_QUEUE_PATH
import jetbrains.buildServer.ai.mcp.resources.BraveModeAwareMcpResource
import jetbrains.buildServer.ai.mcp.tools.rest.RestToolUtils
import org.springframework.stereotype.Component

@Component
class RestApiBraveGuideResource : BraveModeAwareMcpResource {

    override val brave = true

    override val uri = "teamcity://guides/rest-api"

    override val name = "TeamCity REST API Guide"

    override val shortName = RestApiGuideResource.SETTINGS_NAME

    override val description =
        "Compact operating guide for AI agents using teamcity_rest_get, teamcity_rest_post, teamcity_rest_put, teamcity_rest_delete, and teamcity_build_log. " +
        "Covers operational reads plus write, update, and delete flows for TeamCity builds, queues, agents, investigations, mutes, and server entities, with explicit safety and confirmation guidance."

    override val mimeType = "text/markdown"

    private val rendered: String by lazy {
        """
    # TeamCity REST API Guide

    This guide teaches you how to use `teamcity_rest_get`, `teamcity_rest_post`, `teamcity_rest_put`, `teamcity_rest_delete`, and `teamcity_build_log` — read, write, update, and delete operations.

    Official REST API reference: {{REST_API_DOCS_URL}}
    Server-local OpenAPI spec: `path=/app/rest/swagger.json` — use it for endpoint/field/schema discovery beyond this guide.

    ---

    # Safety

    Mutations executed through these tools are **real**:

    - They hit the live TeamCity server with the signed-in user's permissions.
    - Documented calls can still fail if permissions or current server state do not allow them.
    - POSTs to `/app/rest/buildQueue` create **non-personal** queued builds that affect the main build chain and are visible to the whole team. Include `"personal": true` in the body if you want an isolated run.
    - PUT can silently overwrite settings; DELETE is immediate and permanent.
    
    ## Agent obligations
    
    Before performing any write, update, or delete operation:
    
    1. **Confirm with the user first** for any destructive or high-impact action. Quote the exact endpoint, target entity (id + name), and method. Wait for explicit approval. Examples that always require confirmation:
       - `DELETE /app/rest/projects/...` — removes a project and **all its subprojects and build configurations**
       - `DELETE /app/rest/buildTypes/...` — removes a build configuration and its history
       - `DELETE /app/rest/vcs-roots/...` — removes a VCS root (may break dependent configurations)
       - `DELETE /app/rest/agents/...` — deletes an **inactive** agent registration
       - `DELETE /app/rest/investigations/...` — resolves an open investigation
       - `DELETE /app/rest/mutes/...` — unmutes tests/problems
       - `POST /app/rest/buildQueue/<locator>` — cancels a queued build (irreversible — the queue position is lost)
       - `POST /app/rest/builds/<locator>` — cancels or re-adds a finished/running build
       - `PUT /app/rest/buildTypes/<locator>/paused` — pausing disables triggers for the entire config
       - `PUT /app/rest/agents/<locator>/enabledInfo` — disables the agent for new builds
    
    2. **Prefer a dry-run first.** Read the entity via GET before mutating it. Show the user what you're about to change.
    
    3. **Limit blast radius.** Target a single entity per call whenever possible. Avoid bulk endpoints (`/multiple/...`, `/app/rest/investigations/multiple`) unless the user explicitly asked for a batch operation.
    
    4. **Trigger vs. cancel.** Queueing a non-personal build with the wrong `branchName` or `buildType.id` can break downstream chains. Always verify IDs with GET first.
    
    5. **State changes that look reversible often aren't.** Pinning/unpinning, muting/unmuting, enabling/disabling agents all leave audit-log entries you cannot erase.
    
    ## Irreversible operations — always require explicit confirmation
    
    | Operation | Why it's irreversible |
    |-----------|-----------------------|
    | `DELETE /app/rest/projects/{locator}` | Cascade-deletes subprojects, build configs, VCS roots, parameters, and build history references |
    | `DELETE /app/rest/buildTypes/{locator}` | Build configuration + its settings are gone; history entries may become orphaned |
    | `DELETE /app/rest/vcs-roots/{locator}` | Configurations using this VCS root must be re-wired manually |
    | `DELETE /app/rest/buildQueue/{locator}` | Queued build is removed; you cannot re-queue with the same queue position |
    | `POST /app/rest/buildQueue/{locator}` body=`{"comment":...}` | Cancel a queued build while keeping a canceled build record and comment |
    | `POST /app/rest/builds/{locator}` body=`{"comment":...}` | Cancels a running build or re-adds a finished one; history records the cancellation |
    | `DELETE /app/rest/investigations/{locator}` | Closes an open investigation; re-opening requires recreating it |
    | `DELETE /app/rest/mutes/{locator}` | Unmutes — test/problem failures start counting against builds again |
    | `PUT /app/rest/buildTypes/{locator}/paused` body=`true` | Pauses the configuration — scheduled/VCS triggers stop until unpaused |
    | `PUT /app/rest/agents/{locator}/enabledInfo` body with `{"status":false}` | Agent stops accepting new builds; running builds continue |
    
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
    | `branch` | `default:any` or `name:main` | Branch filter — see **Branch Filtering** below |
    | `defaultFilter` | `true`/`false` | Exclude personal/canceled builds (default: true) |
    | `tag` | tag name | Filter by tag |
    | `agent` | `(name:AgentName)` | Agent that ran the build |
    | `sinceDate` | `yyyyMMddTHHmmss+0000` | Builds after this date |
    | `lookupLimit` | integer | Max items to scan (default 5000). Increase if searching far back in history |
    
    ### Branch Filtering (Important)
    
    **By default, build queries only return builds from the default branch.** If results are missing, the builds likely ran on a non-default branch.
    
    - **`branch:default:any`** — all branches. Use when the user doesn't specify a branch or mentions working on a non-default branch. If a query returns 0 results unexpectedly, retry with this.
    - **`branch:<name>`** — exact branch (e.g., `branch:my-feature`). Use when the user asks about a specific branch.
    - *(omitted)* — default branch only.
    
    Include `branchName` in `fields` when querying across branches.
    
    Use `${'$'}help` to discover supported locator dimensions: `path=/app/rest/builds/${'$'}help`
    
    ### Date Format
    
    Dates are formatted as `yyyyMMddTHHmmss+0000` where `+0000` is the UTC offset (e.g., `+0000` for UTC, `-0500` for EST).
    
    Date-based filtering examples:
    ```
    locator=buildType:(id:MyBuild),sinceDate:20260201T000000+0000
    locator=buildType:(id:MyBuild),sinceDate:20260201T000000+0000,untilDate:20260215T235959+0000
    ```
    
    ### String Matching in Locators
    
    Some dimensions in the **build locator** support a condition syntax for fuzzy matching. This works on dimensions like `number` and `agentName`:
    
    ```
    <dimension>:(value:<text>,matchType:<type>,ignoreCase:true)
    ```
    
    Common `matchType` values:
    | matchType | Description |
    |-----------|-------------|
    | `equals` | Exact match (default) |
    | `contains` | Substring match |
    | `starts-with` | Prefix match |
    | `ends-with` | Suffix match |
    | `matches` | Regex match |
    
    **`ignoreCase`** defaults to `false`. Add `ignoreCase:true` when you need case-insensitive matching.
    
    Examples:
    ```
    # Find builds by agent name containing "macos" (case-insensitive)
    /app/rest/builds locator=agentName:(value:macos,matchType:contains,ignoreCase:true),count:10
    
    # Find builds with number starting with "221"
    /app/rest/builds locator=buildType:(id:BT_ID),number:(value:221,matchType:starts-with),count:10
    
    # Find builds with number matching a regex
    /app/rest/builds locator=buildType:(id:BT_ID),number:(value:.*42.*,matchType:matches),count:10
    ```
    
    **Important**: The `name` dimension on projects, build configurations, and agents does **not** support this condition syntax — it only supports exact matching. To find entities by partial name, fetch a list and filter the results, or use `${'$'}help` to check which dimensions support conditions.
    
    ## Pagination
    
    Default: 10 items from offset 0. Maximum page size: 100. Always specify `start` and `count` inside the locator — do NOT use them as top-level query parameters (deprecated).
    
    **Strategy:**
    1. Check total: `fields=count` with your locator
    2. Fetch page: add `count:100` to your locator (e.g., `locator=buildType:(id:BT1),count:100`)
    3. If response contains `nextHref`, extract path and query for the next call
    
    **Parsing `nextHref`**: The value is a relative URL like `/app/rest/builds?locator=...,start:10,count:10&fields=...`. Split on `?` to get the `path` and `query` for your next tool call.
    
    **If a query returns 0 results unexpectedly**, the server may have stopped scanning. Try increasing `lookupLimit` (e.g., `lookupLimit:50000`).
    
    ## Querying Lists of Items (Critical Pattern)
    
    **NEVER access lists as sub-resources** (e.g. `/<parent>/id:123/<items>`) — they return ALL items ignoring pagination, which can be thousands.
    
    **ALWAYS query lists via top-level endpoints** with the parent specified in the locator:
    
    | Correct endpoint | Parent filter in locator |
    |------------------|--------------------------|
    | `/testOccurrences` | `build:(id:123)` |
    | `/problemOccurrences` | `build:(id:123)` |
    | `/builds` | `buildType:(id:BT1)` or `project:(id:P1)` |
    | `/investigations` | `buildType:(id:BT1)` |
    | `/buildTypes` | `project:(id:P1)` |
    | `/agents` | `pool:(id:1)` |
    | `/vcs-root-instances` | `vcsRoot:(id:1)` |
    | `/changes` | `build:(id:123)` or `buildType:(id:BT1)` |
    
    Example — get up to 10 failed tests for a build:
    ```
    path: /app/rest/testOccurrences
    query: locator=build:(id:48231),status:FAILURE,count:10&fields=testOccurrence(name,status)
    ```
    
    This is the only way to get proper pagination (`start`, `count`) and server-side filtering for list resources.
    
    ## Common Task Patterns
    
    ### "Why did build 48231 fail?"
    
    Quick overview → problems → tests → log. For a comprehensive methodology (build chains, failed-to-start builds, cancellation, retries, root cause analysis), see the **Build Failure Analysis Guide**.
    
    ```
    # 1. Build overview — statusText often tells you what failed
    path: /app/rest/builds/id:48231
    query: fields=id,number,status,statusText
    
    # 2. Build problems — always include details and logAnchor (excluded by default)
    path: /app/rest/problemOccurrences
    query: locator=build:(id:48231)&fields=problemOccurrence(id,type,details,logAnchor)
    
    # 3. Log around the problem — start before logAnchor to see the lead-up
    tool: teamcity_build_log
    buildId: 48231, start: 310, count: 60
    
    # 4. Test failures — include details, newFailure, logAnchor (excluded by default)
    path: /app/rest/testOccurrences
    query: locator=build:(id:48231),status:FAILURE&fields=testOccurrence(name,status,details,newFailure,logAnchor)
    
    # 5. Log around a test — start before logAnchor for setup context
    tool: teamcity_build_log
    buildId: 48231, start: 490, count: 70
    ```
    
    **Key**: `details`, `logAnchor`, and `newFailure` are **not returned by default** — always include them in `fields`. `newFailure: true` means the test was passing before — focus on these first.
    
    ### Finding entities by name
    
    Project and build configuration IDs are human-readable strings (e.g., `MyProject`, `MyProject_Build`), not opaque numbers. You can often guess them from names, but to search:
    
    ```
    # Find project by name (case-sensitive exact match)
    path: /app/rest/projects
    query: locator=name:Frontend&fields=project(id,name)
    
    # Find build config by name (case-insensitive exact match)
    path: /app/rest/buildTypes
    query: locator=name:Deploy&fields=buildType(id,name,projectId)
    
    # List configs in a project
    path: /app/rest/buildTypes
    query: locator=project:(id:MyProject)&fields=buildType(id,name)
    ```
    
    **Case sensitivity**: Project and agent `name` matching is **case-sensitive**. Build configuration `name` matching is **case-insensitive**.
    
    ### "What's the status of project X?"
    1. List its build configs: `path=/app/rest/projects/id:PROJECT_ID` `query=fields=id,name,buildTypes(buildType(id,name))`
    2. Get latest build per config: `path=/app/rest/builds` `query=locator=buildType:(id:BT_ID),branch:default:any,defaultFilter:true,count:1&fields=build(id,number,status,branchName,finishOnAgentDate)`
    
    **Note**: Use `branch:default:any` to see the latest build regardless of branch. Without it, you only see the default branch.
    
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
    # Default branch only
    path: /app/rest/builds
    query: locator=buildType:(id:BT_ID),status:SUCCESS,defaultFilter:true,count:1&fields=build(id,number,finishOnAgentDate)
    
    # Across all branches (use when user doesn't specify a branch or works on a non-default branch)
    path: /app/rest/builds
    query: locator=buildType:(id:BT_ID),status:SUCCESS,branch:default:any,defaultFilter:true,count:1&fields=build(id,number,branchName,finishOnAgentDate)
    
    # Specific branch
    path: /app/rest/builds
    query: locator=buildType:(id:BT_ID),status:SUCCESS,branch:feature-login,count:1&fields=build(id,number,branchName,finishOnAgentDate)
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
    path: /app/rest/testOccurrences
    query: locator=build:(id:BUILD_ID),status:FAILURE&fields=testOccurrence(name,status,details,newFailure,logAnchor)
    
    # Test failures — short form (without stacktraces)
    path: /app/rest/testOccurrences
    query: locator=build:(id:BUILD_ID),status:FAILURE&fields=testOccurrence(name,status,duration)
    
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
    path: /app/rest/problemOccurrences
    query: locator=build:(id:BUILD_ID)&fields=problemOccurrence(id,type,details,logAnchor)
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
    
    - **Branches matter**: By default, queries return only default-branch builds. Use `branch:default:any` to search across all branches. When triggering builds, specify `branchName` if the user works on a non-default branch. See **Branch Filtering** above.
    - **Start with `fields=count`** to gauge result size before fetching data.
    - **Use `defaultFilter:true`** to exclude personal and canceled builds.
    - **Use `affectedProject` instead of `project`** to include subprojects.
    - **Build IDs are globally unique**; build numbers are only unique within a configuration.
    - **Use `state:any`** if you want queued, running, and finished builds together.
    - **Follow `nextHref`** for pagination — don't construct page URLs manually.
    
    ---
    
    # Part 2: Write Operations (`teamcity_rest_post`)
    
    ## POST Behavior

    - **By default, all `/app/rest/**` POST paths are allowed.** The tool does not enforce a default allowlist.
    - The administrator may restrict the surface via `teamcity.ai.mcp.rest.post.allowed.paths` (comma-separated list of path prefixes). When unset or null, every POST path is permitted.
    - **No personal-build enforcement.** The tool does **not** inject `"personal": true`. A POST to `$BUILD_QUEUE_PATH` creates a real queued build affecting the whole team and downstream build chains. If you want a personal build, include `"personal": true` explicitly in the body.
    
    ## Tool Parameters
    
    | Parameter | Required | Description |
    |-----------|----------|-------------|
    | `path` | Yes | REST endpoint, must start with `/app/rest/` (subject to allowlist if configured) |
    | `body` | Yes | JSON object request body |
    | `query` | No | Query string without leading `?` |
    | `fields` | No | Field selection for the response (e.g., `id,number,status`) |
    
    The body must be a **JSON object** (not an array or primitive).
    
    ## Write Cookbook
    
    Each entry below lists a single path, an example body, and one key note. Verify IDs via GET first.
    
    ### Trigger a non-personal build (default branch)
    
    ```
    path: $BUILD_QUEUE_PATH
    body: {"buildType": {"id": "MyProject_Build"}}
    ```
    Note: No `branchName` → default branch. This is a **real** build, not personal — it affects the main chain and is visible to the team.
    
    ### Trigger on a specific branch
    
    ```
    path: $BUILD_QUEUE_PATH
    body: {"buildType": {"id": "MyProject_Build"}, "branchName": "feature-login"}
    ```
    Note: When the user works on a non-default branch, always specify `branchName`. TeamCity picks up Kotlin DSL settings from that branch.
    
    ### Trigger with custom parameters
    
    ```
    path: $BUILD_QUEUE_PATH
    body: {"buildType": {"id": "MyProject_Build"}, "properties": {"property": [{"name": "env.MY_VAR", "value": "custom_value"}]}}
    ```
    Note: Parameters override build-config defaults for this run only.
    
    ### Trigger with a comment
    
    ```
    path: $BUILD_QUEUE_PATH
    body: {"buildType": {"id": "MyProject_Build"}, "comment": {"text": "Triggered by AI agent on behalf of alice"}}
    ```
    Note: Comments are surfaced in build history and audit logs — use them to document why.
    
    ### Personal build (opt-in)

    ```
    path: $BUILD_QUEUE_PATH
    body: {"buildType": {"id": "MyProject_Build"}, "personal": true, "branchName": "feature-login"}
    ```
    Note: the tool doesn't force `personal:true` — you must set it yourself for isolation.
    
    ### Cancel a queued build (POST with comment)
    
    ```
    path: /app/rest/buildQueue/id:QUEUED_BUILD_ID
    body: {"comment": {"text": "Superseded by newer change"}}
    ```
    Note: Verify the build is still queued first via `GET /app/rest/buildQueue/id:QUEUED_BUILD_ID`. Unlike DELETE, this cancel form keeps a canceled build record and lets you attach a comment.
    
    ### Cancel a running build or re-add a finished build
    
    ```
    path: /app/rest/builds/id:BUILD_ID
    body: {"comment": {"text": "Stopped to avoid deploy"}, "readdIntoQueue": false}
    ```
    Note: Setting `readdIntoQueue: true` re-queues the canceled/finished build with the same parameters. Omit or set false to just cancel.
    
    ### Approve a queued build waiting on manual approval
    
    ```
    path: /app/rest/buildQueue/id:QUEUED_BUILD_ID/approve
    body: {}
    ```
    Note: Only works if the build is blocked by an approval feature. Check `GET /app/rest/buildQueue/id:ID?fields=approvalInfo` first.
    
    ### Tag a build (append)
    
    ```
    path: /app/rest/builds/id:BUILD_ID/tags/
    body: {"tag": [{"name": "release-candidate"}]}
    ```
    Note: POST **appends** tags. Use PUT on the same path to **replace** all tags.
    
    ### Add a single tag via plain text
    
    ```
    path: /app/rest/builds/id:BUILD_ID/tags/
    body: "release-candidate"
    ```
    Note: The server also accepts a plain-text tag name; the MCP tool requires a JSON object, so prefer the JSON `tag` array form above.
    
    ### Create a project parameter
    
    ```
    path: /app/rest/projects/id:PROJECT_ID/parameters
    body: {"name": "env.DEPLOY_KEY", "value": "staging"}
    ```
    Note: Overwrites existing parameter with the same name in the project. Inherited parameters remain unchanged.
    
    ### Create a build-type parameter
    
    ```
    path: /app/rest/buildTypes/id:BT_ID/parameters
    body: {"name": "env.TIMEOUT", "value": "120"}
    ```
    Note: Same semantics as project parameters but scoped to one build configuration.
    
    ### Add a build step to a build configuration
    
    ```
    path: /app/rest/buildTypes/id:BT_ID/steps
    body: {"name": "Run tests", "type": "simpleRunner", "properties": {"property": [{"name": "script.content", "value": "./gradlew test"}, {"name": "use.custom.script", "value": "true"}]}}
    ```
    Note: Step `type` values are runner-specific (e.g. `simpleRunner`, `Maven2`, `gradle-runner`). Missing or wrong `type` creates a broken step.
    
    ### Add a build feature
    
    ```
    path: /app/rest/buildTypes/id:BT_ID/features
    body: {"type": "commit-status-publisher", "properties": {"property": [{"name": "vcsRootId", "value": "MyRoot"}]}}
    ```
    Note: Feature `type` strings are plugin-specific. Check an existing feature via GET before authoring a new one.
    
    ### Create an investigation
    
    ```
    path: /app/rest/investigations
    body: {"assignee": {"username": "alice"}, "scope": {"buildTypes": {"buildType": [{"id": "MyProject_Build"}]}}, "target": {"anyProblem": true}, "resolution": {"type": "whenFixed"}}
    ```
    Note: The `target` determines what's being investigated (whole build, specific test, problem). Payload schema is documented in the REST docs linked at the bottom.
    
    ### Mute a failing test
    
    ```
    path: /app/rest/mutes
    body: {"scope": {"buildTypes": {"buildType": [{"id": "MyProject_Build"}]}}, "target": {"tests": {"test": [{"name": "com.example.FlakyTest"}]}}, "resolution": {"type": "manually"}}
    ```
    Note: Use sparingly — muted failures no longer fail builds. Always pair with a tracked issue.
    
    ### Create a new (empty) build configuration under a project
    
    ```
    path: /app/rest/projects/id:PROJECT_ID/buildTypes
    body: {"name": "New Build"}
    ```
    Note: Creates an empty build configuration. To copy from an existing config, POST the full BuildType JSON instead.
    
    ### Create a subproject
    
    ```
    path: /app/rest/projects
    body: {"parentProject": {"id": "MyProject"}, "name": "New Subproject", "id": "MyProject_NewSub"}
    ```
    Note: You choose the external `id`. If omitted, TeamCity auto-generates from `name`.
    
    ## Workflow: Trigger and Monitor a Non-Personal Build
    
    **Step 1** — Find the build configuration ID:
    ```
    tool: teamcity_rest_get
    path: /app/rest/buildTypes
    query: locator=project:(id:MyProject)&fields=buildType(id,name)
    ```
    
    **Step 2** — Confirm with the user. Non-personal triggers produce real team-visible builds.
    
    **Step 3** — Trigger the build:
    ```
    tool: teamcity_rest_post
    path: $BUILD_QUEUE_PATH
    body: {"buildType": {"id": "MyProject_Build"}, "branchName": "feature-login"}
    fields: id,number,branchName,webUrl,state
    ```
    
    **Step 4** — Monitor via GET:
    ```
    tool: teamcity_rest_get
    path: /app/rest/builds/id:BUILD_ID
    query: fields=id,number,status,state,statusText,percentageComplete
    ```
    
    ## POST Error Recovery
    
    | Status | Meaning | Action |
    |--------|---------|--------|
    | 400 | Bad request body | Check JSON structure. Verify referenced IDs (`buildType.id`, `project.id`) exist. |
    | 403 | Access denied or not in allowlist | Your token lacks permission, or the path is excluded by `teamcity.ai.mcp.rest.post.allowed.paths`. |
    | 404 | Not found | The target locator resolved to nothing. Verify via GET first. |
    | 409 | Conflict | Duplicate entity, stale state, or already-in-progress operation. |
    | 5xx | Server error | Simplify the body and retry. |
    
    ---
    
    # Part 3: PUT Operations (`teamcity_rest_put`)
    
    ## When REST Uses PUT vs POST
    
    TeamCity's REST API follows a consistent convention:
    
    - **POST** on a collection — **create/append**. Example: `POST /app/rest/builds/{locator}/tags/` appends tags. `POST /app/rest/projects/{locator}/buildTypes` creates a new build configuration.
    - **PUT** on a scalar field or single-entity endpoint — **replace the value**. Example: `PUT /app/rest/builds/{locator}/comment` replaces the comment. `PUT /app/rest/buildTypes/{locator}/paused` replaces the paused flag.
    - **PUT** on a collection — **replace the whole collection**. Example: `PUT /app/rest/builds/{locator}/tags/` replaces all tags.
    
    When a guide examples says "PUT" it really means "replace what's there." Always read via GET before PUT-ing so you know what you're overwriting.
    
    ## Tool Parameters
    
    | Parameter | Required | Description |
    |-----------|----------|-------------|
    | `path` | Yes | REST endpoint, must start with `/app/rest/` (subject to allowlist if configured) |
    | `body` | Yes | Request body — usually plain text (for scalar fields) or JSON (for entity replacement) |
    | `query` | No | Query string without leading `?` |
    
    - No MCP-side allowlist: any `/app/rest/...` PUT path is available here, subject to TeamCity permissions.
    - Content-Type is inferred from the body: plain-text endpoints (comment, paused flag, scalar fields) accept text; structured endpoints accept JSON.
    
    ## PUT Cookbook
    
    ### Set/replace a build comment
    
    ```
    path: /app/rest/builds/id:BUILD_ID/comment
    body: This build deploys the fix for TW-12345
    ```
    Note: Plain-text body. Overwrites any existing comment. DELETE on the same path removes the comment.
    
    ### Pin / unpin a build
    
    ```
    path: /app/rest/builds/id:BUILD_ID/pin/
    body: true
    ```
    Note: This deprecated endpoint pins the build regardless of body content; any plain-text body is treated as the pin comment, not a boolean flag. Pinning protects the build from automatic cleanup. Prefer `PUT /app/rest/builds/id:BUILD_ID/pinInfo/` with JSON body `{"status":true,"comment":{"text":"Keep for audit"}}`.
    
    ### Set build number
    
    ```
    path: /app/rest/builds/id:BUILD_ID/number
    body: 1.2.3-rc1
    ```
    Note: Plain-text body. Must be unique within the build configuration, and only works for a **running** build.
    
    ### Set build status text (displayed on overview)
    
    ```
    path: /app/rest/builds/id:BUILD_ID/statusText
    body: All smoke tests passed
    ```
    Note: Does **not** change the build's pass/fail status — only the human-readable summary line. This only works for a **running** build.
    
    ### Pause / unpause a build configuration
    
    ```
    path: /app/rest/buildTypes/id:BT_ID/paused
    body: true
    ```
    Note: Plain-text `true`/`false`. When paused, VCS and schedule triggers stop firing until unpaused.
    
    ### Change a scalar build-type field (name, description, etc.)
    
    ```
    path: /app/rest/buildTypes/id:BT_ID/name
    body: Nightly Integration Tests
    ```
    Note: On this generic field endpoint, supported writable fields are `name`, `description`, and `paused`. Use dedicated endpoints or full-entity updates for broader changes. Renaming `id` or moving a build configuration is not covered by this field route.
    
    ### Set/replace a build-type parameter value
    
    ```
    path: /app/rest/buildTypes/id:BT_ID/parameters/env.TIMEOUT
    body: 180
    ```
    Note: Plain-text body is the new value. To change type/spec, PUT a JSON property object to `/parameters/<name>`.
    
    ### Set/replace a project parameter value
    
    ```
    path: /app/rest/projects/id:PROJECT_ID/parameters/env.DEPLOY_KEY
    body: production
    ```
    Note: Same semantics at project scope. Inherited parameters are shadowed, not mutated upstream.
    
    ### Change a scalar project field
    
    ```
    path: /app/rest/projects/id:PROJECT_ID/name
    body: Payments Platform
    ```
    Note: On this generic field endpoint, supported writable fields are `name`, `description`, and `archived`. Moving a project is not covered by this field route.
    
    ### Enable / disable an agent
    
    ```
    path: /app/rest/agents/id:AGENT_ID/enabledInfo
    body: {"status": true, "comment": {"text": "Back online after maintenance"}}
    ```
    Note: JSON body. `status:false` prevents the agent from picking up new builds; currently running builds continue.
    
    ### Authorize / unauthorize an agent
    
    ```
    path: /app/rest/agents/id:AGENT_ID/authorizedInfo
    body: {"status": true, "comment": {"text": "New CI host approved"}}
    ```
    Note: Authorization is separate from enabledness. Unauthorized agents are counted toward license caps differently.
    
    ### Move an agent to a pool
    
    ```
    path: /app/rest/agents/id:AGENT_ID/pool
    body: {"id": 3}
    ```
    Note: Pool `id` is numeric. Find it via `GET /app/rest/agentPools?fields=agentPool(id,name)`.
    
    ### Replace all tags on a build (destructive)
    
    ```
    path: /app/rest/builds/id:BUILD_ID/tags/
    body: {"tag": [{"name": "stable"}, {"name": "v1.2"}]}
    ```
    Note: PUT replaces — existing tags not in the payload are removed. Use POST on the same path to append.
    
    ### Reorder the build queue
    
    ```
    path: /app/rest/buildQueue/order
    body: {"build": [{"id": 1001}, {"id": 1002}, {"id": 1003}]}
    ```
    Note: The `build` array defines the new full ordering of queued builds. Items omitted from the list retain relative order at the bottom.
    
    ### Replace a VCS root property
    
    ```
    path: /app/rest/vcs-roots/id:VCS_ROOT_ID/properties/branch
    body: refs/heads/main
    ```
    Note: Plain-text body. Changing `url` or authentication properties affects all build configurations using the VCS root.
    
    ### Replace a VCS root scalar field
    
    ```
    path: /app/rest/vcs-roots/id:VCS_ROOT_ID/name
    body: GitHub — my-repo (main)
    ```
    Note: Valid fields include `name`, `id`, `projectId`, `vcsName`. Renaming `id` breaks external references.
    
    ### Replace investigation state (JSON)
    
    ```
    path: /app/rest/investigations/id:INVESTIGATION_ID
    body: {"assignee": {"username": "bob"}, "resolution": {"type": "whenFixed"}, "target": {"anyProblem": true}}
    ```
    Note: PUT here replaces the investigation. Use DELETE to close it instead.
    
    ## PUT Error Recovery
    
    | Status | Meaning | Action |
    |--------|---------|--------|
    | 400 | Bad body | Check content type — many PUTs want plain text, not JSON. |
    | 403 | Access denied | Check the current user's TeamCity permissions for this target. |
    | 404 | Not found | Target locator resolves to nothing, or the scalar field name is misspelled. |
    | 405 | Method not allowed | This endpoint doesn't support PUT — try POST. |
    | 409 | Conflict | Concurrent modification or invalid state transition. |
    
    ---
    
    # Part 4: DELETE Operations (`teamcity_rest_delete`)
    
    ## Warning — Irreversibility
    
    DELETE is **permanent**. There is no undo, no trash, no soft delete. Always:
    
    1. Read the target via GET and show the user exactly what will be deleted.
    2. Get explicit user confirmation, especially for projects, build configurations, and VCS roots.
    3. Prefer the least destructive alternative (pause instead of delete, unmute instead of delete + recreate).
    
    Cascade behavior matters: deleting a project removes every subproject, build configuration, template, and parameter under it.
    
    ## Tool Parameters
    
    | Parameter | Required | Description |
    |-----------|----------|-------------|
    | `path` | Yes | REST endpoint, must start with `/app/rest/` (subject to allowlist if configured) |
    | `query` | No | Query string without leading `?` |
    
    - No MCP-side allowlist: any `/app/rest/...` DELETE path is available here, subject to TeamCity permissions.
    - DELETE never takes a body.
    
    ## DELETE Cookbook
    
    ### Remove a build from the queue
    
    ```
    path: /app/rest/buildQueue/id:QUEUED_BUILD_ID
    ```
    Note: More destructive than the POST cancel form: DELETE cancels the queued build and removes the resulting canceled build record. Use POST if you want to preserve the canceled build entry and record a reason.
    
    ### Remove a single tag from a build
    
    ```
    path: /app/rest/builds/id:BUILD_ID/tags/release-candidate
    ```
    Note: The tag name is part of the path. Use PUT `/tags/` to replace the whole tag set.
    
    ### Remove a build comment
    
    ```
    path: /app/rest/builds/id:BUILD_ID/comment
    ```
    Note: Clears any previously-set comment. To overwrite instead of clearing, PUT plain text.
    
    ### Unpin a build
    
    ```
    path: /app/rest/builds/id:BUILD_ID/pin/
    ```
    Note: Unpins the build. On this deprecated endpoint, DELETE is the unpin action; `PUT /pin/` pins the build and does not accept a boolean flag.
    
    ### Delete a build (delete from history)
    
    ```
    path: /app/rest/builds/id:BUILD_ID
    ```
    Note: Requires appropriate permissions. Deletes the build record, artifacts, and associated data. **Destructive — confirm first.**
    
    ### Delete a build configuration
    
    ```
    path: /app/rest/buildTypes/id:BT_ID
    ```
    Note: **Destructive.** Removes the configuration and its settings. Historical build records may be orphaned or removed depending on cleanup config.
    
    ### Delete a build step
    
    ```
    path: /app/rest/buildTypes/id:BT_ID/steps/RUNNER_ID
    ```
    Note: Find the `RUNNER_ID` via `GET /app/rest/buildTypes/id:BT_ID/steps`.
    
    ### Delete a build feature
    
    ```
    path: /app/rest/buildTypes/id:BT_ID/features/FEATURE_ID
    ```
    Note: Find the feature id via `GET /app/rest/buildTypes/id:BT_ID/features`.
    
    ### Delete a project
    
    ```
    path: /app/rest/projects/id:PROJECT_ID
    ```
    Note: **Most destructive operation.** Cascade-deletes all subprojects, build configurations, templates, parameters, and VCS roots scoped under the project. Always confirm with the user and consider archiving via `PUT /app/rest/projects/id:PROJECT_ID/archived` as a safer alternative.
    
    ### Delete a project parameter
    
    ```
    path: /app/rest/projects/id:PROJECT_ID/parameters/env.DEPLOY_KEY
    ```
    Note: Only removes the parameter in this project; inherited parameters from parent projects remain.
    
    ### Delete a build-type parameter
    
    ```
    path: /app/rest/buildTypes/id:BT_ID/parameters/env.TIMEOUT
    ```
    Note: Same semantics at build-config scope.
    
    ### Delete a VCS root
    
    ```
    path: /app/rest/vcs-roots/id:VCS_ROOT_ID
    ```
    Note: **Destructive.** Any build configurations referencing this VCS root will lose their source control binding. Check `GET /app/rest/vcs-root-instances?locator=vcsRoot:(id:VCS_ROOT_ID)` before deletion.
    
    ### Delete a VCS root property
    
    ```
    path: /app/rest/vcs-roots/id:VCS_ROOT_ID/properties/branch
    ```
    Note: Removes a single property. May make the VCS root invalid if a required property is removed.
    
    ### Resolve (close) an investigation
    
    ```
    path: /app/rest/investigations/id:INVESTIGATION_ID
    ```
    Note: Closes the investigation. Re-opening requires creating a new investigation with the same target.
    
    ### Unmute tests/problems
    
    ```
    path: /app/rest/mutes/id:MUTE_ID
    ```
    Note: Removes the mute. Test/problem failures count against builds again starting with the next build.
    
    ### Unregister an agent
    
    ```
    path: /app/rest/agents/id:AGENT_ID
    ```
    Note: Deletes an **inactive** agent registration from the server. The agent host may re-register later if started again. For active agents, disable/unauthorize instead of deleting.
    
    ### Delete an agent pool (caution)
    
    ```
    path: /app/rest/agentPools/id:POOL_ID
    ```
    Note: Pool must be empty (no agents, no associated projects). Move agents out and detach projects first via PUT endpoints.
    
    ## DELETE Error Recovery
    
    | Status | Meaning | Action |
    |--------|---------|--------|
    | 400 | Malformed locator | Check parentheses and field names. |
    | 403 | Access denied | Check the current user's TeamCity permissions for this target. |
    | 404 | Not found | Already deleted, or the locator never matched. Idempotent — safe to treat as success in scripts. |
    | 409 | Conflict | Entity has dependents (e.g. non-empty agent pool). Detach/move dependents first. |
    
    ---
    
    # Part 5: Viewing Build Logs (`teamcity_build_log`)
    
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
    
    ---
    
    # Reference
    
    - **Official REST API documentation** (versioned to this server): {{REST_API_DOCS_URL}}
      Use this for schema/payload details beyond the cookbook (especially for investigation, mute, and feature body formats).
    - **Server-local OpenAPI spec**: `path=/app/rest/swagger.json` — very large but authoritative. Use it to confirm endpoint signatures, allowed methods, and request/response schemas the guide doesn't cover.
    - **Interactive `${'$'}help`**: Append `/${'$'}help` to any endpoint to list supported locator dimensions. Examples:
      ```
      path: /app/rest/builds/${'$'}help
      path: /app/rest/buildTypes/${'$'}help
      path: /app/rest/investigations/${'$'}help
      path: /app/rest/mutes/${'$'}help
      ```
        """.trimIndent().replace("{{REST_API_DOCS_URL}}", RestToolUtils.restApiDocsUrl())
    }

    override fun read(): String = rendered
}
