package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.content.ContextCompat

/**
 * Tool icons for the sidebar Tools folder and bottom tools section.
 *
 * Loads vector drawables from `R.drawable.ic_tool_*`.
 */
object ToolIconHelper {

    private val cache = LruCache<String, Drawable>(32)

    private val DRAWABLE_BY_TOOL = mapOf(
        "smartedge.tool.screenshot" to R.drawable.ic_tool_screenshot,
        "smartedge.tool.blackscreen" to R.drawable.ic_tool_black_screen,
        "smartedge.tool.lockscreen" to R.drawable.ic_tool_lock_screen,
        "smartedge.tool.volume_up" to R.drawable.ic_tool_volume,
        "smartedge.tool.brightness_up" to R.drawable.ic_tool_brightness,
        "smartedge.tool.content_picker" to R.drawable.ic_tool_content_picker,
        "smartedge.shortcut.reboot" to R.drawable.ic_tool_power_menu
    )

    private val DRAWABLE_BY_DASH = mapOf(
        "screenshot" to R.drawable.ic_tool_screenshot,
        "black_screen" to R.drawable.ic_tool_black_screen,
        "lock_screen" to R.drawable.ic_tool_lock_screen,
        "volume" to R.drawable.ic_tool_volume,
        "brightness" to R.drawable.ic_tool_brightness,
        "power" to R.drawable.ic_tool_power_menu
    )

    fun forToolId(context: Context, toolId: String, sizeDp: Int = 24): Drawable? {
        if (toolId == "smartedge.tool.tools") {
            return ContextCompat.getDrawable(context, R.drawable.ic_section_tools)?.mutate()
        }
        val key = "t:$toolId"
        cache.get(key)?.let { return copy(it) }
        val d = DRAWABLE_BY_TOOL[toolId]?.let { ContextCompat.getDrawable(context, it)?.mutate() } ?: return null
        cache.put(key, d)
        return copy(d)
    }

    fun forDashboard(context: Context, key: String, sizeDp: Int = 22): Drawable? {
        val cacheKey = "d:$key"
        cache.get(cacheKey)?.let { return copy(it) }
        val d = DRAWABLE_BY_DASH[key]?.let { ContextCompat.getDrawable(context, it)?.mutate() } ?: return null
        cache.put(cacheKey, d)
        return copy(d)
    }

    private fun copy(d: Drawable): Drawable =
        d.constantState?.newDrawable()?.mutate() ?: d.mutate()
}
