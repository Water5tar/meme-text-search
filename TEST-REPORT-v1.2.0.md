# Android 1.2.0 与 iPhone 源码验证

验证日期：2026-09-30。

## Android 安装包

- `MemeSearch-1.2.0.apk`，`com.memeocr.local`，versionCode 3，最低 Android 8.0。
- Release APK 为 45,389,050 字节；比 1.1.0 增加 16,384 字节，约 0.036%。没有增加 OCR 模型或额外识别轮次。
- SHA-256：`7554fe1df0d0119fda105075c21270baca9f1a59aed4d0dc6f78823b6e6a65d5`。
- 使用与 1.1.0 相同的发布证书签名，证书 SHA-256：`19fbe635819f465c8781d22cf07e0d8c6b7570628ec48d4f68029edb220c5640`。Android 14 模拟器已从已安装且索引一张图片的 1.1.0 直接覆盖安装至 1.2.0；启动正常，界面显示已识别 1/1。
- `:core:test :app:assembleRelease :app:lintRelease` 通过，JVM 测试 81 项通过。Release Lint：0 errors、6 warnings。
- Android 14 模拟器的四项 RealOcrTest 全部通过，包括内置中英文 OCR、MediaStore 实际图片索引与增量跳过、空白及损坏图片处理、竖排中文合成图的真实 ML Kit 识别。测试图中模型原始顺序为左列“你我他”再右列“天地人”；新索引额外保存右列在前的“天地人／你我他”。

升级时数据库会将已识别图片标记为待重新识别，由原有后台队列分批处理；在重识别完成前保留旧文字可搜索。此处只验证了模拟器中一张图片的实际覆盖安装，尚未验证大相册升级耗时。用户此前对 Redmi Note 14 Pro+ 的试用反馈针对 1.1.0，1.2.0 尚待该真机复测。

## iPhone 源码

`ios/` 包含 SwiftUI、PhotoKit、Vision、SQLite 原生实现，以及 macOS GitHub Actions 的无签名模拟器构建与排版测试。此版没有真机或用户相册的验证，也没有可安装的签名 iPhone 包。构建与测试状态以 [GitHub Actions](https://github.com/Water5tar/meme-text-search/actions/workflows/ios-build.yml) 最新一次运行结果为准。

## 识别边界

几何重排只纠正能识别出文字的竖排读序，不能修正模型认错的单字。当前没有真实失败表情包样本，因此不声称已解决错字识别。保留原始 OCR 文本，可继续按原词搜索。复杂弧形字、倾斜文字、低清晰度和中英混排仍需实际图片验证。
