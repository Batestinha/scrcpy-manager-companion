package com.diogobatista.scrcpymanager.companion;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.IBinder;
import android.system.Os;

import java.lang.reflect.Method;

/**
 * Physical-display power restoration ported from Genymobile scrcpy's Device,
 * SurfaceControl and DisplayControl classes (Apache License 2.0).
 * Source: https://github.com/Genymobile/scrcpy (retrieved for version 4.1).
 */
@SuppressLint({"PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi"})
final class DisplayPower {
    private static final int POWER_MODE_NORMAL = 2;
    private static final Class<?> SURFACE_CONTROL;
    private static Class<?> displayControl;

    static {
        try { SURFACE_CONTROL = Class.forName("android.view.SurfaceControl"); }
        catch (ClassNotFoundException error) { throw new AssertionError(error); }
    }

    private DisplayPower() { }

    static Boolean isFauxOff() throws Exception {
        Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object manager = global.getMethod("getInstance").invoke(null);
        Object info = global.getMethod("getDisplayInfo", int.class).invoke(manager, 0);
        if (info == null) return null;
        // Real sleep/doze must keep normal call/notification vibration behavior.
        if (info.getClass().getField("state").getInt(info) != 2) return false; // Display.STATE_ON
        return PhysicalDisplayState.allInternalDisplaysOff(
                ShellCommand.run("/system/bin/dumpsys", "SurfaceFlinger", "--displays"));
    }

    static boolean restoreNormal() {
        try {
            boolean multi = Build.VERSION.SDK_INT >= 29;
            if (multi && Build.VERSION.SDK_INT >= 34 && "honor".equalsIgnoreCase(Build.BRAND)
                    && hasMethod(SURFACE_CONTROL, "getInternalDisplayToken")) multi = false;
            if (!multi) {
                Method tokenMethod = Build.VERSION.SDK_INT < 29
                        ? SURFACE_CONTROL.getMethod("getBuiltInDisplay", int.class)
                        : SURFACE_CONTROL.getMethod("getInternalDisplayToken");
                IBinder token = (IBinder) (Build.VERSION.SDK_INT < 29 ? tokenMethod.invoke(null, 0) : tokenMethod.invoke(null));
                return setMode(token);
            }
            Class<?> tokenOwner = SURFACE_CONTROL;
            if (Build.VERSION.SDK_INT >= 34 && !hasMethod(SURFACE_CONTROL, "getPhysicalDisplayIds"))
                tokenOwner = loadDisplayControl();
            long[] ids = (long[]) tokenOwner.getMethod("getPhysicalDisplayIds").invoke(null);
            if (ids == null || ids.length == 0) return false;
            boolean ok = true;
            Method getToken = tokenOwner.getMethod("getPhysicalDisplayToken", long.class);
            for (long id : ids) ok &= setMode((IBinder) getToken.invoke(null, id));
            return ok;
        } catch (Throwable error) {
            android.util.Log.e("ScrcpyMgrCompanion", "Hidden display-power API failed", error);
            return false;
        }
    }

    private static boolean setMode(IBinder token) throws ReflectiveOperationException {
        if (token == null) return false;
        SURFACE_CONTROL.getMethod("setDisplayPowerMode", IBinder.class, int.class)
                .invoke(null, token, POWER_MODE_NORMAL);
        return true;
    }

    private static boolean hasMethod(Class<?> owner, String name) {
        try { owner.getMethod(name); return true; }
        catch (NoSuchMethodException ignored) { return false; }
    }

    private static synchronized Class<?> loadDisplayControl() throws ReflectiveOperationException {
        if (displayControl != null) return displayControl;
        // A Shizuku UserService may already inherit services.jar and android_servers from its
        // app_process. Reuse that defining loader; loading the native library a second time is
        // rejected on current Pixels. Standalone scrcpy needs the fallback loader below.
        try {
            displayControl = Class.forName("com.android.server.display.DisplayControl");
            return displayControl;
        } catch (ClassNotFoundException ignored) {
            // Fall through to scrcpy's standalone app_process bootstrap.
        }
        Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
        Method create = factory.getDeclaredMethod("createClassLoader", String.class, String.class, String.class,
                ClassLoader.class, int.class, boolean.class, String.class);
        ClassLoader loader = (ClassLoader) create.invoke(null, Os.getenv("SYSTEMSERVERCLASSPATH"), null, null,
                ClassLoader.getSystemClassLoader(), 0, true, null);
        Class<?> displayControl = loader.loadClass("com.android.server.display.DisplayControl");
        Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
        load.setAccessible(true);
        load.invoke(Runtime.getRuntime(), displayControl, "android_servers");
        DisplayPower.displayControl = displayControl;
        return DisplayPower.displayControl;
    }
}
