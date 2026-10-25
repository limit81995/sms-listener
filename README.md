# 短信哨兵 / SmsListener

独立编写的 Kotlin Android 项目：任意号码发来的**短信正文包含关键词**时，在本机响铃、循环振动，直到手动停止或达到设置时长。不依赖、不引用 SmsForwarder 的代码或运行服务。

当前版本 0.5.0，范围：多规则 + 有序命中条件 + 本机报警 + 转发目标库（HTTP(S) / 飞书机器人）。最低 Android 8.0，编译和目标 API 36（Android 16）。目前还需要在 Redmi K90 Pro Max / 澎湃 OS 3 真机上验证；编译通过不代表已验证厂商的后台策略和振动硬件。

**第一次接触安卓开发，请先看 [新手使用指南](docs/新手使用指南.md)**：可以直接安装 APK，不必先学习 Kotlin。

本次构建与测试结果见 [验证记录](docs/VALIDATION.md)。

## 界面

查看 [0.2.0 界面截图与操作变化](docs/UI_0.2.md)。

- **首页**：运行状态、规则与转发数量、最新一条动态（点击进入完整日志）。安装后自动监听，没有开关。
- **提醒规则**：规则列表、新建、点击编辑、左滑或长按删除；每条规则可有多条有序命中规则，命中后可执行多个结果动作。
- **转发规则**：统一的转发目标库，支持 HTTP(S) 与飞书；点击编辑、左滑删除，被哪些规则引用会显示出来。
- **设置**：权限、报警测试、短信匹配检查、运行日志与帮助。
- **命中规则**：本机手机号、对方手机号、正文内容三类条件，每类可选全等 / 包含 / 正则；条件之间为“且”，多条命中规则之间为“或”。长按左侧手柄可拖动排序，顺序即列表顺序。
- **结果动作**：转发、响铃、振动、闪光灯，可同时添加多个；转发从“转发规则”库里勾选，目标改动无需改规则。
- **工具**：权限与后台设置、振动/响铃/闪光灯/完整报警测试、短信匹配检查、日志和首次使用说明。
- 报警时所有页面顶部都有停止入口；切换页面和屏幕重建保留未保存的表单。
- 保存规则与开启监听分开；旧版关键词配置会在首次启动时自动迁移为一条正则规则，无需重新配置。

## 功能

- 每条规则独立配置名称、描述、多条命中规则与多个结果动作；规则之间互不影响。
- 命中条件按字段区分：本机手机号（当前 SIM 号码，需要电话权限）、对方手机号、正文内容。
- 匹配方式：全等（忽略大小写与首尾空格）、包含（号码自动忽略 `+86`、空格和连字符）、正则（每行一条，英文忽略大小写，正文不截断）。
- 同一条命中规则内所有条件必须同时满足；多条命中规则之间任意一条满足即可触发。
- 结果动作可同时添加多个：响铃、振动、闪光灯、转发。转发在独立页面维护目标列表，可混合 HTTP(S) 与飞书机器人。
- 合并多段短信后再匹配，正文不截断；按号码、正文、短信时间戳和 SIM 订阅标识去重。
- 每个本地动作可单独设置时长；0 秒代表持续到手动停止，1～3600 秒自动停止。多条规则同时命中时，任一“手动停止”优先，否则取最长时长。
- 闪光灯通过相机手电筒闪烁，需要相机权限；设备无闪光灯时会记录日志并继续执行其他动作。
- 振动为原生循环波形，0.8 秒振动、0.4 秒间隔。Android 13+ 明确指定 `VibrationAttributes.USAGE_ALARM`，较早版本使用闹钟 `AudioAttributes`。
- 自带独立合成的循环提示音，使用系统**闹钟音量**，不自动调高音量或绕过勿扰。
- 监听开启期间运行低优先级常驻服务，保证后台能收到短信广播；报警期间另有独立的前台报警服务，通知与首页都有停止入口。两者都不做轮询。
- 报警中出现新命中时合并触发，不重置原定停止时间。
- 振动、响铃独立测试，关键词匹配测试，设备诊断，最近 200 条日志。
- 联网仅用于你配置的 HTTP / Webhook / 飞书地址；不读取历史短信、不发送短信、不读取联系人。日志不存短信正文、号码和关键词。
- Webhook 默认发送 JSON：`{event, rule, ruleId, sender, text, time}`；正文模板支持 `{text}` `{sender}` `{rule}` `{time}`。
- 飞书机器人支持文本消息与签名校验，签名使用 HMAC-SHA256（`timestamp\nsecret`）。

## 安装与首次使用

1. 编译后安装 `app/build/outputs/apk/debug/app-debug.apk`，打开“短信哨兵”。这是测试签名安装包。
2. 在“工具 → 运行准备”分别允许接收短信和显示通知。不需要设为默认短信应用。
3. 在“工具 → 运行准备”点击“后台运行”。在澎湃 OS 的应用设置中另行允许自启动，并将省电策略设为“无限制”。菜单名称随系统版本可能不同。
4. 在概览先点击“振动测试”，再点击“响铃测试”（各 5 秒）。系统闹钟音量为 0 时不会有可听见的铃声。
5. 在规则页每行输入一个关键词，选择响铃/振动；希望持续报警就在停止时间中选择“手动”。
6. 点击“保存规则”，回到概览点击“开启监听”。请用另一部手机发送实际短信，再验证锁屏、后台和重启后的表现。

SMS 权限属于 Android 受限制权限，安装器/系统策略可能影响授权；如果系统始终不弹出授权或按钮无效，查看应用权限页。开发调试可使用 `adb install -r -g app-debug.apk` 并检查授权结果。

暂停监听也会停止当前报警。修改表单后要保存才会影响真实短信；“完整报警测试”读取当前表单，但不会保存规则。关键词测试只检查已保存规则，不产生声音或振动。

## 构建

需要 JDK 21、Android SDK Platform 36、Build Tools 35.0.0。应用编译到 Java 17 字节码；JDK 21 用于 Robolectric 的 Android 16 框架模拟测试。固定使用 AGP 8.13.2、Kotlin 2.2.21、Gradle 8.13；Gradle Wrapper 校验发行包 SHA-256。

Android Studio 打开项目根目录即可。命令行在项目根目录运行：

```bash
sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
# 在 local.properties 中填写你本机的 SDK 路径，或设置 ANDROID_HOME。
./gradlew testDebugUnitTest lintDebug assembleDebug
```

测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。
静态检查报告：`app/build/reports/lint-results-debug.html`。

运行 `python3 tools/package_delivery.py` 可将构建好的 APK、纯源码压缩包与 SHA-256 校验文件整理到 `artifacts/`。源码压缩包不含本机 SDK 路径、编译缓存和签名密钥。

正式自用版本可以在 Android Studio 的 Generate Signed Bundle / APK 中创建自己的签名密钥。妥善保存同一个密钥，以后才能直接覆盖升级并保留配置。不要提交密钥和 `local.properties`。

内置铃声可以用 `python3 tools/generate_tone.py` 重建。它是本项目生成的双音 WAV，没有外部音频版权依赖。

## WSL 保存后自动更新真机

在 VS Code 的 WSL 窗口中开发，先用 Linux 版 ADB 连接手机。无线调试先执行 `adb pair` 配对，再用手机“无线调试”主页的连接端口执行 `adb connect`；配对端口与连接端口通常不同。

```bash
cd /data/httpd/smslistener
adb devices -l
# 将示例设备标识替换为上面目标手机第一列的实际值。
python3 tools/dev_device.py --serial 192.168.10.203:33641
```

命令启动后先更新一次。随后修改并保存 `app/src/main` 下的 Kotlin、资源或构建配置，会自动增量编译、覆盖安装、重启应用。已有构建缓存会被复用；这不是无编译热更新。连续保存会合并，构建期间的修改会在当前操作结束后继续处理，构建失败时不会安装旧 APK。设备离线时等待同一标识重新连接；无线调试端口变化后，重新连接并用新标识重启监听。

每次更新保留已保存配置，但会中断当前报警和未保存的表单。按 `Ctrl+C` 停止。`--once` 只更新一次，`--dry-run` 只显示命令。ADB 不在 PATH 时可用 `--adb /home/limit/Android/Sdk/platform-tools/adb` 指定。

也可在 VS Code 中选择“终端 → 运行任务 → Android：保存后自动更新真机”，输入设备标识。不要同时启动多个更新任务。

想在电脑旁边观察手机画面，可另开一个 WSL 终端，在支持 WSLg 的环境中运行：

```bash
scrcpy -s 192.168.10.203:33641 --no-audio
```

scrcpy 仅同步手机画面，界面更新由开发命令完成。安装 scrcpy 的命令为 `brew install scrcpy`。正常编译至少使用 JDK 17；项目完整测试使用 JDK 21。`local.properties` 中的 `sdk.dir` 应指向 WSL 的 Linux SDK，例如 `/home/limit/Android/Sdk`，不要填写 Windows SDK 路径。

## 代码结构

```text
app/src/main/java/io/github/smssentinel/
  core/    规则模型、纯 Kotlin 正则匹配、短信指纹、全局状态
  data/    规则 JSON 编解码与旧版迁移、去重记录、本地日志
  sms/     Android SMS_RECEIVED 接收器与多段正文合并
  action/  动作分发、HTTP/Webhook/飞书发送、闪光灯控制
  alarm/   前台服务、原生循环振动、循环音频、通知与停止
  ui/      规则列表与编辑页、左滑删除、权限入口、诊断信息
app/src/test/    匹配、指纹、配置测试；Android 9 / 16 框架模拟测试
```

接口边界：`SmsReceiver → SmsProcessor → RuleMatcher → ActionExecutor → AlarmService / NetSender`。新增动作类型只需扩展 `ActionType`、`NetSender` 与编辑页，匹配和报警链路不受影响。

前台服务使用 `specialUse`，清单已说明“用户设置的短信报警，包括仅振动，持续到用户确认”。这是持续报警服务，不借用有时间限制的短任务类型。若未来上架应用商店，需要按渠道要求申报 SMS 权限和服务用途。

## 系统限制与排查

- 部分厂商系统会冻结后台进程，导致 `SMS_RECEIVED` 广播丢失。应用通过常驻前台服务降低概率；如果仍出现后台不收短信，需要在系统里允许自启动并把省电策略设为“无限制”。
- Android 仍可因勿扰、系统振动设置、厂商策略或硬件条件抑制振动。日志“已提交振动请求”仅代表 API 调用未抛异常，不代表检测到了马达运动。
- Android 12+ 限制后台启动前台服务。应用捕获启动失败并记录异常类型，尽可能发出异常通知；取消电池优化是官方列出的豁免之一。厂商系统的自启动策略仍需真机验证。
- 通知权限/通知渠道关闭后，通知中的停止入口不可见，应用首页仍可停止。开启监听时会检查短信和通知权限。
- 本应用不会强制弹出全屏界面，也不依赖悬浮窗、无障碍或 root。可以从锁屏通知停止，具体锁屏通知展示取决于系统设置。
- 系统“强行停止”后，需要重新打开应用才能继续接收广播。重启后先解锁一次；此版本不在首次解锁前读取受凭据保护的规则。
- 进程被系统终止后不会自动重新播放旧报警；用户在系统任务管理器中停止应用时也会停止报警。
- 音频焦点被来电等收回时会暂停声音，焦点重新授予时恢复；振动独立执行。
- 只处理真正的 SMS 短信，RCS/5G 消息、微信等不属于 SMS 广播。

在应用内复制“诊断与运行日志”。如果日志显示已经提交振动请求，但 K90 实际不振动，可在振动测试进行期间执行：

```bash
adb shell dumpsys vibrator_manager
adb shell dumpsys notification
adb logcat -d -s SmsListener
```

这些系统输出可能包含其他应用的信息，分享前自行检查。具体真机检查步骤见 [docs/DEVICE_TESTS.md](docs/DEVICE_TESTS.md)。

官方参考：[Vibrator API](https://developer.android.com/reference/android/os/Vibrator)、[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)。

## 许可证

本项目自有代码使用 MIT 许可证，见 [LICENSE](LICENSE)。Kotlin、Gradle、Android SDK 和测试库遵循各自许可证。
