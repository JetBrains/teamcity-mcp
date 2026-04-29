package jetbrains.buildServer.ai.mcp.resources.rest

import jetbrains.buildServer.ai.mcp.resources.McpResource
import org.springframework.stereotype.Component

@Component
class FindBuildConfigurationsByRepositoryUrlGuideResource : McpResource {
    companion object {
        const val SETTINGS_NAME = "find_build_configurations_by_repository_url_guide"
        private val CONTENT = ("""
# Find build configurations related to a repository guide
            
A step-by-step playbook for finding TeamCity build configurations that use a given repository.
Assumes familiarity with the tools — see the **REST API Guide** for tool parameters, field syntax, locators, pagination, and response format.

---

# Quick Reference: Find build configurations related to a repository workflow

```
1. Find build configurations that build from the matching VCS root and projects that own them
2. Display results in a table
```

---

# Step 1: Find build configurations that build from the matching VCS root and projects that own them

Use the build types API to find build configurations that use matching VCS roots and the projects that own them.
Since repositories can be connected via both HTTPS and SSH, search using just the org/repo part of the URL.
The `matchType:contains` locator will match both `https://host/org/repo.git` and `git@host:org/repo.git`.

```
path: /app/rest/buildTypes
query: locator=vcsRoot:(property:(name:url,value:org/repo,matchType:contains)),count:100&fields=buildType(id,name,project(id,name))
```

If the repository is hosted on Azure DevOps, the URL will look like this: https://dev.azure.com/{organization}/{project}/_git/{repository}.
Use the following locator:

```
path: /app/rest/buildTypes
query: locator=vcsRoot:(property:(name:url,value:{organization}/{project}/_git/{repository},matchType:contains)),count:100&fields=buildType(id,name,project(id,name))
```

If the repository uses Perforce as the version control system, use the following locator:

```
path: /app/rest/buildTypes
query: locator=vcsRoot:(property:(name:port,value:host:port,matchType:contains)),count:100&fields=buildType(id,name,project(id,name))
```

# Step 2: Display results in a table

When presenting results, display the following columns:
- Build configuration ID
- Build configuration name
- Project ID
- Project name

Optionally, for finding projects that own the matching VCS roots, use the VCS roots API:

```
path: /app/rest/vcs-roots
query: locator=property:(name:url,value:org/repo,matchType:contains),count:100&fields=nextHref,vcs-root(id,name,vcsName,project(id,name),properties(property(name,value)))
```

If the repository is hosted on Azure DevOps, the URL will look like this: https://dev.azure.com/{organization}/{project}/_git/{repository}.
Use the following locator:

```
path: /app/rest/vcs-roots
query: locator=property:(name:url,value:{organization}/{project}/_git/{repository},matchType:contains),count:100&fields=nextHref,vcs-root(id,name,vcsName,project(id,name),properties(property(name,value)))
```

If the repository uses Perforce as the version control system, use the following locator:

```
path: /app/rest/vcs-roots
query: locator=property:(name:port,value:host:port,matchType:contains),count:100&fields=nextHref,vcs-root(id,name,vcsName,project(id,name),properties(property(name,value)))
```

""").trimIndent()
    }

    override val uri = "teamcity://guides/build_configurations_by_repository_url"

    override val name = "Find Build Configurations by Repository URL Guide"

    override val shortName = SETTINGS_NAME

    override val description =
        "Step by step guide to find build configurations with VCS roots that match a repository URL"

    override val mimeType = "text/markdown"

    override fun read(): String = CONTENT
}