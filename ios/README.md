# iPhone 版（源码预览）

这是独立的原生 SwiftUI 实现：使用 PhotoKit 读取获准访问的照片，使用系统 Vision 在设备上识别文字，并在 App 私有 SQLite 索引中按文字搜索。原图不会上传或复制到索引中；未授权或已移除授权的照片不会出现在搜索结果中。

目前仍是源码预览，**没有可安装的 iPhone 安装包，也没有经过 iPhone 真机验证**。GitHub Actions 使用 macOS 构建无签名的模拟器版本并运行文字排版测试；这不能代替真机上的相册权限、识别效果和耗时测试。

如要在 Mac 上自行运行：安装最新稳定版 Xcode 和 [XcodeGen](https://github.com/yonaskolb/XcodeGen)，进入本目录运行 `xcodegen generate`，打开生成的 `MemeSearch.xcodeproj`，选择模拟器运行。若要安装到自己的 iPhone，需要在 Xcode 设置个人 Apple 账号与签名；若要通过 TestFlight 分发给朋友，还需要加入 Apple Developer Program、配置 App Store Connect 并完成相应审核流程。

竖排文字通过字符边界框计算从右到左、从上到下的候选读序，并作为额外搜索文本保存；没有强行替换 OCR 原文。只有字符几何位置足够明确时才启用，以降低横排图片误排风险。中文错字识别仍取决于 Vision 的结果，无法保证所有字体、画质和文字方向都被识别。

本目录与 Android 版共享产品目标，但不共享平台代码。Android 仍是仓库 Releases 中唯一可直接安装的版本。
