# 验证记录

## 0.2.0 界面版 · 2026-10-05

- 53 项自动化测试通过：19 项逻辑/序列化测试、34 项 Android 9/16 框架模拟测试。
- 新增覆盖：切换页面保留草稿、保存规则不自动开启监听、无权限不能开启、暂停不覆盖草稿、无效时长不能覆盖规则、各页报警停止入口随服务状态更新。
- Debug APK 构建成功；Lint 0 错误，3 条构建工具/测试依赖版本更新提示。
- 已在 Android 16 / API 36 模拟器安装运行，检查概览、规则、工具、键盘弹出时的保存按钮，以及 360dp 宽度与 1.3 倍字体布局。
- 模拟器实际短信链路通过：应用返回桌面 → 控制台注入匹配短信 → 系统 SMS 广播 → 日志记录命中 → 前台报警服务提交闹钟振动请求并开始循环音频 → 从新版顶部按钮停止并释放资源。
- 旧版与新版 APK 签名证书 SHA-256 相同，versionCode 从 1 增加到 2；支持同签名覆盖升级。
- 截图与操作变化见 [UI_0.2.md](UI_0.2.md)。

模拟器检查不代表已验证 Redmi K90 Pro Max / 澎湃 OS 3 的物理振动、实际声音或厂商后台策略。仍需要按新手指南在手机上测试。

## 0.1.0 初版

日期：2026-10-05（Asia/Shanghai）。版本：0.1.0 / versionCode 1。

## 已完成

- `testDebugUnitTest`、`lintDebug`、`assembleDebug` 联合构建成功。
- 41 项自动化测试通过，0 失败、0 错误。
  - 19 项纯逻辑/JSON 测试：关键词、排除词、任意/全部模式、空规则、长正文、去重指纹、配置序列化。
  - 22 项 Robolectric 框架模拟测试：同一组 11 项用例分别在 API 28 和 API 36 运行，覆盖闹钟振动属性、循环与取消、自动停止、重复触发不延长计时、暂停后的排队报警、前台服务异常、数据保存、短信 PDU 解析/拼接和 Activity 重建。
- Android Lint：0 错误、3 条提示，均为构建工具/测试依赖有更新版本。当前使用固定且通过验证的版本。
- `apksigner verify --verbose` 通过，APK v2 签名有效。
- APK 元数据确认：包名 `io.github.smslistener`，应用名“短信哨兵”，minSdk 26，targetSdk 36；无 INTERNET、READ_SMS、SEND_SMS 权限。
- Gradle Wrapper JAR SHA-256 与官方校验值一致；Gradle 发行包已配置 SHA-256 校验。

报告位于项目构建目录：

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`

## 尚未完成的真机验证

没有连接 Redmi K90 Pro Max / 澎湃 OS 3，因此还没有验证该手机的实际马达振动、可听见的铃声、运营商短信接收、锁屏、自启动及厂商省电行为。框架模拟测试不等于这些行为的真机验证。

请按照 [新手使用指南](新手使用指南.md) 先测试 5 秒振动与响铃，再发送真实短信；完整清单见 [DEVICE_TESTS.md](DEVICE_TESTS.md)。

交付 APK 为 debug 测试签名。源码压缩包不包含签名密钥；长期自行维护时，应创建和保管自己的正式签名。
