# MiWeatherLocation

面向小米天气 18 的 **纯 LSPosed 单 APK 模块**。当前主线直接采用已经在 HyperOS 4 native-only 应用上跑通的 `native_init` 模式：不 Hook `system_server`，不替换 Rust 启动参数，不复制 sibling proxy，也不需要另外刷 MiWeatherLocation 的 Magisk / Zygisk 模块。

## 当前目标

不修改真实位置、不伪造系统定位，只把“广州塔”作为第一个收藏城市插入小米天气：

```text
当前位置（真实定位，flag=1 / position=0）
广州塔（收藏城市，flag=0 / position=1）
原收藏城市 1
原收藏城市 2
...
```

- 目标应用：`com.miui.weather2`
- 已针对：小米天气 `18.0.0.18-R` / versionCode `180000180`
- 广州塔坐标：`23.106428, 113.324521`
- weather key：`weathercn:101280108`
- 目标设备：arm64 / Android 17 / HyperOS 4

## 为什么使用直接 HYOS native_init

天气 18 是 HyperOS Native/Rust 架构：

- `android:hasCode=false`
- APK 内没有业务 DEX
- 进程实际可执行文件：`/system_ext/bin/hyos_spawner`
- 主 native 入口：`libweather_app.so`
- Flutter AOT：`libapp.so`

项目对照了已运行于 HyperOS 4 native-only App 的开源 LSPosed 实现 `zilewang7/HyperOS4SmallWindowInputFilter`。其核心做法不是依赖 ART/Java package lifecycle，而是：

```text
scope.list -> 目标包
java_init.list -> 仅保留空壳 XposedModule
native_init.list -> 模块 native .so
native_init() -> 在 hyos_spawner 子进程中检查 exe + cmdline
```

MiWeatherLocation 现在采用同一结构：

```text
LSPosed scope: com.miui.weather2
        ↓
Weather HYOS child
/proc/self/exe = /system_ext/bin/hyos_spawner
/proc/self/cmdline = com.miui.weather2
        ↓
META-INF/xposed/native_init.list
        ↓
libmiweatherlocation.so::native_init()
        ↓
等待 libweather_app.so / SQLite / weather.db
        ↓
写入 selectedcity
```

`Modern Running Targets` 仅作为辅助信息，不再用于判断 native-only HYOS 注入是否成功。真正成功标志是天气进程 maps / `MiWeatherLocationNative` 日志中出现模块 native entry。

## APK 结构

```text
META-INF/xposed/java_init.list
META-INF/xposed/native_init.list
META-INF/xposed/module.prop
META-INF/xposed/scope.list
lib/arm64-v8a/libmiweatherlocation.so
```

`java_init.list` 的 `ModuleMain` 只用于 Modern libxposed 模块元数据兼容；实际逻辑全部在 `native_init`。

## 收藏城市写入

目标数据库：

```text
/data/user_de/0/com.miui.weather2/databases/weather.db
```

插入记录：

```text
posID       = 23.106_113.325
flag        = 0
position    = 1
name        = 广州塔
street_name = 阅江西路
longtitude  = 113.324521
latitude    = 23.106428
belongings  = 广州市, 广东, 中国
extra       = weathercn:101280108
locale      = zh_cn
```

写入前只移动：

```sql
UPDATE selectedcity
SET position = position + 1
WHERE flag = 0 AND position >= 1;
```

因此不会修改 `flag=1 / position=0` 的真实定位行。

## 安装与测试

1. 安装 Release 中的 `MiWeatherLocation-debug.apk`；
2. LSPosed 启用 MiWeatherLocation；
3. 作用域只需要“小米天气 / `com.miui.weather2`”；
4. 强制停止并重新打开小米天气；
5. 打开 MiWeatherLocation，点“读取 HYOS Native 日志”。

成功时应看到类似：

```text
native entry initialized in Weather HYOS child
HYOS worker started exe=/system_ext/bin/hyos_spawner process=com.miui.weather2
Weather native runtime ready ...
favorite injection OK name=广州塔 flag=0 position=1; flag=1 current location untouched
```

模块功能本身不需要额外 root 操作。App 内的 root 只用于读取天气沙箱里的诊断日志，以及清理 0.4.x 曾经部署过的旧 sibling proxy；两者都不是运行依赖。

## 0.4.x 迁移说明

0.4.x 曾实验：

```text
system_server -> RustProcessImpl -> sibling proxy -> libweather_app.so
```

该路线已经废弃。0.5 起：

- 删除 `system` scope；
- 删除 `RustProcessImpl` Hook；
- 删除 sibling proxy 转发；
- 删除 RustProcess 广播状态链；
- 不再把 native .so 复制进天气安装目录；
- 直接由 LSPosed `native_init` 加载。

如果设备上仍残留 0.4.x 的 `libmiweatherlocation.so` sibling 文件，可在模块 App 点“清理 0.4.x 旧 proxy（可选）”。

## 16 KB 页面兼容

native payload 显式使用：

```text
-Wl,-z,max-page-size=16384
-Wl,-z,common-page-size=16384
```

CI 会同时检查 ELF `LOAD` 段、APK `zipalign -P 16`、`native_init` 导出、scope 仅包含天气，并确保旧的 `RustProcessImpl` / sibling proxy 入口没有重新混入构建。

## 后续计划

- [ ] 实机确认 Weather HYOS `native_init` 日志
- [ ] 确认广州塔插入后不会被 Rust provider 内存状态覆盖
- [ ] 支持自定义地点
- [ ] 接入小米 `/location/city/geo`
- [ ] 支持广州塔、卡伦海滩等景区/街道级收藏
