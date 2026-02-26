package jetbrains.buildServer.ai.mcp.framework.e2e

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper that runs shell scripts and returns structured [AgentOutput].
 *
 * All Docker operations are delegated to shell scripts in the `scriptsDir`,
 * making the tests cross-platform (Kotlin only calls `bash script.sh args...`).
 */
class ScriptRunner(private val scriptsDir: String) {

    /**
     * Run a script and return structured output. Never throws on non-zero exit.
     *
     * @param script         script filename (e.g. "common.sh")
     * @param args           arguments to pass to the script
     * @param stdin          optional content to pipe to the script's stdin
     * @param timeoutSeconds maximum time to wait for the script to complete
     * @return [AgentOutput] with exit code, stdout, and stderr
     */
    fun run(
        script: String,
        args: List<String> = emptyList(),
        stdin: String? = null,
        timeoutSeconds: Long = 300
    ): AgentOutput {
        val command = mutableListOf("bash", "$scriptsDir/$script")
        command += args

        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()

        // Write stdin if provided
        if (stdin != null) {
            process.outputStream.use { it.write(stdin.toByteArray()) }
        } else {
            process.outputStream.close()
        }

        // Read streams in background threads to prevent pipe-buffer deadlock
        val stdoutFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().readText()
        }
        val stderrFuture = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().readText()
        }

        val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }

        val stdout = try { stdoutFuture.get(10, TimeUnit.SECONDS) } catch (_: Exception) { "" }
        val stderr = try { stderrFuture.get(10, TimeUnit.SECONDS) } catch (_: Exception) { "" }

        if (!exited) {
            return AgentOutput(-1, stdout, stderr + "\nTIMEOUT after ${timeoutSeconds}s")
        }
        return AgentOutput(process.exitValue(), stdout, stderr)
    }

    /**
     * Run a script and throw on non-zero exit. Returns stdout.
     *
     * @param script         script filename (e.g. "common.sh")
     * @param args           arguments to pass to the script
     * @param stdin          optional content to pipe to the script's stdin
     * @param timeoutSeconds maximum time to wait for the script to complete
     * @return stdout content
     * @throws RuntimeException if the script exits with non-zero code or times out
     */
    fun runChecked(
        script: String,
        args: List<String> = emptyList(),
        stdin: String? = null,
        timeoutSeconds: Long = 120
    ): String {
        val result = run(script, args, stdin, timeoutSeconds)
        if (result.exitCode != 0) {
            throw RuntimeException(
                "$script ${args.joinToString(" ")} failed (exit=${result.exitCode}):\n" +
                    "stdout: ${result.stdout.take(2000)}\n" +
                    "stderr: ${result.stderr.take(2000)}"
            )
        }
        return result.stdout
    }

    companion object {
        fun scriptsDir(): String = System.getProperty("user.dir") + "/src/integrationTest/scripts"
    }
}
