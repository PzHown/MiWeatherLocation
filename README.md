# MiWeatherLocation

面向小米天气 18 的 **纯 LSPosed 单 APK 模块**。当前版本使用 **legacy bootstrap + APK 内置 arm64 native payload**，不需要另外刷 MiWeatherLocation 的 Magisk / Zygisk 模块。

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
- 目标设备：arm64

## 为什么改成 legacy bootstrap

天气 18 是 HyperOS Native/Rust 架构：

- `android:hasCode=false`
- APK 内没有业务 DEX
- 主 native 入口：`libweather_app.so`
- Flutter AOT：`libapp.so`

实机上，LSPosed 2.1.1 / API 102 可以正常把现代模块加载进 `com.android.settings:remote`，但同一个模块始终没有把小米天气列为 Running Target。为了绕开现代模块更晚的加载时点，MiWeatherLocation 改用 LSPosed 兼容的 legacy bootstrap 入口：

```text
assets/xposed_init
  -> LegacyEntry.initZygote()
  -> System.loadLibrary("miweatherlocation")
  -> JNI_OnLoad()
  -> native worker
```

这里的 `initZygote()` 是 legacy Xposed API 的生命周期名称；在 LSPosed 中模块仍然只按配置的作用域加载。模块不会把自己的 payload 做成 Magisk/Zygisk 模块。

## Native payload

APK 内包含：

```text
lib/arm64-v8a/libmiweatherlocation.so
assets/xposed_init
```

native worker 只在 `com.miui.weather2` / `com.miui.weather2:*` 进程启动，等待 `libweather_app.so`、SQLite 和 `weather.db` 就绪，再处理 `selectedcity`。

### 16 KB 页面兼容

Android 17 实机曾提示 native library 的 ELF `LOAD` 段不满足 16 KB 对齐。NDK r27 构建现在显式使用：

```text
-Wl,-z,max-page-size=16384
-Wl,-z,common-page-size=16384
```

CI 会读取 ELF Program Headers，要求所有 `LOAD` 段对齐值至少为 `0x4000`，并额外执行 `zipalign -P 16` 校验。

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

写入前只移动 `flag=0 AND position>=1` 的收藏城市；不会更新 `flag=1` 的真实定位行。

## 安装与诊断

1. 安装 Release 中的 `MiWeatherLocation-debug.apk`；
2. 在 LSPosed 中启用 MiWeatherLocation；
3. 作用域只保留“小米天气 / `com.miui.weather2`”；
4. 强制停止并重新打开小米天气；
5. 回到 MiWeatherLocation，点“刷新状态”。

新版诊断使用 rootless marker：

```text
Legacy bootstrap marker: RECEIVED process=com.miui.weather2 ...
```

出现 `RECEIVED` 就说明 legacy bootstrap 已经实际进入天气进程，不再依赖 Modern Running Targets 判断。若仍为 `NOT RECEIVED`，问题位于 LSPosed/底层注入在进入模块代码之前的阶段。

模块 App 连上 libxposed service 后还会自动清理之前诊断阶段残留的 `com.android.settings` scope；最终作用域只需要小米天气。

## 技术栈

- LSPosed legacy Xposed API bootstrap (`api:82` compileOnly)
- `assets/xposed_init`
- APK 内置 arm64 native library
- `JNI_OnLoad` native worker
- 16 KB ELF LOAD alignment
- libxposed service API 102（仅用于模块 App 侧状态/作用域诊断）
- Xiaomi Weather SQLite `selectedcity`

## 后续计划

- [ ] 实机确认 legacy bootstrap marker
- [ ] 验证广州塔插入后是否被 Rust provider 内存状态覆盖
- [ ] 支持自定义地点
- [ ] 接入小米 `/location/city/geo`
- [ ] 支持广州塔、卡伦海滩等景区/街道级收藏
