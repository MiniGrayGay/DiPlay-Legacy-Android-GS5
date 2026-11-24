# DiPlay 混音与音量接管计划（feat/self-mix）

- 分支：`feat/self-mix`，基于 dev `adb7ae0`（0.4.6.10）
- 状态：阶段 1（钩子 + 音量条）在 0.4.6.12-alpha2（vc171）上车测过媒体和导航接管，倒车、通话、关开关三项待测；阶段 2、3 未开始
- 用法：直接改这份文件。标了 **【可改】** 的是设计决定；改完告诉我，我按改后的版本做。

## 1. 目标

1. **DiPlay 混音**：播报和音乐都用媒体流输出，不再申请车机导航音源（27）。车机 DSP 始终处在媒体模式，不切换，所以不会再出现开头偏小、切换时音乐突然变大、播报被推迟、结束后要等恢复这些问题。
2. **音量完全接管**：DiPlay 在运行时，所有音量键（方向盘、面板、旋钮）都由 DiPlay 决定调什么，并由 DiPlay 自己画音量条，不再弹原车音量条。
   - 播报中：调车机真实的导航音量，显示导航音量条。
   - 其他时候：调媒体音量，显示媒体音量条。
3. 混音时，播报增益根据车机当前的导航音量和媒体音量来算。原车设置里的导航音量滑块照样有用。
4. 两项功能都做成开关，可以和现在的「车机导航声道」方式切换对比。

## 2. 已确认的车机事实

| 事实 | 出处 |
|---|---|
| 平台签名、`android.uid.system` 的应用，组件可以声明 `android:process="system"`，在 system_server 里运行。本机的 SettingsProvider、FusedLocation、InputDevices 都这么做 | `extracted/p5/priv-app/*.apk` 的 manifest |
| 所有音量键最后都走到 `PhoneWindowManager.setVolume(step)`，它往 `mHandler` 发消息 19（`MSG_VOLUME_SET_DELAY`，`arg1` 是带正负的步数）。处理时先 `setUnmute()`，再检查倒车状态，然后调 `DspManager.adjustVolume(dir, show, |step|)` | `PhoneWindowManager` 约 1174 行、4684 行、4976 行 |
| `PhoneWindowManager.mHandler` 是普通的 `Handler` 字段（不是 final）；倒车状态在 `mRvcAvmState.state` 里，2 表示倒车 | 同上，333 行、391 行 |
| `doAdjustVolume`：`mNaviState==1` 时调导航音量，否则调当前策略对应的音量；`show=true` 时才发 `callbackVolumeChanged`，原车音量条就是靠这个回调弹出的 | `DspService.java` 1766 行 |
| `setVolumeByFlag(vol, flag)`：flag 0 是媒体（会自动取消静音，并考虑「随车速调音量」SVC 的补偿），flag 2 是导航。不发音量回调，所以不弹原车音量条。原车设置的滑块用的就是这个接口 | `DspService.java` 1623 行 |
| 媒体策略是 0、2、4（`isMediaPolicy`）；策略 3、5、6、8 是通话或语音音量 | `DspService.java` 111 行、1751 行 |
| 监听音量回调的只有 SystemUI（原车音量条）、GaeiSettings（设置页滑块）和框架本身。仪表盘不监听 | dump 的 odex 字符串 |
| DSP 服务名是 `"dsp"`，窗口服务是 `"window"`。在 system_server 里，`ServiceManager.getService` 拿到的就是服务对象本身，可以直接反射 | `ServerThread.java` 414 行 |
| DSP 音量表（档位 → dB）：[-100,-70,-55,-45,-40,-37,-35,-33,-31,-29,-27,-25,-23,-21,-19,-17,-15,-13,-12,-11,-10,-9,-8,-7,-6,-5,-4,-3,-2,-1,0] | `DspService.DSP_VOLUME_TABLE` |
| 媒体模式下，DSP 主音量就是媒体档位，系统音乐流开到最大（0 dB）。导航模式下，DSP 主音量固定在第 20 档（−10 dB），音乐流压到 9/30，导航流用导航档位 | `DspService.doFadeIn`、`funcNavi2media` |
| 导航流（5）用 AOSP 扬声器提示音曲线：{1,-29.7},{33,-20.1},{66,-10.2},{100,0}，档位按 /30 换算 | `audio_policy.default.so` 未改动 |

## 3. 设计决定

### D1. 开关 【可改】
| 开关 | 选项 | 默认 |
|---|---|---|
| 导航播报方式 | 车机导航声道（现有方式）/ DiPlay 混音 | 车机导航声道 |
| 音量键由 DiPlay 接管 | 开 / 关 | 关 |

两个开关互相独立。只开接管、不开混音时：
- 车机自己处在导航模式（`mNaviState==1`，即 CarPlay 播报占着音源 27，或其他导航软件在播报）时，钩子调导航音量，画 DiPlay 的导航音量条；
- 其他时候照常接管媒体音量，并画 DiPlay 的音量条。

（0.4.6.12-alpha1 上车时导航模式还是交回原车，会弹原车的导航音量条；alpha2 起改为接管。）

### D2. 钩子什么时候接管 【可改】
每次按音量键，钩子按下表从上往下判断：

| 条件 | 处理 |
|---|---|
| 钩子总开关 `persist.sys.diplay.vol_hook` 不为 1 | 交回原车 |
| DiPlay 进程不在（`sys.diplay.vol_pid` 对应的进程不存在，或进程名不是 `com.shihab.diplay`） | 交回原车 |
| 倒车中（`mRvcAvmState.state != 0`） | 交回原车 |
| 车机处在导航模式（`mNaviState == 1`），不论策略 | 导航音量 ± 步数，`setVolumeByFlag(v, 2)`。原车 `doAdjustVolume` 此时也只是 `setSwNaviVol(v)` 并存 `mPolicyVolumes[1]`，和 `setVolumeByFlag(v, 2)` 完全一样 |
| 当前不是媒体策略 0、2、4（通话、语音等；−1「还没有策略」也算，因为 `setVolumeByFlag` 在 −1 时只记数值、不改 DSP） | 交回原车 |
| 当前处于导航播报窗口（`uptimeMillis < sys.diplay.navi_until`） | 导航音量 ± 步数，`setVolumeByFlag(v, 2)` |
| 其他（媒体） | 媒体音量 ± 步数，`setVolumeByFlag(v, 0)` |

- 接管后，钩子给 DiPlay 发一个广播：`com.shihab.diplay.action.CAR_VOLUME`，附带 `kind=media|navi`、`value`、`step`。DiPlay 收到后画音量条。
- 交回原车就是照原样调用 `original.handleMessage(msg)`，行为和没装钩子时完全一样。
- 接管时和原车一样，先 `setUnmute()` 再调音量。DSP 调用放在钩子自己的线程上执行：`setVolumeByFlag` 要拿 `DspService.mLock`，车机做导航→媒体渐变时会持锁约 0.4 s，不能让窗口策略线程等。原车的 `adjustVolume` 也是交给 DSP 的 Handler 异步执行的。
- 按住音量键时的连续调节（消息 5/6）调用的也是 `setVolume(±1)`，同样走消息 19，钩子能接管。
- 三指下滑菜单里也有这个开关，关掉后音量键立即交还车机。

### D3. 播报窗口 【可改】
- 混音模式下，每收到一段有声音的导航数据，DiPlay 就把 `sys.diplay.navi_until` 设成「现在 + 1500 ms」。
- 播报结束放掉时（按现有算法：导航音轨延迟 − 50 ms），改成设成「现在 + 宽限时间」。
- 宽限时间：**800 ms**。播报刚结束时按音量键，调的仍然是导航音量。
- 车机导航声道模式下不需要设这个窗口，DSP 自己会处理。

### D4. 混音时的播报增益 【可改】
```
媒体响度   = DSP[媒体档位]                    （音乐流开到最大，0 dB）
播报目标   = DSP[20] + 提示音曲线(导航档位) + 微调
播报增益   = 播报目标 − 媒体响度
```
- 例：媒体 10、导航 15 → −10 + (−15.0) − (−27) = **+2.0 dB**。媒体 20、导航 15 → **−15 dB**。媒体 5、导航 15 → **+12 dB**。
- 播报目标用的是「车机导航模式下，导航也经过 DSP 第 20 档主音量」这个**假设**，第一版要上车对比两种模式下听感是否一致。
- 微调先固定为 0 dB。如果实测有固定偏差，再改常数，或者加一个「播报响度微调」滑块。
- 增益大于 0 dB 时，加软限幅：峰值超过 −1 dBFS 就压，限幅释放时间 100 ms，最大增益 +12 dB。
- 导航档位为 0 时，播报静音，和原车一致。
- 「随车速调音量」(SVC) 打开时，播报也会跟着一起变响。原车导航模式下播报不受 SVC 影响，这里先接受这个差异。
- 媒体档位和导航档位在 DiPlay 启动时读一次（`DspManager.getVolume(0 / 2)`），之后按钩子发来的广播和 `DspManager` 的音量监听更新。

### D5. 混音时的音乐压低 【可改】
- 沿用现有滑块「播报时音乐比播报前低多少 dB」，默认 6 dB。
- 在 DiPlay 里用 `setStereoVolume` 渐变：压低用 **300 ms**，恢复用 **500 ms**，每 20 ms 一步。
- 放掉的时机沿用 0.4.6.10：导航音轨延迟 − 50 ms。混音模式下没有车机切换，播报不用再等，直接播放。

### D6. 输出流
- 混音模式下，导航音轨改用 `STREAM_MUSIC`，播报增益直接乘进 PCM（这样才能放大到 0 dB 以上）。
- 媒体音轨不变。

### D7. DiPlay 音量条 【可改】
- 用悬浮窗显示（`TYPE_SYSTEM_ERROR`，不可触摸、不抢焦点）。DiPlay 是系统 uid，在后台也能显示。
- 位置：屏幕顶部居中，宽度占屏幕 40%。
- 内容：「媒体音量」或「导航音量」加数值 `n/30`。导航用蓝色（和原车一致），媒体用白色。
- 最后一次调节后 1.5 s 自动隐藏。
- 静音键（164）第一版**不接管**，照原车处理。

### D8. 在 system 进程里运行的安全措施
1. **钩子用 Java 写**，不依赖 Kotlin 标准库，并通过 `multidex-config.pro` 放进主 dex。在 system_server 里不执行 `MultiDex.install`。
2. `DiPlayApplication.attachBaseContext` / `onCreate` 先判断当前进程是不是 `system_server`（读 `/proc/self/cmdline`）。如果是，**什么都不做**：不装崩溃处理器、不初始化日志、不恢复悬浮点、不装 MultiDex。
3. 钩子的每个入口都包在 `try/catch (Throwable)` 里，任何一步出错就交回 `original.handleMessage(msg)`。
4. 安装时机：
   - `GuangqiVolumeHookReceiver`（`android:process="system"`，不导出）在 BOOT_COMPLETED 时，只有 `persist.sys.diplay.vol_hook=1` 才安装。开关关闭时，system_server 里不会有钩子。
   - DiPlay 启动时，如果接管开关是开的，再发一次显式广播触发安装。
   - 安装操作 post 到 `PhoneWindowManager` 的 Handler 线程上执行。重复安装时，判断类名和版本号，已经装过就跳过；旧版本就套在旧钩子的 original 外面重新装。
5. 更新 DiPlay 后，system_server 里还是旧钩子，**重启车机才会换成新的**。钩子带版本号，DiPlay 发现版本不一致会写日志，并在设置页提示「重启车机后生效」。
6. **随时可以关闭**：
   - 关掉接管开关，DiPlay 会清掉 `sys.diplay.vol_pid`，钩子立即交回原车；
   - 设 `persist.sys.diplay.vol_hook=0` 也一样（远控救急用）。属性名必须以 `persist.sys.` 开头，系统 uid 才有权限写；
   - 重启车机后钩子完全消失。

### D9. 为什么不用工程模式的按键例外
原车有个例外：前台是工程模式按键测试页面时，所有按键直接交给页面（只比较类名）。我们考虑过让 DiPlay 冒用这个类名，最后没用：
- 例外只看顶层 Activity，后台服务做不到；DiPlay 的设置页、对话框也不匹配。
- 它会把**所有**按键交给 DiPlay。HOME、关屏、屏保、地图、设置、AM/FM、语音、通话都要 DiPlay 自己实现，漏掉的在 DiPlay 前台时就失灵。
- 无法再注入回系统：注入的按键同样只按类名判断，会再交回 DiPlay，形成循环。方向盘键本身就是注入的，也没法区分。
- 按键要经过窗口焦点。焦点被别的窗口拿走时，按键会丢。

钩子只处理音量消息 19，其他按键完全不碰，前台后台一样，所以选钩子。

## 4. 实现步骤

### 阶段 1：钩子 + 音量条（不动混音）
1. `DiPlayApplication`：判断 system 进程并跳过初始化（D8.2）。
2. `GuangqiVolumeHook.java`：
   - 用反射拿到 `WindowManagerService.mPolicy` → `PhoneWindowManager.mHandler`，换成包装 Handler；
   - 用反射调用 `DspService` 的 `mPolicy`、`mNaviState`、`getVolumeByFlag`、`setVolumeByFlag`；
   - 实现 D2 的判断规则；
   - 发 `CAR_VOLUME` 广播。
3. `GuangqiVolumeHookReceiver` 加进 manifest（`process="system"`），并加主 dex 的保留规则。
4. DiPlay 进程这边：
   - 开关打开时设置 `sys.diplay.vol_pid`；
   - 发广播触发安装；
   - 接收 `CAR_VOLUME` 并画音量条（D7）。
5. 设置页加「音量键由 DiPlay 接管」开关。
6. 单元测试：D2 的判断规则抽成纯函数来测。反射部分用假对象测「找不到字段就交回原车」。

→ 打包 0.4.6.11-selfmix.1 上车测：钩子是否装上、媒体音量能不能调、是否不弹原车条、DiPlay 的条是否显示、倒车和通话时是否交回原车。

#### 上车结果（2026-11-24，0.4.6.12-alpha2，vc171）
| 场景 | 结果 |
|---|---|
| 开机后钩子安装 | ✅ DiPlay 启动时装上 v2；约 45 s 后 `BOOT_COMPLETED` 到达，打出 `already installed v2 (boot)`。从 v1 升到 v2 要重启一次 |
| 平时放音乐按音量键 | ✅ `step ±1 → media N`，DSP `change media vol … ret: 0`；没有 `doAdjustVolume` 和 `callbackVolumeChanged`，只出现 DiPlay 的白色媒体条，声音跟着变 |
| 导航播报时按音量键（`mNaviState == 1`） | ✅ 两次播报按 9 下，导航音量 15→18、19→16；DSP 只有 `getVolumeByFlag/setVolumeByFlag flag 2`，只出现 DiPlay 的蓝色导航条，声音跟着变。`setUnmute` 打出的 `cannot unmute media during calling/vr/navi` 和原车一样，不是错误 |
| 导航和媒体模式来回切换 | ✅ 两分钟内切换约 7 次，都正常切回，没有报错 |
| 三指菜单「重启车机」短按（`svc power reboot`） | ✅ 车机自己开机回来 |
| 按住 3 秒（`setprop sys.powerctl reboot`） | 待测 |
| 倒车、通话时交回原车 | 待测 |
| 三指菜单关掉接管开关后立即恢复原车 | 待测 |

「重启车机」按钮只为测试方便，合回 dev 前要去掉。

### 阶段 2：DiPlay 混音
1. 设置页加「导航播报方式」开关（D1）。
2. 混音模式下：`GuangqiNavigationSource` 不申请音源 27，闸门直接打开，改为设置播报窗口（D3）。
3. 导航音轨改用 `STREAM_MUSIC`，PCM 乘增益并做限幅（D4、D6）。
4. 媒体音轨做软件压低和恢复渐变（D5）。
5. 增益计算抽成纯函数，单元测试覆盖 D4 里的几个例子和限幅。

→ 打包 0.4.6.11-selfmix.2 上车测：和车机导航声道模式对比播报响度、音乐压低、恢复的感受。

### 阶段 3：播报中调导航音量
1. 播报窗口生效时，钩子改调导航音量（D2 第 5 行），DiPlay 画导航条，混音增益立即更新。
2. 上车测：播报中按方向盘 ± 和转旋钮，导航音量是否变化、原车设置里的导航滑块是否同步、播报结束后 800 ms 内按键是否仍调导航。

测完合回 dev，版本号按规则顺延。

## 5. 日志
都写进 `diplay.log`，方便上车核对：
- `volume hook: installed vN on PhoneWindowManager.mHandler` / `install failed: …`
- `volume hook: step +2 → media 12` / `→ navi 16` / `pass to car (reason)`
- `guidance mix: media 10 (-27 dB) navi 15 (-15.0 dB) gain +2.0 dB`
- `guidance mix: music -6 dB fade 300ms` / `restore 500ms`

## 6. 风险与回退
| 风险 | 后果 | 对策 |
|---|---|---|
| 钩子代码在 system_server 里抛出未捕获的异常 | 系统软重启（UI 重启几十秒），重启后钩子消失 | D8.3 全部 try/catch；安装前先检查所有反射目标都存在 |
| DiPlay 卡死但进程还在 | 音量照样被调，只是不显示音量条 | 钩子直接调 DSP，不依赖 DiPlay 回应 |
| 播报目标响度的假设不对 | 混音模式下播报偏响或偏轻 | 日志记录计算过程，按实测改常数或加微调滑块 |
| 原车设置页开着时调音量 | 设置页滑块不实时跟着动 | 接受。重新进入设置页就会正确 |

## 7. 待你确认
1. D3 播报结束后的宽限时间：800 ms 合适吗？
2. D7 音量条的位置、样式、显示时长。
3. 静音键第一版不接管，可以吗？
4. 两个开关的默认值（现在都设为原车行为）。
