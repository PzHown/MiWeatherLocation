package io.github.pzhown.miweathlocation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class HookStatusReceiver extends BroadcastReceiver {
    static final String PREFS = "hook_status";
    static final String KEY_PROCESS = "legacy_process";
    static final String KEY_TIMESTAMP = "legacy_timestamp";
    static final String KEY_RUST_STAGE = "rust_stage";
    static final String KEY_RUST_DETAIL = "rust_detail";
    static final String KEY_RUST_TIMESTAMP = "rust_timestamp";

    private static final String LEGACY_ACTION =
            "io.github.pzhown.miweathlocation.LEGACY_BOOTSTRAP_LOADED";
    private static final String RUST_ACTION =
            "io.github.pzhown.miweathlocation.RUSTPROCESS_STATUS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || context == null) return;
        String action = intent.getAction();
        if (LEGACY_ACTION.equals(action)) {
            String process = intent.getStringExtra("process");
            long timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis());
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PROCESS, process == null ? "" : process)
                    .putLong(KEY_TIMESTAMP, timestamp)
                    .apply();
            return;
        }
        if (RUST_ACTION.equals(action)) {
            String stage = intent.getStringExtra("stage");
            String detail = intent.getStringExtra("detail");
            long timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis());
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_RUST_STAGE, stage == null ? "" : stage)
                    .putString(KEY_RUST_DETAIL, detail == null ? "" : detail)
                    .putLong(KEY_RUST_TIMESTAMP, timestamp)
                    .apply();
        }
    }
}
