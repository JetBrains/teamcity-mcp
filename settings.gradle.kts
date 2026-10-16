import java.util.zip.ZipFile

rootProject.name = "mcp"

// TEMPORARY diagnostics: find which Gradle cache entry breaks build.gradle.kts compilation
// ("Cannot access class 'KotlinJvmProjectExtension'") with the seeded /mnt/cache/.gradle.
val diagClass = "org/jetbrains/kotlin/gradle/dsl/KotlinJvmProjectExtension.class"

fun diag(msg: String) = println("[MCP-DIAG] $msg")

fun checkJar(f: File): String {
    if (!f.exists()) return "MISSING"
    if (f.isDirectory) return "DIR has-class=${File(f, diagClass).exists()}"
    return try {
        ZipFile(f).use { z ->
            var broken = 0
            val entries = z.entries().toList()
            for (e in entries) if (!e.isDirectory) try { z.getInputStream(e).use { it.readBytes() } } catch (_: Exception) { broken++ }
            "size=${f.length()} entries=${entries.size} unreadable=$broken has-class=${z.getEntry(diagClass) != null}"
        }
    } catch (e: Exception) {
        "CORRUPT size=${f.length()} ${e.javaClass.simpleName}: ${e.message}"
    }
}

val home = gradle.gradleUserHomeDir
val caches = File(home, "caches")

// Bisecting: delete the listed sub-caches (relative to caches/) before build.gradle.kts is compiled
System.getenv("MCP_DIAG_DELETE")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { rel ->
    val target = File(caches, rel)
    diag("deleting caches/$rel exists=${target.exists()} ok=${target.deleteRecursively()}")
}
File(caches, "${gradle.gradleVersion}/kotlin-dsl").walkTopDown().maxDepth(3).forEach { f ->
    diag("kotlin-dsl ${f.relativeTo(caches)} ${if (f.isFile) "size=${f.length()}" else "dir children=${f.list()?.size}"}")
}
diag("gradle=${gradle.gradleVersion} GRADLE_USER_HOME=$home GRADLE_RO_DEP_CACHE=${System.getenv("GRADLE_RO_DEP_CACHE")}")
diag("caches dirs: ${caches.listFiles()?.map { it.name }?.sorted()}")
File(caches, gradle.gradleVersion).listFiles()?.sortedBy { it.name }?.forEach { d ->
    diag("caches/${gradle.gradleVersion}/${d.name} children=${d.list()?.size}")
}

// Downloaded artifacts of the Kotlin Gradle plugin family
File(caches, "modules-2/files-2.1/org.jetbrains.kotlin").listFiles { f -> f.name.startsWith("kotlin-gradle-plugin") }
    ?.sortedBy { it.name }?.forEach { module ->
        module.listFiles()?.sortedBy { it.name }?.forEach { version ->
            version.walkTopDown().filter { it.isFile }.forEach { f ->
                diag("modules-2 ${module.name}:${version.name} ${f.parentFile.name.take(8)}/${f.name} ${if (f.name.endsWith(".jar")) checkJar(f) else "size=${f.length()}"}")
            }
        }
    }

// Metadata descriptors: which repositories the KGP modules were resolved from
caches.listFiles { f -> f.name.startsWith("modules-2") }?.forEach { m2 ->
    m2.listFiles { f -> f.name.startsWith("metadata-") }?.forEach { meta ->
        File(meta, "descriptors/org.jetbrains.kotlin").listFiles { f -> f.name.startsWith("kotlin-gradle-plugin") }?.forEach { module ->
            module.listFiles()?.forEach { version ->
                diag("descriptor ${meta.name} ${module.name}:${version.name} repos=${version.list()?.toList()}")
            }
        }
    }
}

// Transformed/instrumented copies of KGP jars used on the script compile classpath
caches.walkTopDown().maxDepth(6)
    .filter { it.name.contains("kotlin-gradle-plugin") && (it.name.endsWith(".jar") || it.isDirectory) && !it.path.contains("modules-2") }
    .take(200)
    .forEach { diag("cached-copy ${it.relativeTo(caches)} ${checkJar(it)}") }

// Actual build script classpath, reported even when build.gradle.kts fails to compile
gradle.addProjectEvaluationListener(object : ProjectEvaluationListener {
    override fun beforeEvaluate(project: Project) {}
    override fun afterEvaluate(project: Project, state: ProjectState) {
        diag("afterEvaluate ${project.path} failure=${state.failure?.javaClass?.simpleName}")
        try {
            val classpath = project.buildscript.configurations.getByName("classpath")
            classpath.incoming.resolutionResult.allComponents
                .filter { it.moduleVersion?.group == "org.jetbrains.kotlin" && it.moduleVersion?.name?.startsWith("kotlin-gradle-plugin") == true }
                .forEach { c -> diag("resolved ${c.id.displayName} variants=${c.variants.map { it.displayName }}") }
            classpath.incoming.artifacts.artifacts
                .filter { it.file.name.contains("kotlin-gradle-plugin") }
                .forEach { a -> diag("classpath ${a.id.displayName} ${a.file} ${checkJar(a.file)}") }
        } catch (e: Exception) {
            diag("classpath inspection failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
})
