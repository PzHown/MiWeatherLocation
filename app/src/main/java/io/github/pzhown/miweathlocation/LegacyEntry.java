package io.github.pzhown.miweathlocation;

import android.app.Application;
import android.content.Intent;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.AndroidAppHelper;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;

/**
 * Legacy LSPosed bootstrap entry.
 *
 * Xiaomi Weather 18 declares android:hasCode=false. The modern libxposed
 * module entry never becomes a running target on the tested LSPosed 2.1.1
 * build, so this entry intentionally uses the legacy bootstrap path that
 * LSPosed loads earlier in a scoped process. The module still ships as one
 * ordinary LSPosed APK; there is no MiWeatherLocation Magisk/Zygisk module.
 */
public final class LegacyEntry implements IXposedHookZygoteInit {
    private static final String TARGET = "com.miui.weather2";
    private static final String MODULE = "io.github.pzhown.miweathlocation";
    private static final String ACTION = MODULE + ".LEGACY_BOOTSTRAP_LOADED";

    @Override
    public void initZygote(StartupParam startupParam) {
        String process = readProcessName();
        if (!isWeatherProcess(process)) {
            return;
        }

        XposedBridge.log("MiWeatherLocation legacy bootstrap entered " + process);
        try {
            System.loadLibrary("miweatherlocation");
            XposedBridge.log("MiWeatherLocation native payload loaded in " + process);
        } catch (Throwable t) {
            XposedBridge.log("MiWeatherLocation native payload load failed: " + t);
        }

        // Leave a rootless diagnostic marker in the module app. initZygote is
        // intentionally early, so wait until Android has created an Application
        // object before sending the explicit broadcast.
        Thread marker = new Thread(() -> {
            for (int i = 0; i < 80; i++) {
                try {
                    Application app = AndroidAppHelper.currentApplication();
                    if (app != null) {
                        Intent intent = new Intent(ACTION);
                        intent.setPackage(MODULE);
                        intent.putExtra("process", process);
                        intent.putExtra("timestamp", System.currentTimeMillis());
                        app.sendBroadcast(intent);
                        return;
                    }
                    Thread.sleep(250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable ignored) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "MiWeatherLocation-LegacyMarker");
        marker.setDaemon(true);
        marker.start();
    }

    private static boolean isWeatherProcess(String process) {
        return TARGET.equals(process)
                || (process != null && process.startsWith(TARGET + ":"));
    }

    private static String readProcessName() {
        byte[] buffer = new byte[256];
        try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
            int n = in.read(buffer);
            if (n <= 0) return "";
            int end = 0;
            while (end < n && buffer[end] != 0) end++;
            return new String(buffer, 0, end, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
