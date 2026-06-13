package rikka.shizuku.server;

import static android.app.ActivityManagerHidden.UID_OBSERVER_ACTIVE;
import static android.app.ActivityManagerHidden.UID_OBSERVER_CACHED;
import static android.app.ActivityManagerHidden.UID_OBSERVER_GONE;
import static android.app.ActivityManagerHidden.UID_OBSERVER_IDLE;

import android.app.ActivityManagerHidden;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.List;

import kotlin.collections.ArraysKt;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.PermissionManagerApis;
import rikka.hidden.compat.adapter.ProcessObserverAdapter;
import rikka.hidden.compat.adapter.UidObserverAdapter;
import rikka.shizuku.server.util.Logger;

public class BinderSender {

    private static final Logger LOGGER = new Logger("BinderSender");

    private static final String PERMISSION_MANAGER = "af.shizuku.plus.permission.MANAGER";
    private static final String PERMISSION = ServerConstants.PERMISSION;
    private static final String PERMISSION_LEGACY = ServerConstants.PERMISSION_LEGACY;
    private static final String PERMISSION_ORIGINAL = ServerConstants.PERMISSION_ORIGINAL;

    private static ShizukuService sShizukuService;

    private static class ProcessObserver extends ProcessObserverAdapter {

        private static final List<Integer> PID_LIST = new ArrayList<>();

        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) throws RemoteException {
            LOGGER.d("onForegroundActivitiesChanged: pid=%d, uid=%d, foregroundActivities=%s", pid, uid, foregroundActivities ? "true" : "false");

            synchronized (PID_LIST) {
                if (PID_LIST.contains(pid) || !foregroundActivities) {
                    return;
                }
                PID_LIST.add(pid);
            }

            sendBinder(uid, pid);
        }

        @Override
        public void onProcessDied(int pid, int uid) {
            LOGGER.d("onProcessDied: pid=%d, uid=%d", pid, uid);

            synchronized (PID_LIST) {
                int index = PID_LIST.indexOf(pid);
                if (index != -1) {
                    PID_LIST.remove(index);
                }
            }
        }

        @Override
        public void onProcessStateChanged(int pid, int uid, int procState) throws RemoteException {
            LOGGER.d("onProcessStateChanged: pid=%d, uid=%d, procState=%d", pid, uid, procState);

            synchronized (PID_LIST) {
                if (PID_LIST.contains(pid)) {
                    return;
                }
                PID_LIST.add(pid);
            }

            sendBinder(uid, pid);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private static class UidObserver extends UidObserverAdapter {

        private static final List<Integer> UID_LIST = new ArrayList<>();

        @Override
        public void onUidActive(int uid) throws RemoteException {
            LOGGER.d("onUidCachedChanged: uid=%d", uid);

            uidStarts(uid);
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) throws RemoteException {
            LOGGER.d("onUidCachedChanged: uid=%d, cached=%s", uid, Boolean.toString(cached));

            if (!cached) {
                uidStarts(uid);
            }
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) throws RemoteException {
            LOGGER.d("onUidIdle: uid=%d, disabled=%s", uid, Boolean.toString(disabled));

            uidStarts(uid);
        }

        @Override
        public void onUidGone(int uid, boolean disabled) throws RemoteException {
            LOGGER.d("onUidGone: uid=%d, disabled=%s", uid, Boolean.toString(disabled));

            uidGone(uid);
        }

        private void uidStarts(int uid) throws RemoteException {
            synchronized (UID_LIST) {
                if (UID_LIST.contains(uid)) {
                    LOGGER.v("Uid %d already starts", uid);
                    return;
                }
                UID_LIST.add(uid);
                LOGGER.v("Uid %d starts", uid);
            }

            sendBinder(uid, -1);
        }

        private void uidGone(int uid) {
            synchronized (UID_LIST) {
                int index = UID_LIST.indexOf(uid);
                if (index != -1) {
                    UID_LIST.remove(index);
                    LOGGER.v("Uid %d dead", uid);
                }
            }
        }
    }

    private static void sendBinder(int uid, int pid) throws RemoteException {
        ShizukuService.logJava("BinderSender.sendBinder entered: uid=" + uid + ", pid=" + pid);
        List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(uid);
        ShizukuService.logJava("BinderSender.sendBinder: packages found for uid: " + packages);
        if (packages.isEmpty()) {
            ShizukuService.logJava("BinderSender.sendBinder: no packages found, returning");
            return;
        }

        LOGGER.d("sendBinder to uid %d: packages=%s", uid, TextUtils.join(", ", packages));

        int userId = uid / 100000;
        for (String packageName : packages) {
            PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(packageName, PackageManager.GET_PERMISSIONS, userId);
            if (pi == null || pi.requestedPermissions == null) {
                ShizukuService.logJava("BinderSender.sendBinder: PackageInfo or requestedPermissions is null for " + packageName);
                continue;
            }

            ShizukuService.logJava("BinderSender.sendBinder: package " + packageName + " has requested permissions: " + java.util.Arrays.toString(pi.requestedPermissions));
            if (ArraysKt.contains(pi.requestedPermissions, PERMISSION_MANAGER)) {
                boolean granted = false;
                try {
                    if (pid == -1)
                        granted = PermissionManagerApis.checkPermission(PERMISSION_MANAGER, uid) == PackageManager.PERMISSION_GRANTED;
                    else
                        granted = ActivityManagerApis.checkPermission(PERMISSION_MANAGER, pid, uid) == PackageManager.PERMISSION_GRANTED;
                    ShizukuService.logJava("BinderSender.sendBinder: checkPermission PERMISSION_MANAGER result=" + granted);
                } catch (Throwable e) {
                    LOGGER.w("checkPermission failed for manager");
                    ShizukuService.logJava("checkPermission failed for manager package " + packageName, e);
                }

                if (granted) {
                    ShizukuService.logJava("BinderSender.sendBinder: calling sendBinderToManager for uid=" + uid + ", userId=" + userId);
                    ShizukuService.sendBinderToManager(sShizukuService, userId);
                    return;
                }
            } else if (ArraysKt.contains(pi.requestedPermissions, PERMISSION) || 
                       ArraysKt.contains(pi.requestedPermissions, PERMISSION_LEGACY) ||
                       ArraysKt.contains(pi.requestedPermissions, PERMISSION_ORIGINAL)) {
                ShizukuService.logJava("BinderSender.sendBinder: calling sendBinderToUserApp for packageName=" + packageName + ", userId=" + userId);
                ShizukuService.sendBinderToUserApp(sShizukuService, packageName, userId);
                return;
            }
        }
        ShizukuService.logJava("BinderSender.sendBinder: finished iteration, no target match found");
    }

    public static void register(ShizukuService shizukuService) {
        sShizukuService = shizukuService;

        try {
            ActivityManagerApis.registerProcessObserver(new ProcessObserver());
        } catch (Throwable tr) {
            LOGGER.e(tr, "registerProcessObserver");
        }

        if (Build.VERSION.SDK_INT >= 26) {
            int flags = UID_OBSERVER_GONE | UID_OBSERVER_IDLE | UID_OBSERVER_ACTIVE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                flags |= UID_OBSERVER_CACHED;
            }
            try {
                ActivityManagerApis.registerUidObserver(new UidObserver(), flags,
                        ActivityManagerHidden.PROCESS_STATE_UNKNOWN,
                        null);
            } catch (Throwable tr) {
                LOGGER.e(tr, "registerUidObserver");
            }
        }
    }
}
