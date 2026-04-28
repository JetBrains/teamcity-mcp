package jetbrains.buildServer.ai.mcp.resources.rest

import jetbrains.buildServer.ai.mcp.resources.McpResource
import org.springframework.stereotype.Component

@Component
class FindProjectsRelatedToRepositoryGuideResource : McpResource {
    companion object {
        const val SETTINGS_NAME = "find_projects_related_to_repository_guide"
        private val CONTENT = ("""
Find projects and build configurations related to a repository guide
            
A step-by-step playbook for finding TeamCity projects that use a given repository.
Assumes familiarity with the tools — see the **REST API Guide** for tool parameters, field syntax, locators, pagination, and response format.

---

# Quick Reference: Find projects related to repository Workflow

```
1. Get VCS roots
2. Examine the VCS root results for projects
3. Collect build types and pipelines
4. Assemble results                                                     → Display all projects in tree hierarchy and highlight related projects
```

---

# Step 1: Get VCS roots

Start by getting a list of VCS roots that use the repository URL. Most VCS root types (git, SVN, TFS) use 'url' property to store the repository URL,
while perforce uses 'port' and optionally 'stream'. The result can be paginated, so be sure to handle pagination if necessary.

Key fields of VCS root to examine:
- **`id`** — The public VCS root ID
- **`name`** — The public VCS root name
- **`vcsName`** — The VCS type (e.g., Git, SVN, TFS, Perforce)
- **`project`** — The TeamCity project that owns this VCS root.
- **`properties`** — A map of properties for the VCS root, including 'url' and other configuration details

```
path: /app/rest/vcs-roots?locator=property:(name:url,value:{requested_url_or_part_url},matchType:contains)&fields=nextHref,vcs-root(id,name,vcsName,project(id),properties(property(name,value)))
```

# Step 2: Get projects of VCS roots results
From VCS roots collected in the previous step, use the 'project' object. The result can be paginated, so be sure to handle pagination if necessary.

Key fields of project to examine:
- **`id`** — The public project ID. If not specified, TeamCity generates one by removing all non-alphanumeric characters from the project name.
- **`parentProjectId`** — The ID of a TeamCity project that owns this project. Returns '_Root' if this project resides on the topmost level.
- **`vcsRoots`** — The list of VCS roots owned by this project.
- **`buildTypes`** — The list of build configurations (build types) owned directly by this project - does not include configurations owned by subprojects.
- **`pipelines`** — The list of pipelines owned directly by this project - does not include pipelines owned by subprojects.

# Step 3: Collect build types and pipelines
From projects collected in the previous step, fields 'buildTypes' and 'pipelines' can be used to collect build configurations that use the repository.
The result can be paginated, so be sure to handle pagination if necessary.

Alternatively, the build types API can be used to find build configurations that use related VCS roots. The result can be paginated, so be sure to handle pagination if necessary.

```
path: /app/rest/buildTypes?locator=vcsRoot:(id:{VCS_root_id})&fields=buildType(id,name,projectName)
```

Alternate method: If any of previous steps produces insufficient results, try this method:

1(alt). Get projects with VCS roots                                          → GET projects (id, parentProjectId, vcsRoots, buildTypes)
2(alt). Iterate through VCS roots and compare url property to repository URL → VCS roots for project (id, properties)
3(alt): Collect build types and pipelines

Step 1(alt): Get all projects using the `GET projects` endpoint.
The result can be paginated, so be sure to handle pagination if necessary.

```
path: /app/rest/projects
```

Key fields of project to examine:
- **`id`** — The public project ID. If not specified, TeamCity generates one by removing all non-alphanumeric characters from the project name.
- **`parentProjectId`** — The ID of a TeamCity project that owns this project. Returns '_Root' if this project resides on the topmost level.
- **`vcsRoots`** — The list of VCS roots owned by this project.
- **`buildTypes`** — The list of build configurations (build types) owned directly by this project - does not include configurations owned by subprojects.
- **`pipelines`** — The list of pipelines owned directly by this project - does not include pipelines owned by subprojects.

Important for displaying/organizing output
- **`archived`** — Returns **true** if the project is archived; otherwise, **false**.
- **`webUrl`** — The URL of the project
- **`name`** — The public project name
- **`description`** — The optional project description, or **null** if none was set.

# Step 2(alt): Examine VCS roots of each project. A project can contain more VCS roots than are available initially, so be sure to fetch other pages if necessary.

Key fields of VCS root to examine:
- **`id`** — The public VCS root ID
- **`name`** — The public VCS root name
- **`vcsName`** — The VCS type (e.g., Git, SVN, TFS, Perforce)
- **`properties`** — A map of properties for the VCS root, including 'url' and other configuration details

A VCS root should have a key 'url' in its 'properties' map that matches the repository URL. If it does, the project is related to the repository.

# Step 3(alt): Collect build types and pipelines
Cross reference projects collected in the step 1(alt), with VCS roots that have a matching 'url' and use fields 'buildTypes' and 'pipelines'
 to collect build configurations that use the repository. The result can be paginated, so be sure to handle pagination if necessary.

Alternatively, the build types API can be used to find build configurations that use related VCS roots.
The result can be paginated, so be sure to handle pagination if necessary.

```
path: /app/rest/buildTypes?locator=vcsRoot:(id:{VCS_root_id})&fields=buildType(id,name,projectName)
```

# Step 4: Assemble the results

For better user experience and easier navigation, organize the results into a tree structure.
The projects that are fetched contain `id` and `parentProjectId` fields, use them to identify children of each project.
The tree structure should contain all projects, with highlighted nodes representing the projects that are related to the repository.

        """).trimIndent()
    }

    override val uri = "teamcity://guides/projects-related-to-repository"

    override val name = "Find Projects Related to Repository Guide"

    override val shortName = SETTINGS_NAME

    override val description =
        "Step by step guide to find projects related to repository by comparing VCS root fetch URLs."

    override val mimeType = "text/markdown"

    override fun read(): String = CONTENT
}