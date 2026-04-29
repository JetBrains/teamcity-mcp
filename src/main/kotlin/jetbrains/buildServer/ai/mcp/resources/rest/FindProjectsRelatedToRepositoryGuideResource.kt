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
1. Find projects that own the matching VCS root
2. Find build configurations that build from the matching VCS root and projects that own them
```

---

# Step 1: Find projects that own the matching VCS root

Start by getting a list of VCS roots that use the repository URL. Most VCS root types (git, SVN, TFS) use 'url' property to store the repository URL,
while perforce uses 'port' and optionally 'stream'.

For URL matching, extract organisation and repository name values from requested url and add matchType inside the property sub-locator:
property:(name:url,value:org/repo,matchType:contains)
property:(name:url,value:.*org/repo(.git)?,matchType:matches)

```
path: /app/rest/vcs-roots
query: locator=property:(name:url,value:),count:100&fields=nextHref,vcs-root(id,name,vcsName,project(id,name),properties(property(name,value)))
```

# Step 2: Find build configurations that build from the matching VCS root and projects that own them

Use the build types API to find build configurations that use related VCS roots and projects that own build configurations.

```
path: /app/rest/buildTypes
query: locator=vcsRoot:(property:(name:url,value:)),count:100&fields=buildType(id,name,projectName)
```

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