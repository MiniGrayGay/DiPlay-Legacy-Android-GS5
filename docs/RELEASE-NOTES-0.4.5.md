# DiPlay v0.4.5 — 广汽精简版：移除比亚迪适配层，同步上游协议修复

广汽 GS5 专用版本，彻底移除比亚迪/华深鑫专属代码，合入上游 DiPlay 0.2.22 协议修复。

## 主要变化

### 精简
- 移除比亚迪适配层：BYD HUD/仪表导航、仪表歌曲和通话卡片、电池信息、DiLink 3 输出、车辆数据探测
- 移除本机 ADB 通道（LocalAdb）及依赖它的全部功能：ADB 仪表活动、权限授予、热点修复、Wi-Fi 扫描暂停、方向盘服务自启
- 移除 DiLink 4/5.1 仪表布局、使用访问权限主题监听、仪表安全区编辑器
- 移除华深鑫蓝牙 SDK（HsaeBluetoothAdapter/HsaeRfcommStream）
- 移除 WheelKeyService 无障碍服务及 6 个相关测试
- 移除应用内更新（DiPlayUpdater/GiteeReleases）
- BYD 命名统一改为通用名：NavigationOutputs、WheelIntegrationSettings、RouteGuidanceState

### 上游同步
- 合入 DiPlay 0.2.22 协议修复：数据口 peer 过滤、输入上限、触摸槽位管理、麦克风时钟与分包、车道引导

### 广汽适配（从 v0.4.4 系列合入）
- 首页改为无线/有线两栏布局
- 支持浅色、深色和自动外观切换
- 三指快捷设置（完整菜单收进「更多设置」）
- 方向盘接听、挂断和长按语音键接管给 CarPlay
- 定位改走系统 gps provider（与原厂高德一致）
- NMEA 只转发校验和正确的 GGA/RMC
- CAN 车速修复（之前从未送到 iPhone）
- CarPlay 期间按车机方式断开已连接手机的蓝牙
- 无线连接去掉本机无效选项（5 GHz、root 热点）
- CarPlay 车标名默认改为「传祺」

## 安装信息

- 版本：`0.4.5`（versionCode `153`）
- 包名：`com.shihab.diplay`
- 最低 Android 版本：Android 4.2（API 17）
- 使用广汽车机平台证书签名
