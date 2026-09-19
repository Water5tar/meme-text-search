package com.memeocr.core

/**
 * 相册（bucket）纯数据模型。
 */
data class Album(
    /** MediaStore Images.BUCKET_ID。 */
    val bucketId: String,
    /** 相册名。 */
    val name: String,
    /** 该相册内图片数量（查询时统计）。 */
    val imageCount: Int,
)
