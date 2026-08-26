package io.github.pzhown.miweathlocation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class HookStatusReceiver extends BroadcastReceiver {
    static final String PREFS = "hook_status";
    static final String KEY_PROCESS = "legacy_process";
    static final String KEY_TIMESTAMP = "legacy_timestamp";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!"io.github.pzhown.miweathlocation.LEGACY_BOOTSTRAP_LOADED".equals(intent.getAction())) return;

        String process = intent.getStringExtra("process");
        long timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PROCESS, process == null ? "" : process)
                .putLong(KEY_TIMESTAMP, timestamp)
                .apply();
    }
}
