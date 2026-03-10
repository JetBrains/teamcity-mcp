package jetbrains.buildServer.ai.mcp.tests.smoke

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import jetbrains.buildServer.ai.mcp.framework.TestMcpClient
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Integration tests verifying REST API guide accuracy against a real TeamCity server.
 *
 * Covers:
 * - String matching condition syntax (value/matchType/ignoreCase) on build locator dimensions
 * - Name case sensitivity across projects, buildTypes, agents
 * - Endpoint path correctness (vcs-root-instances vs vcsRootInstances)
 * - Key endpoint patterns from the guide
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestApiGuideTest : McpIntegrationTestBase() {

    // ==================================================================
    // String matching condition syntax on build locator
    // ==================================================================

    @Nested
    inner class BuildLocatorStringMatching {

        @Test
        fun `number - starts-with condition returns matching builds`() {
            mcpClient().withSession {
                // Get a real build number prefix to search for
                val seedResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,count:1&fields=build(id,number)"
                ))
                assertFalse(seedResult.isError, "Seed query failed: ${seedResult.content}")
                val seedBuild = extractBody(seedResult)["build"]?.jsonArray?.firstOrNull()
                    ?: return@withSession println("  ⚠ No builds found, skipping")
                val fullNumber = seedBuild.jsonObject["number"]!!.jsonPrimitive.content
                val prefix = fullNumber.take(3)

                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,number:(value:$prefix,matchType:starts-with),count:5&fields=build(id,number)"
                ))
                assertFalse(result.isError, "starts-with query failed: ${result.content}")
                val builds = extractBody(result)["build"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(builds.isNotEmpty(), "Should find builds starting with '$prefix'")
                builds.forEach { build ->
                    val num = build.jsonObject["number"]!!.jsonPrimitive.content
                    assertTrue(num.startsWith(prefix),
                        "Build number '$num' should start with '$prefix'")
                }
                println("  ✓ number starts-with '$prefix': ${builds.size} build(s)")
            }
        }

        @Test
        fun `number - contains condition returns matching builds`() {
            mcpClient().withSession {
                val seedResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,count:1&fields=build(id,number)"
                ))
                assertFalse(seedResult.isError)
                val seedBuild = extractBody(seedResult)["build"]?.jsonArray?.firstOrNull()
                    ?: return@withSession println("  ⚠ No builds found, skipping")
                val fullNumber = seedBuild.jsonObject["number"]!!.jsonPrimitive.content
                // Use middle part of build number as substring
                val mid = if (fullNumber.length >= 3) fullNumber.substring(1, fullNumber.length - 1) else fullNumber

                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,number:(value:$mid,matchType:contains),count:5&fields=build(id,number)"
                ))
                assertFalse(result.isError, "contains query failed: ${result.content}")
                val builds = extractBody(result)["build"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(builds.isNotEmpty(), "Should find builds containing '$mid'")
                builds.forEach { build ->
                    val num = build.jsonObject["number"]!!.jsonPrimitive.content
                    assertTrue(num.contains(mid),
                        "Build number '$num' should contain '$mid'")
                }
                println("  ✓ number contains '$mid': ${builds.size} build(s)")
            }
        }

        @Test
        fun `number - ends-with condition returns matching builds`() {
            mcpClient().withSession {
                val seedResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,count:1&fields=build(id,number)"
                ))
                assertFalse(seedResult.isError)
                val seedBuild = extractBody(seedResult)["build"]?.jsonArray?.firstOrNull()
                    ?: return@withSession println("  ⚠ No builds found, skipping")
                val fullNumber = seedBuild.jsonObject["number"]!!.jsonPrimitive.content
                val suffix = fullNumber.takeLast(2)

                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,number:(value:$suffix,matchType:ends-with),count:5&fields=build(id,number)"
                ))
                assertFalse(result.isError, "ends-with query failed: ${result.content}")
                val builds = extractBody(result)["build"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(builds.isNotEmpty(), "Should find builds ending with '$suffix'")
                builds.forEach { build ->
                    val num = build.jsonObject["number"]!!.jsonPrimitive.content
                    assertTrue(num.endsWith(suffix),
                        "Build number '$num' should end with '$suffix'")
                }
                println("  ✓ number ends-with '$suffix': ${builds.size} build(s)")
            }
        }

        @Test
        fun `number - matches condition supports regex`() {
            mcpClient().withSession {
                val seedResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,count:1&fields=build(id,number)"
                ))
                assertFalse(seedResult.isError)
                val seedBuild = extractBody(seedResult)["build"]?.jsonArray?.firstOrNull()
                    ?: return@withSession println("  ⚠ No builds found, skipping")
                val fullNumber = seedBuild.jsonObject["number"]!!.jsonPrimitive.content
                // Use a simple digit-based regex pattern (no escaping issues)
                val digitPrefix = fullNumber.takeWhile { it.isDigit() }.take(2)
                if (digitPrefix.isEmpty()) return@withSession println("  ⚠ Build number '$fullNumber' has no digit prefix, skipping")
                val regex = ".*$digitPrefix.*"

                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,number:(value:$regex,matchType:matches),count:5&fields=build(id,number)"
                ))
                assertFalse(result.isError, "matches query failed: ${result.content}")
                val builds = extractBody(result)["build"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(builds.isNotEmpty(), "Should find builds matching regex '$regex'")
                println("  ✓ number matches regex '$regex': ${builds.size} build(s)")
            }
        }

        @Test
        fun `agentName - contains with ignoreCase true is case-insensitive`() {
            mcpClient().withSession {
                // Find a build with an agent name
                val seedResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,count:5&fields=build(id,agent(name))"
                ))
                assertFalse(seedResult.isError)
                val seedBuild = extractBody(seedResult)["build"]?.jsonArray
                    ?.firstOrNull { it.jsonObject["agent"]?.jsonObject?.get("name") != null }
                    ?: return@withSession println("  ⚠ No builds with agents found, skipping")
                val agentName = seedBuild.jsonObject["agent"]!!.jsonObject["name"]!!.jsonPrimitive.content
                val uppercased = agentName.take(5).uppercase()

                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,agentName:(value:$uppercased,matchType:contains,ignoreCase:true),count:5&fields=build(id,agent(name))"
                ))
                assertFalse(result.isError, "agentName ignoreCase:true query failed: ${result.content}")
                val builds = extractBody(result)["build"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(builds.isNotEmpty(),
                    "Should find builds with agentName containing '$uppercased' (case-insensitive)")
                println("  ✓ agentName contains '$uppercased' ignoreCase:true: ${builds.size} build(s)")
            }
        }

        @Test
        fun `agentName - contains with ignoreCase false is case-sensitive`() {
            mcpClient().withSession {
                val seedResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,count:5&fields=build(id,agent(name))"
                ))
                assertFalse(seedResult.isError)
                val seedBuild = extractBody(seedResult)["build"]?.jsonArray
                    ?.firstOrNull { it.jsonObject["agent"]?.jsonObject?.get("name") != null }
                    ?: return@withSession println("  ⚠ No builds with agents found, skipping")
                val agentName = seedBuild.jsonObject["agent"]!!.jsonObject["name"]!!.jsonPrimitive.content
                // Use a wrong-case version that shouldn't match with case-sensitive search
                val wrongCase = agentName.take(5).uppercase()

                // Verify that exact lowercase substring DOES match
                val exactResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,agentName:(value:${agentName.take(5)},matchType:contains,ignoreCase:false),count:5&fields=build(id,agent(name))"
                ))
                assertFalse(exactResult.isError)
                val exactBuilds = extractBody(exactResult)["build"]?.jsonArray ?: JsonArray(emptyList())

                // Wrong-case with ignoreCase:false should return fewer or no results
                val wrongCaseResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,agentName:(value:$wrongCase,matchType:contains,ignoreCase:false),count:5&fields=build(id,agent(name))"
                ))
                assertFalse(wrongCaseResult.isError)
                val wrongCaseBuilds = extractBody(wrongCaseResult)["build"]?.jsonArray ?: JsonArray(emptyList())

                if (wrongCase != agentName.take(5)) {
                    // Only assert if case actually differs
                    assertTrue(exactBuilds.size > wrongCaseBuilds.size || wrongCaseBuilds.isEmpty(),
                        "Case-sensitive search with wrong case '$wrongCase' should find fewer results than " +
                                "correct case '${agentName.take(5)}' (got ${wrongCaseBuilds.size} vs ${exactBuilds.size})")
                }
                println("  ✓ agentName ignoreCase:false: correct='${agentName.take(5)}'→${exactBuilds.size}, wrong='$wrongCase'→${wrongCaseBuilds.size}")
            }
        }
    }

    // ==================================================================
    // Condition syntax NOT supported on name dimension
    // ==================================================================

    @Nested
    inner class NameConditionSyntaxNotSupported {

        @Test
        fun `projects - condition syntax on name silently returns empty`() {
            mcpClient().withSession {
                // Find a project with a safe name (no special chars, no spaces, no commas)
                val listResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/projects",
                    "query" to "locator=count:20&fields=project(id,name)"
                ))
                assertFalse(listResult.isError)
                val safeProject = extractBody(listResult)["project"]?.jsonArray
                    ?.firstOrNull { isSafeLocatorName(it.jsonObject["name"]?.jsonPrimitive?.content ?: "") }
                    ?: return@withSession println("  ⚠ No projects with safe names found, skipping")
                val projectName = safeProject.jsonObject["name"]!!.jsonPrimitive.content

                // Exact name should find it
                val exactNameResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/projects",
                    "query" to "locator=name:$projectName&fields=project(id,name)"
                ))
                assertFalse(exactNameResult.isError)
                val exactProjects = extractBody(exactNameResult)["project"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(exactProjects.isNotEmpty(), "Exact name '$projectName' should find project")

                // Condition syntax should return empty (not supported)
                val conditionResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/projects",
                    "query" to "locator=name:(value:$projectName,matchType:equals)&fields=project(id,name)"
                ))
                assertFalse(conditionResult.isError, "Should not error, just return empty")
                val conditionProjects = extractBody(conditionResult)["project"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(conditionProjects.isEmpty(),
                    "Condition syntax on project name should return empty (not supported), got ${conditionProjects.size}")
                println("  ✓ project name condition syntax returns empty (exact match found ${exactProjects.size})")
            }
        }

        @Test
        fun `buildTypes - condition syntax on name silently returns empty`() {
            mcpClient().withSession {
                val exactResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/buildTypes",
                    "query" to "locator=count:1&fields=buildType(id,name)"
                ))
                assertFalse(exactResult.isError)
                val firstBt = extractBody(exactResult)["buildType"]?.jsonArray?.firstOrNull()
                    ?: return@withSession println("  ⚠ No buildTypes found, skipping")
                val btName = firstBt.jsonObject["name"]!!.jsonPrimitive.content

                // Condition syntax should return empty (not supported)
                val conditionResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/buildTypes",
                    "query" to "locator=name:(value:$btName,matchType:equals)&fields=buildType(id,name)"
                ))
                assertFalse(conditionResult.isError, "Should not error, just return empty")
                val conditionBts = extractBody(conditionResult)["buildType"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(conditionBts.isEmpty(),
                    "Condition syntax on buildType name should return empty (not supported), got ${conditionBts.size}")
                println("  ✓ buildType name condition syntax returns empty")
            }
        }

        @Test
        fun `agents - condition syntax on name silently returns empty`() {
            mcpClient().withSession {
                val exactResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/agents",
                    "query" to "locator=count:1&fields=agent(id,name)"
                ))
                assertFalse(exactResult.isError)
                val firstAgent = extractBody(exactResult)["agent"]?.jsonArray?.firstOrNull()
                    ?: return@withSession println("  ⚠ No agents found, skipping")
                val agentName = firstAgent.jsonObject["name"]!!.jsonPrimitive.content

                // Condition syntax should return empty (not supported)
                val conditionResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/agents",
                    "query" to "locator=name:(value:$agentName,matchType:equals)&fields=agent(id,name)"
                ))
                assertFalse(conditionResult.isError, "Should not error, just return empty")
                val conditionAgents = extractBody(conditionResult)["agent"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(conditionAgents.isEmpty(),
                    "Condition syntax on agent name should return empty (not supported), got ${conditionAgents.size}")
                println("  ✓ agent name condition syntax returns empty")
            }
        }
    }

    // ==================================================================
    // Name case sensitivity
    // ==================================================================

    @Nested
    inner class NameCaseSensitivity {

        @Test
        fun `project name is case-sensitive`() {
            mcpClient().withSession {
                val listResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/projects",
                    "query" to "locator=count:20&fields=project(id,name)"
                ))
                assertFalse(listResult.isError)
                // Find a project with mixed case and safe locator characters
                val project = extractBody(listResult)["project"]?.jsonArray
                    ?.firstOrNull {
                        val name = it.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                        name != name.lowercase() && name != name.uppercase() && isSafeLocatorName(name)
                    }
                    ?: return@withSession println("  ⚠ No mixed-case project names with safe chars found, skipping")

                val name = project.jsonObject["name"]!!.jsonPrimitive.content

                // Exact case should find it
                val exactResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/projects",
                    "query" to "locator=name:$name&fields=project(id,name)"
                ))
                assertFalse(exactResult.isError)
                val exactProjects = extractBody(exactResult)["project"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(exactProjects.isNotEmpty(), "Exact case '$name' should find project")

                // Wrong case should NOT find it
                val wrongCase = name.lowercase()
                val wrongResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/projects",
                    "query" to "locator=name:$wrongCase&fields=project(id,name)"
                ))
                assertFalse(wrongResult.isError)
                val wrongProjects = extractBody(wrongResult)["project"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(wrongProjects.isEmpty(),
                    "Wrong case '$wrongCase' should NOT find project (case-sensitive)")
                println("  ✓ project name is case-sensitive: '$name' → found, '$wrongCase' → not found")
            }
        }

        @Test
        fun `buildType name is case-insensitive`() {
            mcpClient().withSession {
                val listResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/buildTypes",
                    "query" to "locator=count:5&fields=buildType(id,name)"
                ))
                assertFalse(listResult.isError)
                val bt = extractBody(listResult)["buildType"]?.jsonArray
                    ?.firstOrNull {
                        val name = it.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                        name != name.lowercase() && name != name.uppercase()
                    }
                    ?: return@withSession println("  ⚠ No mixed-case buildType names found, skipping")

                val name = bt.jsonObject["name"]!!.jsonPrimitive.content

                // Exact case should find it
                val exactResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/buildTypes",
                    "query" to "locator=name:$name&fields=buildType(id,name)"
                ))
                assertFalse(exactResult.isError)
                val exactBts = extractBody(exactResult)["buildType"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(exactBts.isNotEmpty(), "Exact case '$name' should find buildType")

                // Wrong case should ALSO find it (case-insensitive)
                val wrongCase = name.lowercase()
                val wrongResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/buildTypes",
                    "query" to "locator=name:$wrongCase&fields=buildType(id,name)"
                ))
                assertFalse(wrongResult.isError)
                val wrongBts = extractBody(wrongResult)["buildType"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(wrongBts.isNotEmpty(),
                    "Wrong case '$wrongCase' should ALSO find buildType (case-insensitive)")
                assertEquals(exactBts.size, wrongBts.size,
                    "Both cases should return same number of results")
                println("  ✓ buildType name is case-insensitive: '$name' → ${exactBts.size}, '$wrongCase' → ${wrongBts.size}")
            }
        }

        @Test
        fun `agent name is case-sensitive`() {
            mcpClient().withSession {
                val listResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/agents",
                    "query" to "locator=count:5&fields=agent(id,name)"
                ))
                assertFalse(listResult.isError)
                val agent = extractBody(listResult)["agent"]?.jsonArray?.firstOrNull()
                    ?: return@withSession println("  ⚠ No agents found, skipping")

                val name = agent.jsonObject["name"]!!.jsonPrimitive.content

                // Exact case should find it
                val exactResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/agents",
                    "query" to "locator=name:$name&fields=agent(id,name)"
                ))
                assertFalse(exactResult.isError)
                val exactAgents = extractBody(exactResult)["agent"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(exactAgents.isNotEmpty(), "Exact case '$name' should find agent")

                // Wrong case should NOT find it
                val wrongCase = name.replaceFirstChar { it.uppercaseChar() }
                if (wrongCase != name) {
                    val wrongResult = callTool("teamcity_rest_get", mapOf(
                        "path" to "/app/rest/agents",
                        "query" to "locator=name:$wrongCase&fields=agent(id,name)"
                    ))
                    assertFalse(wrongResult.isError)
                    val wrongAgents = extractBody(wrongResult)["agent"]?.jsonArray ?: JsonArray(emptyList())
                    assertTrue(wrongAgents.isEmpty(),
                        "Wrong case '$wrongCase' should NOT find agent (case-sensitive)")
                    println("  ✓ agent name is case-sensitive: '$name' → found, '$wrongCase' → not found")
                } else {
                    println("  ⚠ Agent name '$name' doesn't change with case flip, skipping case test")
                }
            }
        }
    }

    // ==================================================================
    // Endpoint path correctness
    // ==================================================================

    @Nested
    inner class EndpointPaths {

        @Test
        fun `vcs-root-instances - hyphenated path works`() {
            mcpClient().withSession {
                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/vcs-root-instances",
                    "query" to "locator=count:2&fields=vcs-root-instance(id,name)"
                ))
                assertFalse(result.isError, "vcs-root-instances should work: ${result.content}")
                val meta = extractMeta(result)
                assertEquals(200, meta["statusCode"]?.jsonPrimitive?.intOrNull,
                    "vcs-root-instances should return 200")
                println("  ✓ /vcs-root-instances works")
            }
        }

        @Test
        fun `vcsRootInstances - camelCase path returns 404`() {
            mcpClient().withSession {
                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/vcsRootInstances",
                    "query" to "locator=count:2&fields=vcs-root-instance(id,name)"
                ))
                val meta = extractMeta(result)
                assertEquals(404, meta["statusCode"]?.jsonPrimitive?.intOrNull,
                    "vcsRootInstances (camelCase) should return 404")
                println("  ✓ /vcsRootInstances (camelCase) returns 404 as expected")
            }
        }

        @Test
        fun `vcs-roots - path works`() {
            mcpClient().withSession {
                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/vcs-roots",
                    "query" to "locator=count:2&fields=vcs-root(id,name)"
                ))
                assertFalse(result.isError, "vcs-roots should work: ${result.content}")
                val meta = extractMeta(result)
                assertEquals(200, meta["statusCode"]?.jsonPrimitive?.intOrNull,
                    "vcs-roots should return 200")
                println("  ✓ /vcs-roots works")
            }
        }

        @Test
        fun `agentPools - camelCase path works`() {
            mcpClient().withSession {
                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/agentPools",
                    "query" to "locator=count:2&fields=agentPool(id,name)"
                ))
                assertFalse(result.isError, "agentPools should work: ${result.content}")
                val meta = extractMeta(result)
                assertEquals(200, meta["statusCode"]?.jsonPrimitive?.intOrNull,
                    "agentPools should return 200")
                println("  ✓ /agentPools works")
            }
        }
    }

    // ==================================================================
    // Key endpoint patterns from the guide
    // ==================================================================

    @Nested
    inner class GuideEndpointPatterns {

        @Test
        fun `server info endpoint works`() {
            mcpClient().withSession {
                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/server",
                    "query" to "fields=version,buildNumber"
                ))
                assertFalse(result.isError, "Server info should work: ${result.content}")
                val body = extractBody(result)
                assertNotNull(body["version"], "Should have version field")
                println("  ✓ server info: version=${body["version"]}")
            }
        }

        @Test
        fun `single build with fields works`() {
            mcpClient().withSession {
                // Get a build ID first
                val listResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,count:1&fields=build(id)"
                ))
                assertFalse(listResult.isError)
                val buildId = extractBody(listResult)["build"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull
                    ?: return@withSession println("  ⚠ No builds found, skipping")

                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds/id:$buildId",
                    "query" to "fields=id,number,status,statusText,state"
                ))
                assertFalse(result.isError, "Single build should work: ${result.content}")
                val body = extractBody(result)
                assertEquals(buildId, body["id"]?.jsonPrimitive?.intOrNull, "Should return requested build")
                assertNotNull(body["status"], "Should have status field")
                println("  ✓ single build: id=$buildId, status=${body["status"]}")
            }
        }

        @Test
        fun `build statistics endpoint works`() {
            mcpClient().withSession {
                val listResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,state:finished,count:1&fields=build(id)"
                ))
                assertFalse(listResult.isError)
                val buildId = extractBody(listResult)["build"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull
                    ?: return@withSession println("  ⚠ No finished builds found, skipping")

                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds/id:$buildId/statistics",
                    "query" to "fields=property(name,value)"
                ))
                assertFalse(result.isError, "Build statistics should work: ${result.content}")
                val body = extractBody(result)
                val props = body["property"]?.jsonArray
                assertNotNull(props, "Should have property array")
                assertTrue((props?.size ?: 0) > 0, "Should have at least one stat")
                println("  ✓ build statistics: build $buildId has ${props?.size} stat(s)")
            }
        }

        @Test
        fun `build artifacts endpoint works`() {
            mcpClient().withSession {
                val listResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,state:finished,count:1&fields=build(id)"
                ))
                assertFalse(listResult.isError)
                val buildId = extractBody(listResult)["build"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull
                    ?: return@withSession println("  ⚠ No finished builds found, skipping")

                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds/id:$buildId/artifacts/children/",
                    "query" to "fields=file(name,size)"
                ))
                // This may return empty if build has no artifacts, but should not error with 4xx/5xx
                assertFalse(result.isError, "Artifacts endpoint should not error: ${result.content}")
                val meta = extractMeta(result)
                assertEquals(200, meta["statusCode"]?.jsonPrimitive?.intOrNull,
                    "Artifacts endpoint should return 200")
                println("  ✓ build artifacts: build $buildId, status=200")
            }
        }

        @Test
        fun `triggered info works`() {
            mcpClient().withSession {
                val listResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds",
                    "query" to "locator=defaultFilter:false,count:1&fields=build(id)"
                ))
                assertFalse(listResult.isError)
                val buildId = extractBody(listResult)["build"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull
                    ?: return@withSession println("  ⚠ No builds found, skipping")

                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/builds/id:$buildId",
                    "query" to "fields=id,triggered(type,user(username))"
                ))
                assertFalse(result.isError, "Triggered info should work: ${result.content}")
                val body = extractBody(result)
                val triggered = body["triggered"]?.jsonObject
                assertNotNull(triggered, "Should have triggered field")
                assertNotNull(triggered?.get("type"), "Triggered should have type")
                println("  ✓ triggered info: build $buildId, type=${triggered?.get("type")}")
            }
        }

        @Test
        fun `vcs-root-instances with vcsRoot locator works`() {
            mcpClient().withSession {
                // Get a VCS root ID first
                val vcsResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/vcs-roots",
                    "query" to "locator=count:1&fields=vcs-root(id)"
                ))
                assertFalse(vcsResult.isError)
                val vcsRootId = extractBody(vcsResult)["vcs-root"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("id")?.jsonPrimitive?.content
                    ?: return@withSession println("  ⚠ No VCS roots found, skipping")

                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/vcs-root-instances",
                    "query" to "locator=vcsRoot:(id:$vcsRootId),count:3&fields=vcs-root-instance(id,name)"
                ))
                assertFalse(result.isError, "vcs-root-instances with vcsRoot locator should work: ${result.content}")
                val meta = extractMeta(result)
                assertEquals(200, meta["statusCode"]?.jsonPrimitive?.intOrNull)
                println("  ✓ vcs-root-instances with vcsRoot filter: vcsRoot=$vcsRootId")
            }
        }

        @Test
        fun `changes with pending filter works`() {
            mcpClient().withSession {
                // Get a buildType ID first
                val btResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/buildTypes",
                    "query" to "locator=count:1&fields=buildType(id)"
                ))
                assertFalse(btResult.isError)
                val btId = extractBody(btResult)["buildType"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("id")?.jsonPrimitive?.content
                    ?: return@withSession println("  ⚠ No buildTypes found, skipping")

                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/changes",
                    "query" to "locator=buildType:(id:$btId),pending:true,count:5&fields=change(id,username,comment)"
                ))
                assertFalse(result.isError, "Pending changes should work: ${result.content}")
                val meta = extractMeta(result)
                assertEquals(200, meta["statusCode"]?.jsonPrimitive?.intOrNull)
                println("  ✓ pending changes: buildType=$btId")
            }
        }

        @Test
        fun `investigations with buildType locator works`() {
            mcpClient().withSession {
                val btResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/buildTypes",
                    "query" to "locator=count:1&fields=buildType(id)"
                ))
                assertFalse(btResult.isError)
                val btId = extractBody(btResult)["buildType"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("id")?.jsonPrimitive?.content
                    ?: return@withSession println("  ⚠ No buildTypes found, skipping")

                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/investigations",
                    "query" to "locator=buildType:(id:$btId),count:5&fields=investigation(id,state,assignee(username))"
                ))
                assertFalse(result.isError, "Investigations should work: ${result.content}")
                val meta = extractMeta(result)
                assertEquals(200, meta["statusCode"]?.jsonPrimitive?.intOrNull)
                println("  ✓ investigations with buildType filter: buildType=$btId")
            }
        }

        @Test
        fun `agents with connected and authorized filters work`() {
            mcpClient().withSession {
                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/agents",
                    "query" to "locator=connected:true,authorized:true,count:5&fields=agent(id,name,connected,enabled,pool(name))"
                ))
                assertFalse(result.isError, "Agents query should work: ${result.content}")
                val body = extractBody(result)
                val agents = body["agent"]?.jsonArray ?: JsonArray(emptyList())
                agents.forEach { agent ->
                    val connected = agent.jsonObject["connected"]?.jsonPrimitive?.booleanOrNull
                    assertTrue(connected == true, "All agents should be connected")
                }
                println("  ✓ agents connected+authorized: ${agents.size} agent(s)")
            }
        }

        @Test
        fun `buildTypes with project locator works`() {
            mcpClient().withSession {
                // Get a project with buildTypes
                val projectResult = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/projects",
                    "query" to "locator=count:10&fields=project(id,buildTypes(count))"
                ))
                assertFalse(projectResult.isError)
                val project = extractBody(projectResult)["project"]?.jsonArray
                    ?.firstOrNull {
                        val btCount = it.jsonObject["buildTypes"]?.jsonObject?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                        btCount > 0
                    }
                    ?: return@withSession println("  ⚠ No projects with buildTypes found, skipping")
                val projectId = project.jsonObject["id"]!!.jsonPrimitive.content

                val result = callTool("teamcity_rest_get", mapOf(
                    "path" to "/app/rest/buildTypes",
                    "query" to "locator=project:(id:$projectId),count:5&fields=buildType(id,name,projectId)"
                ))
                assertFalse(result.isError, "buildTypes with project filter should work: ${result.content}")
                val bts = extractBody(result)["buildType"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(bts.isNotEmpty(), "Should find buildTypes in project $projectId")
                println("  ✓ buildTypes with project filter: project=$projectId → ${bts.size} buildType(s)")
            }
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /** Returns true if the name can be safely used in a locator without escaping issues. */
    private fun isSafeLocatorName(name: String): Boolean =
        name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ' ' }
                && !name.contains(',') && !name.contains('(') && !name.contains(')')
                && !name.contains('<') && !name.contains('>')
                && !name.contains(':')

    private fun extractBody(result: TestMcpClient.ToolResult): JsonObject {
        val text = result.content.first().text
        val envelope = Json.parseToJsonElement(text).jsonObject
        return envelope["body"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private fun extractMeta(result: TestMcpClient.ToolResult): JsonObject {
        val text = result.content.first().text
        val envelope = Json.parseToJsonElement(text).jsonObject
        return envelope["meta"]?.jsonObject ?: JsonObject(emptyMap())
    }
}
