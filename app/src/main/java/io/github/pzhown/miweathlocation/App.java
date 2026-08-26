package io.github.pzhown.miweathlocation;

import android.app.Application;

import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class App extends Application implements XposedServiceHelper.OnServiceListener {
    private static final String WEATHER = "com.miui.weather2";
    private static volatile XposedService service;

    public static XposedService getService() {
        return service;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService xposedService) {
        service = xposedService;
        cleanupLegacyDiagnosticScopes(xposedService);
    }

    private void cleanupLegacyDiagnosticScopes(XposedService xposedService) {
        try {
            List<String> current = xposedService.getScope();
            ArrayList<String> stale = new ArrayList<>();
            for (String packageName : current) {
                if (!WEATHER.equals(packageName)) stale.add(packageName);
            }
            if (!stale.isEmpty()) {
                xposedService.removeScope(stale);
            }
        } catch (Throwable ignored) {
            // Scope cleanup is best-effort. The legacy module also declares
            // com.miui.weather2 as its only recommended scope in the manifest.
        }
    }

    @Override
    public void onServiceDied(XposedService xposedService) {
        if (service == xposedService) {
            service = null;
        }
    }
}
