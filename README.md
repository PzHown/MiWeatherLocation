# MiWeatherLocation

面向小米天气的 LSPosed 模块，基于 **libxposed API 102**。

## 当前版本

第一版目标已经调整为：**不修改真实位置，不伪造系统定位，只把“广州塔”作为第一个收藏城市插入小米天气。**

预期顺序：

```text
当前位置（真实定位）
广州塔（收藏城市）
原收藏城市 1
原收藏城市 2
...
```

- 目标应用：小米天气 `com.miui.weather2`
- 已针对版本：`18.0.0.18-R` / versionCode `180000180`
- libxposed API：`102.0.0`
- 收藏地点：广州塔
- 坐标：`23.106428, 113.324521`
- `flag=0`
- `position=1`
- 当前位置 `flag=1 / position=0` 完全不修改

## 工作方式

模块只加载进 `com.miui.weather2`。

在小米天气 Application 启动后，模块打开设备加密区数据库：

```text
/data/user_de/0/com.miui.weather2/databases/weather.db
```

检查 `selectedcity` 表：

1. 如果广州塔已经存在，不做任何修改；
2. 如果不存在，将现有 `flag=0` 且 `position>=1` 的收藏城市统一后移一位；
3. 插入广州塔为：

```text
flag        = 0
position    = 1
name        = 广州塔
street_name = 阅江西路
latitude    = 23.106428
longtitude  = 113.324521
belongings  = 广州市, 广东, 中国
extra       = weathercn:101280108
locale      = zh_cn
```

这样真实定位仍由系统正常提供，小米天气的“当前位置”不会被替换。

## 为什么先用海珠区 weather key

小米天气的收藏城市记录除了显示名称、经纬度外，还需要 `extra` 天气 location key。第一版先复用海珠区的：

```text
weathercn:101280108
```

因此天气数据仍对应广州海珠区，但 UI 收藏名称和坐标是广州塔。后续可以继续逆向更细粒度地点是否有独立 key，或改成动态地点解析。

## 使用

1. 安装 GitHub Actions 产出的 APK；
2. 在 LSPosed 中启用模块；
3. 作用域只勾选“小米天气 / `com.miui.weather2`”；
4. 强制停止小米天气；
5. 重新打开小米天气。

ADB：

```bash
adb shell am force-stop com.miui.weather2
adb shell monkey -p com.miui.weather2 1
```

验证：

```bash
adb shell content query --uri content://weather/selected_city
```

预期广州塔是 `flag=0, position=1`，而真实位置仍是 `flag=1, position=0`。

## 技术栈

- libxposed API `102.0.0`
- `XposedModule`
- `onPackageReady()`
- `hook(...).intercept(...)`
- `META-INF/xposed/java_init.list`
- 静态作用域 `com.miui.weather2`

## 后续计划

- [ ] 验证 18.0.0.18-R 是否会主动覆盖 `selectedcity`
- [ ] 支持自定义地点
- [ ] 支持地点搜索
- [ ] 接入小米 `/location/city/geo`
- [ ] 支持广州塔、卡伦海滩等景区/街道级收藏
- [ ] 配置页与调试日志
