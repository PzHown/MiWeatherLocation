package io.github.pzhown.miweathlocation;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Modern libxposed API 102 entry for HyperOS 4 Rust applications.
 *
 * Xiaomi Weather 18 has android:hasCode=false and is spawned by hyos_spawner,
 * so the module does not wait for a normal app zygote/package lifecycle. Instead
 * it loads in system_server and hooks android.os.RustProcessImpl.startRustProcess,
 * matching the public HyperOS Rust pattern used by DPIS.
 */
public final class ModuleMain extends XposedModule {
    private static final String TAG = "MiWeatherLocation";
    private static final String TARGET = "com.miui.weather2";
    private static final String MODULE = "io.github.pzhown.miweathlocation";
    private static final String STATUS_ACTION = MODULE + ".RUSTPROCESS_STATUS";
    private static final String RUST_PROCESS_IMPL = "android.os.RustProcessImpl";
    private static final String START_RUST_PROCESS = "startRustProcess";
    private static final String PROXY_LIBRARY = "libmiweatherlocation.so";
    private static final String ENV_ORIGINAL = "MIWEATHERLOCATION_ORIGINAL_BINARY";
    private static final int ARG_PACKAGE_NAME = 1;
    private static final int ARG_BINARY_PATH = 20;
    private static final int ARG_ENVIRONMENTS = 21;

    private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private volatile String currentProcessName = "unknown";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        currentProcessName = param == null ? "unknown" : param.getProcessName();
        bridgeLog(Log.INFO, "Modern module loaded process=" + currentProcessName);
        if (isSystemServer(currentProcessName)) {
            installRustProcessHook(null, "module-loaded");
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        ClassLoader loader = param == null ? null : param.getClassLoader();
        bridgeLog(Log.INFO, "onSystemServerStarting process=" + currentProcessName
                + " classLoader=" + describe(loader));
        installRustProcessHook(loader, "system-server-starting");
    }

    private void installRustProcessHook(ClassLoader loader, String source) {
        if (INSTALLED.get() || !INSTALLING.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> clazz = resolveRustProcessClass(loader);
            int count = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!START_RUST_PROCESS.equals(method.getName())
                        || Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                hook(method)
                        .setId("miweather-rustprocess-start")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                List<Object> args = chain.getArgs();
                                Object[] updated = buildUpdatedArgs(args);
                                if (updated != null) {
                                    return chain.proceed(updated);
                                }
                            } catch (Throwable t) {
                                bridgeLog(Log.ERROR, "Rust spawn intercept failed: " + t);
                                sendStatus("WEATHER_RUST_SPAWN_INTERCEPT_FAILED", t.toString());
                            }
                            return chain.proceed();
                        });
                count++;
            }
            if (count == 0) {
                throw new NoSuchMethodException(RUST_PROCESS_IMPL + "." + START_RUST_PROCESS);
            }
            INSTALLED.set(true);
            String detail = "source=" + source + " methods=" + count
                    + " process=" + currentProcessName;
            bridgeLog(Log.INFO, "RustProcess hook ready " + detail);
            sendStatus("RUSTPROCESS_HOOK_READY", detail);
        } catch (Throwable t) {
            bridgeLog(Log.ERROR, "RustProcess hook unavailable source=" + source + " error=" + t);
            sendStatus("RUSTPROCESS_HOOK_UNAVAILABLE", source + ": " + t);
        } finally {
            INSTALLING.set(false);
        }
    }

    private Class<?> resolveRustProcessClass(ClassLoader loader) throws ClassNotFoundException {
        if (loader != null) {
            try {
                return Class.forName(RUST_PROCESS_IMPL, false, loader);
            } catch (ClassNotFoundException ignored) {
                // Framework class is normally visible from the boot class loader.
            }
        }
        return Class.forName(RUST_PROCESS_IMPL);
    }

    private Object[] buildUpdatedArgs(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        int packageIndex = findPackageIndex(args);
        if (packageIndex < 0) {
            return null;
        }

        int binaryIndex = findBinaryIndex(args);
        if (binaryIndex < 0) {
            String summary = summarizeStringArgs(args);
            bridgeLog(Log.WARN, "Weather Rust spawn seen but binary unresolved args=" + summary);
            sendStatus("WEATHER_SPAWN_BINARY_UNRESOLVED", summary);
            return null;
        }
        if (args.size() <= ARG_ENVIRONMENTS) {
            String detail = "args=" + args.size() + " binaryIndex=" + binaryIndex;
            bridgeLog(Log.WARN, "Weather Rust spawn env arg missing " + detail);
            sendStatus("WEATHER_SPAWN_ENV_UNRESOLVED", detail);
            return null;
        }

        String originalBinary = String.valueOf(args.get(binaryIndex));
        String proxyBinary = resolveSiblingProxyLibraryPath(originalBinary);
        if (proxyBinary == null) {
            String detail = "original=" + originalBinary
                    + " expected=" + expectedSiblingProxyPath(originalBinary);
            bridgeLog(Log.WARN, "Weather sibling proxy not deployed " + detail);
            sendStatus("WEATHER_PROXY_NOT_DEPLOYED", detail);
            return null;
        }

        String existingEnv = args.get(ARG_ENVIRONMENTS) instanceof String
                ? (String) args.get(ARG_ENVIRONMENTS) : "";
        String updatedEnv = appendEnvironment(existingEnv, ENV_ORIGINAL, originalBinary);

        Object[] updated = args.toArray();
        updated[binaryIndex] = proxyBinary;
        updated[ARG_ENVIRONMENTS] = updatedEnv;

        String detail = "packageIndex=" + packageIndex
                + " binaryIndex=" + binaryIndex
                + " original=" + originalBinary
                + " proxy=" + proxyBinary
                + " env=" + updatedEnv;
        bridgeLog(Log.INFO, "Weather Rust spawn intercepted " + detail);
        sendStatus("WEATHER_RUST_SPAWN_INTERCEPTED", detail);
        return updated;
    }

    private static int findPackageIndex(List<Object> args) {
        if (args.size() > ARG_PACKAGE_NAME && TARGET.equals(args.get(ARG_PACKAGE_NAME))) {
            return ARG_PACKAGE_NAME;
        }
        for (int i = 0; i < args.size(); i++) {
            if (TARGET.equals(args.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int findBinaryIndex(List<Object> args) {
        if (args.size() > ARG_BINARY_PATH && isWeatherBinary(args.get(ARG_BINARY_PATH))) {
            return ARG_BINARY_PATH;
        }
        for (int i = 0; i < args.size(); i++) {
            if (isWeatherBinary(args.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isWeatherBinary(Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String text = (String) value;
        return text.contains("libweather_app.so")
                || (text.contains(TARGET) && text.contains(".so"));
    }

    private static String resolveSiblingProxyLibraryPath(String originalBinaryPath) {
        String path = expectedSiblingProxyPath(originalBinaryPath);
        if (path == null) {
            return null;
        }
        File proxy = new File(path);
        return proxy.isFile() && proxy.length() > 0 ? proxy.getAbsolutePath() : null;
    }

    private static String expectedSiblingProxyPath(String originalBinaryPath) {
        if (originalBinaryPath == null || originalBinaryPath.isEmpty()) {
            return null;
        }
        File parent = new File(originalBinaryPath).getParentFile();
        return parent == null ? null : new File(parent, PROXY_LIBRARY).getAbsolutePath();
    }

    /** Mirrors DPIS' proven HyperOS RustProcess environment encoding. */
    private static String appendEnvironment(String existing, String key, String value) {
        StringBuilder builder = new StringBuilder();
        if (existing != null && !existing.trim().isEmpty()) {
            builder.append(existing.trim());
            if (builder.charAt(builder.length() - 1) != ',') {
                builder.append(',');
            }
        }
        appendPair(builder, key, value);
        builder.append(" --cold-boot-speed");
        return builder.toString();
    }

    private static void appendPair(StringBuilder builder, String key, String value) {
        if (builder.length() > 0) {
            builder.append(" --envs=");
        }
        builder.append(key).append('=').append(sanitize(value));
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(',', '_')
                .replace('\n', '_')
                .replace('\r', '_')
                .replace(' ', '_');
    }

    private static String summarizeStringArgs(List<Object> args) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            Object raw = args.get(i);
            if (!(raw instanceof String)) {
                continue;
            }
            String value = (String) raw;
            if (value.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(i).append('=').append(value.length() > 180
                    ? value.substring(0, 180) + "..." : value);
        }
        return builder.toString();
    }

    private static boolean isSystemServer(String processName) {
        return "system_server".equals(processName)
                || "system".equals(processName)
                || "android".equals(processName);
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private void bridgeLog(int priority, String message) {
        try {
            log(priority, TAG, message);
        } catch (Throwable ignored) {
            Log.println(priority, TAG, message);
        }
    }

    private static void sendStatus(String stage, String detail) {
        try {
            Context context = getSystemContext();
            if (context == null) {
                return;
            }
            Intent intent = new Intent(STATUS_ACTION);
            intent.setPackage(MODULE);
            intent.putExtra("stage", stage);
            intent.putExtra("detail", detail == null ? "" : detail);
            intent.putExtra("timestamp", System.currentTimeMillis());
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
            // Framework log remains the fallback diagnostic channel.
        }
    }

    private static Context getSystemContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = activityThread.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            Object thread = currentActivityThread.invoke(null);
            if (thread == null) {
                return null;
            }
            Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Object value = getSystemContext.invoke(thread);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
