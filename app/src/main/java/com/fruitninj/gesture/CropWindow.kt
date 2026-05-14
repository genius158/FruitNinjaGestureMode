package com.fruitninj.gesture

data class CropWindow(
    var centerXNorm: Float = 0.5f,
    var centerYNorm: Float = 0.5f,
    var widthNorm: Float = 0.5f,
    var heightNorm: Float = 0.5f,
) {
    companion object {
        val DEFAULT = CropWindow(
            centerXNorm = 0.5f,
            centerYNorm = 0.5f,
            widthNorm = 0.5f,
            heightNorm = 0.5f,
        )

        val FULL_FRAME = CropWindow(
            centerXNorm = 0.5f,
            centerYNorm = 0.5f,
            widthNorm = 1f,
            heightNorm = 1f,
        )
    }

    fun set(expanded: CropWindow) {
        centerXNorm = expanded.centerXNorm
        centerYNorm = expanded.centerYNorm
        widthNorm = expanded.widthNorm
        heightNorm = expanded.heightNorm
    }

}

