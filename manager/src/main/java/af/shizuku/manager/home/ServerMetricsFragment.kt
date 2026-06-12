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

class ServerMetricsFragment : Fragment() {

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
        binding.btnShareDiag.setOnClickListener {
            // Guard: if the Fragment is detached (e.g. in back stack), context is null — bail early
            val context = context ?: return@setOnClickListener

            val diagText = try {
                generateDiagnostics()
            } catch (e: Exception) {
                Timber.e(e, "Failed to generate diagnostics")
                "Error generating diagnostics: ${e.message}\n${Log.getStackTraceString(e)}"
            }

            // Guard: diagText may be empty if generateDiagnostics bailed out early
            if (diagText.isBlank()) return@setOnClickListener

            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("ShizukuPlus Diagnostics", diagText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, R.string.toast_copied_to_clipboard, Toast.LENGTH_SHORT).show()

            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, diagText)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.btn_share_diagnostics)))
            } catch (e: Exception) {
                Timber.e(e, "Failed to share diagnostics")
                Toast.makeText(context, R.string.error_settings_copied_to_clipboard, Toast.LENGTH_LONG).show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
