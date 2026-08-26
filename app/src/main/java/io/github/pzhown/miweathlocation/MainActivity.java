package io.github.pzhown.miweathlocation;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
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
    private static final String SETTINGS = "com.android.settings";
    private static final int PER_USER_RANGE = 100000;

    private TextView output;
    private volatile String lastRootProbe = "Root injection probe: not run\n";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("MiWeatherLocation 注入诊断");
        title.setTextSize(22f);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("天气是实际目标；系统设置只是无 Hook 的对照探针。Root 注入检查会读取天气进程 maps，判断 LSPosed/Zygisk native 层是否真的进入目标进程。");
        hint.setPadding(0, dp(8), 0, dp(12));
        root.addView(hint);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        Button refresh = new Button(this);
        refresh.setText("刷新诊断");
        refresh.setOnClickListener(v -> refreshDiagnostics());
        row1.addView(refresh, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button copy = new Button(this);
        copy.setText("复制诊断");
        copy.setOnClickListener(v -> copyDiagnostics());
        row1.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        Button openWeather = new Button(this);
        openWeather.setText("打开小米天气");
        openWeather.setOnClickListener(v -> openPackage(WEATHER));
        row2.addView(openWeather, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button openSettings = new Button(this);
        openSettings.setText("打开系统设置");
        openSettings.setOnClickListener(v -> openSettings());
        row2.addView(openSettings, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row2);

        Button requestSettingsScope = new Button(this);
        requestSettingsScope.setText("把系统设置加入诊断作用域");
        requestSettingsScope.setOnClickListener(v -> requestSettingsScope());
        root.addView(requestSettingsScope);

        Button rootProbe = new Button(this);
        rootProbe.setText("Root 注入检查");
        rootProbe.setOnClickListener(v -> runRootInjectionProbe());
        root.addView(rootProbe);

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
        refreshDiagnostics();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.postDelayed(this::refreshDiagnostics, 300L);
    }

    private void refreshDiagnostics() {
        StringBuilder sb = new StringBuilder();
        int moduleUid = Process.myUid();
        int userId = moduleUid / PER_USER_RANGE;
        sb.append("App version: 0.1.4-root-probe\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Current userId: ").append(userId).append('\n');
        sb.append("Module uid: ").append(moduleUid).append("\n\n");

        appendPackageInfo(sb, "Weather package", WEATHER);
        appendPackageInfo(sb, "Settings probe", SETTINGS);
        sb.append('\n');

        XposedService service = App.getService();
        if (service == null) {
            sb.append("Xposed Service: NOT CONNECTED\n");
            sb.append("Conclusion: module app is not connected to LSPosed service.\n\n");
            sb.append(lastRootProbe);
            output.setText(sb.toString());
            return;
        }

        try {
            sb.append("Xposed Service: CONNECTED\n");
            sb.append("Framework: ").append(service.getFrameworkName())
                    .append(' ').append(service.getFrameworkVersion()).append('\n');
            sb.append("Framework code: ").append(service.getFrameworkVersionCode()).append('\n');
            sb.append("API: ").append(service.getApiVersion()).append('\n');
            sb.append("Properties: 0x").append(Long.toHexString(service.getFrameworkProperties())).append('\n');

            List<String> scope = service.getScope();
            sb.append("Scope: ").append(scope).append('\n');
            sb.append("Scope contains weather: ").append(scope.contains(WEATHER)).append('\n');
            sb.append("Scope contains settings probe: ").append(scope.contains(SETTINGS)).append('\n');

            if (service.getApiVersion() >= 102) {
                var targets = service.getRunningTargets();
                sb.append("Running targets count: ").append(targets.size()).append('\n');
                for (var target : targets) {
                    sb.append(" - ")
                            .append(target.getProcessName())
                            .append(" pid=").append(target.getPid())
                            .append(" uid=").append(target.getUid())
                            .append(" state=").append(target.getState())
                            .append(" loadedVersionCode=").append(target.getLoadedVersionCode())
                            .append('\n');
                }

                boolean weatherRunning = targets.stream()
                        .anyMatch(t -> WEATHER.equals(t.getProcessName()));
                boolean settingsRunning = targets.stream()
                        .anyMatch(t -> SETTINGS.equals(t.getProcessName()));
                sb.append("Weather hooked target present: ").append(weatherRunning).append('\n');
                sb.append("Settings probe hooked target present: ").append(settingsRunning).append('\n');
                sb.append("\nService conclusion: ");
                if (settingsRunning && !weatherRunning) {
                    sb.append("LSPosed injection works; Xiaomi Weather is being skipped specifically.\n");
                } else if (!settingsRunning && !weatherRunning) {
                    sb.append("No scoped probe is loaded; inspect native injection/root backend.\n");
                } else if (weatherRunning) {
                    sb.append("Weather module entry is loaded; continue with hook/SQLite diagnostics.\n");
                } else {
                    sb.append("Mixed target state; inspect the target list above.\n");
                }
            }
        } catch (Throwable t) {
            sb.append("\nDiagnostic exception: ")
                    .append(t.getClass().getName()).append(": ")
                    .append(t.getMessage()).append('\n');
            for (StackTraceElement e : t.getStackTrace()) {
                sb.append("  at ").append(e).append('\n');
                if (sb.length() > 10000) break;
            }
        }

        sb.append('\n').append(lastRootProbe);
        output.setText(sb.toString());
    }

    private void appendPackageInfo(StringBuilder sb, String label, String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            sb.append(label).append(": installed=true")
                    .append(" uid=").append(info.uid)
                    .append(" process=").append(info.processName)
                    .append(" system=").append((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                    .append(" enabled=").append(info.enabled)
                    .append('\n');
        } catch (Throwable t) {
            sb.append(label).append(": installed=false/error=")
                    .append(t.getClass().getSimpleName()).append(':')
                    .append(t.getMessage()).append('\n');
        }
    }

    private void requestSettingsScope() {
        XposedService service = App.getService();
        if (service == null) {
            Toast.makeText(this, "Xposed Service 未连接", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            if (service.getScope().contains(SETTINGS)) {
                Toast.makeText(this, "系统设置已在作用域", Toast.LENGTH_SHORT).show();
                refreshDiagnostics();
                return;
            }
            service.requestScope(List.of(SETTINGS), new XposedService.OnScopeEventListener() {
                @Override
                public void onScopeRequestApproved(List<String> approved) {
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this,
                                "已批准诊断作用域: " + approved,
                                Toast.LENGTH_LONG).show();
                        refreshDiagnostics();
                    });
                }

                @Override
                public void onScopeRequestFailed(String message) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                            "作用域请求失败: " + message,
                            Toast.LENGTH_LONG).show());
                }
            });
        } catch (Throwable t) {
            Toast.makeText(this,
                    "作用域请求异常: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void runRootInjectionProbe() {
        Toast.makeText(this, "正在执行 root 注入检查", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String script = "echo '=== root ==='; id; "
                    + "echo '=== magisk ==='; magisk -V 2>/dev/null || true; "
                    + "echo '=== related modules ==='; ls -1 /data/adb/modules 2>/dev/null | grep -Ei 'lsposed|lspd|zygisk|riru' || true; "
                    + "echo '=== lspd processes ==='; ps -A 2>/dev/null | grep -Ei 'lspd|lsposed' || true; "
                    + "PID=$(pidof com.miui.weather2 | awk '{print $1}'); echo weather_pid=$PID; "
                    + "if [ -n \"$PID\" ]; then "
                    + "echo '=== weather injected libraries ==='; "
                    + "grep -Ei 'lsposed|lspd|lsplant|zygisk|libxposed' /proc/$PID/maps 2>/dev/null | head -n 100 || true; "
                    + "fi";
            StringBuilder result = new StringBuilder("Root injection probe:\n");
            try {
                java.lang.Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", script});
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                     BufferedReader error = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.append(line).append('\n');
                    }
                    while ((line = error.readLine()) != null) {
                        result.append("stderr: ").append(line).append('\n');
                    }
                }
                int exit = proc.waitFor();
                result.append("exitCode=").append(exit).append('\n');
                if (result.indexOf("weather_pid=\n") >= 0) {
                    result.append("Root conclusion: Xiaomi Weather is not running. Open it, then run this probe again.\n");
                } else if (result.indexOf("=== weather injected libraries ===\nexitCode=") >= 0) {
                    result.append("Root conclusion: weather is running but no LSPosed/Zygisk-related mapping was found; native injection is likely missing/skipped.\n");
                } else {
                    result.append("Root conclusion: related mappings were found; if Running Targets is still 0, investigate Modern Java entry loading/configuration.\n");
                }
            } catch (Throwable t) {
                result.append("Root probe exception: ")
                        .append(t.getClass().getName()).append(": ")
                        .append(t.getMessage()).append('\n');
            }
            lastRootProbe = result.toString();
            mainHandler.post(this::refreshDiagnostics);
        }, "MiWeatherLocation-RootProbe").start();
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("MiWeatherLocation diagnostics", output.getText()));
            Toast.makeText(this, "诊断已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void openPackage(String packageName) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            Toast.makeText(this, "未找到 " + packageName, Toast.LENGTH_SHORT).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void openSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            openPackage(SETTINGS);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
