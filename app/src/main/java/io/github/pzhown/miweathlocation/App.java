package io.github.pzhown.miweathlocation;

import android.app.Application;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class App extends Application implements XposedServiceHelper.OnServiceListener {
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
    }

    @Override
    public void onServiceDied(XposedService xposedService) {
        if (service == xposedService) {
            service = null;
        }
    }
}
