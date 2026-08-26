package io.github.pzhown.miweathlocation;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity {
    private static final String WEATHER = "com.miui.weather2";
    private static final int PER_USER_RANGE = 100000;

    private TextView output;
    private volatile String nativeStatus = "Native status: loading...\n";

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
        hint.setText("Weather 18 是 hasCode=false 的 HyperOS Native/Rust 应用。主实现已切换为 Zygisk Native；LSPosed 信息仅保留用于对照诊断。");
        hint.setPadding(0, dp(8), 0, dp(12));
        root.addView(hint);

        Button nativeConfig = new Button(this);
        nativeConfig.setText("Native 配置 / 日志");
        nativeConfig.setOnClickListener(v -> startActivity(new Intent(this, NativeConfigActivity.class)));
        root.addView(nativeConfig);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button openWeather = new Button(this);
        openWeather.setText("打开小米天气");
        openWeather.setOnClickListener(v -> openWeather());
        row.addView(openWeather, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button refresh = new Button(this);
        refresh.setText("刷新状态");
        refresh.setOnClickListener(v -> refreshAll());
        row.addView(refresh, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

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
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        setContentView(root);
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private void refreshAll() {
        refreshDiagnostics();
        refreshNativeStatus();
    }

    private void refreshDiagnostics() {
        StringBuilder sb = new StringBuilder();
        int moduleUid = Process.myUid();
        int userId = moduleUid / PER_USER_RANGE;
        sb.append("App version: 0.2.0-native-alpha\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Current userId: ").append(userId).append('\n');
        sb.append("Module uid: ").append(moduleUid).append("\n\n");

        appendPackageInfo(sb, "Weather package", WEATHER);
        sb.append('\n');

        XposedService service = App.getService();
        if (service == null) {
            sb.append("Legacy LSPosed service: NOT CONNECTED\n");
        } else {
            try {
                sb.append("Legacy LSPosed service: CONNECTED\n");
                sb.append("Framework: ").append(service.getFrameworkName())
                        .append(' ').append(service.getFrameworkVersion()).append('\n');
                sb.append("API: ").append(service.getApiVersion()).append('\n');
                List<String> scope = service.getScope();
                sb.append("Legacy scope: ").append(scope).append('\n');
                if (service.getApiVersion() >= 102) {
                    var targets = service.getRunningTargets();
                    sb.append("Legacy running targets: ").append(targets.size()).append('\n');
                    for (var target : targets) {
                        sb.append(" - ").append(target.getProcessName())
                                .append(" pid=").append(target.getPid())
                                .append(" state=").append(target.getState())
                                .append('\n');
                    }
                }
            } catch (Throwable t) {
                sb.append("Legacy LSPosed diagnostic error: ")
                        .append(t.getClass().getSimpleName()).append(": ")
                        .append(t.getMessage()).append('\n');
            }
        }

        sb.append('\n').append(nativeStatus);
        output.setText(sb.toString());
    }

    private void refreshNativeStatus() {
        new Thread(() -> {
            String command = "echo '=== Native module ==='; "
                    + "if [ -f /data/adb/modules/miweatherlocation/module.prop ]; then "
                    + "cat /data/adb/modules/miweatherlocation/module.prop; else echo 'not installed'; fi; "
                    + "echo '=== Weather process ==='; pidof com.miui.weather2 || true; "
                    + "echo '=== Config ==='; cat /data/adb/miweatherlocation/config.properties 2>/dev/null || echo 'config missing'; "
                    + "echo '=== Latest native log ==='; "
                    + "for F in /data/user_de/0/com.miui.weather2/files/miweatherlocation_native.log /data/user/0/com.miui.weather2/files/miweatherlocation_native.log; do "
                    + "if [ -f \"$F\" ]; then echo \"--- $F ---\"; tail -n 40 \"$F\"; fi; done";
            StringBuilder result = new StringBuilder("Native status:\n");
            try {
                java.lang.Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                     BufferedReader error = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) result.append(line).append('\n');
                    while ((line = error.readLine()) != null) result.append("stderr: ").append(line).append('\n');
                }
                result.append("exitCode=").append(proc.waitFor()).append('\n');
            } catch (Throwable t) {
                result.append("root status error: ").append(t.getClass().getSimpleName())
                        .append(": ").append(t.getMessage()).append('\n');
            }
            nativeStatus = result.toString();
            runOnUiThread(this::refreshDiagnostics);
        }, "MiWeatherLocation-NativeStatus").start();
    }

    private void appendPackageInfo(StringBuilder sb, String label, String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            sb.append(label).append(": installed=true")
                    .append(" uid=").append(info.uid)
                    .append(" process=").append(info.processName)
                    .append(" hasCode=").append((info.flags & ApplicationInfo.FLAG_HAS_CODE) != 0)
                    .append(" enabled=").append(info.enabled)
                    .append('\n');
        } catch (Throwable t) {
            sb.append(label).append(": query-error=")
                    .append(t.getClass().getSimpleName()).append(':')
                    .append(t.getMessage()).append('\n');
        }
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
