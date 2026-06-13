package af.shizuku.manager.utils

import android.content.Context
import af.shizuku.manager.ShizukuApplication
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object ManagerBinderLogger {

    fun log(message: String, throwable: Throwable? = null) {
        try {
            var filesDir: File? = null
            try {
                filesDir = ShizukuApplication.appContext.filesDir
            } catch (ignored: Throwable) {
                // If appContext is not yet initialized
            }

            val file = if (filesDir != null) {
                File(filesDir, "shizuku_manager_binder.log")
            } else {
                File("/data/data/af.shizuku.plus.api/files/shizuku_manager_binder.log")
            }

            val dir = file.parentFile
            if (dir != null && !dir.exists()) {
                dir.mkdirs()
            }

            val fw = FileWriter(file, true)
            val pw = PrintWriter(fw)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            
            val pkg = try {
                ShizukuApplication.appContext.packageName
            } catch (ignored: Throwable) {
                "af.shizuku.plus.api"
            }

            pw.printf(
                "[%s] [PID:%d] [UID:%d] [PKG:%s] %s\n",
                sdf.format(Date()),
                android.os.Process.myPid(),
                android.os.Process.myUid(),
                pkg,
                message
            )
            throwable?.printStackTrace(pw)
            pw.flush()
            pw.close()
            fw.close()
        } catch (ignored: Throwable) {
            android.util.Log.e("ShizukuDiag", "logBinder failed: $message", ignored)
        }
    }
}
