package io.github.pzhown.miweathlocation;

import android.content.Context;
import android.content.Intent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LegacyEntry implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    private static final String TARGET = "com.miui.weather2";
    private static final String MODULE = "io.github.pzhown.miweathlocation";
    private static final String RUST_PROCESS_IMPL = "android.os.RustProcessImpl";
    private static final String START_RUST_PROCESS = "startRustProcess";
    private static final String PROXY_LIBRARY = "libmiweatherlocation.so";
    private static final String ENV_ORIGINAL = "MIWEATHERLOCATION_ORIGINAL_BINARY";
    private static final String STATUS_ACTION = MODULE + ".RUSTPROCESS_STATUS";
    private static final int ARG_PACKAGE_NAME = 1;
    private static final int ARG_BINARY_PATH = 20;
    private static final int ARG_ENVIRONMENTS = 21;

    private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile String modulePath = "";

    @Override
    public void initZygote(StartupParam startupParam) {
        modulePath = startupParam == null || startupParam.modulePath == null
                ? "" : startupParam.modulePath;
        XposedBridge.log("MiWeatherLocation RustProcess bootstrap init modulePath=" + modulePath);
        installRustProcessHook("initZygote");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !isSystemServer(lpparam.packageName, lpparam.processName)) return;
        XposedBridge.log("MiWeatherLocation system_server entry package="
                + lpparam.packageName + " process=" + lpparam.processName);
        installRustProcessHook("handleLoadPackage");
    }

    private static boolean isSystemServer(String packageName, String processName) {
        return "android".equals(packageName)
                || "android".equals(processName)
                || "system".equals(processName);
    }

    private static void installRustProcessHook(String source) {
        if (INSTALLED.get() || !INSTALLING.compareAndSet(false, true)) return;
        try {
            Class<?> clazz = Class.forName(RUST_PROCESS_IMPL);
            int count = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!START_RUST_PROCESS.equals(method.getName())
                        || Modifier.isAbstract(method.getModifiers())) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        interceptRustStart(param);
                    }
                });
                count++;
            }
            if (count > 0) {
                INSTALLED.set(true);
                XposedBridge.log("MiWeatherLocation RustProcess hook ready source="
                        + source + " methods=" + count);
                sendStatus("RUSTPROCESS_HOOK_READY", "methods=" + count);
            } else {
                XposedBridge.log("MiWeatherLocation RustProcess hook missing method="
                        + START_RUST_PROCESS + " source=" + source);
            }
        } catch (Throwable t) {
            XposedBridge.log("MiWeatherLocation RustProcess hook unavailable source="
                    + source + " error=" + t);
        } finally {
            INSTALLING.set(false);
        }
    }

    private static void interceptRustStart(XC_MethodHook.MethodHookParam param) {
        try {
            Object[] args = param.args;
            if (args == null || args.length == 0) return;
            int packageIndex = findPackageIndex(args);
            if (packageIndex < 0) return;

            int binaryIndex = findBinaryIndex(args);
            if (binaryIndex < 0) {
                String summary = summarizeStringArgs(args);
                XposedBridge.log("MiWeatherLocation Weather Rust spawn seen but binary unresolved args=" + summary);
                sendStatus("WEATHER_SPAWN_BINARY_UNRESOLVED", summary);
                return;
            }
            if (args.length <= ARG_ENVIRONMENTS) {
                String detail = "args=" + args.length + " binaryIndex=" + binaryIndex;
                XposedBridge.log("MiWeatherLocation Weather Rust spawn env arg missing " + detail);
                sendStatus("WEATHER_SPAWN_ENV_UNRESOLVED", detail);
                return;
            }

            String originalBinary = String.valueOf(args[binaryIndex]);
            String proxyBinary = buildProxyBinaryPath();
            if (proxyBinary.isEmpty()) {
                XposedBridge.log("MiWeatherLocation proxy path unavailable modulePath=" + modulePath);
                sendStatus("PROXY_PATH_UNAVAILABLE", "modulePath=" + modulePath);
                return;
            }

            String existingEnv = args[ARG_ENVIRONMENTS] instanceof String
                    ? (String) args[ARG_ENVIRONMENTS] : "";
            String updatedEnv = appendEnvironment(existingEnv, ENV_ORIGINAL, originalBinary);
            Object[] updated = Arrays.copyOf(args, args.length);
            updated[binaryIndex] = proxyBinary;
            updated[ARG_ENVIRONMENTS] = updatedEnv;
            param.args = updated;

            String detail = "packageIndex=" + packageIndex
                    + " binaryIndex=" + binaryIndex
                    + " original=" + originalBinary
                    + " proxy=" + proxyBinary
                    + " env=" + updatedEnv;
            XposedBridge.log("MiWeatherLocation Weather Rust spawn intercepted " + detail);
            sendStatus("WEATHER_RUST_SPAWN_INTERCEPTED", detail);
        } catch (Throwable t) {
            XposedBridge.log("MiWeatherLocation Weather Rust spawn intercept failed: " + t);
            sendStatus("WEATHER_RUST_SPAWN_INTERCEPT_FAILED", t.toString());
        }
    }

    private static int findPackageIndex(Object[] args) {
        if (args.length > ARG_PACKAGE_NAME && TARGET.equals(args[ARG_PACKAGE_NAME])) return ARG_PACKAGE_NAME;
        for (int i = 0; i < args.length; i++) if (TARGET.equals(args[i])) return i;
        return -1;
    }

    private static int findBinaryIndex(Object[] args) {
        if (args.length > ARG_BINARY_PATH && isWeatherBinary(args[ARG_BINARY_PATH])) return ARG_BINARY_PATH;
        for (int i = 0; i < args.length; i++) if (isWeatherBinary(args[i])) return i;
        return -1;
    }

    private static boolean isWeatherBinary(Object value) {
        if (!(value instanceof String)) return false;
        String text = (String) value;
        return text.contains("libweather_app.so")
                || (text.contains("com.miui.weather2") && text.contains(".so"));
    }

    private static String buildProxyBinaryPath() {
        String path = modulePath;
        if (path == null || path.isBlank()) return "";
        return path + "!/lib/arm64-v8a/" + PROXY_LIBRARY;
    }

    // Match the argument shape used by HyperOS RustProcess implementations and
    // the working DPIS Weather/Gallery hook: preserve the existing string,
    // separate another environment with " --envs=", and keep the cold-boot flag.
    private static String appendEnvironment(String existing, String key, String value) {
        StringBuilder builder = new StringBuilder();
        if (existing != null && !existing.trim().isEmpty()) {
            builder.append(existing.trim());
            if (builder.charAt(builder.length() - 1) != ',') {
                builder.append(',');
            }
        }
        if (builder.length() > 0) {
            builder.append(" --envs=");
        }
        builder.append(key).append('=').append(sanitizeEnvironmentValue(value));
        builder.append(" --cold-boot-speed");
        return builder.toString();
    }

    private static String sanitizeEnvironmentValue(String value) {
        if (value == null) return "";
        return value.replace(',', '_').replace('\n', '_').replace('\r', '_').replace(' ', '_');
    }

    private static String summarizeStringArgs(Object[] args) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof String)) continue;
            String value = (String) args[i];
            if (value.isEmpty()) continue;
            if (builder.length() > 0) builder.append(" | ");
            builder.append(i).append('=').append(value.length() > 180
                    ? value.substring(0, 180) + "..." : value);
        }
        return builder.toString();
    }

    private static void sendStatus(String stage, String detail) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = activityThread.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            Object thread = currentActivityThread.invoke(null);
            if (thread == null) return;
            Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Object value = getSystemContext.invoke(thread);
            if (!(value instanceof Context)) return;
            Context context = (Context) value;
            Intent intent = new Intent(STATUS_ACTION);
            intent.setPackage(MODULE);
            intent.putExtra("stage", stage);
            intent.putExtra("detail", detail == null ? "" : detail);
            intent.putExtra("timestamp", System.currentTimeMillis());
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }
}
