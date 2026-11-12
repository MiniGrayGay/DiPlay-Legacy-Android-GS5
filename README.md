# DiPlay Legacy Android — 广汽 GS5 适配版

[简体中文](README.md) · [English](README_EN.md)

> 本项目基于 [DiPlay Legacy Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android)（[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) 的低版本 Android 适配分支）进行二次深度适配，专门针对 **广汽传祺 2019–2022 款车机（GAEI AVNTG2A10）** 进行定制。
>
> **与上游的定位差异**：
> - 上游 DiPlay Legacy Android 面向通用老车机（Android 4.4–6.0、32 位芯片），以普通 APK 形式安装。
> - 本分支专为广汽 AVNTG2A10 车机深度适配：使用 **平台证书签名 + SYSTEM APP 权限** 运行，绕过该车机 ROM 裁剪导致的 USB 授权弹窗和 VPN 授权弹窗无法弹出的问题；并采用 **用户态 lwIP 网络栈** 替代不可用的内核 TUN/VPN 通道，实现有线 CarPlay 数据链路。
>
> 上游项目：[DiPlay Legacy Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android) · [DiPlay 原版](https://github.com/shihabal3amri/DiPlay)

---

## 广汽车机专属适配

### 目标平台

| 项目 | 详情 |
| :--- | :--- |
| 车型 | 广汽传祺 GS5 / GS4 / GS8 / GM6 等 2019–2022 款 |
| 车机型号 | GAEI AVNTG2A10 |
| SoC | Freescale (NXP) i.MX6 SABRESD |
| 系统 | Android 4.4.2 (API 19)，内核 3.0.35 |
| 架构 | ARMv7 (armeabi-v7a) |

### 平台签名 & SYSTEM APP 权限

广汽车机 ROM 裁剪了 USB 设备授权和 VPN 授权的系统 Activity，普通第三方 APP 在请求 USB 权限或建立 VPN 隧道时会因为找不到授权界面而静默失败。本分支的解决方案：

- **使用广平台证书签名** (`android@freescale.com`，SHA-256: `7fbae817...`)，APK 以系统应用身份安装。
- **声明 `android:sharedUserId=”android.uid.system”`**，进程以 `uid=1000 (system)` 运行，自动获得 USB Host 权限，无需弹窗授权。
- 该签名 + uid 组合使 APP 运行在 `system_app` SELinux 域，可直接访问 USB 设备节点，绕过了 ROM 中被移除的 `UsbPermissionActivity`。

> [!WARNING]
> 本版本 **无法与通用版 DiPlay 互相覆盖安装**。安装前必须先卸载已有版本，切换版本时数据会丢失。

### 用户态 lwIP 网络栈（替代 VPN/TUN）

该车机内核 3.0.35 的 TUN 驱动不可用，系统 VPN 框架也因缺少授权 Activity 而无法使用。本分支采用完全不同的网络方案：

- **用户态 lwIP**：在 JNI 层运行完整的 lwIP TCP/IP 协议栈 (`lwip_ncm_relay.c`)，直接解析 iPhone NCM（USB 网卡）的以太网帧。
- **Loopback Relay**：lwIP 将 iPhone 的 TCP/UDP 连接中继到本机 `127.0.0.1` 上的对应端口，上层 Java 代码通过标准 Socket 与 iPhone 通信，无需内核网络栈参与。
- 该方案完全工作在用户空间，不依赖 TUN/TAP 设备、VPN Service 或任何内核模块。

### 仪表盘 CarPlay（Presentation 显示）

- 车机拥有副屏（display 1, 1280×720），通过 LVDS 连接仪表盘中央区域。
- DiPlay 启动后自动接管仪表盘，将 CarPlay 仪表流（stream type 111）渲染到 `Presentation` 窗口。
- 通过广播 `AUTONAVI_STANDARD_BROADCAST_SEND` 通知仪表 MCU 切换到导航图层，无需安装高德地图。

---

## 继承自上游的核心改进

以下特性继承自 [DiPlay Legacy Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android) 上游通用版：

### 1. 深度适配低版本 Android 系统 (Android 4.4 ~ Android 7.0)
- **系统门槛下探至 Android 4.4 (API 19)**：
  - 修复 Android 7.0 以下 `Configuration.locales` 引发的 `NoSuchFieldError` 启动闪退。
  - 修复 Android 6.0 以下 `Context.getSystemService(Class)` 引发的 `NoSuchMethodError` 闪退。
  - 修复 Android 5.0 以下 `Theme.Material` 主题缺失，回退兼容 Holo 主题。
  - 修复无 `BluetoothLeScanner` 低版本系统的蓝牙扫描，支持手动输入蓝牙 MAC 地址连接。

### 2. 解决安装与解析包失败问题
- **双重签名机制 (v1 JAR + v2 APK Signature)**：满足 Android 4.4 / 6.0 老车机的签名格式要求，避免”无法解析安装包”。

### 3. 全架构 CPU 支持
- 原生编译 `armeabi-v7a`、`x86`、`arm64-v8a`、`x86_64` 四种架构。

### 4. 内置离线 MFi 认证证书
- 独立构建带入本地私有认证材料，无需外部认证服务器。该身份是实验性的，**不是 Apple 官方认证**。

### 5. USB 底层连接优化
- 自动驱动脱钩（解决 `EBUSY` / errno 16）；单 fd 共享避免二次 `openDevice`。

---

## 安装与升级重要说明

> [!IMPORTANT]
> **必须先卸载旧版本！**
> 
> 本分支使用 **平台证书签名**，与上游通用版及其他第三方版本的签名完全不同：
> 1. 上游原版、第三方修改版与本分支的 **签名证书不同**（本分支为 Freescale 平台证书，非个人开发者证书）。
> 2. 如果车机上已安装其他版本的 DiPlay，直接安装会被系统拒绝（签名冲突）。
> 3. **解决方法**：在车机【应用管理】中**完全卸载旧版 DiPlay**，然后再安装本分支的 APK。后续升级本分支的新版本可直接覆盖安装。
> 4. 同理，从本分支切换回通用版也需要先卸载。

---

## 连接使用

### 有线 (USB) 连接
1. 打开 DiPlay 进入等待连接界面；
2. 将 iPhone 插入车机 USB 数据口；
3. iPhone 弹出”信任此电脑”时点击**【信任】**。

由于本分支以 SYSTEM APP 运行，USB 权限自动获取，不需要手动授权弹窗。

> 广汽车机出厂自带 CarLife，开机后可能抢占 USB。建议在【应用管理】中将 CarLife **强行停止** 或禁用自启动。

### 无线连接
- 支持车机热点（Car Hotspot）、Wi-Fi Direct 以及局域网（Same LAN）三种模式。
- 广汽车机蓝牙模块为 Parrot FC6050（仅 HFP/A2DP/AVRCP/PBAP，无 BLE），需在设置中手动填入蓝牙 MAC 地址完成握手配对。

---

## 问题反馈

- **Issues**：[GitHub Issues](https://github.com/MiniGrayGay/DiPlay-Legacy-Android-GS5/issues)
- **提供日志**：DiPlay【设置】→【诊断】→【保存诊断报告】，将 `.txt` 文件附在 Issue 中，注明车型、车机固件版本、iPhone 型号及 iOS 版本。
- 日志路径：`/data/data/com.shihab.diplay/files/logs/diplay.log`

---

## 开源协议与鸣谢

- 基于 [xcertplay](https://github.com/shilapi/xcertplay) (GPL-3.0) 与 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) 开发。
- UI 交互衍生自 [DiAuto](https://github.com/shihabal3amri/DiAuto) (AGPL-3.0)。
- CarPlay 为 Apple Inc. 的注册商标，本项目仅供技术学习与车载兼容性研究。
