package info.loveyu.mfca.input

import android.content.Context
import android.system.Os
import info.loveyu.mfca.config.Udp2RawInputConfig
import info.loveyu.mfca.util.LogManager
import java.io.File
import java.util.concurrent.TimeUnit

class Udp2RawInput(
    private val context: Context,
    private val config: Udp2RawInputConfig,
) : InputSource {
    override val inputName: String = config.name
    override val inputType: InputType = InputType.udp2raw

    @Volatile private var process: Process? = null
    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var fatalError: String? = null
    @Volatile private var lastError: String? = null
    private val lock = Any()

    override fun start() {
        synchronized(lock) {
            if (running) return
            if (config.args.isEmpty()) {
                fatalError = "udp2raw args is empty"
                throw IllegalArgumentException(fatalError)
            }

            val executable = try {
                ensureExecutableBinary()
            } catch (e: Exception) {
                fatalError = "prepare udp2raw binary failed: ${e.message}"
                throw IllegalStateException(fatalError, e)
            }

            val workDir = File(context.filesDir, "udp2raw/runtime/${sanitize(config.name)}").apply { mkdirs() }
            val stdoutLog = File(workDir, "udp2raw.stdout.log").apply { writeText("") }
            val stderrLog = File(workDir, "udp2raw.stderr.log").apply { writeText("") }
            val command = listOf(executable.absolutePath) + config.args

            LogManager.logInfo("UDP2RAW", "Starting udp2raw input ${config.name}: ${command.joinToString(" ")}")
            val startedProcess =
                ProcessBuilder(command)
                    .directory(workDir)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(stdoutLog))
                    .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog))
                    .apply {
                        environment()["HOME"] = workDir.absolutePath
                    }.start()

            if (startedProcess.waitFor(1200, TimeUnit.MILLISECONDS)) {
                val exitCode = startedProcess.exitValue()
                val tail = readFailureOutput(stdoutLog, stderrLog)
                lastError = "udp2raw exited immediately ($exitCode): $tail"
                throw IllegalStateException(lastError)
            }

            process = startedProcess
            running = true
            stopping = false
            lastError = null

            Thread {
                val exitCode = startedProcess.waitFor()
                synchronized(lock) {
                    if (process != startedProcess) {
                        return@Thread
                    }
                    process = null
                    running = false
                    if (!stopping) {
                        val tail = readFailureOutput(stdoutLog, stderrLog)
                        lastError = "udp2raw exited unexpectedly ($exitCode): $tail"
                        LogManager.logError("UDP2RAW", "udp2raw input ${config.name} exited unexpectedly: $lastError")
                    } else {
                        LogManager.logDebug("UDP2RAW", "udp2raw input ${config.name} stopped")
                    }
                    stopping = false
                }
            }.apply {
                isDaemon = true
                name = "udp2raw-watch-${sanitize(config.name)}"
                start()
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            stopping = true
            val currentProcess = process
            running = false
            process = null
            if (currentProcess == null) {
                stopping = false
                return
            }
            currentProcess.destroy()
            if (!currentProcess.waitFor(1500, TimeUnit.MILLISECONDS)) {
                currentProcess.destroyForcibly()
                currentProcess.waitFor(1500, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun isRunning(): Boolean = running && (process?.isAlive == true)

    override fun setOnMessageListener(listener: (InputMessage) -> Unit) = Unit

    override fun getError(): String? = fatalError ?: lastError

    override fun hasFatalError(): Boolean = fatalError != null

    private fun ensureExecutableBinary(): File {
        val lib = File(context.applicationInfo.nativeLibraryDir, "libudp2raw.so")
        require(lib.exists()) { "built-in udp2raw library not found: ${lib.absolutePath}" }
        val runtimeDir = File(context.filesDir, "udp2raw/core").apply { mkdirs() }
        val executable = File(runtimeDir, "udp2raw")
        executable.delete()
        Os.symlink(lib.absolutePath, executable.absolutePath)
        return executable
    }

    private fun readFailureOutput(stdoutLog: File, stderrLog: File): String {
        val merged =
            buildString {
                append(stdoutLog.readTailOrEmpty())
                if (isNotBlank() && stderrLog.length() > 0) {
                    append('\n')
                }
                append(stderrLog.readTailOrEmpty())
            }.trim()
        return merged.ifBlank { "no output" }
    }

    private fun File.readTailOrEmpty(maxChars: Int = 1200): String {
        if (!exists()) return ""
        val content = runCatching { readText() }.getOrDefault("")
        return if (content.length <= maxChars) content else content.takeLast(maxChars)
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
