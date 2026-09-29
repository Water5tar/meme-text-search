# MemeSearch 1.1.0 · 预览版

一个给朋友们试用的 Android 离线表情包文字搜索工具。

基于 [rb-tyz/wheres-my-meme](https://github.com/rb-tyz/wheres-my-meme) 改进，感谢原作者；保留 MIT 许可证和版权声明。

## 下载与使用

请下载本页附件 **MemeSearch-1.1.0.apk**，在 Android 手机上安装。最低 Android 8.0。
GitHub 自动提供的 Source code ZIP / tar.gz 是源码，不能直接安装。

打开后授权照片，App 自动识别图片中的中英文。输入图片里的文字即可搜索，点击结果可预览和调用系统分享。

无需联网、账号或 API Key，不上传照片和 OCR 文字。原图只读，索引留在本机。

## 本版变化

- 授权后自动索引，支持暂停、继续和失败重试。
- Compose 图片网格，输入即搜索，Room 分页。
- WorkManager 持久队列，重新打开后从未完成项继续。
- 新增、修改、删除的增量同步，Android 14 部分照片授权。

## 验证与限制

77 项 JVM 测试、13 项 Android 测试通过；实际创建 1,000 / 5,000 / 10,000 张合成图片并完成 OCR 压力测试。
自动化验证环境为 Android 14 模拟器。用户已在 Redmi Note 14 Pro+ 上试用并反馈未发现问题；该手机的系统版本、完整测试步骤和性能数据尚未记录。OCR 可能错字、漏字，长图和小字可能识别不准；后台处理速度受系统调度影响。

这是 MVP / 预览版，欢迎试用反馈。详细结果见仓库 TEST-REPORT.md，原始证据随 Release 附件提供。

APK SHA-256：`bc521b2e6a1a6e0d5e4c4d451069be012ffee9a812807ba752f0333bf1547669`。
