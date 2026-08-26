package io.github.pzhown.miweathlocation;

import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * Pure LSPosed/libxposed API 102 entry for Xiaomi Weather 18.
 *
 * Weather 18 is a HyperOS native/Rust package with android:hasCode=false. The
 * Java entry is intentionally tiny: once LSPosed loads this module into the
 * Weather process it loads the APK-embedded native payload. The native payload
 * waits for libweather_app.so and performs the selected-city operation inside
 * the Weather process. No Magisk/Zygisk module is required.
 */
public final class ModuleMain extends XposedModule {
    private static final String TAG = "MiWeatherLocation";
    private static final String TARGET_PACKAGE = "com.miui.weather2";
    private static final AtomicBoolean NATIVE_LOAD_STARTED = new AtomicBoolean();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        String process = param.getProcessName();
        logInfo("onModuleLoaded process=" + process
                + " framework=" + getFrameworkName()
                + " frameworkVersionCode=" + getFrameworkVersionCode()
                + " api=" + getApiVersion());

        if (isWeatherProcess(process)) {
            loadNativePayloadDelayed();
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        logInfo("onPackageReady package=" + param.getPackageName()
                + " firstPackage=" + param.isFirstPackage());
        if (TARGET_PACKAGE.equals(param.getPackageName())) {
            loadNativePayloadDelayed();
        }
    }

    private void loadNativePayloadDelayed() {
        if (!NATIVE_LOAD_STARTED.compareAndSet(false, true)) {
            return;
        }

        Thread loader = new Thread(() -> {
            // Modern LSPosed records META-INF/xposed/native_init.list as the
            // module finishes loading. Delay the first dlopen slightly so both
            // native_init registration and JNI_OnLoad fallback can work.
            for (int attempt = 1; attempt <= 8; attempt++) {
                try {
                    Thread.sleep(attempt == 1 ? 350L : 500L);
                    System.loadLibrary("miweatherlocation");
                    logInfo("embedded native payload loaded attempt=" + attempt);
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logError("native loader interrupted", e);
                    return;
                } catch (Throwable t) {
                    logError("native load attempt=" + attempt + " failed", t);
                }
            }
        }, "MiWeatherLocation-LSPosedNativeLoader");
        loader.setDaemon(true);
        loader.start();
    }

    private static boolean isWeatherProcess(String process) {
        return TARGET_PACKAGE.equals(process)
                || (process != null && process.startsWith(TARGET_PACKAGE + ":"));
    }

    private void logInfo(String text) {
        try {
            log(Log.INFO, TAG, text);
        } catch (Throwable ignored) {
            Log.i(TAG, text);
        }
    }

    private void logError(String text, Throwable t) {
        try {
            log(Log.ERROR, TAG, text, t);
        } catch (Throwable ignored) {
            Log.e(TAG, text, t);
        }
    }
}
