# MiWeatherLocation

面向小米天气的 LSPosed 定位覆写模块。

## 当前版本

第一版是验证性 PoC：仅作用于 `com.miui.weather2`，将小米天气进程获取到的位置固定覆写为 **广州塔**。

- 目标应用：小米天气 `com.miui.weather2`
- 已针对版本：`18.0.0.18-R` / versionCode `180000180`
- 固定地点：广州塔
- 高德坐标（GCJ-02）：`23.106428, 113.324521`
- 地址：广东省广州市海珠区阅江西路 222 号

广州塔参考：https://ditu.amap.com/place/B00140WBI1

## 工作方式

模块只加载进小米天气进程，不修改系统全局定位。

PoC 会：

1. Hook `android.location.Location#getLatitude()` / `getLongitude()`；
2. Hook `Location#setLatitude()` / `setLongitude()`，将写入坐标固定为广州塔；
3. 在 `Location#writeToParcel()` 前再次固定坐标，覆盖部分跨层传递场景；
4. 若检测到 `com.amap.api.location.AMapLocation`，同步覆写常用行政区、街道与 POI getter，避免坐标已经到广州塔但文字仍是原地点。

## 使用

1. 安装 GitHub Actions 产出的 APK；
2. 在 LSPosed 中启用模块；
3. 作用域仅勾选 **天气 / `com.miui.weather2`**；
4. 强制停止小米天气后重新打开；
5. 在天气中刷新“当前位置”。

ADB 可用于快速重启：

```bash
adb shell am force-stop com.miui.weather2
adb shell monkey -p com.miui.weather2 1
```

## 当前限制

- 第一版坐标写死为广州塔，没有配置界面；
- 目标是验证小米天气 18.x 的定位链路，不处理“收藏城市”数据库；
- 如果小米天气某条 native/Rust 路径完全绕过 Android/AMap Java getter，后续需要针对该路径补 Hook；
- GCJ-02 与 WGS84 路径可能需要分别处理，PoC 目前优先针对中国大陆小米天气常见的 AMap 路径。

## 后续计划

- [ ] 验证 18.0.0.18-R 的 AMap / NLP / GMS 三条定位链
- [ ] 增加经纬度自定义
- [ ] 增加地点搜索与小米 `/location/city/geo` 解析
- [ ] 支持街道/景区级固定地点
- [ ] 增加启用状态与调试日志页面

## 构建

仓库通过 GitHub Actions 自动构建可直接安装的 Debug APK。
