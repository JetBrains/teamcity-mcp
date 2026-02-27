package jetbrains.buildServer.ai.mcp.resources.rest

import jetbrains.buildServer.ai.mcp.BUILD_QUEUE_PATH
import jetbrains.buildServer.ai.mcp.resources.McpResource
import org.springframework.stereotype.Component

@Component
class RestPostGuideResource : McpResource {

    companion object {
        const val SETTINGS_NAME = "rest_post_guide"

        private val CONTENT = """
# TeamCity REST POST Guide

This guide teaches you how to use the `teamcity_rest_post` tool to trigger actions in TeamCity.

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
query: locator=status:FAILURE&fields=testOccurrence(name,details)
```

## Response Format

Same JSON envelope as `teamcity_rest_get`:

```json
{"meta": {"url": "$BUILD_QUEUE_PATH", "statusCode": 200, "notes": ["\"personal\": true was enforced in the request body."]}, "contentType": "application/json", "body": {"id": 12345, "personal": true, "buildType": {"id": "MyProject_Build"}}}
```

- `meta.notes` will always include a reminder that personal mode was enforced.
- Use `fields` to control response size.

## Error Recovery

| Status | Meaning | Action |
|--------|---------|--------|
| 400 | Bad request body | Check JSON structure. Verify `buildType.id` exists. |
| 403 | Access denied | Your token may lack permission to queue builds for this configuration. |
| 404 | Not found | The build configuration ID may be wrong. Use `teamcity_rest_get` to verify it. |
| 409 | Conflict | The build may already be queued or there's a state conflict. |
| 5xx | Server error | Simplify the request and retry. |

## Tips

- **Always use `fields`** in your request to keep the response manageable.
- **Find the build config ID first** using `teamcity_rest_get` before triggering a build.
- **Use `branchName`** to target a specific branch.
- **Add a `comment`** to document why the build was triggered.
- **Monitor after triggering** — use `teamcity_rest_get` with the returned build ID to track progress.
        """.trimIndent()
    }

    override val uri = "teamcity://guides/rest-post"

    override val name = "TeamCity REST POST Guide"

    override val shortName = SETTINGS_NAME

    override val description =
        "Guide for AI agents on using the teamcity_rest_post tool to trigger personal builds via the TeamCity REST API. " +
        "Covers request body format, workflows, and error recovery."

    override val mimeType = "text/markdown"

    override fun read(): String = CONTENT
}
