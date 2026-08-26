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
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity {
    private static final String WEATHER = "com.miui.weather2";
    private static final String SYSTEM = "system";
    private static final String ANDROID_SCOPE_ALIAS = "android";
    private static final String PROXY = "libmiweatherlocation.so";
    private static final String WEATHER_RUST = "libweather_app.so";
    private static final int PER_USER_RANGE = 100000;

    private TextView output;
    private volatile String operationText = "Proxy deployment: not run\n";
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
        hint.setText("HyperOS 4 Rust 主线：Modern libxposed API 102 在 system_server 的 onSystemServerStarting() Hook RustProcessImpl.startRustProcess；root 仅用于把 proxy 作为 sibling .so 部署到小米天气 native 目录。不需要额外 Magisk 模块。升级后先点“部署/更新 Rust proxy”；如果 APK 是本次开机后更新的，再重启手机一次。");
        hint.setPadding(0, dp(8), 0, dp(12));
        root.addView(hint);

        Button deploy = new Button(this);
        deploy.setText("部署/更新 Rust proxy");
        deploy.setOnClickListener(v -> deployProxy());
        root.addView(deploy);

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
        sb.append("App version: 0.4.2-modern-systemserver-alpha\n");
        sb.append("Architecture: Modern API102 system_server hook + Weather sibling HYOS proxy\n");
        sb.append("Separate Magisk module required: false\n");
        sb.append("Root required for sibling proxy deployment: true\n");
        sb.append("16 KB ELF alignment: enabled\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Current userId: ").append(userId).append('\n');
        sb.append("Module uid: ").append(moduleUid).append('\n');
        boolean updatedAfterBoot = appendBootFreshness(sb);
        sb.append('\n');

        appendPackageInfo(sb, "Weather package", WEATHER);
        appendProxyPaths(sb);
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
                boolean systemScope = scope.contains(SYSTEM);
                boolean androidAlias = scope.contains(ANDROID_SCOPE_ALIAS);
                boolean systemHostVisible = systemScope || androidAlias;
                sb.append("Scope: ").append(scope).append('\n');
                sb.append("Scope contains system virtual target: ").append(systemScope).append('\n');
                sb.append("Scope contains android host alias: ").append(androidAlias).append('\n');
                sb.append("System host scope visible: ").append(systemHostVisible).append('\n');
                sb.append("Scope contains weather: ").append(scope.contains(WEATHER)).append('\n');
                if (!systemHostVisible) {
                    sb.append("ACTION REQUIRED: neither system nor android is visible in the framework scope. Re-enable the module/static scope and reboot.\n");
                } else if (!systemScope && androidAlias) {
                    sb.append("Scope note: this LSPosed service reports android for the system host; do not treat this alone as a missing-system-scope failure. The APK static scope still declares the special system target.\n");
                }
                if (service.getApiVersion() >= 102) {
                    var targets = service.getRunningTargets();
                    boolean systemServerLoaded = false;
                    sb.append("Modern Running targets count: ").append(targets.size()).append('\n');
                    for (var target : targets) {
                        String processName = target.getProcessName();
                        sb.append(" - ").append(processName)
                                .append(" pid=").append(target.getPid())
                                .append(" uid=").append(target.getUid())
                                .append(" state=").append(target.getState())
                                .append('\n');
                        if (isSystemServerProcess(processName)) {
                            systemServerLoaded = true;
                        }
                    }
                    sb.append("System-server hook target present: ").append(systemServerLoaded).append('\n');
                    sb.append("Expected hook host: system_server/system. Weather itself may not appear because it is spawned by hyos_spawner.\n");
                    if (!systemServerLoaded && updatedAfterBoot) {
                        sb.append("REBOOT REQUIRED: this APK was updated after the current boot, so the already-running system_server cannot be expected to contain this module generation yet. Deploy the proxy, reboot once, then open Weather.\n");
                    } else if (!systemServerLoaded) {
                        sb.append("System-server hook not observed after a boot that already contained this APK generation. Use the RustProcess log probe; this is now a framework/system_server loading problem, not a Weather hasCode=false app-process problem.\n");
                    }
                }
            } catch (Throwable t) {
                sb.append("LSPosed service diagnostic error: ")
                        .append(t.getClass().getSimpleName()).append(": ")
                        .append(t.getMessage()).append('\n');
            }
        }

        sb.append('\n').append(operationText);
        sb.append('\n').append(rootProbeText);
        output.setText(sb.toString());
    }

    private boolean appendBootFreshness(StringBuilder sb) {
        try {
            long now = System.currentTimeMillis();
            long bootEpoch = now - SystemClock.elapsedRealtime();
            long lastUpdate = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            boolean updatedAfterBoot = lastUpdate > bootEpoch + 5000L;
            sb.append("Module updated after current boot: ").append(updatedAfterBoot)
                    .append(" lastUpdateEpochMs=").append(lastUpdate)
                    .append(" bootEpochMs≈").append(bootEpoch)
                    .append('\n');
            return updatedAfterBoot;
        } catch (Throwable t) {
            sb.append("Module boot freshness: query-error=")
                    .append(t.getClass().getSimpleName()).append(':')
                    .append(t.getMessage()).append('\n');
            return false;
        }
    }

    private static boolean isSystemServerProcess(String processName) {
        return "system_server".equals(processName)
                || "system".equals(processName)
                || "android".equals(processName);
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
                    .append(" nativeLibraryDir=").append(info.nativeLibraryDir)
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

    private void appendProxyPaths(StringBuilder sb) {
        try {
            ApplicationInfo self = getPackageManager().getApplicationInfo(getPackageName(), 0);
            ApplicationInfo weather = getPackageManager().getApplicationInfo(WEATHER, 0);
            File source = new File(self.nativeLibraryDir, PROXY);
            File original = new File(weather.nativeLibraryDir, WEATHER_RUST);
            File sibling = new File(weather.nativeLibraryDir, PROXY);
            sb.append("Proxy source: ").append(source.getAbsolutePath())
                    .append(" exists=").append(source.isFile())
                    .append(" size=").append(source.isFile() ? source.length() : -1).append('\n');
            sb.append("Weather original: ").append(original.getAbsolutePath())
                    .append(" exists=").append(original.isFile()).append('\n');
            sb.append("Weather sibling proxy: ").append(sibling.getAbsolutePath())
                    .append(" exists=").append(sibling.isFile())
                    .append(" size=").append(sibling.isFile() ? sibling.length() : -1).append('\n');
        } catch (Throwable t) {
            sb.append("Proxy path query error: ").append(t).append('\n');
        }
    }

    private void deployProxy() {
        operationText = "Proxy deployment: RUNNING...\n";
        refreshDiagnostics();
        new Thread(() -> {
            String result;
            try {
                result = executeProxyDeployment();
            } catch (Throwable t) {
                result = "Proxy deployment: ERROR " + t + "\n";
            }
            operationText = result;
            final String deployResult = result;
            runOnUiThread(() -> {
                refreshDiagnostics();
                Toast.makeText(this,
                        deployResult.contains("DEPLOY_OK") ? "Rust proxy 已部署；若本 APK 是本次开机后更新，请重启手机一次" : "Rust proxy 部署失败，请复制诊断",
                        Toast.LENGTH_LONG).show();
            });
        }, "proxy-deploy").start();
    }

    private String executeProxyDeployment() throws Exception {
        ApplicationInfo self = getPackageManager().getApplicationInfo(getPackageName(), 0);
        ApplicationInfo weather = getPackageManager().getApplicationInfo(WEATHER, 0);
        File source = new File(self.nativeLibraryDir, PROXY);
        File original = new File(weather.nativeLibraryDir, WEATHER_RUST);
        File target = new File(weather.nativeLibraryDir, PROXY);

        String script = "set -e\n"
                + "SRC=" + shellQuote(source.getAbsolutePath()) + "\n"
                + "ORIGINAL=" + shellQuote(original.getAbsolutePath()) + "\n"
                + "TARGET=" + shellQuote(target.getAbsolutePath()) + "\n"
                + "TMP=\"$TARGET.tmp\"\n"
                + "echo source=$SRC\n"
                + "echo original=$ORIGINAL\n"
                + "echo target=$TARGET\n"
                + "test -f \"$SRC\"\n"
                + "test -f \"$ORIGINAL\"\n"
                + "am force-stop " + WEATHER + " >/dev/null 2>&1 || true\n"
                + "rm -f \"$TMP\"\n"
                + "cp -f \"$SRC\" \"$TMP\"\n"
                + "uidgid=$(stat -c '%u:%g' \"$ORIGINAL\")\n"
                + "mode=$(stat -c '%a' \"$ORIGINAL\")\n"
                + "chown \"$uidgid\" \"$TMP\" || true\n"
                + "chmod \"$mode\" \"$TMP\" || chmod 755 \"$TMP\"\n"
                + "ctx=$(ls -Zd \"$ORIGINAL\" 2>/dev/null | awk '{print $1}')\n"
                + "if [ -n \"$ctx\" ]; then chcon \"$ctx\" \"$TMP\" 2>/dev/null || true; fi\n"
                + "mv -f \"$TMP\" \"$TARGET\"\n"
                + "if [ -n \"$ctx\" ]; then chcon \"$ctx\" \"$TARGET\" 2>/dev/null || true; fi\n"
                + "test -s \"$TARGET\"\n"
                + "echo '=== deployed file ==='\n"
                + "ls -lZ \"$TARGET\" 2>/dev/null || ls -l \"$TARGET\"\n"
                + "echo '=== hashes ==='\n"
                + "sha256sum \"$SRC\" \"$TARGET\"\n"
                + "a=$(sha256sum \"$SRC\" | awk '{print $1}')\n"
                + "b=$(sha256sum \"$TARGET\" | awk '{print $1}')\n"
                + "[ \"$a\" = \"$b\" ]\n"
                + "echo DEPLOY_OK\n";
        java.lang.Process process = new ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start();
        String commandOutput = readProcessOutput(process, 15);
        boolean success = commandOutput.contains("DEPLOY_OK");
        return "Proxy deployment: " + (success ? "COMPLETED\n" : "FAILED\n") + commandOutput;
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
        ApplicationInfo self = getPackageManager().getApplicationInfo(getPackageName(), 0);
        ApplicationInfo weather = getPackageManager().getApplicationInfo(WEATHER, 0);
        String source = new File(self.nativeLibraryDir, PROXY).getAbsolutePath();
        String sibling = new File(weather.nativeLibraryDir, PROXY).getAbsolutePath();
        String original = new File(weather.nativeLibraryDir, WEATHER_RUST).getAbsolutePath();

        String script = "echo '=== proxy files ==='\n"
                + "ls -lZ " + shellQuote(source) + " " + shellQuote(original) + " " + shellQuote(sibling) + " 2>/dev/null || true\n"
                + "sha256sum " + shellQuote(source) + " " + shellQuote(sibling) + " 2>/dev/null || true\n"
                + "echo '=== relevant processes ==='\n"
                + "ps -A 2>/dev/null | grep -E 'system_server|hyos_spawner|com.miui.weather2' || true\n"
                + "pid=$(ps -A 2>/dev/null | awk '$NF ~ /^com\\.miui\\.weather2(:|$)/ {print $2; exit}')\n"
                + "if [ -n \"$pid\" ]; then\n"
                + "  echo weather_pid=$pid\n"
                + "  printf 'weather_exe='; readlink /proc/$pid/exe 2>/dev/null || true\n"
                + "  echo '=== weather proxy/original maps ==='\n"
                + "  grep -E 'miweatherlocation|libweather_app.so|base.apk' /proc/$pid/maps 2>/dev/null | head -n 100 || true\n"
                + "fi\n"
                + "echo '=== MiWeatherLocation / RustProcess logs ==='\n"
                + "logcat -d -b all 2>/dev/null | grep -E 'MiWeatherLocation|MiWeatherLocationProxy|RustProcessImpl|hyos_spawner|rust fork' | tail -n 420\n"
                + "echo '=== LSPosed persistent matches ==='\n"
                + "for f in /data/adb/lspd/log/* /data/adb/lspd/log/*/*; do\n"
                + "  [ -f \"$f\" ] || continue\n"
                + "  grep -aH -E 'MiWeatherLocation|io\\.github\\.pzhown\\.miweathlocation|ModuleMain|RustProcess hook ready|Weather Rust spawn' \"$f\" 2>/dev/null\n"
                + "done | tail -n 360\n";
        java.lang.Process process = new ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start();
        return "RustProcess runtime probe: COMPLETED\n" + readProcessOutput(process, 15);
    }

    private String readProcessOutput(java.lang.Process process, int timeoutSeconds) throws Exception {
        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                raw.append(line).append('\n');
                if (raw.length() > 36000) {
                    raw.delete(0, raw.length() - 36000);
                }
            }
        }
        boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            raw.append("probe_timeout=true\n");
        } else {
            raw.append("exitCode=").append(process.exitValue()).append('\n');
        }
        return raw.toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
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
