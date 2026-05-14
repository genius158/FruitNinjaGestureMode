package com.fruitninj.gesture

import android.content.Context

/**
 * 持久化保存裁剪框与映射框，供下次进入页面恢复。
 */
object OverlayWindowStore {
    private const val PREF_NAME = "overlay_window_store"

    private const val KEY_CROP_CX = "crop_cx"
    private const val KEY_CROP_CY = "crop_cy"
    private const val KEY_CROP_W = "crop_w"
    private const val KEY_CROP_H = "crop_h"

    private const val KEY_MAPPING_CX = "mapping_cx"
    private const val KEY_MAPPING_CY = "mapping_cy"
    private const val KEY_MAPPING_W = "mapping_w"
    private const val KEY_MAPPING_H = "mapping_h"

    fun saveCropWindow(context: Context, cropWindow: CropWindow) {
        prefs(context).edit()
            .putFloat(KEY_CROP_CX, cropWindow.centerXNorm)
            .putFloat(KEY_CROP_CY, cropWindow.centerYNorm)
            .putFloat(KEY_CROP_W, cropWindow.widthNorm)
            .putFloat(KEY_CROP_H, cropWindow.heightNorm)
            .apply()
    }

    fun loadCropWindow(context: Context): CropWindow? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_CROP_CX)) return null
        return CropWindow(
            centerXNorm = prefs.getFloat(KEY_CROP_CX, CropWindow.DEFAULT.centerXNorm),
            centerYNorm = prefs.getFloat(KEY_CROP_CY, CropWindow.DEFAULT.centerYNorm),
            widthNorm = prefs.getFloat(KEY_CROP_W, CropWindow.DEFAULT.widthNorm),
            heightNorm = prefs.getFloat(KEY_CROP_H, CropWindow.DEFAULT.heightNorm),
        )
    }

    fun saveMappingWindow(context: Context, mappingWindow: MappingWindow) {
        prefs(context).edit()
            .putFloat(KEY_MAPPING_CX, mappingWindow.centerXNorm)
            .putFloat(KEY_MAPPING_CY, mappingWindow.centerYNorm)
            .putFloat(KEY_MAPPING_W, mappingWindow.widthNorm)
            .putFloat(KEY_MAPPING_H, mappingWindow.heightNorm)
            .apply()
    }

    fun loadMappingWindow(context: Context): MappingWindow? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_MAPPING_CX)) return null
        return MappingWindow(
            centerXNorm = prefs.getFloat(KEY_MAPPING_CX, 0.5f),
            centerYNorm = prefs.getFloat(KEY_MAPPING_CY, 0.5f),
            widthNorm = prefs.getFloat(KEY_MAPPING_W, 0.5f),
            heightNorm = prefs.getFloat(KEY_MAPPING_H, 0.5f),
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}

