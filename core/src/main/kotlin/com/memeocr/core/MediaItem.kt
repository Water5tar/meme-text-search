package com.memeocr.core

/**
 * 一张待识别图片的稳定描述。
 * 只持有元数据，不持有 Bitmap，便于纯逻辑测试与列表选择。
 */
data class MediaItem(
    /** 形如 content://media/external/images/media/<id> 的 URI 字符串。 */
    val uri: String,
    /** 文件大小（字节），用于版本判断。 */
    val size: Long,
    /** 上次修改时间（秒或毫秒，与查询时一致即可），用于版本判断。 */
    val lastModified: Long,
) {
    /** 缓存判断用的版本标识，只由 URI+大小+修改时间构成，不依赖文件名。 */
    val versionKey: String get() = "$uri|$size|$lastModified"
}
