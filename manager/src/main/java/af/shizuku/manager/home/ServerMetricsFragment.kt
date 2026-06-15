package af.shizuku.manager.home

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import af.shizuku.manager.R
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.Manifest
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.utils.CrashHandler
import af.shizuku.manager.databinding.FragmentServerMetricsBinding
import af.shizuku.server.IAICorePlus
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.FileProvider

class ServerMetricsFragment : Fragment() {

    sealed class LogCollectionResult {
        object Success : LogCollectionResult()
        data class Failure(val exitCode: Int, val exceptionMessage: String) : LogCollectionResult()
    }

    private var lastCollectionResult: LogCollectionResult? = null

    private var _binding: FragmentServerMetricsBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private var aiCore: IAICorePlus? = null

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentServerMetricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initAiCore()
        initListeners()
    }

    private fun initListeners() {
        binding.btnCollectServerLog.setOnClickListener {
            val context = context ?: return@setOnClickListener
            setButtonsEnabled(false)
            lifecycleScope.launch(Dispatchers.IO) {
                val result = try {
                    collectServerLog(context)
                } catch (e: Exception) {
                    LogCollectionResult.Failure(-1, e.message ?: "Unknown error")
                }
                lastCollectionResult = result
                withContext(Dispatchers.Main) {
                    setButtonsEnabled(true)
                    when (result) {
                        is LogCollectionResult.Success -> {
                            Toast.makeText(context, R.string.toast_server_log_collected, Toast.LENGTH_SHORT).show()
                        }
                        is LogCollectionResult.Failure -> {
                            Toast.makeText(context, context.getString(R.string.toast_server_log_collect_failed, result.exceptionMessage), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        binding.btnShareDiag.setOnClickListener {
            // Guard: if the Fragment is detached (e.g. in back stack), context is null — bail early
            val context = context ?: return@setOnClickListener
            setButtonsEnabled(false)
            lifecycleScope.launch(Dispatchers.IO) {
                val result = try {
                    collectServerLog(context)
                } catch (e: Exception) {
                    LogCollectionResult.Failure(-1, e.message ?: "Unknown error")
                }
                lastCollectionResult = result

                val diagText = try {
                    generateDiagnostics()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to generate diagnostics")
                    "Error generating diagnostics: ${e.message}\n${Log.getStackTraceString(e)}"
                }

                withContext(Dispatchers.Main) {
                    setButtonsEnabled(true)
                    if (diagText.isBlank()) return@withContext

                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("ShizukuPlus Diagnostics", diagText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, R.string.toast_copied_to_clipboard, Toast.LENGTH_SHORT).show()

                    try {
                        val logFile = File(context.filesDir, "shizuku_server_java.log")
                        val logFileUri = if (logFile.exists() && result is LogCollectionResult.Success) {
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", logFile)
                        } else {
                            null
                        }

                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, diagText)
                            if (logFileUri != null) {
                                putExtra(Intent.EXTRA_STREAM, logFileUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        }
                        startActivity(Intent.createChooser(intent, getString(R.string.btn_share_diagnostics)))
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to share diagnostics")
                        Toast.makeText(context, R.string.error_settings_copied_to_clipboard, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun generateDiagnostics(): String {
        // Guard: if the Fragment is detached, context is null — return empty to signal caller
        val context = context ?: return ""
        val sb = StringBuilder()
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        sb.append("=== ShizukuPlus Diagnostics Report ===\n")
        sb.append("Generated: ${df.format(Date())}\n\n")
        
        sb.append("[App Info]\n")
        sb.append("Package: ${context.packageName}\n")
        sb.append("VersionName: ${BuildConfig.VERSION_NAME}\n")
        sb.append("VersionCode: ${BuildConfig.VERSION_CODE}\n")
        sb.append("BuildType: ${BuildConfig.BUILD_TYPE}\n\n")
        
        sb.append("[Device Info]\n")
        sb.append("Manufacturer: ${Build.MANUFACTURER}\n")
        sb.append("Model: ${Build.MODEL}\n")
        sb.append("Android SDK: ${Build.VERSION.SDK_INT}\n")
        // R8: FINGERPRINT omitted — contains carrier, serial and build-path data
        // Only expose the non-identifying prefix: brand/product/SDK
        sb.append("Fingerprint (redacted): ${Build.BRAND}/${Build.PRODUCT}/sdk${Build.VERSION.SDK_INT}\n\n")
        
        sb.append("[App Process Info]\n")
        sb.append("UID: ${android.os.Process.myUid()}\n")
        sb.append("PID: ${android.os.Process.myPid()}\n")
        sb.append("Context Package: ${context.packageName}\n")
        sb.append("ApplicationInfo Package: ${context.applicationInfo.packageName}\n")
        val pmPackages = context.packageManager.getPackagesForUid(android.os.Process.myUid())
        sb.append("Packages for UID: ${pmPackages?.joinToString()}\n\n")

        sb.append("[Shizuku Server Info]\n")
        try {
            val binder = Shizuku.getBinder()
            sb.append("Binder Received: ${binder != null}\n")
            val alive = Shizuku.pingBinder()
            sb.append("Binder Alive: $alive\n")
            sb.append("Attach In Progress: ${Shizuku.isAttachInProgress()}\n")
            sb.append("Attached Package: ${Shizuku.getAttachedPackage() ?: "none"}\n")
            val attachErr = Shizuku.getAttachError()
            if (attachErr != null) {
                sb.append("Attach Error: $attachErr\n")
            }

            if (alive) {
                try {
                    sb.append("Server Version: ${Shizuku.getVersion()}\n")
                    sb.append("Patch Version: ${Shizuku.getServerPatchVersion()}\n")
                    sb.append("Server UID: ${Shizuku.getUid()}\n")
                    sb.append("SEContext: ${Shizuku.getSELinuxContext()}\n")
                    sb.append("Permission Granted: ${Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED}\n")
                } catch (e: Exception) {
                    sb.append("ATTACH STATUS: NOT ATTACHED (${e.message})\n")
                }
            }
            sb.append("Latest Version (App): ${Shizuku.getLatestServiceVersion()}\n")
            sb.append("Expected Patch (App): ${ShizukuApiConstants.SERVER_PATCH_VERSION}\n")
        } catch (e: Exception) {
            sb.append("ERROR collecting server info: ${e.message}\n")
        }
        sb.append("\n")
        
        sb.append("[Custom AI Core Stats]\n")
        try {
            val stats = aiCore?.serverStats
            if (stats != null) {
                sb.append("Uptime: ${stats.getLong("uptime_ms")} ms\n")
                sb.append("Client Count: ${stats.getInt("client_count")}\n")
                sb.append("Mem Total: ${stats.getLong("mem_total")}\n")
                sb.append("Mem Free: ${stats.getLong("mem_free")}\n")
                sb.append("Mem Max: ${stats.getLong("mem_max")}\n")
            } else {
                sb.append("AI Core Stats not available (binder null or call failed)\n")
            }
        } catch (e: Exception) {
            sb.append("ERROR collecting AI core stats: ${e.message}\n")
        }
        sb.append("\n")
        
        sb.append("[Authorization]\n")
        try {
            val packages = AuthorizationManager.getPackages()
            val granted = packages.filter { 
                AuthorizationManager.granted(it.packageName, it.applicationInfo?.uid ?: -1)
            }
            sb.append("Authorized Apps Count: ${granted.size} / ${packages.size}\n")
            
            // Focus on ShizuCallRecorder
            val targetPkg = "com.kitsumed.shizucallrecorder"
            val targetPi = packages.find { it.packageName == targetPkg }
            sb.append("\n[Target: $targetPkg]\n")
            if (targetPi != null) {
                sb.append("Installed: true\n")
                val isGranted = AuthorizationManager.granted(targetPkg, targetPi.applicationInfo?.uid ?: -1)
                sb.append("Authorized (Server Flags): $isGranted\n")
                val permGranted = context.packageManager.checkPermission(Manifest.permission.API_V23, targetPkg) == PackageManager.PERMISSION_GRANTED
                sb.append("Android Permission: $permGranted\n")
            } else {
                sb.append("Installed: false\n")
            }
        } catch (e: Exception) {
            sb.append("ERROR collecting authorization info: ${e.message}\n")
        }
        sb.append("\n")
        
        sb.append("[Permissions (Manager)]\n")
        val permissions = arrayOf(
            Manifest.permission.API_V23,
            "moe.shizuku.manager.permission.API_V23"
        )
        for (p in permissions) {
            val granted = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            sb.append("$p: $granted\n")
        }
        sb.append("\n")

        sb.append("[Providers]\n")
        val authorities = arrayOf(
            "${context.packageName}.shizuku",
            "moe.shizuku.privileged.api.shizuku",
            "moe.shizuku.privileged.api",
            "com.rosan.dhizuku"
        )
        for (a in authorities) {
            val resolve = context.packageManager.resolveContentProvider(a, 0)
            sb.append("$a: ${if (resolve != null) "FOUND (${resolve.packageName})" else "NOT FOUND"}\n")
        }
        sb.append("\n")
        
        sb.append("[Last Crash]\n")
        try {
            val crash = CrashHandler.getLastCrashReport(context)
            if (crash != null) {
                sb.append(crash.take(1000))
                if (crash.length > 1000) sb.append("... (truncated)")
            } else {
                sb.append("No crash report found.")
            }
            sb.append("\n")
        } catch (e: Exception) {
            sb.append("ERROR reading crash report: ${e.message}\n")
        }
        sb.append("\n")

        sb.append("[Shizuku Manager Crash Log (shizuku_crash.log)]\n")
        try {
            val crashLogFile = java.io.File(context.filesDir, "shizuku_crash.log")
            if (crashLogFile.exists()) {
                val content = crashLogFile.readText()
                sb.append(content.take(2000))
                if (content.length > 2000) sb.append("... (truncated)")
            } else {
                sb.append("No shizuku_crash.log found.")
            }
            sb.append("\n")
        } catch (e: Exception) {
            sb.append("ERROR reading shizuku_crash.log: ${e.message}\n")
        }
        sb.append("\n")

        sb.append("[Server Logs (Recent)]\n")
        try {
            val shizukuService = af.shizuku.server.IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val serverLogs = shizukuService?.recentLogs
            if (serverLogs != null && serverLogs.isNotEmpty()) {
                serverLogs.takeLast(15).forEach { sb.append("$it\n") }
            } else {
                sb.append("No server logs available.\n")
            }
        } catch (e: Exception) {
            sb.append("ERROR collecting server logs: ${e.message}\n")
        }
        sb.append("\n")

        sb.append("[Server Java Binder Log]\n")
        val result = lastCollectionResult
        if (result is LogCollectionResult.Success) {
            try {
                val logFile = File(context.filesDir, "shizuku_server_java.log")
                if (logFile.exists()) {
                    sb.append(logFile.readText())
                } else {
                    sb.append("Log file does not exist after successful collection.\n")
                }
            } catch (e: Exception) {
                sb.append("Error reading collected log file: ${e.message}\n")
            }
        } else if (result is LogCollectionResult.Failure) {
            sb.append("Collection Failed:\n")
            sb.append("Exit Code: ${result.exitCode}\n")
            sb.append("Exception: ${result.exceptionMessage}\n")
        } else {
            sb.append("Not collected yet.\n")
        }
        sb.append("\n")

        sb.append("[Activity Logs (Recent)]\n")
        try {
            val records = ActivityLogManager.getRecords()
            if (records.isNotEmpty()) {
                records.take(10).forEach { 
                    sb.append("${df.format(Date(it.timestamp))} [${it.packageName}] ${it.action}\n")
                }
            } else {
                sb.append("No activity logs found.\n")
            }
        } catch (e: Exception) {
            sb.append("ERROR collecting activity logs: ${e.message}\n")
        }
        
        return sb.toString()
    }

    private fun initAiCore() {
        try {
            if (Shizuku.pingBinder()) {
                val binder = Shizuku.getBinder()
                val shizukuService = af.shizuku.server.IShizukuService.Stub.asInterface(binder)
                aiCore = shizukuService.aiCorePlus
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun updateStats() {
        val ai = aiCore ?: return
        try {
            val stats = ai.serverStats
            val uptimeMs = stats.getLong("uptime_ms")
            binding.textUptime.text = formatUptime(uptimeMs)
            val clientCount = stats.getInt("client_count")
            binding.textClientCount.text = "$clientCount applications connected"
            val total = stats.getLong("mem_total")
            val free = stats.getLong("mem_free")
            val max = stats.getLong("mem_max")
            val used = total - free
            val progress = (used.toFloat() / max.toFloat() * 100).toInt()
            binding.progressMemory.progress = progress
            binding.textMemoryDetails.text = "${formatSize(used)} / ${formatSize(max)} (Max Allowed)"
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun formatUptime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = (ms / (1000 * 60 * 60)) % 24
        val days = (ms / (1000 * 60 * 60 * 24))
        return if (days > 0) String.format("%dd %02d:%02d:%02d", days, hours, minutes, seconds)
        else String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024
        val mb = kb / 1024
        return if (mb > 0) "$mb MB" else "$kb KB"
    }

    private fun collectServerLog(context: android.content.Context): LogCollectionResult {
        if (!Shizuku.pingBinder()) {
            return LogCollectionResult.Failure(-1, "Shizuku binder connection is not active")
        }

        val outFile = File(context.filesDir, "shizuku_server_java.log")
        outFile.parentFile?.mkdirs()

        var process: java.lang.Process? = null
        try {
            process = Shizuku.newProcess(arrayOf("cat", "/data/local/tmp/shizuku_server_java.log"), null, null)

            FileOutputStream(outFile).use { fos ->
                val stdoutStream = process.inputStream
                val stderrStream = process.errorStream

                val t1 = Thread {
                    try {
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        while (stdoutStream.read(buffer).also { bytesRead = it } != -1) {
                            synchronized(fos) {
                                fos.write(buffer, 0, bytesRead)
                            }
                        }
                    } catch (e: Exception) {
                        // ignore or log
                    }
                }

                val t2 = Thread {
                    try {
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        while (stderrStream.read(buffer).also { bytesRead = it } != -1) {
                            synchronized(fos) {
                                fos.write(buffer, 0, bytesRead)
                            }
                        }
                    } catch (e: Exception) {
                        // ignore or log
                    }
                }

                t1.start()
                t2.start()

                t1.join()
                t2.join()
            }

            val exitCode = process.waitFor()
            return if (exitCode == 0) {
                LogCollectionResult.Success
            } else {
                LogCollectionResult.Failure(exitCode, "Process exited with code $exitCode")
            }
        } catch (e: Exception) {
            val stackTrace = Log.getStackTraceString(e)
            return LogCollectionResult.Failure(-1, "${e.message}\n$stackTrace")
        } finally {
            process?.destroy()
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnCollectServerLog.isEnabled = enabled
        binding.btnShareDiag.isEnabled = enabled
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
