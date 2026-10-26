# Android 1.1.0 历史验证记录

验证日期：2026-09-28。上游基线：`rb-tyz/wheres-my-meme`，MIT，提交 `168a6630d65f4a5a0d9fc03a7bab7d4aef85b6b5`。本文是当时的发布前记录；最新版本请见 [1.2.0 测试报告](TEST-REPORT-v1.2.0.md)。

## 交付构建

- 文件：`MemeSearch-1.1.0.apk`；包名 `com.memeocr.local`；版本 1.1.0 / versionCode 2。
- 最低 Android 8.0 / API 26；target / compile SDK 35。
- release 构建开启 R8 压缩，包含离线中文和拉丁文字模型及多架构原生库。
- APK 45,372,666 字节，约 43.3 MiB。
- SHA-256：`bc521b2e6a1a6e0d5e4c4d451069be012ffee9a812807ba752f0333bf1547669`。
- 本地 RSA 3072 发布密钥，APK Signature Scheme v2 验证通过。证书 SHA-256：`19fbe635819f465c8781d22cf07e0d8c6b7570628ec48d4f68029edb220c5640`。
- APK Manifest 无 `INTERNET`、`ACCESS_NETWORK_STATE`、`WRITE_EXTERNAL_STORAGE` 或 `MANAGE_EXTERNAL_STORAGE`。包含照片读取、WorkManager 相关系统权限及应用自己的内部 receiver 权限。
- `:core:test :app:assembleRelease :app:assembleDebugAndroidTest :app:lintRelease :app:lintDebug` 成功。Lint 为 0 errors / 24 warnings；警告包括固定依赖存在新版、刻意用于持久化检查点的 SharedPreferences.commit，以及上游保留的未使用资源。

## 环境与边界

Windows 主机：AMD Ryzen 7 6800H，16 个逻辑处理器。Android 14 / API 34 Google APIs x86_64 模拟器，4 vCPU、2 GiB RAM、1080×2340，AEHD 硬件加速，启动使用软件图形渲染。模拟器 Wi-Fi、移动数据关闭。全程仅操作 `emulator-5554` 的合成测试图片。

本报告的自动化验证没有连接真实手机。之后用户提供了 Redmi Note 14 Pro+ 的真机试用反馈，见下文独立记录。以下耗时是模拟器的实测值，不能预测用户手机、不同分辨率、长截图、复杂背景、中文密集图片或厂商后台策略的耗时。压力测试运行 debug 构建的生产索引路径；签名 release 包另做实际安装与功能验证。没有对 release 包重复做整套一万张压力测试。

## 自动化测试

**77 项 JVM 测试、13 项最终 Android 设备测试全部通过，0 failures / errors。** 另有 5,000 和 10,000 张规模的独立 Android 压力测试，各 1 项通过。

最终 13 项设备测试覆盖：

- Room 的中文 substring、英文大小写、全角规范化、普通字符 `%` / `_`；不匹配语义或文件名。
- 未修改的图片、已识别无文字的图片和失败图片不重复 OCR；用户重试只重置失败项。
- 图片版本变化失效、过期 OCR 结果不能覆盖新版本、MediaStore 重建失效。
- 部分授权隐藏缓存与完整访问下删除索引的区别。
- Room 关闭重开后 pending / indexed 状态保留，控制器重建后恢复未提交同步请求。
- 1k / 5k / 10k / 50k 数据库查询与分页。
- 实际合成中文图片 → MediaStore → ML Kit → WorkManager → Room → Compose 搜索、Grid、预览、返回。
- 实际分享方法生成 ACTION_SEND / chooser，验证 MIME、EXTRA_STREAM、ClipData 和临时 URI 读取授权；测试拦截 chooser，不向第三方发送内容。
- 离线中文与英文模型、真实 MediaStore 图片索引、增量同步跳过已完成项。
- 有效空白图片标为 indexed 且文本为空；损坏文件标为 failed；失败重试不重做空白图片。
- 1,000 张真实合成图片的分段后台队列、暂停 / 恢复、已完成结果复用。

单张 MediaStore → 可搜索记录的本次测量为 317 ms；单独中英文测试的模型调用测量为 166.7 ms。这是指定测试图片的单次结果，不是平均 OCR 延迟。

## 真实图片批量压力测试

每个规模都实际创建 MediaStore JPEG 文件，用真实解码器、内置 ML Kit 两个模型和生产 WorkManager / Room 路径逐张识别。图片为重复的 512×256 黑白 `HELLO MEME` 合成图，不能代表复杂真实相册。准备与清理图片时间不计入下表索引时间。

| 图片数量 | 首次元数据同步 | OCR 队列完成时间（含一次暂停） | 每秒采样的最大进程 PSS | 暂停时已完成 | 结果 |
|---:|---:|---:|---:|---:|---|
| 1,000 | 210.6 ms | 82.890 s | 181,345 KiB / 177.1 MiB | 31 | 通过 |
| 5,000 | 1,313.7 ms | 628.263 s / 10 分 28 秒 | 229,744 KiB / 224.4 MiB | 34 | 通过 |
| 10,000 | 2,412.1 ms | 840.130 s / 14 分 00 秒 | 191,390 KiB / 186.9 MiB | 32 | 通过 |

三次均完成全部图片并命中 `hello`，暂停后检查队列停住，恢复后完成项的 indexedAt 保持不变。未出现测试 App 的 OOM 或崩溃。OCR 并发 1；每张图片结束即释放 Bitmap。PSS 是进程内存的每秒采样最大值，包含模型 / 堆 / 原生等内存，可能漏掉短暂峰值，不能视为 Bitmap 堆峰值。

5,000 张运行期间实际打开首页，搜索 `hello`；界面显示已经完成的 3,046 个结果，同时索引仍在继续。证据包含 `search-during-index.png`。10,000 张运行主要在后台。因此不同规模的吞吐量不能简单线性比较。

## 搜索查询实测

独立 Room 测试创建以下规模记录，约 10% 命中。每个规模连续执行五次结果计数及首屏 60 条分页读取，使用 `instr(normalizedText, query)` 字面匹配。

| 数据库记录数 | 五次中位数 | 五次最大值 |
|---:|---:|---:|
| 1,000 | 10.68 ms | 13.63 ms |
| 5,000 | 10.14 ms | 13.15 ms |
| 10,000 | 13.46 ms | 14.75 ms |
| 50,000 | 41.78 ms | 44.12 ms |

这些值只包含数据库 count + 首屏分页，未包含 200 ms 输入防抖、Compose 绘制或图片解码。50,000 是数据库规模验证，没有做 50,000 张真实图片 OCR。手动滑动验证了 80 个 release 搜索结果的 Grid，但未测 FPS / 帧时间，也未验证一万张相册在真机的滚动流畅度。

## 签名 release 包实际验收

安装上述 SHA-256 对应的 release APK 后，用由 shell 写入、非本 App 所有的测试图片进行以下检查，避免应用自有照片绕过授权而造成误判。

| 操作 | 实际结果 |
|---|---|
| 首次打开，系统弹窗选择 Allow all | 两张图片自动 OCR；中文、英文进入搜索索引 |
| 强停再打开 | 两条 indexedAt 均未变化，没有重新 OCR |
| 撤销全部照片权限 | 显示授权界面，缓存两条记录，visible 为 0 |
| Android 14 系统 picker 只选择英文图 | READ_MEDIA_IMAGES=false、READ_MEDIA_VISUAL_USER_SELECTED=true；界面提示仅搜索获准照片，visible 为 1；两条缓存的 OCR 时间戳均保留 |
| 恢复完整照片权限 | 两张重新可见，OCR 时间戳仍未变化 |
| 同一原图路径从 HELLO MEME 改成 GOODBYE MEME | 修改图重新 OCR；中文图记录与时间戳保持不变；搜索 goodbye 命中 1 张 |
| 删除中文测试图 | 完整授权下同步后删除该条索引，只剩英文图 |
| 点击英文结果预览、分享 | release 预览正常，系统分享面板显示图片缩略图；没有选择接收 App |
| 新增 80 张，已完成 4 条时强停（含原有 1 条） | 剩余 77 条 pending；重新打开后共 81 条 indexed；强停前完成项的时间戳不变 |
| 搜索 hello、连续滑动 Grid | 80 个结果，3 列缩略图正常展示，返回后界面可操作 |

这些操作的数据库快照、授权状态、截图与断言结果存于证据包 `release-qa/`。测试用照片在验收后删除。没有修改用户真实相册。

## OCR 的已知限制

中文测试图片第二句“摸鱼时间到了”识别正确，第一句“今天也要开心”本次被识别成“今天也妻开心”。这说明字面搜索受 OCR 漏字、错字影响；输入原图文字中的“要”可能找不到该图。没有语义纠错、云 OCR 或 LLM。大图解码最长边 2,560 像素；极长图缩小后的小字识别率可能下降。

未实测 Android 8–13 / 15+、SD 卡拔插、所有 OEM 省电策略、真实 20k / 50k 图片全量 OCR。源代码包含相应权限 / 旧版本 MediaStore 路径及存储卷处理，但不把代码覆盖当成真机验证结果。

## 证据与复现

`验证证据.zip` 包含最终 JVM JUnit XML、13 项 Android 测试 XML / logcat、5k / 10k 独立压力测试原始 XML / logcat、构建与 Lint 报告、APK 权限与签名验证、release 验收快照及截图。

源码 README 给出了编译和带 `photoScale=5000` / `10000` 的复现命令。建议在专用模拟器或测试设备运行；测试会创建大量测试图片并清理自己创建的文件。签名备份独立保存，源码和验证证据包均不含私钥或签名密码。

## 用户真机试用反馈（2026-09-29）

用户在 Redmi Note 14 Pro+ 上试用了本次 APK，并反馈“没什么问题”。这是用户报告的真机试用结果，不是工具自动化测试。Android / HyperOS 版本、具体测试覆盖、照片数量和性能数据未提供，因此不把模拟器压力测试的耗时当作这台手机的实测数据。
