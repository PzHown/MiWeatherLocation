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

import java.util.List;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity {
    private static final String WEATHER = "com.miui.weather2";
    private static final int PER_USER_RANGE = 100000;
    private TextView output;

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
        hint.setText("纯 LSPosed 单 APK 版本。Native payload 已内置在 APK，不需要另外刷 Magisk / Zygisk 模块。目标仍是只添加广州塔收藏城市，不修改真实定位。");
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
        sb.append("App version: 0.3.0-lsposed-native-alpha\n");
        sb.append("Architecture: pure LSPosed APK + embedded arm64 native payload\n");
        sb.append("Magisk module required: false\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Current userId: ").append(userId).append('\n');
        sb.append("Module uid: ").append(moduleUid).append("\n\n");

        appendPackageInfo(sb, "Weather package", WEATHER);
        sb.append('\n');

        XposedService service = App.getService();
        if (service == null) {
            sb.append("Xposed Service: NOT CONNECTED\n");
            sb.append("Conclusion: LSPosed has not connected to the module app yet.\n");
            output.setText(sb.toString());
            return;
        }

        try {
            sb.append("Xposed Service: CONNECTED\n");
            sb.append("Framework: ").append(service.getFrameworkName())
                    .append(' ').append(service.getFrameworkVersion()).append('\n');
            sb.append("Framework code: ").append(service.getFrameworkVersionCode()).append('\n');
            sb.append("API: ").append(service.getApiVersion()).append('\n');
            List<String> scope = service.getScope();
            sb.append("Scope: ").append(scope).append('\n');
            sb.append("Scope contains weather: ").append(scope.contains(WEATHER)).append('\n');

            boolean weatherLoaded = false;
            if (service.getApiVersion() >= 102) {
                var targets = service.getRunningTargets();
                sb.append("Running targets count: ").append(targets.size()).append('\n');
                for (var target : targets) {
                    sb.append(" - ").append(target.getProcessName())
                            .append(" pid=").append(target.getPid())
                            .append(" uid=").append(target.getUid())
                            .append(" state=").append(target.getState())
                            .append('\n');
                    String process = target.getProcessName();
                    if (WEATHER.equals(process) || process.startsWith(WEATHER + ":")) {
                        weatherLoaded = true;
                    }
                }
            }

            sb.append("Weather LSPosed target present: ").append(weatherLoaded).append("\n\n");
            if (weatherLoaded) {
                sb.append("Conclusion: LSPosed loaded this module into Xiaomi Weather. ")
                        .append("The embedded native payload is eligible to run even though Weather 18 hasCode=false.\n");
            } else {
                sb.append("Conclusion: Xiaomi Weather still is not a Running Target. ")
                        .append("If this remains false after force-stop/relaunch, the blocker is LSPosed process injection/scope resolution before our Java/native payload can run.\n");
            }
        } catch (Throwable t) {
            sb.append("LSPosed diagnostic error: ")
                    .append(t.getClass().getSimpleName()).append(": ")
                    .append(t.getMessage()).append('\n');
        }

        output.setText(sb.toString());
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
