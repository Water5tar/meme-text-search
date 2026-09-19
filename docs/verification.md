# 验证记录 — 1.0.0

日期：2026-09-19。目标设备为华为 Mate 60 Pro、HarmonyOS 4.2.0；本轮未连接客户手机。

## 交付物

- [签名发布 APK](../artifacts/MemeOCR-1.0.0.apk)：46,390,803 字节，约 44.2 MiB。
- 包名 com.memeocr.app；versionCode 1；versionName 1.0.0。
- minSdk 26，targetSdk/compileSdk 35。
- 包含 arm64-v8a、armeabi-v7a、x86、x86_64 库。
- 非 debuggable；APK v2/v3 签名验证通过。
- [SHA-256](../artifacts/SHA256SUMS)：e192575d812c1d423f62224a94ae9ba8579eff0ef11fb9197515965434ca12f7。
- [合并后的权限清单](../artifacts/permissions.txt)没有 INTERNET、ACCESS_NETWORK_STATE、WRITE_EXTERNAL_STORAGE 或 MANAGE_EXTERNAL_STORAGE。

签名密钥仅保存在项目私有的 .signing/ 中，未加入交付目录。后续更新必须保留同一签名身份。

## 自动检查

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| Kotlin/JVM 行为测试 | 75 项通过，0 失败、0 跳过 | [结果摘要](test-results/unit-summary.json) |
| Android 集成测试 | 8 项通过 | [实际设备测试输出](test-results/android-integration.txt) |
| 发布版 Android lint | 0 errors，11 warnings | [完整报告](test-results/lint-release.txt) |
| 发布 APK 构建 | 成功 | scripts/build-apk.sh |
| 签名、非调试标记、ABI、权限 | 通过 | 安装包及权限清单 |

lint 警告为依赖存在更新版本，以及中文界面字符串的国际化/数字格式化建议。没有通过屏蔽 lint 错误让构建通过。

纯逻辑代码 256 行，相关测试 656 行。Android 平台与界面代码 854 行，设备测试 157 行。平台与 UI 接线代码采用上级规则允许的样板代码例外；它们通过实际 Android 测试与界面操作验证，不以测试行数代替运行证据。早期未被应用使用的重复批次辅助类和仅检查数据字段的测试已移除。

覆盖内容包括：9000 条数据的分批处理与搜索、重复跳过、版本变化、失败单独重试、损坏/删除/无权限图片、中断续做、空文字保存、保存失败不计成功、正则错误、特殊字符与全半角。

## Android 运行环境与实际操作

环境：Android 11 / API 30、AOSP x86_64，无 Google Play services；模拟器 Wi-Fi 和移动数据关闭。使用隔离的 SDK、adb 5039 端口和 emulator-5580，不连接真实手机。

输入为自制的 3 张中文文字图、1 张英文/标点图、1 张空白图、1 个损坏 JPEG。它们不是用户的真实 meme。测试期间没有发送任何分享消息。

已实际检查：

1. 从应用首屏申请照片读取权限并进入相册。
2. 设置每批 2 张，识别并保存 2 张。
3. 继续处理剩余 4 张，最终为 4 张有文字、1 张无文字、1 张损坏失败。
4. 再次扫描：数据库所有记录与保存时间戳均未变化。
5. 强制结束并重开：已保存结果保留。
6. 普通文字 Hello 搜索命中 1 张；正则 [0-9]{4} 命中同一张；非法表达式 [ 显示错误。
7. 打开图片预览和系统分享菜单，未选择接收方或发送内容。
8. 比较 6 个测试原文件的 SHA-256，内容逐字节未变。
9. 卸载仅在模拟器中的调试版，安装最终签名发布 APK。
10. 发布版识别到第 2/6 张时停止，强制结束后重开保留 2 张，续做批次为 4 张；最终状态仍为 4 有文字、1 无文字、1 失败。
11. 发布版搜索 Hello、打开预览成功；AndroidRuntime 日志未发现应用崩溃。

[发布版机器可读结果](test-results/release-acceptance.json) · [样本 OCR 记录](test-results/sample-ocr-records.json) · [原图校验](test-results/original-image-hashes.json)

## 识别误差与测试边界

一张清晰中文样本的预期文本是：

    今天也要开心
    摸鱼时间到了

模型实际输出：

    今天也妻开心
    摸鱼时间到了

最初要求整句完全一致的检查因此失败：[首次输出](test-results/ocr-initial-mismatch.txt)。这不是解码失败，也没有被修正成“模型识别准确”。最终 OCR 集成测试检查模型在离线、无 GMS 条件下可运行，并识别“今天”“开心”和完整的第二行；源码保留了该误差的说明。

这批样本不足以测量真实 meme 的准确率。9000 条规模测试使用内存中的文字/元数据，不是 9000 张图片的 OCR 性能测试。

实际运行还发现并修复了图片尺寸探测返回空值被误认为解码失败的问题；Android 解码测试现已覆盖这一回归。测试图片最初处于系统媒体库的 is_pending 导入状态，发布为可见后相册读取正常，未将这个测试准备问题误报为应用权限故障。

## 界面截图

以下为最终签名发布版，截图已人工查看：

[停止后的进度](screenshots/release-cancel.png) · [续做完成](screenshots/release-album.png) · [搜索结果](screenshots/release-search.png) · [图片预览](screenshots/release-preview.png)

## Mate 60 Pro 待验

- 安装与本地相册权限。
- 先用约 100 张真实 meme 检查文字命中率和耗时。
- 切到聊天应用、锁屏、后台停留后的识别行为。
- 从预览打开分享菜单后，目标聊天应用是否接收原图。
- 全量图库的内存、发热和处理时长。

这些项目需要客户设备反馈，当前没有写成通过。应用具备断点保存；华为系统是否允许持续后台运行仍待核对。

## 复现

构建和签名：

    JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/sdk ./scripts/build-apk.sh

独立逻辑检查与 Android 集成测试：

    ./gradlew :core:test :app:lintRelease
    ./gradlew :app:connectedDebugAndroidTest

本轮 Android 集成测试通过专用 adb 安装调试 APK 与测试 APK，再执行：

    adb -P 5039 -s emulator-5580 shell am instrument -w -r \
      com.memeocr.app.test/androidx.test.runner.AndroidJUnitRunner

测试环境的模拟器曾在自带 SwiftShader 库中崩溃。读取本任务的崩溃堆栈后，主代理使用仅作用于模拟器进程的 Mesa 软件渲染配置启动成功：LIBGL_ALWAYS_SOFTWARE=1，-gpu host，-feature -Vulkan。没有更改系统图形、Java 或网络配置。验收后关闭该模拟器和本任务的 adb 服务。

## 协作记录

- ses_f46578949ffe3joCldWzLJt3z9：早期只读工具链调查。
- ses_f4652ca61ffeyXnnmj2emUUlW7：生成项目骨架、初步逻辑和测试。主代理审查发现问题后取消该会话；桥接确认已停止。主代理接管服务、解码、UI、测试、构建和验收。
- ses_f46435beaffeC4IsKJ6VbO4XDp：隔离模拟器调查，下载旧版后仍未启动成功，600 秒超时且服务端确认终止。主代理据实际堆栈解决启动条件，没有把该子任务算作完成验收。
