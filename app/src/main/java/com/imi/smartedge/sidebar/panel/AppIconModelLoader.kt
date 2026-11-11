package com.imi.smartedge.sidebar.panel

import android.content.Context
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
 * Fetches the repository's processed Drawable for an app icon and hands it
 * to Glide. (It does not convert anything to a Bitmap — the processed
 * Drawable is passed through as-is.)
 */
class AppIconDataFetcher(private val context: Context, private val request: AppIconRequest) : DataFetcher<Drawable> {
    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Drawable>) {
        try {
            // Shared repository (see AppRepository.shared): a fresh one per
            // icon load rebuilt the picker cache and icon-pack state for
            // every single icon in the list.
            val repository = AppRepository.shared(context)
            val processedIcon = repository.getProcessedIcon(request.packageName, request.appearanceKey)
            
            if (processedIcon == null) {
                callback.onLoadFailed(Exception("Icon null for ${request.packageName}"))
                return
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
    companion object {
        // lastUpdateTime per package, cached so a cache-key build does not
        // cost a PackageManager binder call on every bind. Invalidated by
        // the service's package receiver when an app is installed,
        // updated or removed.
        private val versionTags = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /** Drop the cached version tag so the next load re-reads it. */
        fun invalidateVersionTag(packageName: String) {
            versionTags.remove(packageName)
        }
    }

    private fun versionTag(packageName: String): Long =
        versionTags.getOrPut(packageName) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        packageName,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0)
                    ).lastUpdateTime
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime
                }
            } catch (_: Exception) {
                0L
            }
        }

    override fun buildLoadData(model: AppIconRequest, width: Int, height: Int, options: Options): ModelLoader.LoadData<Drawable> {
        // Version-aware key: lastUpdateTime changes whenever the app is
        // updated, so Glide stops serving the pre-update icon from its
        // memory/disk cache after an update. (The repository's own
        // processed-icon cache is cleared on ACTION_PACKAGE_REPLACED by
        // the service's package receiver, so the fetch underneath the new
        // key also returns a fresh icon.)
        val uniqueKey = "pkg:${model.packageName}|state:${model.appearanceKey}|v:${versionTag(model.packageName)}"
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
