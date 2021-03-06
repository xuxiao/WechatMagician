package com.gh0u1l5.wechatmagician.util

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat.JPEG
import com.gh0u1l5.wechatmagician.xposed.WechatHook
import de.robv.android.xposed.XposedHelpers.*
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

// ImageUtil is a helper object for processing thumbnails.
object ImageUtil {

    // blockTable records all the thumbnail files changed by ImageUtil
    // In WechatHook.hookImgStorage, the module hooks FileOutputStream
    // to prevent anyone from overwriting these files.
    var blockTable: Set<String> = setOf()

    // getPathFromImgId maps the given imgId to corresponding absolute path.
    private fun getPathFromImgId(imgId: String): String? {
        val storage = WechatHook.pkg.ImgStorageObject ?: return null
        val load = WechatHook.pkg.ImgStorageLoadMethod
        return callMethod(storage, load, imgId, "th_", "", false) as String
    }

    // replaceThumbDiskCache replaces the disk cache of a specific
    // thumbnail with the given bitmap.
    private fun replaceThumbDiskCache(path: String, bitmap: Bitmap, retry: Boolean = true) {
        val file = File(path)
        var out: FileOutputStream? = null

        try {
            // Write bitmap to disk cache
            out = FileOutputStream(file)
            bitmap.compress(JPEG, 100, out)
            out.flush()
        } catch (_: FileNotFoundException) {
            if (!retry) {
                return
            }
            // Create missing directories and try again
            file.parentFile.mkdirs()
            replaceThumbDiskCache(path, bitmap, false)
        } catch (_: Throwable) {
            // Ignore any other errors
        } finally {
            // Update block table and cleanup
            synchronized(blockTable) {
                blockTable += path
            }
            out?.close()
        }
    }

    // replaceThumbMemoryCache replaces the memory cache of a specific
    // thumbnail with the given bitmap.
    private fun replaceThumbMemoryCache(path: String, bitmap: Bitmap) {
        // Check if memory cache and image storage are established
        val storage = WechatHook.pkg.ImgStorageObject ?: return
        val cache = getObjectField(storage, WechatHook.pkg.ImgStorageCacheField)

        // Update memory cache
        callMethod(cache, WechatHook.pkg.CacheMapRemoveMethod, path)
        callMethod(cache, WechatHook.pkg.CacheMapPutMethod, "${path}hd", bitmap)

        // Notify storage update
        callMethod(storage, WechatHook.pkg.ImgStorageNotifyMethod)
    }

    // replaceThumbnail replaces the memory cache and disk cache of a
    // specific thumbnail with the given bitmap.
    fun replaceThumbnail(imgId: String, bitmap: Bitmap) {
        val path = getPathFromImgId(imgId) ?: return
        replaceThumbDiskCache("${path}hd", bitmap)
        replaceThumbMemoryCache(path, bitmap)
    }
}