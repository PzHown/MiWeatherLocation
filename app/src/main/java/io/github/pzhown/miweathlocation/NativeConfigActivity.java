package io.github.pzhown.miweathlocation;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NativeConfigActivity extends Activity {
    private static final String CONFIG_PATH = "/data/adb/miweatherlocation/config.properties";
    private static final String WEATHER = "com.miui.weather2";

    private CheckBox enabled;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("MiWeatherLocation Native");
        title.setTextSize(22f);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Weather 18 是 hasCode=false 的 HyperOS Native/Rust 应用。这里配置 Zygisk Native 模块要插入的收藏城市；不会修改真实定位行 flag=1 / position=0。");
        hint.setPadding(0, dp(8), 0, dp(8));
        root.addView(hint);

        enabled = new CheckBox(this);
        enabled.setText("启用 Native 收藏城市注入");
        enabled.setChecked(true);
        root.addView(enabled);

        addField(root, "pos_id", "posID", "23.106_113.325");
        addField(root, "name", "名称", "广州塔");
        addField(root, "street_name", "街道/地点", "阅江西路");
        addField(root, "longitude", "经度", "113.324521");
        addField(root, "latitude", "纬度", "23.106428");
        addField(root, "belongings", "归属", "广州市, 广东, 中国");
        addField(root, "extra", "天气 location key", "weathercn:101280108");
        addField(root, "locale", "locale", "zh_cn");

        Button save = new Button(this);
        save.setText("Root 保存配置");
        save.setOnClickListener(v -> saveConfig());
        root.addView(save);

        Button load = new Button(this);
        load.setText("读取当前配置");
        load.setOnClickListener(v -> loadConfig());
        root.addView(load);

        Button log = new Button(this);
        log.setText("读取 Native 日志");
        log.setOnClickListener(v -> readNativeLog());
        root.addView(log);

        Button openWeather = new Button(this);
        openWeather.setText("打开小米天气");
        openWeather.setOnClickListener(v -> openWeather());
        root.addView(openWeather);

        Button copy = new Button(this);
        copy.setText("复制下方状态/日志");
        copy.setOnClickListener(v -> copyStatus());
        root.addView(copy);

        status = new TextView(this);
        status.setTextIsSelectable(true);
        status.setTextSize(13f);
        status.setPadding(0, dp(12), 0, dp(32));
        root.addView(status);

        setContentView(scroll);
        refreshModuleStatus();
    }

    private void addField(LinearLayout root, String key, String label, String value) {
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setPadding(0, dp(8), 0, 0);
        root.addView(labelView);

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(value);
        fields.put(key, edit);
        root.addView(edit);
    }

    private void refreshModuleStatus() {
        runRoot("echo '=== Native module ==='; "
                + "if [ -f /data/adb/modules/miweatherlocation/module.prop ]; then cat /data/adb/modules/miweatherlocation/module.prop; else echo 'not installed'; fi; "
                + "echo '=== Config ==='; cat " + CONFIG_PATH + " 2>/dev/null || echo 'config missing'",
                result -> status.setText(result));
    }

    private void saveConfig() {
        String content = buildConfigText();
        File temp = new File(getCacheDir(), "miweatherlocation-config.properties");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Toast.makeText(this, "写临时配置失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        String command = "mkdir -p /data/adb/miweatherlocation; "
                + "cp " + shellQuote(temp.getAbsolutePath()) + " " + shellQuote(CONFIG_PATH) + "; "
                + "chmod 600 " + shellQuote(CONFIG_PATH) + "; "
                + "echo '=== Saved config ==='; cat " + shellQuote(CONFIG_PATH);
        runRoot(command, result -> {
            status.setText(result);
            Toast.makeText(this, "配置已保存；重启小米天气进程后生效", Toast.LENGTH_LONG).show();
        });
    }

    private String buildConfigText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# MiWeatherLocation Native configuration\n");
        sb.append("enabled=").append(enabled.isChecked() ? "1" : "0").append('\n');
        for (Map.Entry<String, EditText> entry : fields.entrySet()) {
            String value = entry.getValue().getText().toString().replace("\n", " ").replace("\r", " ");
            sb.append(entry.getKey()).append('=').append(value).append('\n');
        }
        return sb.toString();
    }

    private void loadConfig() {
        runRoot("cat " + shellQuote(CONFIG_PATH) + " 2>/dev/null || echo '__MISSING__'", result -> {
            if (result.contains("__MISSING__")) {
                status.setText("Native 配置不存在。安装 Zygisk ZIP 后会生成默认广州塔配置。\n");
                return;
            }
            applyConfig(result);
            status.setText("=== Loaded config ===\n" + result);
        });
    }

    private void applyConfig(String text) {
        for (String raw : text.split("\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if ("enabled".equals(key)) {
                enabled.setChecked(!"0".equals(value) && !"false".equalsIgnoreCase(value));
            } else {
                EditText field = fields.get(key);
                if (field != null) field.setText(value);
            }
        }
    }

    private void readNativeLog() {
        String command = "echo '=== Native module ==='; "
                + "if [ -f /data/adb/modules/miweatherlocation/module.prop ]; then grep -E '^(id|version|versionCode)=' /data/adb/modules/miweatherlocation/module.prop; else echo 'not installed'; fi; "
                + "echo '=== Weather process ==='; pidof com.miui.weather2 || true; "
                + "echo '=== Native logs ==='; "
                + "FOUND=0; "
                + "for F in /data/user_de/0/com.miui.weather2/files/miweatherlocation_native.log /data/user/0/com.miui.weather2/files/miweatherlocation_native.log; do "
                + "if [ -f \"$F\" ]; then FOUND=1; echo \"--- $F ---\"; tail -n 200 \"$F\"; fi; done; "
                + "if [ \"$FOUND\" = 0 ]; then echo 'no native log yet'; fi";
        runRoot(command, result -> status.setText(result));
    }

    private void runRoot(String command, ResultCallback callback) {
        status.setText("执行 root 命令中...\n");
        new Thread(() -> {
            StringBuilder result = new StringBuilder();
            try {
                java.lang.Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
                try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream()));
                     BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = out.readLine()) != null) result.append(line).append('\n');
                    while ((line = err.readLine()) != null) result.append("stderr: ").append(line).append('\n');
                }
                int code = process.waitFor();
                result.append("exitCode=").append(code).append('\n');
            } catch (Throwable t) {
                result.append("root error: ").append(t.getClass().getSimpleName()).append(": ")
                        .append(t.getMessage()).append('\n');
            }
            String text = result.toString();
            runOnUiThread(() -> callback.onResult(text));
        }, "MiWeatherLocation-NativeConfig").start();
    }

    private void openWeather() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(WEATHER);
        if (launch == null) {
            Toast.makeText(this, "未找到小米天气", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(launch);
    }

    private void copyStatus() {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("MiWeatherLocation Native", status.getText()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private static String shellQuote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface ResultCallback {
        void onResult(String result);
    }
}
