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
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity {
    private static final String WEATHER = "com.miui.weather2";
    private static final String SYSTEM = "system";
    private static final int PER_USER_RANGE = 100000;
    private TextView output;
    private volatile String rootProbeText = "RustProcess runtime probe: not run\n";

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
        hint.setText("HyperOS 4 Rust 方案：LSPosed 在 system_server Hook RustProcessImpl.startRustProcess，再让内置 native proxy 启动原始小米天气。仍然只有一个 APK，不需要 MiWeatherLocation Magisk 模块。首次升级后请确认 LSPosed 作用域包含“系统框架/system”。");
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
        rootProbe.setText("读取 RustProcess 日志");
        rootProbe.setOnClickListener(v -> runRuntimeProbe());
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
        sb.append("App version: 0.4.0-rustprocess-proxy-alpha\n");
        sb.append("Architecture: LSPosed system_server RustProcess hook + embedded HYOS Weather proxy\n");
        sb.append("Separate Magisk module required: false\n");
        sb.append("16 KB ELF alignment: enabled\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Current userId: ").append(userId).append('\n');
        sb.append("Module uid: ").append(moduleUid).append("\n\n");

        appendPackageInfo(sb, "Weather package", WEATHER);
        appendRustStatus(sb);
        sb.append('\n');

        XposedService service = App.getService();
        if (service == null) {
            sb.append("Xposed Service: NOT CONNECTED\n");
        } else {
            try {
                sb.append("Xposed Service: CONNECTED\n");
                sb.append("Framework: ").append(service.getFrameworkName())
                        .append(' ').append(service.getFrameworkVersion()).append('\n');
                sb.append("Framework code: ").append(service.getFrameworkVersionCode()).append('\n');
                sb.append("API: ").append(service.getApiVersion()).append('\n');
                List<String> scope = service.getScope();
                sb.append("Scope: ").append(scope).append('\n');
                sb.append("Scope contains system: ").append(scope.contains(SYSTEM)).append('\n');
                sb.append("Scope contains weather: ").append(scope.contains(WEATHER)).append('\n');
                if (!scope.contains(SYSTEM)) {
                    sb.append("ACTION REQUIRED: enable 系统框架/system scope; Weather itself is not the Rust hook host.\n");
                }
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
                    sb.append("Note: Weather is spawned by hyos_spawner, so Weather not appearing here is expected on stock LSPosed.\n");
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

    private void appendRustStatus(StringBuilder sb) {
        SharedPreferences prefs = getSharedPreferences(HookStatusReceiver.PREFS, MODE_PRIVATE);
        String stage = prefs.getString(HookStatusReceiver.KEY_RUST_STAGE, "");
        String detail = prefs.getString(HookStatusReceiver.KEY_RUST_DETAIL, "");
        long timestamp = prefs.getLong(HookStatusReceiver.KEY_RUST_TIMESTAMP, 0L);
        if (stage == null || stage.isEmpty() || timestamp <= 0L) {
            sb.append("RustProcess status marker: NOT RECEIVED\n");
            return;
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - timestamp);
        sb.append("RustProcess status marker: ").append(stage)
                .append(" ageMs=").append(ageMs).append('\n');
        if (detail != null && !detail.isEmpty()) {
            sb.append("RustProcess detail: ").append(detail).append('\n');
        }
    }

    private void appendPackageInfo(StringBuilder sb, String label, String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName,
                    android.content.pm.PackageManager.GET_META_DATA);
            sb.append(label).append(": installed=true")
                    .append(" uid=").append(info.uid)
                    .append(" process=").append(info.processName)
                    .append(" hasCode=").append((info.flags & ApplicationInfo.FLAG_HAS_CODE) != 0)
                    .append(" dataDir=").append(info.dataDir)
                    .append(" enabled=").append(info.enabled);
            if (info.metaData != null) {
                sb.append(" hyperos_package=").append(info.metaData.getBoolean("hyperos_package", false))
                        .append(" rustLib=").append(info.metaData.getString("hyperos_app_lib_name", ""))
                        .append(" rustEntry=").append(info.metaData.getString("hyperos_application_entry", ""));
            }
            sb.append('\n');
        } catch (Throwable t) {
            sb.append(label).append(": query-error=")
                    .append(t.getClass().getSimpleName()).append(':')
                    .append(t.getMessage()).append('\n');
        }
    }

    private void runRuntimeProbe() {
        rootProbeText = "RustProcess runtime probe: RUNNING...\n";
        refreshDiagnostics();
        new Thread(() -> {
            String result;
            try {
                result = executeRuntimeProbe();
            } catch (Throwable t) {
                result = "RustProcess runtime probe: ERROR " + t + "\n";
            }
            rootProbeText = result;
            runOnUiThread(this::refreshDiagnostics);
        }, "rustprocess-runtime-probe").start();
    }

    private String executeRuntimeProbe() throws Exception {
        String script = """
                echo '=== weather process ==='
                ps -A 2>/dev/null | grep 'com.miui.weather2' || true
                pid=$(ps -A 2>/dev/null | awk '$NF ~ /^com\\.miui\\.weather2(:|$)/ {print $2; exit}')
                if [ -n "$pid" ]; then
                  echo weather_pid=$pid
                  printf 'weather_exe='; readlink /proc/$pid/exe 2>/dev/null || true
                  echo '=== weather proxy/original maps ==='
                  grep -E 'miweatherlocation|libweather_app.so|base.apk' /proc/$pid/maps 2>/dev/null | head -n 80 || true
                fi
                echo '=== MiWeatherLocation / RustProcess logs ==='
                logcat -d -b all 2>/dev/null | grep -E 'MiWeatherLocation|MiWeatherLocationProxy|RustProcessImpl|hyos_spawner|rust fork' | tail -n 320
                echo '=== LSPosed persistent matches ==='
                for f in /data/adb/lspd/log/* /data/adb/lspd/log/*/*; do
                  [ -f "$f" ] || continue
                  grep -aH -E 'MiWeatherLocation|RustProcessImpl|com\\.miui\\.weather2' "$f" 2>/dev/null
                done | tail -n 220
                """;
        java.lang.Process process = new ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start();
        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                raw.append(line).append('\n');
                if (raw.length() > 30000) raw.delete(0, raw.length() - 30000);
            }
        }
        boolean exited = process.waitFor(12, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            raw.append("probe_timeout=true\n");
        } else {
            raw.append("exitCode=").append(process.exitValue()).append('\n');
        }
        return "RustProcess runtime probe: COMPLETED\n" + raw;
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
