# MiWeatherLocation

面向小米天气 18 的 **纯 LSPosed 单 APK 模块**，基于 libxposed API 102 + LSPosed Native Hook。

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
- libxposed API：`102.0.0`
- 广州塔坐标：`23.106428, 113.324521`
- weather key：`weathercn:101280108`

## 为什么使用 Native Hook

天气 18 的 APK 是 HyperOS Native/Rust 架构：

- `android:hasCode=false`
- APK 内没有 `classes*.dex`
- 主 native 入口：`libweather_app.so`
- Flutter AOT：`libapp.so`

因此模块不再依赖天气自身的 Java 类或 `Activity.onResume()`。MiWeatherLocation 的 APK 内直接包含：

```text
lib/arm64-v8a/libmiweatherlocation.so
META-INF/xposed/java_init.list
META-INF/xposed/native_init.list
META-INF/xposed/scope.list
```

LSPosed 加载模块后，Java entry 只负责加载 APK 内 native payload；native payload 在天气进程内等待 `libweather_app.so` / SQLite 就绪，再处理 `selectedcity`。

**不需要另外刷 MiWeatherLocation 的 Magisk / Zygisk ZIP。**

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

## 安装

1. 安装 Release 中的 `MiWeatherLocation-debug.apk`；
2. 在 LSPosed 中启用 MiWeatherLocation；
3. 作用域选择“小米天气 / `com.miui.weather2`”；
4. 强制停止并重新打开小米天气；
5. 打开 MiWeatherLocation，查看 `Weather LSPosed target present`。

如果显示 `true`，说明 LSPosed 已经把模块加载到天气进程，APK 内置 native payload 可以继续工作。

如果始终显示 `false`，说明阻塞发生在 LSPosed 对这个 HyperOS native-only 进程的注入/作用域解析阶段，早于 MiWeatherLocation 的 Java/native 业务代码。

## 技术栈

- libxposed API `102.0.0`
- `META-INF/xposed/java_init.list`
- `META-INF/xposed/native_init.list`
- LSPosed Native Hook `native_init`
- APK 内置 arm64 native library
- `JNI_OnLoad` fallback
- Xiaomi Weather SQLite `selectedcity`

## 后续计划

- [ ] 实机验证 Weather 18 是否成为 LSPosed Running Target
- [ ] 验证广州塔插入后是否被 Rust provider 内存状态覆盖
- [ ] 支持自定义地点
- [ ] 接入小米 `/location/city/geo`
- [ ] 支持广州塔、卡伦海滩等景区/街道级收藏
