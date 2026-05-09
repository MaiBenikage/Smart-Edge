package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey

/**
 * Custom model for Glide to load an app icon.
 * The 'appearanceKey' ensures that ANY change to shapes, themes, or packs
 * will result in a fresh icon load, bypassing stale caches.
 */
data class AppIconRequest(val packageName: String, val appearanceKey: String)

/**
 * Fetches the actual Drawable and converts it to a static Bitmap for maximum stability.
 */
class AppIconDataFetcher(private val context: Context, private val request: AppIconRequest) : DataFetcher<Drawable> {
    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Drawable>) {
        try {
            val repository = AppRepository(context)
            val icon = repository.loadIconForAppSync(request.packageName)
            
            if (icon == null) {
                callback.onLoadFailed(Exception("Icon null for ${request.packageName}"))
                return
            }

            // --- THE NUCLEAR OPTION: Convert to Static Bitmap ---
            // This removes all adaptive logic, masking, and state conflicts.
            val processedIcon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && icon is AdaptiveIconDrawable) {
                try {
                    val density = context.resources.displayMetrics.density
                    // Double the size for 2x super-sampling (ensures perfectly smooth edges)
                    val size = (144 * density).toInt().coerceAtLeast(256)
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    
                    // To draw 108dp unclipped layers into a 72dp bitmap:
                    // The layers must be 1.5x larger than the bitmap.
                    val layerSize = (size * 1.5f).toInt()
                    val offset = (size - layerSize) / 2
                    val layerBounds = Rect(offset, offset, offset + layerSize, offset + layerSize)
                    
                    icon.background?.mutate()?.let {
                        it.bounds = layerBounds
                        it.draw(canvas)
                    }
                    icon.foreground?.mutate()?.let {
                        it.bounds = layerBounds
                        it.draw(canvas)
                    }
                    
                    BitmapDrawable(context.resources, bitmap)
                } catch (e: Exception) {
                    Log.e("AppIconDataFetcher", "Bitmap conversion failed", e)
                    icon.mutate() 
                }
            } else {
                icon.mutate()
            }

            callback.onDataReady(processedIcon)
        } catch (e: Exception) {
            Log.e("AppIconDataFetcher", "Load failed", e)
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {}
    override fun cancel() {}
    override fun getDataClass(): Class<Drawable> = Drawable::class.java
    override fun getDataSource(): DataSource = DataSource.LOCAL
}

/**
 * Loader that links the AppIconRequest model to the AppIconDataFetcher.
 */
class AppIconModelLoader(private val context: Context) : ModelLoader<AppIconRequest, Drawable> {
    override fun buildLoadData(model: AppIconRequest, width: Int, height: Int, options: Options): ModelLoader.LoadData<Drawable> {
        val uniqueKey = "pkg:${model.packageName}|state:${model.appearanceKey}"
        return ModelLoader.LoadData(ObjectKey(uniqueKey), AppIconDataFetcher(context, model))
    }

    override fun handles(model: AppIconRequest): Boolean = true

    class Factory(private val context: Context) : ModelLoaderFactory<AppIconRequest, Drawable> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<AppIconRequest, Drawable> {
            return AppIconModelLoader(context)
        }
        override fun teardown() {}
    }
}
