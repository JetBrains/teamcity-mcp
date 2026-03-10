package jetbrains.buildServer.ai.mcp.resources.rest

import jetbrains.buildServer.ai.mcp.resources.McpResource
import org.springframework.stereotype.Component

@Component
class BuildFailureAnalysisGuideResource : McpResource {

    companion object {
        const val SETTINGS_NAME = "build_failure_analysis_guide"

        private val CONTENT = """
# Build Failure Analysis Guide

A step-by-step playbook for investigating failed TeamCity builds and build chains.
Assumes familiarity with the tools — see the **REST API Guide** for tool parameters, field syntax, locators, pagination, and response format.

---

# Quick Reference: Analysis Workflow

```
1. Identify the build        → GET build overview (status, statusText, failedToStart)
2. Check if it's a chain     → GET snapshot-dependencies for composite/chain builds
3. Find the root failure      → Walk upstream deps to the first failed build
4. Get build problems         → GET problemOccurrences (type, details, logAnchor)
5. Get failed tests           → GET testOccurrences with status:FAILURE (details, newFailure, logAnchor)
6. Read the log smartly       → filter=errors first, then use logAnchor to jump to problems/tests, warnings and/or other messages if needed
7. Check what changed         → GET changes for the failing build
8. Compare with last success  → Identify what's different
```

---

# Step 1: Get the Build Overview

Start by understanding what kind of failure you're dealing with.

```
path: /app/rest/builds/id:BUILD_ID
query: fields=id,number,status,statusText,state,failedToStart,composite,canceled,personal,buildType(id,name),agent(name),triggered(type,user(username)),startDate,finishOnAgentDate
```

Key fields to examine:
- **`statusText`** — human-readable summary like "Tests failed: 3 (2 new), exit code 1 (Step: Compile)". This often tells you exactly what failed.
- **`failedToStart`** — if `true`, the build never ran (see Step 1b below).
- **`composite`** — if `true`, this build aggregates results from dependency builds (see Step 2).
- **`canceled`** — if `true`, someone or something canceled the build.
- **`state`** — should be `finished` for completed builds; `running` means it's still going.

## Step 1b: Failed-to-Start Builds

A `failedToStart: true` build was removed from the queue before execution. Common causes: no compatible agents, snapshot dependency failure, etc.

**Critical**: The default filter (`defaultFilter:true`) **hides** failed-to-start builds. You must explicitly include them:

```
# Find failed-to-start builds for a configuration
path: /app/rest/builds
query: locator=buildType:(id:BT_ID),failedToStart:true,defaultFilter:false,count:5&fields=build(id,number,status,statusText,failedToStart)
```

For failed-to-start builds, check build problems — they usually explain why the build couldn't start:
```
path: /app/rest/problemOccurrences
query: locator=build:(id:BUILD_ID)&fields=problemOccurrence(type,details,logAnchor)
```

## Step 1c: Canceled Builds

Similar to failed-to-start, canceled builds are hidden by default. Query with:
```
path: /app/rest/builds
query: locator=buildType:(id:BT_ID),canceled:true,defaultFilter:false,count:5&fields=build(id,number,statusText,canceledInfo(text,user(username),timestamp))
```

---

# Step 2: Investigate Build Chains

If the build is **composite** or part of a **snapshot dependency chain**, the actual failure often lives in an upstream build. A composite build itself has no build steps — it only aggregates the results of its dependencies.

## 2a: Get the dependency graph

```
# Upstream builds (builds this one depends on)
path: /app/rest/builds
query: locator=snapshotDependency:(to:(id:BUILD_ID),includeInitial:true),defaultFilter:false&fields=build(id,number,status,statusText,buildType(id,name),failedToStart)
```

```
# Downstream builds (builds that depend on this one)
path: /app/rest/builds
query: locator=snapshotDependency:(from:(id:BUILD_ID),includeInitial:true),defaultFilter:false&fields=build(id,number,status,statusText,buildType(id,name))
```

**Important**: Always use `defaultFilter:false` when querying dependency chains — failed-to-start and canceled builds in the chain would otherwise be hidden.

## 2b: Find the root cause build

Look at the upstream builds and find the **first one that failed**. This is usually the real culprit. Then continue analysis from Step 3 on *that* build.

Pattern: scan upstream builds for `status:FAILURE` and pick the one with no failed upstream dependencies of its own — that's your root cause.

## 2c: Snapshot dependency problems

When a build fails because an upstream dependency failed, it gets a snapshot dependency problem. The `additionalData` field on these problems contains the **promotion ID** of the failed upstream build:

```
path: /app/rest/problemOccurrences
query: locator=build:(id:BUILD_ID)&fields=problemOccurrence(type,details,logAnchor,additionalData)
```

If you see `type` containing "snapshotDependency", the `additionalData` points to the upstream build that actually failed. Investigate that build instead.

---

# Step 3: Get Build Problems

Build problems represent issues detected during the build — failed steps, exit codes, compilation errors, out-of-memory, etc.

```
path: /app/rest/problemOccurrences
query: locator=build:(id:BUILD_ID)&fields=problemOccurrence(id,type,identity,details,logAnchor,newFailure,additionalData)
```

**Important**: `details`, `logAnchor`, `newFailure`, and `additionalData` are **not returned by default** — you must include them in `fields`.

### Problem types and what they mean

| Type | Meaning | What to do |
|------|---------|------------|
| `TC_EXIT_CODE` | A build step exited with non-zero code | Use `logAnchor` to jump to the failing step in the log |
| `TC_FAILED_TESTS` | Tests reported failures | Get test details from testOccurrences (Step 4) |
| `TC_COMPILATION_ERROR` | Compilation failed | Use `logAnchor` to see compiler output |
| `TC_OOME` | Out of memory error | Check agent memory, build parameters |
| `BuildFailureOnMessage` | Build failure condition triggered by log message | Use `logAnchor` to see which log line matched |
| Contains "snapshotDependency" | Upstream build in chain failed | Check `additionalData` for the failed upstream build ID |

### Using logAnchor

The `logAnchor` is a message index in the build log. Use it as the `start` parameter in `teamcity_build_log` to jump directly to where the problem occurred:

```
tool: teamcity_build_log
buildId: BUILD_ID
start: <logAnchor value>
count: 50
```

**Tip**: The anchor points to the problem itself. To understand what led to the failure, start reading a bit earlier — e.g., 30-50 messages before the anchor. This often reveals the actual cause (a missing dependency, a failed command, an environment issue) that preceded the error:

```
tool: teamcity_build_log
buildId: BUILD_ID
start: <logAnchor - 40>
count: 80
```

---

# Step 4: Get Test Failures

Test failures are the most common build failure type. Get them with details:

```
path: /app/rest/testOccurrences
query: locator=build:(id:BUILD_ID),status:FAILURE&fields=testOccurrence(name,status,details,newFailure,logAnchor,duration,muted,currentlyInvestigated)
```

**Important**: `details`, `newFailure`, and `logAnchor` are **not returned by default** — you must include them in `fields`.

### Prioritize new failures

**`newFailure: true`** means the test was passing in the previous build — these are the tests most likely caused by recent changes. **Investigate new failures first.**

If there are many failures but only a few are new, the rest may be pre-existing (flaky or known broken). Focus on the `newFailure: true` ones.

### Understanding test details

The `details` field contains the full test output: assertion messages, stacktraces, stdout, stderr. This is usually enough to understand why a test failed.

If `details` alone isn't sufficient (e.g., the error depends on setup or environment context), use `logAnchor` to see the test's position in the build log. Start a bit before the anchor to capture setup and context that preceded the test:

```
tool: teamcity_build_log
buildId: BUILD_ID
start: <logAnchor - 20>
count: 100
```

This shows surrounding build output: setup steps, other test runs, environment info that may explain the failure.

### Check when a test first started failing

```
path: /app/rest/testOccurrences
query: locator=build:(id:BUILD_ID),status:FAILURE&fields=testOccurrence(name,firstFailed(id,number,buildType(id)),nextFixed(id,number))
```

- `firstFailed` — the build where this test first broke. Compare its changes to understand the root cause.
- `nextFixed` — the build where it was fixed (if already fixed).

### Check test history for flakiness

The REST API has no dedicated "flaky" flag. To check if a test is flaky, query its recent history and look for alternating SUCCESS/FAILURE patterns:

```
path: /app/rest/testOccurrences
query: locator=test:(name:com.example.MyTest),count:20&fields=testOccurrence(status,build(id,number,startDate))
```

If the test alternates between SUCCESS and FAILURE across builds, it's likely flaky — and the current failure may not be caused by recent changes.

### Get only test counts first

If a build has many test failures, start with counts to understand the scale:

```
path: /app/rest/builds/id:BUILD_ID
query: fields=id,testOccurrences(count,passed,failed,newFailed,muted,ignored)
```

---

# Step 5: Read the Build Log Strategically

Build logs can be very large. Use a progressive approach — each level adds more context but consumes more tokens:

1. **`filter=errors`** — only FAILURE and ERROR lines. Fastest way to see what went wrong. Often sufficient.
2. **`filter=warnings`** — adds WARNINGs. Reveals deprecation issues, configuration problems, resource constraints.
3. **`logAnchor` jump** — use anchors from Steps 3-4 to jump to the exact problem/test location. Start 30-50 messages before the anchor to see the lead-up.
4. **Read the end of the log** — failures and summary info are usually near the bottom. Use a high `start` value (e.g., `9999`) to jump near the end; if past the end, the tool returns 0 messages — reduce and retry.
5. **Full log pagination (last resort)** — paginate from the beginning with `count: 300`. **Warning**: this consumes a lot of context. Only use when all other approaches haven't clarified the root cause.

See the **REST API Guide**, Part 3 for `teamcity_build_log` tool parameters, response format, and pagination mechanics.

---

# Step 6: Check What Changed

Compare changes in the failing build with the last successful build.

```
# Changes in the failing build — look at files, authors, commit messages
path: /app/rest/changes
query: locator=build:(id:BUILD_ID)&fields=change(id,version,username,comment,date,files(file(file,changeType)))

# Last successful build — to compare against
path: /app/rest/builds
query: locator=buildType:(id:BT_ID),status:SUCCESS,defaultFilter:true,count:1&fields=build(id,number,finishOnAgentDate)

# All changes since last success — these are the suspects
path: /app/rest/changes
query: locator=buildType:(id:BT_ID),sinceChange:(build:(id:LAST_SUCCESS_BUILD_ID))&fields=change(id,version,username,comment,files(file(file,changeType)))
```

**Build chains**: Changes may come from upstream builds. These have `type: SNAPSHOT_DEPENDENCY_VCS_CHANGE` and include a `snapshotDependencyLink`:

```
path: /app/rest/changes
query: locator=build:(id:BUILD_ID)&fields=change(id,username,comment,type,snapshotDependencyLink(build(id,buildType(id))))
```

---

# Step 7: Check Investigations and Mutes

Before spending time investigating, check if someone is already looking at it:

```
path: /app/rest/investigations
query: locator=buildType:(id:BT_ID)&fields=investigation(id,state,assignee(username),resolution(type),scope(buildType(id)))
```

Also check if known failures are muted:

```
path: /app/rest/testOccurrences
query: locator=build:(id:BUILD_ID),status:FAILURE,currentlyMuted:true&fields=testOccurrence(name,mute(assignment(user(username)),resolution(type)))
```

---

# Step 8: Verify the Fix

After applying a fix, trigger a personal build to verify. See the **REST API Guide**, Part 2 for how to use `teamcity_rest_post` to trigger and monitor builds.

**Tip: use `branchName` to run builds from a feature branch.** TeamCity picks up Kotlin DSL settings from that branch too, so you can iterate on build configuration changes without committing to the main branch. This is especially useful when debugging build steps, parameters, or dependency settings — push to a branch, trigger a personal build on it, and verify without affecting others.

---

# Common Scenarios

## Scenario A: "The build failed with exit code 1"

1. Get build problems → find the `TC_EXIT_CODE` problem
2. Use `logAnchor` to jump to the failing step
3. Read the log around that point — look for compiler errors, missing files, permission issues
4. Check changes — was a build script modified?

## Scenario B: "Tests are failing"

1. Get test failures with `newFailure` flag
2. Focus on **new failures** first — these are caused by recent changes
3. Read `details` for stacktraces
4. If needed, check `firstFailed` to see when it started
5. Check if some failures are flaky (query test history — alternating SUCCESS/FAILURE)
6. Check changes in the `firstFailed` build

## Scenario C: "The composite build is red"

1. Get snapshot dependencies of the composite build
2. Find which child build(s) actually failed
3. Investigate the failed child build (repeat from Step 1)
4. Ignore the composite build itself — it has no steps or log

## Scenario D: "The build failed to start"

1. Confirm `failedToStart: true` (must use `defaultFilter:false`)
2. Get build problems — they explain why it couldn't start
3. Common causes: no compatible agents, snapshot dependency failure, VCS checkout error, missing approval
4. If caused by a snapshot dependency, follow the `additionalData` to the failed upstream build

## Scenario E: "The whole build chain is broken"

1. Get all builds in the chain: `snapshotDependency:(to:(id:CHAIN_TOP_BUILD),includeInitial:true),defaultFilter:false`
2. Find the **first** (most upstream) build with `status:FAILURE`
3. Investigate that build — it's the root cause
4. All downstream failures are likely cascade effects (snapshot dependency errors)

## Scenario F: "Build was fine yesterday, broken today"

1. Get last successful build for the configuration
2. Get all changes between last success and current failure
3. Correlate changed files with the failure area
4. Check if an infrastructure/agent change happened (compare `agent(name)`)

---

# Tips

- **Always request `details`, `logAnchor`, and `newFailure` explicitly** — they're excluded from default fields.
- **Use `defaultFilter:false`** when you need to see failed-to-start, canceled, or personal builds.
- **Start with `filter=errors`** when reading logs — expand to warnings and full log only if needed.
- **For composite builds, investigate the child builds** — the composite build itself has no log or steps.
- **For build chains, find the root upstream failure** — most downstream failures are just cascade effects.
- **Focus on `newFailure: true` tests** — pre-existing failures are less likely to be caused by recent changes.
- **Use `firstFailed`** on test occurrences to trace when a test first broke and what changes caused it.
- **Use `fields=count` first** on test/problem endpoints to understand the scale before fetching details.
- **Paginate wisely** — build logs and test lists can be very large. Use anchors and filters instead of reading everything.
        """.trimIndent()
    }

    override val uri = "teamcity://guides/build-failure-analysis"

    override val name = "Build Failure Analysis Guide"

    override val shortName = SETTINGS_NAME

    override val description =
        "Step-by-step playbook for AI agents diagnosing failed TeamCity builds and build chains. " +
        "Covers identifying root causes in build chains, reading build problems and test failures, " +
        "navigating build logs with anchors, analyzing changes, and handling composite builds, " +
        "failed-to-start builds, and cascading chain failures."

    override val mimeType = "text/markdown"

    override fun read(): String = CONTENT
}
