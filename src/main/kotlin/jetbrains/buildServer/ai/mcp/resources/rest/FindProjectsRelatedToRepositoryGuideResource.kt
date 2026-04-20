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
1. Get projects with VCS roots                                          → GET projects (id, parentProjectId, vcsRoots, buildTypes)
2. Iterate through VCS roots and compare url property to repository URL → VCS roots for project (id, properties)
3. Assemble results                                                     → Display all projects in tree hierarchy and highlight related projects
```

---

# Step 1: Get projects

Start by getting a list of projects in the TeamCity instance using the `GET projects` endpoint. The result can be paginated, so be sure to handle pagination if necessary.

```
path: /app/rest/projects
```

Key fields of project to examine:
- **`id`** — The public project ID. If not specified, TeamCity generates one by removing all non-alphanumeric characters from the project name.
- **`parentProjectId`** — The ID of a TeamCity project that owns this project. Returns '_Root' if this is project resides on the topmost level.
- **`vcsRoots`** — The list of VCS roots owned by this project.
- **`buildTypes`** — The list of build configurations (build types) owned directly by this project - does not include configurations owned by subprojects.
- **`pipelines`** — The list of pipelines owned directly by this project - does not include pipelines owned by subprojects.

Important for displaying/organizing output
- **`archived`** — Returns **true** if the project is archived; otherwise, **false**.
- **`webUrl`** — The URL of the project
- **`name`** — The public project name
- **`description`** — The optional project description, or **null** if none was set.


# Step 2: Examine VCS roots of each project. A project can contain more VCS roots than are available initially, so be sure to fetch other pages if necessary.

Key fields of VCS root to examine:
- **`id`** — The public VCS root ID
- **`name`** — The public VCS root name
- **`vcsName`** — The VCS type (e.g., Git, SVN, Mercurial)
- **`properties`** — A map of properties for the VCS root, including 'url' and other configuration details

A VCS root should in its `properties` map have a key 'url' that matches the repository URL. If it does, the project is related to the repository.

# Step 3: Assemble the results

For better user experience and easier navigation, organize the results into a tree structure.
The projects that are fetched in the Step 1 have contain `id` and `parentProjectId` fields, use them to identify children of each project.
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