# MiWeatherLocation

面向小米天气的 **LSPosed API 102 / libxposed 新 API** 定位覆写模块。

## 当前版本

第一版是验证性 PoC：仅作用于 `com.miui.weather2`，将小米天气进程读取到的位置固定覆写为 **广州塔**。

- 目标应用：小米天气 `com.miui.weather2`
- 已针对版本：`18.0.0.18-R` / versionCode `180000180`
- LSPosed API：`102`
- libxposed：`io.github.libxposed:api:102.0.0`
- 固定地点：广州塔
- 高德坐标（GCJ-02）：`23.106428, 113.324521`
- 地址：广东省广州市海珠区阅江西路 222 号

## API 102

项目不使用 legacy `de.robv.android.xposed` API，也不包含 `assets/xposed_init`。

模块入口与作用域使用 libxposed 新格式：

```text
META-INF/xposed/java_init.list
META-INF/xposed/module.prop
META-INF/xposed/scope.list
```

`module.prop`：

```properties
minApiVersion=102
targetApiVersion=102
staticScope=true
autoHotReload=true
```

## 当前 Hook

PoC 当前优先验证定位读取链：

1. API 102 `XposedModule#onPackageReady` 中安装 Hook；
2. Hook `android.location.Location#getLatitude()`，固定返回 `23.106428`；
3. Hook `android.location.Location#getLongitude()`，固定返回 `113.324521`；
4. 如果目标进程存在 `com.amap.api.location.AMapLocation`，同步覆写省、市、区、街道、POI、地址等 getter；
5. 静态作用域只包含 `com.miui.weather2`，不会全局修改其他 App 定位。

## 使用

1. 从 GitHub Actions 下载 `MiWeatherLocation-debug` APK；
2. 安装 APK；
3. 在支持 libxposed API 102 的 LSPosed 中启用模块；
4. 作用域确认只有 **天气 / `com.miui.weather2`**；
5. 强制停止并重新打开小米天气；
6. 刷新当前定位。

快速重启：

```bash
adb shell am force-stop com.miui.weather2
adb shell monkey -p com.miui.weather2 1
```

## 调试

模块日志 TAG：

```text
MiWeatherLocation
```

可以通过 LSPosed 日志确认是否出现：

```text
Hooks installed for com.miui.weather2 -> Canton Tower 23.106428,113.324521
```

以及是否检测到了 `AMapLocation`。

## 构建链

按当前 libxposed 官方 example 对齐：

- Android Gradle Plugin `9.2.1`
- Gradle `9.5.1`
- JDK `21`
- compileSdk `37`
- libxposed API `102.0.0`

GitHub Actions 每次 push 自动构建 Debug APK。

## 当前限制

- 坐标暂时写死为广州塔，没有配置界面；
- 当前只验证小米天气定位链，不操作收藏城市数据库；
- 如果 18.x 的 Rust/native 定位路径绕过 Android/AMap Java getter，需要根据日志和实机结果继续补 Hook；
- 后续再区分 GCJ-02 / WGS84 以及 AMap / NLP / GMS 路径。

## 后续计划

- [ ] 实机验证小米天气 18.0.0.18-R
- [ ] 针对未命中的 native/Rust 路径补 Hook
- [ ] 增加经纬度和地点名称自定义
- [ ] 接入小米 `/location/city/geo`
- [ ] 支持街道、景区级固定地点
