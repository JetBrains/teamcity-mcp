package jetbrains.buildServer.ai.mcp.resources.rest

internal object PipelineGuideContent {
    fun readOnly(): String = """
# TeamCity Pipelines Guide: Read-Only Mode

This guide teaches AI agents how to work with TeamCity Pipelines when MCP exposes read-only pipeline support.

TeamCity Pipelines are the modern YAML-based alternative to classic TeamCity build chains. When a user describes a CI workflow as a pipeline, staged job flow, or chain of dependent jobs, first consider whether they mean a TeamCity Pipeline rather than a traditional build-chain configuration assembled from individual build configurations and snapshot dependencies.

In this mode:
- `teamcity_pipeline_get` tool is available

Assumes familiarity with the **REST API Guide** for `fields`, locators, pagination, and response envelopes.
For deep diagnosis of a failing pipeline job after you know its underlying build id, switch to `teamcity://guides/build-failure-analysis`.

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

In MCP read-only mode:
- use `teamcity_pipeline_get` for GET requests under `/app/pipeline...`
- use `teamcity_rest_get` and `teamcity_rest_post` for `/app/rest/...`, including `/app/rest/pipelines...`

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

TeamCity exposes this split:
- `GET /app/pipeline?parentProjectExtId=...` lists pipelines for a project
- `GET /app/pipeline/{id}` reads one pipeline including its YAML
- `GET /app/pipeline/{id}/parameters` lists parameter names available to that pipeline
- `GET /app/pipeline/provider/vcs...` endpoints discover providers and repositories
- `POST /app/pipeline` creates a pipeline
- `POST /app/pipeline/{id}` updates a pipeline
- `POST /app/pipeline/schema/generate` generates YAML schema data
- `POST /app/rest/pipelines/{pipelineId}/run` triggers debug-style runs with modified YAML

So for agent reasoning:
- treat `/app/pipeline` as the main product API for authoring, discovery, and editor assistance
- treat `/app/rest/pipelines` as a secondary API for pipeline REST objects and selected runtime operations

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

# Running Pipelines

## Debug or remote-style pipeline run with custom YAML

Use `/app/rest/pipelines/{pipelineId}/run` for debug-style runs.
It sends:
- `debug`
- `yaml`
- optionally `branch`

This still goes through `/app/rest/pipelines`, not `/app/pipeline`.

Important caveats:
- the current MCP `teamcity_rest_post` tool may block this call if the REST POST allowlist does not include the endpoint
- if the MCP tool rejects the POST before it reaches TeamCity, report that as a tooling limitation, not as a pipeline validation failure

## Poll the pipeline run

```
path: /app/rest/pipelines/MyPipeline/run/123456
query: fields=number,build(id,state,status,statusText,branchName,finishOnAgentDate),jobs(job(id,name,build(id,state,status,statusText)))
```

When diagnosing failures:
- read the pipeline run first
- find the failed job entry and its underlying `build.id`
- then switch to `teamcity://guides/build-failure-analysis` for the ordinary build/problem/test/log workflow

---

# What Is Reachable Today

- list pipelines: `GET /app/pipeline?parentProjectExtId=...` via `teamcity_pipeline_get`
- get pipeline details and YAML: `GET /app/pipeline/{id}` via `teamcity_pipeline_get`
- get available pipeline parameters: `GET /app/pipeline/{id}/parameters`
- discover VCS providers, repositories, capabilities, and JDKs through `/app/pipeline/provider/...`
- get pipeline optimizations, run optimizations, run VCS roots, and personal notifications through `/app/pipeline/...`
- list branches: `GET /app/rest/pipelines/{id}/branches`
- get pipeline run: `GET /app/rest/pipelines/{id}/run/{runId}`
- inspect nested job builds through run details
- inspect build/test/problem/log data for pipeline jobs
- in some server configurations, trigger debug/custom-YAML runs via `POST /app/rest/pipelines/{id}/run`

---

# Agent Rules of Thumb

- Prefer `teamcity_pipeline_get` for product-style pipeline discovery, setup discovery, and editor reads.
- Prefer `teamcity_rest_get` for pipeline run summaries, branch lists, and job-level runtime inspection.
- If the task is "set up a pipeline", start by discovering the project context: existing pipelines, providers, repositories, and available parameters.
- For a failed pipeline run, pivot quickly from the pipeline run to the failing job build id.
    """.trimIndent()

    fun brave(): String = """
# TeamCity Pipelines Guide: Brave Mode

This guide teaches AI agents how to work with TeamCity Pipelines when MCP exposes both read and write-style pipeline operations.

TeamCity Pipelines are the modern YAML-based alternative to classic TeamCity build chains. When a user describes a CI workflow as a pipeline, staged job flow, or chain of dependent jobs, first consider whether they mean a TeamCity Pipeline rather than a traditional build-chain configuration assembled from individual build configurations and snapshot dependencies.

In this mode:
- `teamcity_pipeline_get` is available
- `teamcity_pipeline_post` is available
- `teamcity.ai.mcp.pipeline.post.allowed.paths` narrows POST access only when it is explicitly set

Assumes familiarity with the **REST API Guide** for `fields`, locators, pagination, and response envelopes.
For deep diagnosis of a failing pipeline job after you know its underlying build id, switch to `teamcity://guides/build-failure-analysis`.

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

In MCP brave mode:
- use `teamcity_pipeline_get` for GET requests under `/app/pipeline...`
- use `teamcity_pipeline_post` for POST requests under `/app/pipeline...`
- use `teamcity_rest_get` and `teamcity_rest_post` for `/app/rest/...`, including `/app/rest/pipelines...`

`/app/pipeline` support is independent from the REST tools because TeamCity handles these endpoints through multiple web controllers, not the single REST controller used for `/app/rest/...`.

## Safety model

- Pipeline support is controlled by `teamcity.ai.mcp.pipeline.enabled`
- Brave mode is controlled by `teamcity.ai.mcp.braveMode.enabled`
- when both are true, `teamcity_pipeline_get` and `teamcity_pipeline_post` are exposed
- if `teamcity.ai.mcp.pipeline.post.allowed.paths` is absent, all pipeline POST paths are enabled
- if `teamcity.ai.mcp.pipeline.post.allowed.paths` is present, only matching pipeline POST paths are enabled

Important nuance:
- several pipeline helper operations are POST endpoints even though they are logically "read-like"
- examples: repository branch discovery, test connection, versioned settings checks, schema generation, parameter resolution, compatibility checks, job descriptions
- those still require `teamcity_pipeline_post`
- if `teamcity.ai.mcp.pipeline.post.allowed.paths` is configured, they must also match that configured path set

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

TeamCity exposes this split:
- `GET /app/pipeline?parentProjectExtId=...` lists pipelines for a project
- `GET /app/pipeline/{id}` reads one pipeline including its YAML
- `GET /app/pipeline/{id}/parameters` lists parameter names available to that pipeline
- `GET /app/pipeline/provider/vcs...` endpoints discover providers and repositories
- `POST /app/pipeline` creates a pipeline
- `POST /app/pipeline/{id}` updates a pipeline
- `POST /app/pipeline/schema/generate` generates YAML schema data
- `POST /app/pipeline/repository/branches` resolves repository branches
- `POST /app/pipeline/{id}/parameters/resolve` resolves parameter references
- `POST /app/pipeline/{id}/job-descriptions`, `buildStepDescription`, and compatibility endpoints power editor assistance
- `POST /app/rest/pipelines/{pipelineId}/run` triggers debug-style runs with modified YAML

So for agent reasoning:
- treat `/app/pipeline` as the main product API for authoring, discovery, and editor assistance
- treat `/app/rest/pipelines` as a secondary API for pipeline REST objects and selected runtime operations

---

# Core Workflows

## 1. List pipelines in a project

```
path: /app/pipeline
query: parentProjectExtId=MyProject
```

## 2. Read one pipeline

```
path: /app/pipeline/MyPipeline
```

## 3. Read parameter names and setup context

Useful GET endpoints:

```
path: /app/pipeline/MyPipeline/parameters
```

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

Use these before creating or updating YAML so you know what repository, provider, parameter, and JDK choices are actually available.

## 4. Pipeline editor helpers

These are real TeamCity pipeline operations even though they are not classic CRUD:
- `POST /app/pipeline/schema/generate`
- `POST /app/pipeline/repository/branches`
- `POST /app/pipeline/repository/testConnection`
- `POST /app/pipeline/repository/checkVersionedSettings`
- `POST /app/pipeline/{id}/parameters/resolve`
- `POST /app/pipeline/{id}/job-descriptions`
- `POST /app/pipeline/{id}/buildStepDescription`
- `POST /app/pipeline/{id}/compatibility/agents`
- `POST /app/pipeline/{id}/compatibility/agents/{jobId}`
- `POST /app/pipeline/{id}/notifications`
- `POST /app/pipeline/{id}/kotlinDsl`

Because they are POST endpoints, they require:
1. pipeline support enabled
2. brave mode enabled
3. `teamcity_pipeline_post`
4. a matching configured path only when `teamcity.ai.mcp.pipeline.post.allowed.paths` is set

Important body-shape rule:
- create, update, and most helper endpoints expect the full draft pipeline object, not a tiny patch
- typical payloads include fields such as `name`, `yaml`, `vcsRoot`, `additionalVcsRoots`, `triggers`, `integrations`, `notifications`, and optional `versionedSettings`
- `parameters/resolve` is special: it wraps the draft pipeline as `{ "pipeline": <draft>, "scope": {...} }`

## 5. Pipeline create and update

Main write endpoints:
- `POST /app/pipeline`
- `POST /app/pipeline/{id}`

If a user asks for one of these, check the exposed POST path set first and avoid promising unsupported writes.

For new pipelines, the query usually includes the target project:

```
path: /app/pipeline
query: parentProjectExtId=MyProject
```

Minimal create/update payload shape:

```json
{
  "name": "My Pipeline",
  "yaml": "jobs:\\n  Job1:\\n    name: Build\\n    steps: []\\n",
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

## 6. Runtime-oriented GET helpers

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

---

# Running Pipelines

## Debug or remote-style pipeline run with custom YAML

Use `/app/rest/pipelines/{pipelineId}/run` for debug-style runs.
It sends:
- `debug`
- `yaml`
- optionally `branch`

Important caveats:
- this still depends on the regular REST POST allowlist, not pipeline brave mode alone
- if the MCP REST tool rejects the POST before it reaches TeamCity, report that as a tooling limitation

## Poll the pipeline run

```
path: /app/rest/pipelines/MyPipeline/run/123456
query: fields=number,build(id,state,status,statusText,branchName,finishOnAgentDate),jobs(job(id,name,build(id,state,status,statusText)))
```

When diagnosing failures:
- read the pipeline run first
- find the failed job entry and its underlying `build.id`
- then switch to `teamcity://guides/build-failure-analysis` for the ordinary build/problem/test/log workflow

---

# What Is Reachable vs Still Conditional

## Reachable in brave mode

- list pipelines: `GET /app/pipeline?parentProjectExtId=...` via `teamcity_pipeline_get`
- get pipeline details and YAML: `GET /app/pipeline/{id}` via `teamcity_pipeline_get`
- get available pipeline parameters: `GET /app/pipeline/{id}/parameters`
- discover VCS providers, repositories, capabilities, and JDKs through `/app/pipeline/provider/...`
- get pipeline optimizations, run optimizations, run VCS roots, and personal notifications through `/app/pipeline/...`
- use exposed `/app/pipeline` POST helpers and writes via `teamcity_pipeline_post`
- list branches: `GET /app/rest/pipelines/{id}/branches`
- get pipeline run: `GET /app/rest/pipelines/{id}/run/{runId}`
- inspect nested job builds through run details
- inspect build/test/problem/log data for pipeline jobs

## Still conditional or unsupported

- if `teamcity.ai.mcp.pipeline.post.allowed.paths` is configured, pipeline POST operations outside that set are blocked
- pipeline debug runs are blocked unless the regular REST POST allowlist allows `/app/rest/pipelines/.../run`
- `DELETE /app/pipeline/{id}` remains unsupported because MCP has no pipeline DELETE tool
- multipart upload flows such as pipeline SSH key upload remain unsupported through current MCP tooling

---

# Agent Rules of Thumb

- Prefer `teamcity_pipeline_get` for product-style pipeline discovery, setup discovery, and editor flows.
- If `teamcity.ai.mcp.pipeline.post.allowed.paths` is configured, use `teamcity_pipeline_post` only for matching paths.
- If the task is "set up a pipeline", start by discovering the project context: existing pipelines, providers, repositories, and available parameters.
- Before calling a POST helper, decide whether it needs a full draft-pipeline body or a smaller helper payload.
- Prefer `teamcity_rest_get` for pipeline run summaries, branch lists, and job-level runtime inspection.
- For a failed pipeline run, pivot quickly from the pipeline run to the failing job build id.
- Check pipeline support and brave mode before promising validation or updates, and also check the pipeline POST path set when it is configured.
    """.trimIndent()
}
