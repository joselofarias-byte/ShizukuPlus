package af.shizuku.manager.starter

import android.content.Context
import androidx.lifecycle.asFlow
import java.io.File
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import af.shizuku.manager.R
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.ManagerBinderLogger

/**
 * Starter object for launching Shizuku service
 * Uses appContext from ShizukuApplication
 */
object Starter {

    private var context: Context? = null
    
    private fun getContext(): Context {
        return context ?: throw IllegalStateException("Context not initialized")
    }
    
    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    private val starterFile: File
        get() = File(getContext().applicationInfo.nativeLibraryDir, "libshizuku.so")

    val userCommand: String
        get() = starterFile.absolutePath

    val adbCommand: String
        get() = "adb shell $userCommand"

    val internalCommand: String
        get() = "$userCommand --apk=${getContext().applicationInfo.sourceDir}"

    val serviceStartedMessage: String
        get() = getContext().getString(R.string.starter_service_started)

    suspend fun waitForBinder(log: ((String) -> Unit)? = null) {
        ManagerBinderLogger.log("Starter.waitForBinder started")
        if (ShizukuStateMachine.isRunning()) {
            log?.invoke(serviceStartedMessage)
            return
        }
        log?.invoke("\n" + getContext().getString(R.string.starter_waiting))
        val t0 = System.currentTimeMillis()

        // 60s timeout matches upstream Shizuku. TimeoutCancellationException is rethrown as
        // TimeoutException (non-cancellation) so the CoroutineExceptionHandler in StarterActivity
        // treats it as a real error and shows R.string.adb_error_timeout instead of freezing.
        try {
            withTimeout(60_000) {
                ShizukuStateMachine.asFlow()
                    .first { it == ShizukuStateMachine.State.RUNNING }
            }
        } catch (e: TimeoutCancellationException) {
            ManagerBinderLogger.log("Starter.waitForBinder timed out after 60 seconds", e)
            throw TimeoutException("Failed to receive binder within 1 minute")
        }

        val elapsed = (System.currentTimeMillis() - t0) / 1000.0
        log?.invoke("Connected in ${String.format("%.1f", elapsed)}s")
        log?.invoke(serviceStartedMessage)
    }
}
