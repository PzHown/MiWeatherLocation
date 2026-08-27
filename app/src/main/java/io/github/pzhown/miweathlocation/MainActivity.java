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
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity {
    private static final String WEATHER = "com.miui.weather2";
    private static final String NATIVE_LIB = "libmiweatherlocation.so";
    private static final int PER_USER_RANGE = 100000;

    private TextView output;
    private volatile String probeText = "HYOS native probe: not run\n";
    private volatile String cleanupText = "Legacy 0.4.x sibling cleanup: not run\n";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); int p = dp(16); root.setPadding(p,p,p,p);
        TextView title = new TextView(this); title.setText("MiWeatherLocation"); title.setTextSize(22f); root.addView(title);
        TextView hint = new TextView(this); hint.setText("0.8.2 使用支持 HYOS-spawner 的 LSPosed。当前定位星标改为独立 Native 按钮，不再修改天气的 name/street_name；2 km 内存在收藏时显示实心星，否则显示空心星。"); hint.setPadding(0,dp(8),0,dp(12)); root.addView(hint);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        Button openWeather = new Button(this); openWeather.setText("打开小米天气"); openWeather.setOnClickListener(v -> openWeather()); row.addView(openWeather,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
        Button refresh = new Button(this); refresh.setText("刷新状态"); refresh.setOnClickListener(v -> refreshDiagnostics()); row.addView(refresh,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)); root.addView(row);
        Button probe = new Button(this); probe.setText("读取 HYOS Native 诊断"); probe.setOnClickListener(v -> runNativeProbe()); root.addView(probe);
        Button cleanup = new Button(this); cleanup.setText("清理 0.4.x 旧 proxy（可选）"); cleanup.setOnClickListener(v -> cleanupLegacySibling()); root.addView(cleanup);
        Button copy = new Button(this); copy.setText("复制诊断"); copy.setOnClickListener(v -> copyDiagnostics()); root.addView(copy);
        output = new TextView(this); output.setTextIsSelectable(true); output.setTextSize(14f); output.setPadding(0,dp(12),0,dp(24));
        ScrollView scroll = new ScrollView(this); scroll.addView(output); root.addView(scroll,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f));
        setContentView(root); refreshDiagnostics();
    }

    @Override protected void onResume() { super.onResume(); refreshDiagnostics(); }

    private void refreshDiagnostics() {
        StringBuilder sb = new StringBuilder(); int moduleUid = Process.myUid(); int userId = moduleUid / PER_USER_RANGE;
        sb.append("App version: ").append(appVersionName()).append('\n');
        sb.append("Architecture: LSPosed HYOS-spawner native_init -> Weather child\n");
        sb.append("Star UI: independent Native GL button; does not modify city title text\n");
        sb.append("Favorite radius: 2000m\nSeparate Magisk/Zygisk module required: false\nRoot required for module function: false\nRoot used by this UI only for diagnostics / old-proxy cleanup\n16 KB ELF alignment: enabled\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\nCurrent userId: ").append(userId).append("\nModule uid: ").append(moduleUid).append("\n\n");
        appendPackageInfo(sb); appendPackagingInfo(sb); appendLegacySiblingInfo(sb); sb.append('\n');
        XposedService service = App.getService();
        if (service == null) sb.append("Xposed Service: NOT CONNECTED\n");
        else try {
            sb.append("Xposed Service: CONNECTED\nFramework: ").append(service.getFrameworkName()).append(' ').append(service.getFrameworkVersion()).append('\n');
            sb.append("Framework code: ").append(service.getFrameworkVersionCode()).append("\nAPI: ").append(service.getApiVersion()).append('\n');
            List<String> scope = service.getScope(); sb.append("Scope: ").append(scope).append("\nScope contains weather: ").append(scope.contains(WEATHER)).append('\n');
            if (service.getApiVersion() >= 102) {
                var targets = service.getRunningTargets(); sb.append("Modern Running targets count: ").append(targets.size()).append('\n');
                for (var target : targets) sb.append(" - ").append(target.getProcessName()).append(" pid=").append(target.getPid()).append(" uid=").append(target.getUid()).append(" state=").append(target.getState()).append('\n');
                sb.append("RunningTargets note: HYOS native targets may not appear here; functional/native logs are authoritative.\n");
            }
        } catch (Throwable t) { sb.append("LSPosed service diagnostic error: ").append(t).append('\n'); }
        sb.append('\n').append(probeText).append('\n').append(cleanupText); output.setText(sb.toString());
    }

    private String appVersionName() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version == null ? "unknown" : version;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private void appendPackageInfo(StringBuilder sb) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(WEATHER, android.content.pm.PackageManager.GET_META_DATA);
            sb.append("Weather package: installed=true uid=").append(info.uid).append(" process=").append(info.processName).append(" hasCode=").append((info.flags & ApplicationInfo.FLAG_HAS_CODE) != 0).append(" dataDir=").append(info.dataDir).append(" nativeLibraryDir=").append(info.nativeLibraryDir).append(" enabled=").append(info.enabled);
            if (info.metaData != null) sb.append(" hyperos_package=").append(info.metaData.getBoolean("hyperos_package",false)).append(" rustLib=").append(info.metaData.getString("hyperos_app_lib_name","")).append(" rustEntry=").append(info.metaData.getString("hyperos_application_entry",""));
            sb.append('\n');
        } catch (Throwable t) { sb.append("Weather package query error: ").append(t).append('\n'); }
    }

    private void appendPackagingInfo(StringBuilder sb) {
        try (ZipFile zip = new ZipFile(getApplicationInfo().sourceDir)) {
            sb.append("APK native_init entry present: ").append(zip.getEntry("META-INF/xposed/native_init.list") != null).append('\n');
            sb.append("APK scope entry present: ").append(zip.getEntry("META-INF/xposed/scope.list") != null).append('\n');
            sb.append("APK arm64 native payload present: ").append(zip.getEntry("lib/arm64-v8a/" + NATIVE_LIB) != null).append('\n');
        } catch (Throwable t) { sb.append("APK packaging query error: ").append(t).append('\n'); }
    }

    private void appendLegacySiblingInfo(StringBuilder sb) {
        try { ApplicationInfo weather = getPackageManager().getApplicationInfo(WEATHER,0); File sibling = new File(weather.nativeLibraryDir,NATIVE_LIB); sb.append("Legacy 0.4.x Weather sibling proxy exists: ").append(sibling.isFile()).append('\n'); }
        catch (Throwable t) { sb.append("Legacy sibling query error: ").append(t).append('\n'); }
    }

    private void runNativeProbe() {
        probeText = "HYOS native probe: RUNNING...\n"; refreshDiagnostics();
        new Thread(() -> { String result; try { result = executeNativeProbe(); } catch (Throwable t) { result = "HYOS native probe: ERROR " + t + "\n"; } probeText = result; runOnUiThread(this::refreshDiagnostics); },"hyos-native-probe").start();
    }

    private String executeNativeProbe() throws Exception {
        ApplicationInfo weather = getPackageManager().getApplicationInfo(WEATHER,0); File oldSibling = new File(weather.nativeLibraryDir,NATIVE_LIB);
        String script = "echo '=== Weather process ==='\n"
                + "pid=$(ps -A 2>/dev/null | awk '$NF ~ /^com\\.miui\\.weather2(:|$)/ {print $2; exit}')\n"
                + "echo weather_pid=${pid:-none}\n"
                + "if [ -n \"$pid\" ]; then echo -n 'weather_exe='; readlink /proc/$pid/exe 2>/dev/null || true; fi\n"
                + "echo '=== LSPosed HYOS native mapping (informational) ==='\n"
                + "if [ -n \"$pid\" ]; then grep -F 'libmiweatherlocation.so' /proc/$pid/maps 2>/dev/null || echo NOT_VISIBLE_IN_MAPS; else echo NO_WEATHER_PROCESS; fi\n"
                + "echo '=== Weather runtime mapping ==='\n"
                + "if [ -n \"$pid\" ]; then grep -F 'libweather_app.so' /proc/$pid/maps 2>/dev/null | head -n 8 || true; fi\n"
                + "echo '=== MiWeatherLocation native/star logs ==='\n"
                + "for p in /data/user_de/0/com.miui.weather2/cache/miweatherlocation_native.log /data/user/0/com.miui.weather2/cache/miweatherlocation_native.log; do if [ -f \"$p\" ]; then echo log_path=$p; tail -n 220 \"$p\"; fi; done\n"
                + "logcat -d -b all 2>/dev/null | grep -E 'MiWeatherLocationNative|MiWeatherLocationStar|STAR_BUTTON|LEGACY_STAR_TEXT_CLEANED' | tail -n 220 || true\n"
                + "echo '=== result hints ==='\n"
                + "echo MAPS_RESULT_IS_INFORMATIONAL=true\n"
                + "echo CHECK_FOR=STAR_BUTTON_INPUT_HOOK_OK,STAR_BUTTON_RENDER_HOOK_OK,STAR_BUTTON_TOGGLE\n"
                + "echo '=== old 0.4.x sibling residue ==='\nls -lZ " + shellQuote(oldSibling.getAbsolutePath()) + " 2>/dev/null || echo none\n";
        return runSuScript("HYOS native probe",script);
    }

    private void cleanupLegacySibling() {
        cleanupText = "Legacy 0.4.x sibling cleanup: RUNNING...\n"; refreshDiagnostics();
        new Thread(() -> { String result; try { ApplicationInfo weather = getPackageManager().getApplicationInfo(WEATHER,0); File sibling = new File(weather.nativeLibraryDir,NATIVE_LIB); String script = "am force-stop " + WEATHER + " >/dev/null 2>&1 || true\nrm -f " + shellQuote(sibling.getAbsolutePath()) + "\nif [ -e " + shellQuote(sibling.getAbsolutePath()) + " ]; then echo CLEANUP_FAILED; exit 1; fi\necho CLEANUP_OK\n"; result = runSuScript("Legacy 0.4.x sibling cleanup",script); } catch (Throwable t) { result = "Legacy 0.4.x sibling cleanup: ERROR " + t + "\n"; } cleanupText = result; final String shown=result; runOnUiThread(() -> { refreshDiagnostics(); Toast.makeText(this,shown.contains("CLEANUP_OK")?"旧 proxy 已清理":"清理失败，请复制诊断",Toast.LENGTH_LONG).show(); }); },"legacy-proxy-cleanup").start();
    }

    private String runSuScript(String label,String script) throws Exception {
        java.lang.Process process = new ProcessBuilder("su","-c",script).redirectErrorStream(true).start(); StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) { String line; while ((line=reader.readLine()) != null) out.append(line).append('\n'); }
        boolean finished=process.waitFor(20,TimeUnit.SECONDS); if(!finished){process.destroyForcibly();return label+": TIMEOUT\n"+out;} return label+": COMPLETED\n"+out+"exitCode="+process.exitValue()+"\n";
    }

    private void openWeather(){Intent launch=getPackageManager().getLaunchIntentForPackage(WEATHER);if(launch==null){Toast.makeText(this,"未找到小米天气",Toast.LENGTH_SHORT).show();return;}startActivity(launch);}
    private void copyDiagnostics(){ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);if(clipboard!=null){clipboard.setPrimaryClip(ClipData.newPlainText("MiWeatherLocation diagnostics",output.getText()));Toast.makeText(this,"诊断已复制",Toast.LENGTH_SHORT).show();}}
    private static String shellQuote(String value){return "'"+value.replace("'","'\\''")+"'";}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
