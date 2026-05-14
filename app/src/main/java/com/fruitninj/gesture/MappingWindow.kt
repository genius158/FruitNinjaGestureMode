package com.fruitninj.gesture

/**
 * 映射框描述，坐标归一化到完整帧尺寸（fullImageWidth / fullImageHeight）。
 *
 * 与 [CropWindow] 结构对齐，但语义不同：
 * - [CropWindow] 描述送入模型识别的子区域。
 * - [MappingWindow] 描述手指坐标映射到屏幕的目标区域，通常在裁剪框内部。
 */
data class MappingWindow(
    var centerXNorm: Float=0.5F,
    var centerYNorm: Float=0.5F,
    var widthNorm: Float = 0.5F,
    var heightNorm: Float = 0.5F,
)

