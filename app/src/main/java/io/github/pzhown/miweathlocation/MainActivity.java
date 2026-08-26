package io.github.pzhown.miweathlocation;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity {
    private static final String WEATHER = "com.miui.weather2";
    private static final int PER_USER_RANGE = 100000;
    private TextView output;
    private volatile String rootProbeText = "Root LSPosed fork probe: not run\n";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("MiWeatherLocation");
        title.setTextSize(22f);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("当前只诊断 LSPosed 在 Zygisk fork 阶段为什么跳过小米天气。Native payload 仍内置，不需要另外刷 MiWeatherLocation Magisk 模块。");
        hint.setPadding(0, dp(8), 0, dp(12));
        root.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button openWeather = new Button(this);
        openWeather.setText("打开小米天气");
        openWeather.setOnClickListener(v -> openWeather());
        row.addView(openWeather, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button refresh = new Button(this);
        refresh.setText("刷新状态");
        refresh.setOnClickListener(v -> refreshDiagnostics());
        row.addView(refresh, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        Button rootProbe = new Button(this);
        rootProbe.setText("读取 LSPosed Root 日志");
        rootProbe.setOnClickListener(v -> runRootForkProbe());
        root.addView(rootProbe);

        Button copy = new Button(this);
        copy.setText("复制诊断");
        copy.setOnClickListener(v -> copyDiagnostics());
        root.addView(copy);

        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setTextSize(14f);
        output.setPadding(0, dp(12), 0, dp(24));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshDiagnostics();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDiagnostics();
    }

    private void refreshDiagnostics() {
        StringBuilder sb = new StringBuilder();
        int moduleUid = Process.myUid();
        int userId = moduleUid / PER_USER_RANGE;
        sb.append("App version: 0.3.2-lsposed-fork-probe\n");
        sb.append("Architecture: pure LSPosed legacy bootstrap + embedded arm64 native payload\n");
        sb.append("Modern java_init entry: false\n");
        sb.append("Magisk module required: false\n");
        sb.append("16 KB ELF alignment: enabled at link time\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Current userId: ").append(userId).append('\n');
        sb.append("Module uid: ").append(moduleUid).append("\n\n");

        appendPackageInfo(sb, "Weather package", WEATHER);
        appendLegacyMarker(sb);
        sb.append('\n');

        XposedService service = App.getService();
        if (service == null) {
            sb.append("Xposed Service: NOT CONNECTED\n");
            sb.append("Note: the legacy bootstrap itself does not depend on the app-side service connection.\n");
        } else {
            try {
                sb.append("Xposed Service: CONNECTED\n");
                sb.append("Framework: ").append(service.getFrameworkName())
                        .append(' ').append(service.getFrameworkVersion()).append('\n');
                sb.append("Framework code: ").append(service.getFrameworkVersionCode()).append('\n');
                sb.append("API: ").append(service.getApiVersion()).append('\n');
                List<String> scope = service.getScope();
                sb.append("Scope: ").append(scope).append('\n');
                sb.append("Scope contains weather: ").append(scope.contains(WEATHER)).append('\n');
                sb.append("Unexpected extra scopes: ").append(countExtraScopes(scope)).append('\n');

                if (service.getApiVersion() >= 102) {
                    var targets = service.getRunningTargets();
                    sb.append("Modern Running targets count: ").append(targets.size()).append('\n');
                    for (var target : targets) {
                        sb.append(" - ").append(target.getProcessName())
                                .append(" pid=").append(target.getPid())
                                .append(" uid=").append(target.getUid())
                                .append(" state=").append(target.getState())
                                .append('\n');
                    }
                    sb.append("Note: Running targets is not the success signal for this legacy build.\n");
                }
            } catch (Throwable t) {
                sb.append("LSPosed service diagnostic error: ")
                        .append(t.getClass().getSimpleName()).append(": ")
                        .append(t.getMessage()).append('\n');
            }
        }

        sb.append('\n').append(rootProbeText);
        output.setText(sb.toString());
    }

    private void appendLegacyMarker(StringBuilder sb) {
        SharedPreferences prefs = getSharedPreferences(HookStatusReceiver.PREFS, MODE_PRIVATE);
        long timestamp = prefs.getLong(HookStatusReceiver.KEY_TIMESTAMP, 0L);
        String process = prefs.getString(HookStatusReceiver.KEY_PROCESS, "");
        if (timestamp <= 0L) {
            sb.append("Legacy bootstrap marker: NOT RECEIVED\n");
            return;
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - timestamp);
        sb.append("Legacy bootstrap marker: RECEIVED")
                .append(" process=").append(process)
                .append(" ageMs=").append(ageMs)
                .append('\n');
    }

    private int countExtraScopes(List<String> scope) {
        int count = 0;
        for (String packageName : scope) {
            if (!WEATHER.equals(packageName)) count++;
        }
        return count;
    }

    private void appendPackageInfo(StringBuilder sb, String label, String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            sb.append(label).append(": installed=true")
                    .append(" uid=").append(info.uid)
                    .append(" process=").append(info.processName)
                    .append(" hasCode=").append((info.flags & ApplicationInfo.FLAG_HAS_CODE) != 0)
                    .append(" dataDir=").append(info.dataDir)
                    .append(" enabled=").append(info.enabled)
                    .append('\n');
        } catch (Throwable t) {
            sb.append(label).append(": query-error=")
                    .append(t.getClass().getSimpleName()).append(':')
                    .append(t.getMessage()).append('\n');
        }
    }

    private void runRootForkProbe() {
        rootProbeText = "Root LSPosed fork probe: RUNNING...\n";
        refreshDiagnostics();
        Toast.makeText(this, "正在读取 LSPosed / Zygisk 日志", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            String result;
            try {
                result = executeRootProbe();
            } catch (Throwable t) {
                result = "Root LSPosed fork probe: ERROR " + t.getClass().getSimpleName()
                        + ": " + t.getMessage() + "\n";
            }
            rootProbeText = result;
            runOnUiThread(this::refreshDiagnostics);
        }, "lsposed-fork-probe").start();
    }

    private String executeRootProbe() throws Exception {
        String script = """
                echo '=== root identity ==='
                id
                echo '=== weather package/data ==='
                cmd package path com.miui.weather2 2>&1
                dumpsys package com.miui.weather2 2>/dev/null | grep -E 'dataDir=|credentialProtectedDataDir=|deviceProtectedDataDir=|processName=|flags=' | head -n 100
                echo '=== weather processes ==='
                ps -A 2>/dev/null | grep 'com.miui.weather2' || true
                echo '=== Zygisk Next config ==='
                if [ -f /data/adb/modules/zygisksu/module.prop ]; then grep -E '^(id|name|version|versionCode)=' /data/adb/modules/zygisksu/module.prop; fi
                for f in /data/adb/zygisksu/denylist_enforce /data/adb/zygisksu/memory_type /data/adb/zygisksu/linker; do [ -f "$f" ] && echo "$(basename "$f")=$(cat "$f" 2>/dev/null)"; done
                echo '=== Magisk Zygisk setting ==='
                magisk --sqlite "SELECT key,value FROM settings WHERE key='zygisk';" 2>&1 || true
                echo '=== LSPosed log directory ==='
                ls -la /data/adb/lspd/log 2>&1 || true
                echo '=== persistent LSPosed matches ==='
                for f in /data/adb/lspd/log/* /data/adb/lspd/log/*/*; do
                  [ -f "$f" ] || continue
                  grep -aH -E 'com\\.miui\\.weather2|skip injecting|skipped|no data dir|child zygote|isolated|denylist' "$f" 2>/dev/null
                done | tail -n 260
                echo '=== current logcat matches ==='
                logcat -d -b all 2>/dev/null | grep -E 'LSPosed|lspd|zygisk|Zygisk' | grep -E 'com\\.miui\\.weather2|weather2|skip injecting|skipped|no data dir|child zygote|isolated|denylist' | tail -n 260
                """;

        java.lang.Process process = new ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start();
        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                raw.append(line).append('\n');
                if (raw.length() > 24000) {
                    raw.delete(0, raw.length() - 24000);
                }
            }
        }
        boolean exited = process.waitFor(12, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            raw.append("probe_timeout=true\n");
        } else {
            raw.append("exitCode=").append(process.exitValue()).append('\n');
        }

        String body = raw.toString();
        StringBuilder out = new StringBuilder();
        out.append("Root LSPosed fork probe: COMPLETED\n");
        out.append("Fork diagnosis: ").append(classifyRootProbe(body)).append('\n');
        out.append(body);
        return out.toString();
    }

    private String classifyRootProbe(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        boolean mentionsWeather = lower.contains("com.miui.weather2") || lower.contains("weather2");
        if (mentionsWeather && lower.contains("because it has no data dir")) {
            return "CONFIRMED_LSPOSED_SKIP_APP_DATA_DIR_NULL";
        }
        if (mentionsWeather && lower.contains("because it's a child zygote")) {
            return "CONFIRMED_LSPOSED_SKIP_CHILD_ZYGOTE";
        }
        if (mentionsWeather && lower.contains("because it's isolated")) {
            return "CONFIRMED_LSPOSED_SKIP_ISOLATED_UID";
        }
        if (mentionsWeather && lower.contains("injected xposed into")) {
            return "LSPOSED_CORE_INJECTED_BUT_MODULE_BOOTSTRAP_MISSING";
        }
        if (mentionsWeather && lower.contains("skipped com.miui.weather2")) {
            return "LSPOSED_CORE_SKIPPED_WEATHER_REASON_NOT_LOGGED";
        }
        if (lower.contains("uid=0(root)")) {
            return "ROOT_OK_NO_DECISIVE_LSPOSED_FORK_LINE";
        }
        return "ROOT_UNAVAILABLE_OR_NO_OUTPUT";
    }

    private void openWeather() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(WEATHER);
        if (launch == null) {
            Toast.makeText(this, "未找到小米天气", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(launch);
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("MiWeatherLocation diagnostics", output.getText()));
            Toast.makeText(this, "诊断已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
