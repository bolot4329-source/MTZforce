package com.bluequark.mtzforge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.bluequark.mtzforge.core.PngCodec
import com.bluequark.mtzforge.core.Raster
import java.io.ByteArrayOutputStream

object AndroidPng : PngCodec {
    override fun decode(bytes: ByteArray): Raster {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalArgumentException("Не удалось открыть картинку")
        val r = bmp.toRaster()
        bmp.recycle()
        return r
    }

    override fun encode(raster: Raster): ByteArray {
        val bmp = raster.toBitmap()
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return bos.toByteArray()
    }
}

fun Bitmap.toRaster(): Raster {
    val src = if (config != Bitmap.Config.ARGB_8888) copy(Bitmap.Config.ARGB_8888, false) else this
    val r = Raster(src.width, src.height)
    src.getPixels(r.pixels, 0, src.width, 0, 0, src.width, src.height)
    if (src !== this) src.recycle()
    return r
}

fun Raster.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
