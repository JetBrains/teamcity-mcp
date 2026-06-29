package jetbrains.buildServer.ai.mcp.resources.rest

internal object PipelineGuideContent {
    fun readOnly(): String = """
# TeamCity Pipelines Guide

This guide teaches AI agents how to work with TeamCity Pipelines.

TeamCity Pipelines are the modern YAML-based alternative to classic TeamCity build chains. When a user describes a CI workflow as a pipeline, staged job flow, or chain of dependent jobs, first consider whether they mean a TeamCity Pipeline rather than a traditional build-chain configuration assembled from individual build configurations and snapshot dependencies.

Available tools: `teamcity_pipeline_get` (for `/app/pipeline` endpoints), `teamcity_rest_get` (for `/app/rest/pipelines` endpoints).

For build failure diagnosis after you have a job's build id, read the resource `teamcity://guides/build-failure-analysis`.

---

# Capability Split

Pipeline operations are split across two TeamCity HTTP surfaces:

- **Primary pipeline controller API** under `/app/pipeline...`
  Use this for normal pipeline CRUD, setup discovery, and editor-assistance workflows.
- **Pipeline REST API** under `/app/rest/pipelines...`
  Use this more selectively, mainly for debug runs and some pipeline-scoped SSH key operations.

Important naming note:
- the main controller path is **`/app/pipeline`** (singular), not `/app/pipelines`
- the REST API path is **`/app/rest/pipelines`** (plural)

Use `teamcity_pipeline_get` for GET requests under `/app/pipeline...`, and `teamcity_rest_get` for `/app/rest/...` reads (including `/app/rest/pipelines...`).
NEVER call `teamcity_pipeline_get` with `/app/rest...`; use `teamcity_rest_get` for every `/app/rest...` path.

---

# How Agents Should Think About Pipelines

Use pipeline reasoning when the task is about:
- a YAML file that defines jobs and dependencies
- a staged CI flow that would otherwise be modeled as a build chain
- a pipeline run, pipeline job, or pipeline-specific editor assistance

For mixed cases:
- start from the pipeline if the user says "pipeline", "pipeline run", or refers to pipeline YAML
- then pivot to ordinary build-level investigation once you have a failing job build id

---

# TeamCity Pipeline Model

A TeamCity pipeline is a project-owned YAML configuration. In the REST model:
- the pipeline has an **external id** (`id`)
- the backing project also has an **internal id** (`internalId`)
- the pipeline has a **head build type**
- the YAML can be returned inline from the pipeline resource
- jobs are derived from the YAML

Conceptually:
- **Pipelines** are the YAML-first alternative to old-school TeamCity build chains.
- **Pipeline jobs** play the role that individual build configurations often play in a classic chain.
- **Pipeline runs** should be analyzed first as pipeline objects, then as underlying job builds when deeper diagnostics are needed.

Key GET endpoints:
- `GET /app/pipeline?parentProjectExtId=...` lists pipelines for a project
- `GET /app/pipeline/{id}` reads one pipeline including its YAML
- `GET /app/pipeline/{id}/parameters` lists parameter names (may return 400 on some versions)
- `GET /app/pipeline/provider/vcs...` endpoints discover providers and repositories
- `GET /app/rest/pipelines/{id}/branches` lists branches
- `GET /app/rest/pipelines/{id}/run/{runId}` reads a pipeline run

---

# Read Workflows

## 1. List pipelines in a project

Use `teamcity_pipeline_get` on `/app/pipeline`.

```
path: /app/pipeline
query: parentProjectExtId=MyProject
```

## 2. Read one pipeline

```
path: /app/pipeline/MyPipeline
```

This returns the pipeline object, including YAML and repository/settings metadata.

If you specifically need a narrow REST projection, you can still use:
```
path: /app/rest/pipelines/MyPipeline
query: fields=id,name,internalId,parentProject(id,name),headBuildType(id,name),jobs(job(id,name))
```

## 3. Read parameter names already available to the pipeline

```
path: /app/pipeline/MyPipeline/parameters
```

Use this before adding jobs or expressions that reference `%parameter.name%`.

## 4. Discover available VCS providers, repositories, capabilities, and JDKs

These GET endpoints are useful when the task is "set up a pipeline" and the agent needs to understand the environment before proposing YAML or VCS wiring:

```
path: /app/pipeline/provider/vcs
query: parentProjectExtId=MyProject
```

```
path: /app/pipeline/provider/vcs/types
query: parentProjectExtId=MyProject
```

```
path: /app/pipeline/provider/vcs/MyConnection/repositories
query: parentProjectExtId=MyProject&q=my-repo&pageSize=20
```

```
path: /app/pipeline/provider/vcs/MyConnection/capabilities
query: parentProjectExtId=MyProject
```

```
path: /app/pipeline/provider/jdk
```

## 5. Inspect runtime-oriented pipeline helpers

Useful GET endpoints under `/app/pipeline` include:

```
path: /app/pipeline/MyPipeline/optimizations
query: branch=refs/heads/main
```

```
path: /app/pipeline/MyPipeline/123456/optimizations
```

```
path: /app/pipeline/MyPipeline/123456/vcsRoots
```

```
path: /app/pipeline/MyPipeline/notifications
```

These are not generic REST endpoints; they come from pipeline web controllers and should be read through `teamcity_pipeline_get`.

## 6. List branches from runtime history or REST state

If branch names are already known from runtime history, use REST:

```
path: /app/rest/pipelines/MyPipeline/branches
query: fields=branch(name,default)
```

---

# Inspecting Pipeline Runs

## Retrieving pipeline run data

Use `teamcity_rest_get` for pipeline run data:

```
path: /app/rest/pipelines/MyPipeline/run/123456
query: fields=number,build(id,state,status,statusText,branchName,finishOnAgentDate),jobs(job(id,name,build(id,state,status,statusText)))
```

## Finding the latest pipeline run

To find the latest pipeline run, you need the pipeline's head build type id.
If you do not know the pipeline's head build type id yet, use:

```
path: /app/rest/pipelines/MyPipeline
query: fields=headBuildType(id)
```

When you know the head build type id, use:

```
path: /app/rest/builds
query: locator=buildType:(id:MyPipelineHeadBuildTypeId),branch:default:any,state:any,count:1&fields=build(id)
```

Use the returned `build[0].id` as `{runId}`. `state:any` includes queued, running, and finished runs.
Normal defaults still exclude personal, canceled, failed-to-start, and non-default-branch builds 
unless you add `defaultFilter:false` or explicit dimensions such as `personal:any`, `canceled:any`, or `failedToStart:any`.

## Diagnosing run failures

- read the pipeline run first to find the failed job and its `build.id`
- then use `teamcity://guides/build-failure-analysis` for build-level investigation
    """.trimIndent()

    fun brave(): String = """
# TeamCity Pipelines Guide

TeamCity Pipelines are YAML-based CI configurations (alternative to classic build chains). Use pipeline reasoning when the user says "pipeline", refers to pipeline YAML, or describes a staged job flow.

Available tools: `teamcity_pipeline_get`, `teamcity_pipeline_post`, `teamcity_pipeline_delete`, `teamcity_rest_get`, `teamcity_rest_post`.
If `teamcity.ai.mcp.pipeline.post.allowed.paths` is set, only matching POST paths work.

NEVER call `teamcity_pipeline_get` with `/app/rest...`; use `teamcity_rest_get` for every `/app/rest...` GET path.

For build failure diagnosis after you have a job's build id, read the resource `teamcity://guides/build-failure-analysis`.

---

# Two API Surfaces

| Surface | Tool | Use for |
|---------|------|---------|
| `/app/pipeline` (singular) | `teamcity_pipeline_get` / `teamcity_pipeline_post` | CRUD, discovery, editor helpers |
| `/app/rest/pipelines` (plural) | `teamcity_rest_get` / `teamcity_rest_post` (POST may require allowlist) | Pipeline runs, branches, debug runs |

Note: many "read-like" helpers (compatibility checks, schema generation) are POST endpoints and require `teamcity_pipeline_post`.

---

# End-to-End: Create and Run a Pipeline

1. **Discover context:** `GET /app/pipeline/provider/vcs?parentProjectExtId=...` (connections), `GET /app/pipeline/provider/jdk` (JDKs)
2. **Create:** `POST /app/pipeline?parentProjectExtId=...` with a full pipeline draft (see Create section). Response returns the pipeline `id`.
3. **Verify:** `POST /app/pipeline/{id}/compatibility/agents` with the same full pipeline draft as the body — check all jobs show `compatible: true`.
4. **Trigger:** `POST /app/rest/buildQueue` via `teamcity_rest_post` with `{"buildType": {"id": "<pipeline-id>"}}`. The response contains the build `id` — this is the pipeline run id.
5. **Monitor:** `GET /app/rest/builds/id:<buildId>` via `teamcity_rest_get` for high-level status. For per-job details: `GET /app/rest/pipelines/<pipeline-id>/run/<buildId>` with fields including `jobs(job(id,name,build(id,state,status,statusText)))`. On failure, see Diagnosing Failed Runs below.

---

# YAML Authoring

Step field names map to TeamCity runner parameters. Key rules:
- Every step MUST have a `type` field — omitting it causes a parse error.
- Valid step types include: `script`, `gradle`, `maven`, `dotnet`, `docker`, `node-js`, etc. Use `POST /app/pipeline/schema/generate?pipelineId=...` to get the full list. **Warning:** unrecognized type values (e.g. `command-line`) are silently accepted but produce a build configuration with zero steps — the pipeline will appear valid but builds will never run. Always verify the type against the schema.
- For command-line steps, use `type: script` with field `script-content` (NOT `script`). Wrong name → empty runner parameter → zero compatible agents.
- Use `POST /app/pipeline/{id}/compatibility/agents` to verify steps are compatible with available agents before triggering a run. This endpoint requires the full pipeline draft as the request body (it does not use the stored pipeline definition).

Single-job example:

```yaml
jobs:
  Build:
    name: My Build
    steps:
      - name: Run tests
        type: script
        script-content: |
          echo "Running tests"
          ./gradlew test
```

Multi-job example with dependencies:

```yaml
jobs:
  Build:
    name: Build
    steps:
      - name: Compile
        type: script
        script-content: ./gradlew build
  Test:
    name: Test
    depends-on:
      - Build
    steps:
      - name: Run tests
        type: script
        script-content: ./gradlew test
  Deploy:
    name: Deploy
    depends-on:
      - Test
    steps:
      - name: Deploy
        type: script
        script-content: echo "Deploying..."
```

---

# Create and Update

## Create

```
path: /app/pipeline
query: parentProjectExtId=MyProject
```

A **full pipeline draft** — used for create, update, and most POST helper endpoints:

```json
{
  "name": "My Pipeline",
  "yaml": "jobs:\\n  Job1:\\n    name: Build\\n    steps:\\n      - name: Run\\n        type: script\\n        script-content: echo hello\\n",
  "vcsRoot": {
    "url": "https://example.com/org/repo.git",
    "branch": "refs/heads/main",
    "vcsName": "jetbrains.git"
  },
  "additionalVcsRoots": [],
  "triggers": [],
  "integrations": [],
  "notifications": []
}
```

The response returns the created pipeline object including its assigned `id` (auto-generated from the name in PascalCase, e.g. "My Pipeline" → `MyPipeline`) — use this `id` for all subsequent calls.

**YAML escaping:** The `yaml` value is a JSON string. Build the YAML content first, then JSON-encode it (newlines become `\n`, quotes become `\"` — standard JSON string escaping). Preserve YAML indentation.

**Note:** TeamCity uses POST (not PUT) for pipeline updates.

## Update

**Always read the pipeline first** with `GET /app/pipeline/{id}`. The GET response vcsRoot looks like:

```json
"vcsRoot": {
  "id": "MyProject_HttpsGithubComOrgRepoGitRefsHeadsMain",
  "isExternal": true,
  "url": "https://github.com/org/repo.git",
  "branch": "refs/heads/main",
  "vcsName": "jetbrains.git",
  ...
}
```

If `isExternal` is `true`, you MUST include `externalVcsRootId` in the update payload, set to `vcsRoot.id` from the GET response. Without it, the server interprets the vcsRoot as a new internal root and rejects the update with a 500 error.

**Important:** GET returns `id`, but POST expects `externalVcsRootId` — different field names, same value.

Update with **external** VCS root (most common when no VCS connection is configured):

```json
{
  "name": "My Pipeline",
  "yaml": "...",
  "vcsRoot": {
    "externalVcsRootId": "<vcsRoot.id from GET response>",
    "url": "https://example.com/org/repo.git",
    "branch": "refs/heads/main",
    "vcsName": "jetbrains.git"
  },
  "additionalVcsRoots": [],
  "triggers": [],
  "integrations": [],
  "notifications": []
}
```

Update with **internal** VCS root (has a VCS connection):

```json
{
  "name": "My Pipeline",
  "yaml": "...",
  "vcsRoot": {
    "url": "https://example.com/org/repo.git",
    "branch": "refs/heads/main",
    "vcsName": "jetbrains.git",
    "connectionId": "MyConnection"
  },
  "additionalVcsRoots": [],
  "triggers": [],
  "integrations": [],
  "notifications": []
}
```

If an update returns 500, the most common cause is a missing `externalVcsRootId` for external VCS roots.

---

# Diagnosing Failed Pipeline Runs

1. The build `id` returned from `POST /app/rest/buildQueue` when you triggered the pipeline is also the pipeline run id.
2. Get the pipeline run: `GET /app/rest/pipelines/{pipelineId}/run/{runId}` via `teamcity_rest_get` with fields including `jobs(job(id,name,build(id,state,status,statusText)))`
3. Find the failed job and its `build.id`
4. Use `teamcity://guides/build-failure-analysis` for build-level investigation (build log, test failures, problem occurrences)

---

# Endpoint Reference

## GET — via `teamcity_pipeline_get`

| Endpoint | Purpose |
|----------|---------|
| `/app/pipeline?parentProjectExtId=...` | List pipelines in project |
| `/app/pipeline/{id}` | Read pipeline with YAML |
| `/app/pipeline/{id}/parameters` | Available parameter names (may return 400 on some versions) |
| `/app/pipeline/{id}/optimizations?branch=...` | Build optimizations |
| `/app/pipeline/{id}/{runId}/optimizations` | Run optimizations |
| `/app/pipeline/{id}/{runId}/vcsRoots` | Run VCS roots |
| `/app/pipeline/{id}/notifications` | Notification settings |
| `/app/pipeline/provider/vcs?parentProjectExtId=...` | VCS connections |
| `/app/pipeline/provider/vcs/types?parentProjectExtId=...` | VCS provider types |
| `/app/pipeline/provider/vcs/{connId}/repositories?parentProjectExtId=...&q=...` | Search repos |
| `/app/pipeline/provider/vcs/{connId}/capabilities?parentProjectExtId=...` | Connection capabilities |
| `/app/pipeline/provider/jdk` | Available JDKs |

## POST — via `teamcity_pipeline_post`

All POST helper endpoints (compatibility, schema, job-descriptions, etc.) require the full pipeline draft as the request body — they do not read the stored pipeline definition. Sending an empty body `{}` returns 500.

| Endpoint | Purpose | Body |
|----------|---------|------|
| `/app/pipeline?parentProjectExtId=...` | Create pipeline | Full pipeline draft |
| `/app/pipeline/{id}` | Update pipeline | Full pipeline draft |
| `/app/pipeline/{id}/compatibility/agents` | Check agent compatibility | Full pipeline draft |
| `/app/pipeline/{id}/compatibility/agents/{jobId}` | Check per-job compatibility | Full pipeline draft |
| `/app/pipeline/{id}/job-descriptions` | Job descriptions | Full pipeline draft |
| `/app/pipeline/{id}/kotlinDsl` | Generate Kotlin DSL | Full pipeline draft |
| `/app/pipeline/schema/generate?pipelineId=...` | YAML schema | Full pipeline draft |
| `/app/pipeline/repository/branches` | Discover branches (requires VCS connectivity) | Full pipeline draft |
| `/app/pipeline/repository/testConnection` | Test VCS connection (requires VCS connectivity) | Full pipeline draft |
| `/app/pipeline/repository/checkVersionedSettings` | Check versioned settings (requires VCS connectivity) | Full pipeline draft |

The `repository/*` endpoints require working VCS connectivity — they return 500 for pipelines with external VCS roots that have no stored credentials.

## DELETE — via `teamcity_pipeline_delete`

| Endpoint | Purpose |
|----------|---------|
| `/app/pipeline/{id}` | Delete pipeline and its backing project (permanent) |

## REST — via `teamcity_rest_get` / `teamcity_rest_post`

| Endpoint | Purpose |
|----------|---------|
| `GET /app/rest/pipelines/{id}/branches` | List branches |
| `GET /app/rest/pipelines/{id}/run/{runId}` | Pipeline run details |
| `POST /app/rest/pipelines/{id}/run` | Trigger debug run (requires REST POST allowlist) |
| `POST /app/rest/buildQueue` | Standard pipeline trigger: `{"buildType": {"id": "<pipeline-id>"}}` |

Poll a run with: `fields=number,build(id,state,status,statusText,branchName,finishOnAgentDate),jobs(job(id,name,build(id,state,status,statusText)))`

**Note:** Job status lives inside `job.build`, not on the job object itself. Using `jobs(job(id,status))` will return empty fields — always nest through `job.build`.

---

# Limitations

- Debug runs via `POST /app/rest/pipelines/{id}/run` — requires REST POST allowlist entry (by default only `/app/rest/buildQueue` is allowed)
- SSH key upload — multipart, not supported through MCP
- If `teamcity.ai.mcp.pipeline.post.allowed.paths` is configured, unlisted POST paths are blocked
    """.trimIndent()
}
