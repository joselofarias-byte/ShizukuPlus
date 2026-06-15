# Reporte de Verificación — Fixes Shizuku+

Este reporte contiene el registro de los cambios aplicados, los resultados de la compilación y el estado de la verificación manual.

---

## 1. Cambios Aplicados y Diagnóstico

### A. Crash de Binder en Aplicaciones Externas (aShell / Ever Call Recorder)
* **Archivo Modificado:** [Service.java](file:///c:/Users/Dell/ShizukuPlus/api/server-shared/src/main/java/rikka/shizuku/server/Service.java) (Submódulo `api`)
* **Problema:** Las aplicaciones externas envían descriptores legacy (como `rikka.shizuku.server.IShizukuService` o `moe.shizuku.server.IShizukuService`). Al delegar las transacciones al Stub generado, el compilador AIDL de Shizuku+ espera `af.shizuku.server.IShizukuService` y lanza un error `SecurityException: Binder invocation to an incorrect interface`. Además, en algunas versiones de Android la lectura reflexiva del token fallaba, provocando un bypass en la reescritura.
* **Solución:**
  1. Se implementó una **detección de token basada en enforceInterface** en `detectInterfaceToken` que comprueba candidatos de descriptores válidos en un bucle y captura la posición exacta de `payloadStart` sin usar análisis manual basado en `readInt() + readString()`.
  2. Si el análisis tiene éxito, se deja el Parcel posicionado en `payloadStart` para permitir despachos manuales.
  3. Si la detección falla por completo, se restaura la posición original y se fuerza la posición a `0` antes de invocar los despachadores del Stub generado.
  4. Para descriptores legacy v13, se realiza una **reescritura limpia sobre un Parcel nuevo** inyectando el descriptor de Shizuku+ (`af.shizuku.server.IShizukuService`) y concatenando exactamente `dataSize - payloadStart` bytes.
  5. Se asegura que el Parcel reescrito se reposiciona a `0` antes de cada llamada de despacho (`rishService` y `super.onTransact()`), liberándolo de manera segura en un bloque `finally`.
  6. Se añadieron logs diagnósticos exhaustivos incluyendo capturas de `SecurityException` y registro explícito ante fallos de detección.

---

## 2. Detalles de Compilación

* **JDK de Compilación:** Java 21 (Android Studio JBR)
* **Comando:**
  ```powershell
  $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :manager:assembleDebug
  ```
* **Estado:** ✅ BUILD SUCCESSFUL (en 3m 37s)
* **Ruta de Salida del APK Generado:**
  [manager/build/outputs/apk/debug/shizuku-Shizuku+ 13.6.0.r1953-debug-20260614_185151.apk](file:///c:/Users/Dell/ShizukuPlus/manager/build/outputs/apk/debug/shizuku-Shizuku+%2013.6.0.r1953-debug-20260614_185151.apk)

---

## 3. Estado de Verificación Manual (Checklist)

- [x] Shizuku+ inicia correctamente.
- [x] La sección de Ajustes abre sin colapsar.
- [x] No hay rastros de la interfaz de LeakCanary.
- [x] El recuento de aplicaciones autorizadas se refresca correctamente en el menú principal.
- [ ] **aShell no lanza el crash de Binder** (Pendiente de prueba en dispositivo por parte del usuario).
- [ ] **Ever Call Recorder detecta la autorización** (Pendiente de prueba en dispositivo por parte del usuario).

---

## 4. Diff Completo de `Service.java`

```diff
diff --git a/server-shared/src/main/java/rikka/shizuku/server/Service.java b/server-shared/src/main/java/rikka/shizuku/server/Service.java
index dd88f12..565cdc2 100644
--- a/server-shared/src/main/java/rikka/shizuku/server/Service.java
+++ b/server-shared/src/main/java/rikka/shizuku/server/Service.java
@@ -384,53 +384,95 @@ public abstract class Service<
         return true;
     }
 
-    // readInterfaceToken() is a hidden API not in public SDK stubs — access via reflection.
-    // Safe in the privileged server process where hidden API restrictions are not enforced.
-    private static String readInterfaceTokenCompat(Parcel parcel) {
-        try {
-            Method m = Parcel.class.getDeclaredMethod("readInterfaceToken");
-            m.setAccessible(true);
-            return (String) m.invoke(parcel);
-        } catch (Exception ignored) {
-            return "";
+    private static class InterfaceTokenResult {
+        final String descriptor;
+        final int payloadStart;
+        final boolean success;
+
+        InterfaceTokenResult(String descriptor, int payloadStart, boolean success) {
+            this.descriptor = descriptor;
+            this.payloadStart = payloadStart;
+            this.success = success;
+        }
+    }
+
+    private static final String[] KNOWN_DESCRIPTORS = {
+        "af.shizuku.server.IShizukuService",
+        "moe.shizuku.server.IShizukuService",
+        "rikka.shizuku.IShizukuService",
+        "rikka.shizuku.server.IShizukuService"
+    };
+
+    private static InterfaceTokenResult detectInterfaceToken(Parcel parcel) {
+        int originalPos = parcel.dataPosition();
+        for (String candidate : KNOWN_DESCRIPTORS) {
+            parcel.setDataPosition(0);
+            try {
+                parcel.enforceInterface(candidate);
+                int payloadStart = parcel.dataPosition();
+                // Success: leave the original Parcel positioned at payloadStart for manual dispatch
+                return new InterfaceTokenResult(candidate, payloadStart, true);
+            } catch (SecurityException ignored) {
+                // Try next candidate
+            }
         }
+        // Failure: restore originalPosition
+        parcel.setDataPosition(originalPos);
+        return new InterfaceTokenResult(null, 0, false);
     }
 
     @CallSuper
     @Override
     public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
-        if (code >= 14 && code <= 18) {
-            String threadInfo = Thread.currentThread().getName() + " (ID: " + Thread.currentThread().getId() + ")";
-            ServerSharedLog.log("onTransact: code=" + code + ", callingUid=" + Binder.getCallingUid() + ", callingPid=" + Binder.getCallingPid() + ", thread=" + threadInfo);
-        }
-        // Support legacy interface tokens from existing Shizuku apps
-        data.setDataPosition(0);
-        String descriptor = readInterfaceTokenCompat(data);
-        boolean isLegacy = "moe.shizuku.server.IShizukuService".equals(descriptor)
-                || "rikka.shizuku.IShizukuService".equals(descriptor);
-        boolean isNew = ShizukuApiConstants.BINDER_DESCRIPTOR.equals(descriptor);
+        int callingUid = Binder.getCallingUid();
+        int callingPid = Binder.getCallingPid();
+        String threadInfo = Thread.currentThread().getName() + " (ID: " + Thread.currentThread().getId() + ")";
+        
+        int posBeforeRead = data.dataPosition();
+        InterfaceTokenResult tokenResult = detectInterfaceToken(data);
+        int posAfterRead = data.dataPosition();
+
+        boolean isNew = tokenResult.success && ShizukuApiConstants.BINDER_DESCRIPTOR.equals(tokenResult.descriptor);
+        boolean isLegacy = tokenResult.success && !isNew;
+        
+        // moe.shizuku.server.IShizukuService is the old v11/v12 descriptor with different transaction codes
+        boolean isOldLegacy = tokenResult.success && "moe.shizuku.server.IShizukuService".equals(tokenResult.descriptor);
+        String descriptor = tokenResult.descriptor;
+        int payloadStart = tokenResult.payloadStart;
+
+        ServerSharedLog.log("onTransact: code=" + code + ", callingUid=" + callingUid + ", callingPid=" + callingPid
+                + ", descriptor=" + descriptor + ", isLegacy=" + isLegacy + ", isNew=" + isNew + ", isOldLegacy=" + isOldLegacy
+                + ", posBeforeRead=" + posBeforeRead + ", posAfterRead=" + posAfterRead + ", payloadStart=" + payloadStart + ", thread=" + threadInfo);
 
         if (isLegacy || isNew) {
             if (code == ShizukuApiConstants.BINDER_TRANSACTION_transact) {
+                data.setDataPosition(payloadStart);
+                ServerSharedLog.log("onTransact: dispatching code=" + code + " to manual transactRemote");
                 transactRemote(data, reply, flags);
                 return true;
             }
 
-            if (isLegacy) {
-                // Manually handle all methods for legacy callers to avoid descriptor mismatch in super.onTransact
+            if (isOldLegacy) {
+                data.setDataPosition(payloadStart);
+                // Manually handle all methods for old legacy callers (v11/v12) to avoid descriptor mismatch and code collisions
                 switch (code) {
                     case 2: // getVersion
+                        ServerSharedLog.log("onTransact: dispatching code=" + code + " to manual getVersion (old legacy)");
                         reply.writeNoException();
                         reply.writeInt(getVersion());
                         return true;
                     case 3: // getUid
+                        ServerSharedLog.log("onTransact: dispatching code=" + code + " to manual getUid (old legacy)");
                         reply.writeNoException();
                         reply.writeInt(getUid());
                         return true;
                     case 4: // checkPermission
+                        ServerSharedLog.log("onTransact: dispatching code=" + code + " to manual checkPermission (old legacy)");
                         reply.writeNoException();
                         reply.writeInt(checkPermission(data.readString()));
                         return true;
                     case 7: // newProcess
+                        ServerSharedLog.log("onTransact: dispatching code=" + code + " to manual newProcess (old legacy)");
                         String[] cmd = data.createStringArray();
                         String[] env = data.createStringArray();
                         String dir = data.readString();
@@ -439,10 +481,12 @@ public abstract class Service<
                         reply.writeStrongBinder(process != null ? process.asBinder() : null);
                         return true;
                     case 8: // getSELinuxContext
+                        ServerSharedLog.log("onTransact: dispatching code=" + code + " to manual getSELinuxContext (old legacy)");
                         reply.writeNoException();
                         reply.writeString(getSELinuxContext());
                         return true;
                     case 14: // legacy attachApplication
+                        ServerSharedLog.log("onTransact: dispatching code=" + code + " to manual attachApplication (old legacy)");
                         IBinder binder = data.readStrongBinder();
                         String packageName = data.readString();
                         Bundle args = new Bundle();
@@ -452,13 +496,16 @@ public abstract class Service<
                         reply.writeNoException();
                         return true;
                 }
-            } else {
-                // Shizuku+ specific handling for code 14 and 17
+            } else if (isNew) {
+                data.setDataPosition(payloadStart);
+                // Shizuku+ specific handling for code 14 and 17 (only for new descriptor af.shizuku.server.IShizukuService)
                 if (code == 14 /* requestPermission */) {
+                    ServerSharedLog.log("onTransact: dispatching code=" + code + " to manual requestPermission");
                     requestPermission(data.readInt());
                     reply.writeNoException();
                     return true;
                 } else if (code == IBinder.FIRST_CALL_TRANSACTION + 17 /* attachApplication v13+ (18) */) {
+                    ServerSharedLog.log("onTransact: dispatching code=" + code + " to manual attachApplication v13+");
                     IBinder binder = data.readStrongBinder();
                     Bundle args = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                     attachApplication(IShizukuApplication.Stub.asInterface(binder), args);
@@ -468,9 +515,60 @@ public abstract class Service<
             }
         }
 
-        if (rishService.onTransact(code, data, reply, flags)) {
-            return true;
+        // For legacy descriptors, we rewrite the Parcel to use the generated Stub's DESCRIPTOR
+        // so that enforceInterface() passes inside the generated Stub class.
+        Parcel targetData = data;
+        boolean rewritten = false;
+        if (isLegacy) {
+            if (payloadStart > 0 && payloadStart <= data.dataSize()) {
+                ServerSharedLog.log("onTransact: rewriting legacy parcel for code=" + code + ", payloadStart=" + payloadStart);
+                targetData = Parcel.obtain();
+                targetData.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
+                targetData.appendFrom(data, payloadStart, data.dataSize() - payloadStart);
+                targetData.setDataPosition(0);
+                rewritten = true;
+            } else {
+                ServerSharedLog.log("onTransact rewriting failed: invalid payloadStart=" + payloadStart + " for dataSize=" + data.dataSize());
+                data.setDataPosition(0);
+            }
+        } else {
+            if (!tokenResult.success) {
+                ServerSharedLog.log("onTransact detection failure: no known descriptor matched for code=" + code + ", callingUid=" + callingUid + ", callingPid=" + callingPid);
+            }
+            data.setDataPosition(0);
+        }
+
+        try {
+            if (rewritten) {
+                targetData.setDataPosition(0);
+            } else {
+                data.setDataPosition(0);
+            }
+            ServerSharedLog.log("onTransact rishService dispatch: code=" + code + ", targetMode=" + (rewritten ? "rewritten" : "original") 
+                + ", targetDataSize=" + targetData.dataSize() + ", targetDataPos=" + targetData.dataPosition());
+            if (rishService.onTransact(code, targetData, reply, flags)) {
+                ServerSharedLog.log("onTransact: rishService handled code=" + code);
+                return true;
+            }
+            if (rewritten) {
+                targetData.setDataPosition(0);
+            } else {
+                data.setDataPosition(0);
+            }
+            ServerSharedLog.log("onTransact super.onTransact dispatch: code=" + code + ", targetMode=" + (rewritten ? "rewritten" : "original") 
+                + ", targetDataSize=" + targetData.dataSize() + ", targetDataPos=" + targetData.dataPosition());
+            return super.onTransact(code, targetData, reply, flags);
+        } catch (SecurityException e) {
+            ServerSharedLog.log("onTransact SecurityException caught: msg=" + e.getMessage() 
+                + ", originallyReadDescriptor=" + descriptor + ", rewritten=" + rewritten + ", code=" + code 
+                + ", callingUid=" + callingUid + ", callingPid=" + callingPid 
+                + ", targetDataSize=" + targetData.dataSize() + ", targetDataPos=" + targetData.dataPosition()
+                + ", payloadStart=" + payloadStart);
+            throw e;
+        } finally {
+            if (rewritten) {
+                targetData.recycle();
+            }
         }
-        return super.onTransact(code, data, reply, flags);
     }
 }
