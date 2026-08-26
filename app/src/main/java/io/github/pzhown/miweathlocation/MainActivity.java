package io.github.pzhown.miweathlocation;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity {
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
        title.setText("MiWeatherLocation 诊断");
        title.setTextSize(22f);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("用于确认 LSPosed Service、Scope 与 Hook 目标进程。\n如果 Scope 正确但 Running Targets 没有 com.miui.weather2，问题发生在 Hook 加载之前。");
        hint.setPadding(0, dp(8), 0, dp(12));
        root.addView(hint);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button refresh = new Button(this);
        refresh.setText("刷新诊断");
        refresh.setOnClickListener(v -> refreshDiagnostics());
        buttons.addView(refresh, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button copy = new Button(this);
        copy.setText("复制诊断");
        copy.setOnClickListener(v -> copyDiagnostics());
        buttons.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttons);

        Button openWeather = new Button(this);
        openWeather.setText("打开小米天气");
        openWeather.setOnClickListener(v -> openWeather());
        root.addView(openWeather);

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
        sb.append("App version: 0.1.2-diagnostic\n");
        sb.append("Expected target: com.miui.weather2\n\n");

        XposedService service = App.getService();
        if (service == null) {
            sb.append("Xposed Service: NOT CONNECTED\n");
            sb.append("结论：LSPosed 没有把 Service binder 提供给本模块。\n");
            sb.append("请确认模块在 LSPosed 中已启用，然后返回此页面点刷新。\n");
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
            sb.append("Scope contains weather: ").append(scope.contains("com.miui.weather2")).append('\n');

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
                        .anyMatch(t -> "com.miui.weather2".equals(t.getProcessName()));
                sb.append("Weather hooked target present: ").append(weatherRunning).append('\n');
            }
        } catch (Throwable t) {
            sb.append("\nDiagnostic exception: ")
                    .append(t.getClass().getName()).append(": ")
                    .append(t.getMessage()).append('\n');
            for (StackTraceElement e : t.getStackTrace()) {
                sb.append("  at ").append(e).append('\n');
                if (sb.length() > 8000) break;
            }
        }

        output.setText(sb.toString());
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("MiWeatherLocation diagnostics", output.getText()));
            Toast.makeText(this, "诊断已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void openWeather() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.miui.weather2");
        if (launch == null) {
            Toast.makeText(this, "未找到小米天气", Toast.LENGTH_SHORT).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
