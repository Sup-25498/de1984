package io.github.dorumrr.de1984.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * What one bounded process run produced.
 *
 * [stdout] and [stderr] are kept apart because the callers disagree about which one they want, and
 * merging them at the source is how a `settings get` ends up returning an error string as if it
 * were a value. Use [merged] where that merge is what you actually mean.
 *
 * [exitCode] is `-1` whenever the run produced no code of its own - it never started, it threw, or
 * it was abandoned - which is the failure value every caller in this app already reads.
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false
) {
    /**
     * stdout when it holds anything but whitespace, otherwise stderr. Both trimmed.
     *
     * Blankness is judged AFTER trimming on purpose: a command that prints a stray newline to
     * stdout and its real complaint to stderr would otherwise report the newline and drop the
     * complaint, which is exactly the message a failing command needs to hand back.
     */
    val merged: String
        get() {
            val out = stdout.trim()
            return if (out.isNotEmpty()) out else stderr.trim()
        }
}

/**
 * Runs one external process with a ceiling that can actually fire, and returns its output.
 *
 * The single place in the app that talks to a [Process]. It exists because the same three bugs had
 * been copied to nine call sites, and a second way to do this is how they would come back.
 *
 * ## 1. A timeout wrapped round blocking code does nothing
 *
 * Nothing on this path checks for coroutine cancellation: not `Runtime.exec`, not the stream reads,
 * not `Process.waitFor()`, and not `Binder.transact` underneath Shizuku's remote process. A
 * `withTimeoutOrNull` placed straight round them registers a cancel that nothing ever acts on. It
 * does not even flag the overrun: when the block never suspends it runs to completion and its value
 * is returned, so the `?: -1` fallbacks written next to those guards were unreachable code.
 * Measured: a 500ms ceiling round a 3000ms blocking call returned the value after 3027ms.
 *
 * So the blocking half runs on [shellScope], which is NOT a child of the caller. Structured
 * concurrency would put the bug straight back, because a scope waits for children before it gives
 * up and these children cannot be cancelled. The caller waits on `await()` instead - a real
 * suspension point a timeout can cut - and on expiry walks away and destroys the process. Closing
 * the remote ends is the only thing that frees a read already blocked on a pipe.
 *
 * ## 2. Draining one pipe at a time deadlocks
 *
 * stdout and stderr are separate pipes with their own ~64KB kernel buffer. Reading stdout to EOF
 * first hangs forever on any command that fills stderr: the child blocks writing stderr, therefore
 * never closes stdout, therefore the stdout read never ends. Measured: still hung after 4 seconds
 * on 200KB of stderr, and freed only by `destroy()`. Both pipes are drained together here.
 *
 * ## 3. Nothing is destroyed on the abandoned path
 *
 * `destroy()` is what releases the process and its pipes. Shizuku's `ShizukuRemoteProcess` also
 * holds itself in a static CACHE until its binderDied fires. See issue #93.
 */
object ShellRunner {

    private const val TAG = "ShellRunner"

    /**
     * How long one command may take before it is abandoned.
     *
     * Deliberately generous, because this bounds liveness and not performance. Two callers set the
     * floor, and both were measured rather than guessed:
     *
     *  - IptablesFirewallBackend puts two iptables invocations per blocked uid into a SINGLE
     *    command. At ~20ms per invocation on a real device - and writes cost more than reads - a
     *    user blocking 200 apps pays 400 invocations. A five-second cap would have cut their
     *    firewall mid-write.
     *  - A `su` command has to outlast the root manager's grant dialog. This is the same 30 seconds
     *    De1984Application already gives libsu for exactly that reason, so the two agree.
     *
     * A cap that fires on a healthy device is worse than the hang it replaces.
     */
    const val DEFAULT_TIMEOUT_MS = 30_000L

    /**
     * Process-lifetime, and detached from every caller. See the note on this class.
     *
     * Nothing cancels it: an abandoned read is released by `destroy()`, not by cancellation, and
     * cancelling the scope would only orphan the thread it was meant to free.
     */
    private val shellScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Runs [spawn] and reads it to completion, or gives up after [timeoutMs].
     *
     * Never throws for a process failure - a failed start, a thrown read and an abandoned run all
     * come back as [ShellResult] with `exitCode == -1`. Cancellation of the CALLER is passed
     * through untouched, because swallowing it would break structured concurrency upstream.
     *
     * [label] names the work in the log when something goes wrong. Keep it short.
     */
    suspend fun run(
        label: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        spawn: () -> Process
    ): ShellResult {
        val processRef = AtomicReference<Process?>(null)

        val work = shellScope.async {
            val process = spawn().also { processRef.set(it) }
            try {
                val out = async { drain(process.inputStream) }
                val err = async { drain(process.errorStream) }

                val stdout = out.await()
                val stderr = err.await()

                // Safe here and only here: both pipes are at EOF, so the child has stopped writing
                // and this returns at once.
                ShellResult(process.waitFor(), stdout, stderr)
            } finally {
                // Do not touch outputStream: Shizuku's getOutputStream() is lazy, so asking for it
                // would open an fd over binder that no caller ever wanted. Nothing writes stdin.
                runCatching { process.destroy() }
            }
        }

        val result = try {
            withTimeoutOrNull(timeoutMs) { work.await() }
        } catch (e: CancellationException) {
            release(processRef)
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "$label failed: ${describe(e)}")
            return ShellResult(-1, "", describe(e))
        }

        if (result != null) {
            return result
        }

        AppLogger.e(TAG, "$label timed out after ${timeoutMs}ms - abandoning it")
        release(processRef)
        return ShellResult(-1, "", "Timed out after ${timeoutMs}ms", timedOut = true)
    }

    /**
     * Frees a process whose reader we have given up on.
     *
     * Detached, because `destroy()` can be a binder call too: a wedged privileged service must not
     * block the caller a second time on the way out. A null reference means the process was not
     * created yet, so the abandoned job still reaches its own `finally` - nothing leaks either way.
     */
    private fun release(processRef: AtomicReference<Process?>) {
        shellScope.launch { runCatching { processRef.get()?.destroy() } }
    }

    private fun drain(stream: InputStream): String {
        val text = StringBuilder()
        stream.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                text.append(line).append("\n")
            }
        }
        return text.toString()
    }

    /**
     * The root cause in words. Reflection wraps everything in InvocationTargetException, whose own
     * message is null, so the useful part is always one or more levels down.
     */
    private fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val name = root.javaClass.simpleName
        val message = root.message
        return if (message.isNullOrBlank()) name else "$name: $message"
    }
}
