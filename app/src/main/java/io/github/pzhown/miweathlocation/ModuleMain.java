package io.github.pzhown.miweathlocation;

import io.github.libxposed.api.XposedModule;

/**
 * Java entry retained only for Modern libxposed module metadata compatibility.
 *
 * Xiaomi Weather 18 is a HyperOS native/Rust app started by hyos_spawner and
 * does not run its own ART/Dex application code. The active implementation is
 * the arm64 native entry declared in META-INF/xposed/native_init.list.
 */
public final class ModuleMain extends XposedModule {
    public ModuleMain() {
        super();
    }
}
