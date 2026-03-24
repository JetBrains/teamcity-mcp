import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.rodm.teamcity.server)
    id("idea")
}

val jdkVersion = (anyParam("jdkVersion"))?.toIntOrNull() ?: 21
val teamcityVersion = anyParam("teamcityVersion") ?: "2026.1-SNAPSHOT"
val pluginVersion = anyParam("PluginVersion")  ?: "SNAPSHOT-${SimpleDateFormat("yyyyMMddHHmm").format(Date())}"
val localRepo = anyParam("TC_LOCAL_REPO_ABS")

kotlin {
    jvmToolchain(jdkVersion)
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    resources.srcDir("src/integrationTest/resources")
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

dependencies {
    //teamcity
    provided("org.jetbrains.teamcity.internal:server:${teamcityVersion}")
    provided("org.jetbrains.teamcity.internal:web:${teamcityVersion}")
    provided("org.jetbrains.teamcity.internal:fus-events-model:${teamcityVersion}")
    //needed to set explicitly to don't take server-web-api as transitive deps from io.github.rodm.teamcity-server plugin
    provided("org.jetbrains.teamcity:server-web-api:${teamcityVersion}")

    implementation(libs.mcp)

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)

    // Integration-test dependencies (inherit junit + mockk from testImplementation above)
    "integrationTestImplementation"(libs.junit.jupiter.api)
    "integrationTestImplementation"(libs.junit.jupiter.engine)
    "integrationTestRuntimeOnly"(libs.junit.platform.launcher)
    "integrationTestImplementation"(libs.ktor.client.cio)
}

val spaceUsername = anyParam("spaceUsername")
val spacePassword = anyParam("spacePassword")

allprojects {
    repositories {

        // the order of the repositories matters
        if (localRepo == null) {
            maven(url = "https://packages.jetbrains.team/maven/p/tc/maven") {
                credentials {
                    username = spaceUsername
                    password = spacePassword
                }
            }
        } else {
            maven(url = "file:///$localRepo")
        }
        maven(url = "https://download.jetbrains.com/teamcity-repository")
        maven(url = "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        maven(url = "https://repository.jetbrains.com/all")
        mavenCentral()
    }
}

configurations.runtimeClasspath {
    // Exclude libraries already provided by TeamCity runtime
    exclude(group = "org.slf4j", module = "slf4j-api")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests against a prepared TeamCity server."
    group = "verification"

    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath        = integrationTestSourceSet.runtimeClasspath

    // Exclude e2e tests from the regular integration-test run (they require Docker + API keys)
    useJUnitPlatform {
        excludeTags("e2e")
    }

    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
    }

    listOf("TC_SERVER_URL", "TC_SERVER_TOKEN", "TC_SERVER_RESTRICTED_TOKEN").forEach { key ->
        anyParam(key)?.let { systemProperty(key, it) }
    }
}

val e2eTest by tasks.registering(Test::class) {
    description = "Runs end-to-end tests with real AI agents in Docker against TeamCity MCP."
    group = "verification"

    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath        = integrationTestSourceSet.runtimeClasspath

    useJUnitPlatform {
        includeTags("e2e")
    }

    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
    }

    listOf("TC_SERVER_URL", "TC_SERVER_TOKEN", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY").forEach { key ->
        anyParam(key)?.let { systemProperty(key, it) }
    }
}

// Make 'check' include integration tests only when explicitly requested
// (to avoid slowing down regular builds)
// Run with: ./gradlew integrationTest
// Run with: ./gradlew e2eTest -DANTHROPIC_API_KEY=sk-ant-... -DOPENAI_API_KEY=sk-...

teamcity {
    server {
        descriptor = file("teamcity-plugin.xml")
        tokens = mapOf("Plugin_Version" to pluginVersion)
        archiveName = "mcp"
        allowSnapshotVersions = true
    }
}

fun anyParam(name: String): String? = (rootProject.findProperty(name) ?: System.getProperty(name) ?: System.getenv(name)) as? String
