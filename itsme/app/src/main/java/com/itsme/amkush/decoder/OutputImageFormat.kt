package com.itsme.amkush.decoder

enum class OutputImageFormat(val friendlyName: String) {
    I420("I420"),
    NV21("NV21"),
    JPEG("JPEG");

    override fun toString() = friendlyName

    companion object {
        fun fromFormat(format: Int): OutputImageFormat {
            return when (format) {
                android.graphics.ImageFormat.NV21 -> NV21
                android.graphics.ImageFormat.YUV_420_888 -> I420
                android.graphics.ImageFormat.JPEG -> JPEG
                else -> NV21
            }
        }
    }
}