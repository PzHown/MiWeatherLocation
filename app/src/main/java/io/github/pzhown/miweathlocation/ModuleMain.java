package io.github.pzhown.miweathlocation;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * LSPosed API 102 module for Xiaomi Weather.
 *
 * The module does NOT spoof the device location. It only inserts Canton Tower as
 * the first saved city (position=1), immediately after the real located city
 * (flag=1, position=0) in Xiaomi Weather's device-protected weather.db.
 */
public final class ModuleMain extends XposedModule {
    private static final String TAG = "MiWeatherLocation";
    private static final String TARGET_PACKAGE = "com.miui.weather2";

    private static final String DB_NAME = "weather.db";
    private static final String TABLE_SELECTED_CITY = "selectedcity";

    // Canton Tower / Guangzhou Tower.
    // Keep posID in the same 3-decimal style used by Xiaomi Weather while
    // retaining the more precise coordinates in latitude/longtitude.
    private static final String POS_ID = "23.106_113.325";
    private static final String LATITUDE = "23.106428";
    private static final String LONGITUDE = "113.324521";
    private static final String LOCATION_KEY = "weathercn:101280108"; // Haizhu District

    private static final int MAX_RETRIES = 6;
    private static final long RETRY_DELAY_MS = 1000L;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Loaded in process=" + param.getProcessName()
                + ", framework=" + getFrameworkName()
                + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }

        try {
            hookApplicationOnCreate(param);
            log(Log.INFO, TAG, "Ready: real location is untouched; Canton Tower will be injected as first favorite");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to install Application hook", t);
        }
    }

    private void hookApplicationOnCreate(PackageReadyParam param) throws ReflectiveOperationException {
        String applicationClassName = param.getApplicationInfo().className;
        Class<?> applicationClass = applicationClassName == null
                ? Application.class
                : Class.forName(applicationClassName, false, param.getClassLoader());

        Method onCreate = applicationClass.getMethod("onCreate");
        hook(onCreate).intercept(chain -> {
            Object result = chain.proceed();
            Object thisObject = chain.getThisObject();
            if (thisObject instanceof Application) {
                scheduleInjection((Application) thisObject, 0);
            } else {
                log(Log.WARN, TAG, "Application hook fired without Application instance");
            }
            return result;
        });
    }

    private void scheduleInjection(Application application, int attempt) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            try {
                if (injectFavorite(application)) {
                    return;
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "Favorite injection attempt " + (attempt + 1) + " failed", t);
            }

            if (attempt + 1 < MAX_RETRIES) {
                scheduleInjection(application, attempt + 1);
            } else {
                log(Log.ERROR, TAG, "Giving up after " + MAX_RETRIES + " attempts");
            }
        }, attempt == 0 ? 0L : RETRY_DELAY_MS);
    }

    /**
     * @return true when no further retry is needed (already present or inserted successfully).
     */
    private boolean injectFavorite(Context context) {
        Context deContext = context.createDeviceProtectedStorageContext();
        File dbFile = deContext.getDatabasePath(DB_NAME);

        if (!dbFile.isFile()) {
            log(Log.DEBUG, TAG, "Database not ready yet: " + dbFile);
            return false;
        }

        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);

        try {
            if (!tableExists(db, TABLE_SELECTED_CITY)) {
                log(Log.DEBUG, TAG, "selectedcity table not ready yet");
                return false;
            }

            db.beginTransaction();
            try {
                if (favoriteExists(db)) {
                    log(Log.INFO, TAG, "Canton Tower favorite already exists; no changes made");
                    db.setTransactionSuccessful();
                    return true;
                }

                // Keep the real located city at position=0. Existing saved cities
                // move down one slot so Canton Tower becomes the first favorite.
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
                values.put("longtitude", LONGITUDE); // Xiaomi Weather schema spelling
                values.put("latitude", LATITUDE);
                values.put("belongings", "广州市, 广东, 中国");
                values.put("extra", LOCATION_KEY);
                values.put("locale", "zh_cn");

                long rowId = db.insertOrThrow(TABLE_SELECTED_CITY, null, values);
                db.setTransactionSuccessful();
                log(Log.INFO, TAG, "Inserted Canton Tower as first favorite, rowId=" + rowId);
                return true;
            } finally {
                db.endTransaction();
            }
        } finally {
            db.close();
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

    private boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                new String[]{table})) {
            return cursor.moveToFirst();
        }
    }
}
