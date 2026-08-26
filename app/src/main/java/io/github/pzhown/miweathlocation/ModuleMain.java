package io.github.pzhown.miweathlocation;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;

/**
 * LSPosed/libxposed API 102 module for Xiaomi Weather.
 *
 * This module never spoofs the real device location. It injects Canton Tower as
 * the first saved city (flag=0, position=1), after Xiaomi Weather's real located
 * city (flag=1, position=0).
 *
 * Diagnostic build: when Xiaomi Weather opens, an in-app dialog shows the full
 * injection trace and provides a Copy log button.
 */
public final class ModuleMain extends XposedModule {
    private static final String TAG = "MiWeatherLocation";
    private static final String TARGET_PACKAGE = "com.miui.weather2";

    private static final String DB_NAME = "weather.db";
    private static final String TABLE_SELECTED_CITY = "selectedcity";

    private static final String POS_ID = "23.106_113.325";
    private static final String LATITUDE = "23.106428";
    private static final String LONGITUDE = "113.324521";
    private static final String LOCATION_KEY = "weathercn:101280108";

    private static final Object LOG_LOCK = new Object();
    private static final StringBuilder DIAGNOSTIC_LOG = new StringBuilder();

    private static volatile boolean diagnosticStarted;
    private static volatile boolean dialogShown;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        addLog("onModuleLoaded process=" + param.getProcessName());
        addLog("framework=" + getFrameworkName()
                + " versionCode=" + getFrameworkVersionCode()
                + " api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        addLog("onPackageReady package=" + param.getPackageName()
                + " firstPackage=" + param.isFirstPackage());

        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        try {
            hookActivityResume();
            addLog("Activity.onResume hook installed");
        } catch (Throwable t) {
            addError("Failed to hook Activity.onResume", t);
        }
    }

    private void hookActivityResume() throws ReflectiveOperationException {
        Method onResume = Activity.class.getDeclaredMethod("onResume");
        hook(onResume)
                .setId("miweather-diagnostic-onresume")
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Object thisObject = chain.getThisObject();
                    if (thisObject instanceof Activity) {
                        Activity activity = (Activity) thisObject;
                        if (TARGET_PACKAGE.equals(activity.getPackageName())) {
                            onWeatherActivityResumed(activity);
                        }
                    }
                    return result;
                });
    }

    private void onWeatherActivityResumed(Activity activity) {
        if (diagnosticStarted) {
            return;
        }
        synchronized (ModuleMain.class) {
            if (diagnosticStarted) {
                return;
            }
            diagnosticStarted = true;
        }

        addLog("Weather Activity resumed: " + activity.getClass().getName());

        Handler main = new Handler(Looper.getMainLooper());
        Thread worker = new Thread(() -> {
            try {
                addLog("Starting favorite-city injection");
                InjectionResult result = injectFavorite(activity.getApplicationContext());
                addLog("Injection result: " + result.message);
            } catch (Throwable t) {
                addError("Unhandled injection failure", t);
            }

            main.post(() -> showDiagnosticDialog(activity));
        }, "MiWeatherLocation-Diagnostic");
        worker.start();
    }

    private InjectionResult injectFavorite(Context context) {
        addLog("Real location spoofing: DISABLED");
        addLog("Target favorite: 广州塔, flag=0, position=1");

        Context deContext = context.createDeviceProtectedStorageContext();
        File dbFile = deContext.getDatabasePath(DB_NAME);
        addLog("Database path: " + dbFile.getAbsolutePath());
        addLog("Database exists=" + dbFile.isFile()
                + " readable=" + dbFile.canRead()
                + " writable=" + dbFile.canWrite());

        if (!dbFile.isFile()) {
            return new InjectionResult(false, "FAILED: weather.db not found");
        }

        SQLiteDatabase db = null;
        try {
            addLog("Opening weather.db READWRITE");
            db = SQLiteDatabase.openDatabase(
                    dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            addLog("Database opened; version=" + db.getVersion()
                    + " walEnabled=" + db.isWriteAheadLoggingEnabled());

            if (!tableExists(db, TABLE_SELECTED_CITY)) {
                return new InjectionResult(false, "FAILED: selectedcity table not found");
            }
            addLog("selectedcity table found");

            int beforeCount = countRows(db);
            addLog("selectedcity rows before=" + beforeCount);

            db.beginTransaction();
            try {
                if (favoriteExists(db)) {
                    addLog("广州塔 already exists; no insert needed");
                    db.setTransactionSuccessful();
                    return new InjectionResult(true, "OK: 广州塔 already exists");
                }

                addLog("Shifting existing favorites position >= 1 by +1");
                db.execSQL(
                        "UPDATE " + TABLE_SELECTED_CITY
                                + " SET position = position + 1"
                                + " WHERE flag = 0 AND position >= 1");

                ContentValues values = new ContentValues();
                values.put("posID", POS_ID);
                values.put("flag", 0);
                values.put("position", 1);
                values.put("name", "广州塔");
                values.put("street_name", "阅江西路");
                values.put("longtitude", LONGITUDE);
                values.put("latitude", LATITUDE);
                values.put("belongings", "广州市, 广东, 中国");
                values.put("extra", LOCATION_KEY);
                values.put("locale", "zh_cn");

                addLog("Inserting 广州塔 row");
                long rowId = db.insertOrThrow(TABLE_SELECTED_CITY, null, values);
                addLog("Insert returned rowId=" + rowId);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            boolean verified = favoriteExists(db);
            int afterCount = countRows(db);
            addLog("Verify 广州塔 exists=" + verified);
            addLog("selectedcity rows after=" + afterCount);

            return verified
                    ? new InjectionResult(true, "OK: 广州塔 inserted and verified")
                    : new InjectionResult(false, "FAILED: insert completed but verification missed");
        } catch (Throwable t) {
            addError("SQLite injection exception", t);
            return new InjectionResult(false,
                    "FAILED: " + t.getClass().getSimpleName() + ": " + safeMessage(t));
        } finally {
            if (db != null) {
                try {
                    db.close();
                    addLog("Database connection closed");
                } catch (Throwable t) {
                    addError("Database close exception", t);
                }
            }
        }
    }

    private boolean favoriteExists(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM " + TABLE_SELECTED_CITY
                        + " WHERE posID = ? AND flag = 0 LIMIT 1",
                new String[]{POS_ID})) {
            return cursor.moveToFirst();
        }
    }

    private int countRows(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_SELECTED_CITY, null)) {
            if (!cursor.moveToFirst()) {
                return -1;
            }
            return cursor.getInt(0);
        }
    }

    private boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                new String[]{table})) {
            return cursor.moveToFirst();
        }
    }

    private void showDiagnosticDialog(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            addLog("Dialog skipped because Activity is finishing/destroyed");
            return;
        }
        if (dialogShown) {
            return;
        }
        dialogShown = true;

        final String logText = getDiagnosticLog();
        try {
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("MiWeatherLocation 诊断日志")
                    .setMessage(logText)
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("复制日志", null)
                    .create();

            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        try {
                            ClipboardManager clipboard = (ClipboardManager)
                                    activity.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(
                                        ClipData.newPlainText("MiWeatherLocation log", getDiagnosticLog()));
                                Toast.makeText(activity, "日志已复制", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(activity, "无法访问剪贴板", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Throwable t) {
                            addError("Copy log failed", t);
                            Toast.makeText(activity, "复制失败: " + safeMessage(t), Toast.LENGTH_LONG).show();
                        }
                    }));
            dialog.show();
        } catch (Throwable t) {
            addError("Failed to show diagnostic dialog", t);
            Toast.makeText(activity,
                    "MiWeatherLocation 无法弹出日志: " + safeMessage(t),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void addLog(String message) {
        String line = timestamp() + "  " + message;
        synchronized (LOG_LOCK) {
            DIAGNOSTIC_LOG.append(line).append('\n');
        }
        try {
            log(Log.INFO, TAG, message);
        } catch (Throwable ignored) {
            // Keep the in-memory log even if framework logging is unavailable.
        }
    }

    private void addError(String message, Throwable t) {
        StringBuilder error = new StringBuilder(message)
                .append(" -> ")
                .append(t.getClass().getName())
                .append(": ")
                .append(safeMessage(t));

        StackTraceElement[] stack = t.getStackTrace();
        int limit = Math.min(stack.length, 8);
        for (int i = 0; i < limit; i++) {
            error.append("\n    at ").append(stack[i]);
        }

        String text = error.toString();
        synchronized (LOG_LOCK) {
            DIAGNOSTIC_LOG.append(timestamp()).append("  ").append(text).append('\n');
        }
        try {
            log(Log.ERROR, TAG, message, t);
        } catch (Throwable ignored) {
            // Keep the in-memory log even if framework logging is unavailable.
        }
    }

    private String getDiagnosticLog() {
        synchronized (LOG_LOCK) {
            return DIAGNOSTIC_LOG.toString();
        }
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null ? "(no message)" : message;
    }

    private static String timestamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static final class InjectionResult {
        final boolean success;
        final String message;

        InjectionResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
