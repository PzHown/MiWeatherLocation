package io.github.pzhown.miweathlocation;

import android.location.Location;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

public final class ModuleMain extends XposedModule {
    private static final String TAG = "MiWeatherLocation";
    private static final String TARGET_PACKAGE = "com.miui.weather2";

    // Guangzhou Tower / Canton Tower (GCJ-02, AMap)
    private static final double LATITUDE = 23.106428d;
    private static final double LONGITUDE = 113.324521d;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Loaded in process=" + param.getProcessName()
                + ", framework=" + getFrameworkName()
                + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        try {
            hookLocationGetters();
            hookAmapTextGetters(param.getClassLoader());
            log(Log.INFO, TAG, "Hooks installed for " + TARGET_PACKAGE
                    + " -> Canton Tower " + LATITUDE + "," + LONGITUDE);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to install hooks", t);
        }
    }

    private void hookLocationGetters() throws ReflectiveOperationException {
        Method getLatitude = Location.class.getDeclaredMethod("getLatitude");
        Method getLongitude = Location.class.getDeclaredMethod("getLongitude");

        hook(getLatitude).intercept(chain -> LATITUDE);
        hook(getLongitude).intercept(chain -> LONGITUDE);
    }

    private void hookAmapTextGetters(ClassLoader classLoader) {
        try {
            Class<?> amapLocation = Class.forName(
                    "com.amap.api.location.AMapLocation", false, classLoader);

            hookStringGetter(amapLocation, "getProvince", "广东省");
            hookStringGetter(amapLocation, "getCity", "广州市");
            hookStringGetter(amapLocation, "getDistrict", "海珠区");
            hookStringGetter(amapLocation, "getStreet", "阅江西路");
            hookStringGetter(amapLocation, "getStreetNum", "222号");
            hookStringGetter(amapLocation, "getPoiName", "广州塔");
            hookStringGetter(amapLocation, "getAoiName", "广州塔");
            hookStringGetter(amapLocation, "getAddress", "广东省广州市海珠区阅江西路222号广州塔");

            log(Log.INFO, TAG, "AMapLocation detected; text getters hooked");
        } catch (ClassNotFoundException e) {
            log(Log.INFO, TAG, "AMapLocation not present in target process");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "AMap text hooks partially unavailable", t);
        }
    }

    private void hookStringGetter(Class<?> clazz, String methodName, String value) {
        try {
            Method method = clazz.getMethod(methodName);
            hook(method).intercept(chain -> value);
        } catch (NoSuchMethodException ignored) {
            log(Log.DEBUG, TAG, "Getter not found: " + clazz.getName() + "#" + methodName);
        }
    }
}
