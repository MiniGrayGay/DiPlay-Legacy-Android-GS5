# DiPlay v0.4.2 — 广汽原始 NMEA 与 CAN 车速预发布版

这是面向广汽 GS5 车机的预发布测试版本。

## 主要变化

- 直接从车机 GPS 设备 `/dev/athrnmea` 读取原始 NMEA，备用路径为 `/dev/athrpipeout`，不再依赖 Android `LocationManager` 获取定位数据。
- 通过广汽车机 `android.vehicle.IVehicleManager` 的 `vehicle` Binder 服务读取 CAN 车速和挡位。
- 在识别到广汽仪表环境时，自动向 CarPlay 组合发送原始 GPS NMEA 与车辆速度数据。

## 安装信息

- 版本：`0.4.2`（versionCode `138`）
- 包名：`com.shihab.diplay`
- 最低 Android 版本：Android 4.2（API 17）
- 使用广汽车机平台证书签名。

此版本标记为 prerelease，建议先验证 GPS 定位、车速和挡位数据后再用于日常环境。
