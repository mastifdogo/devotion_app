package com.mastifdogo.devotion.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mastifdogo.devotion.logic.ServerWallpaper
import com.mastifdogo.devotion.logic.WallpaperLogic
import java.io.File
import java.io.IOException

/**
 * The one wallpaper kept on the phone, the app background: filesDir/wallpaper/current.<ext>.
 * Browsing thumbnails and previews are never written here.
 */
class WallpaperStore(filesDir: File) {
    private val dir = File(filesDir, "wallpaper")

    /** The current background file, or null. */
    fun currentFile(): File? =
        dir.listFiles()?.firstOrNull { it.isFile && WallpaperLogic.isCurrentFileName(it.name) }

    /**
     * Downloads [wallpaper] from the server via a .part file, checks its SHA-256 against the list
     * and that it decodes, then makes it the current background (replacing the old one). Blocking.
     */
    fun replace(api: ApiClient, baseUrl: String, wallpaper: ServerWallpaper): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the wallpaper folder.")
        val part = File(dir, "download.part")
        try {
            val result = api.downloadWallpaper(baseUrl, wallpaper.id, part)
            if (!WallpaperLogic.shaMatches(wallpaper.sha256, result.sha256)) {
                throw ApiException("The downloaded wallpaper failed its SHA-256 check; please try again.")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(part.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("The downloaded file is not a picture.")
            dir.listFiles()?.forEach { if (WallpaperLogic.isCurrentFileName(it.name)) it.delete() }
            val dest = File(dir, WallpaperLogic.currentFileName(result.contentType))
            if (!part.renameTo(dest)) throw IOException("Could not save the wallpaper.")
            return dest
        } finally {
            part.delete()
        }
    }

    /** Decodes [file] so that its longer side is at most [maxLongSide] pixels; null if it cannot be read. */
    fun decode(file: File, maxLongSide: Int = MAX_LONG_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = WallpaperLogic.sampleSize(bounds.outWidth, bounds.outHeight, maxLongSide)
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    fun remove() {
        dir.deleteRecursively()
    }

    companion object {
        const val MAX_LONG_SIDE = 1440
    }
}
