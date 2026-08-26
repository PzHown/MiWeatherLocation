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
import android.os.UserHandle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity {
    private static final String WEATHER = "com.miui.weather2";
    private static final String SETTINGS = "com.android.settings";

    private TextView output;
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
        hint.setText("对照测试：天气是目标，系统设置只作为无 Hook 的加载探针。\n如果设置能出现在 Running Targets 而天气不能，说明问题只在小米天气；如果两者都为 0，问题在 LSPosed/Zygote 注入链。");
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
        sb.append("App version: 0.1.3-injection-probe\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Current userId: ").append(UserHandle.myUserId()).append('\n');
        sb.append("Module uid: ").append(Process.myUid()).append("\n\n");

        appendPackageInfo(sb, "Weather package", WEATHER);
        appendPackageInfo(sb, "Settings probe", SETTINGS);
        sb.append('\n');

        XposedService service = App.getService();
        if (service == null) {
            sb.append("Xposed Service: NOT CONNECTED\n");
            sb.append("Conclusion: module app is not connected to LSPosed service.\n");
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
                sb.append("\nConclusion: ");
                if (settingsRunning && !weatherRunning) {
                    sb.append("LSPosed injection works; Xiaomi Weather is being skipped specifically.\n");
                } else if (!settingsRunning && !weatherRunning) {
                    sb.append("No scoped probe is injected; investigate LSPosed/Zygote/root backend rather than weather hooks.\n");
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
