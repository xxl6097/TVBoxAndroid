package com.hong.tvbox.core

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码生成器 — 用 ZXing core 在纯代码层画 Bitmap，不依赖外部扫码 SDK。
 *
 * TV 端把配对地址编成二维码，手机扫一扫就能打开填写页，
 * 比在电视上用遥控器逐字输入 URL/账号密码方便太多。
 */
object QRCodeGenerator {

    /**
     * 生成二维码 Bitmap。
     *
     * @param content 编码内容（URL 或纯文本）
     * @param sizePx 输出尺寸（px），默认 600×600
     * @param margin 留白边距（模块数），默认 1
     * @return Bitmap，黑底白码（适配深色主题）
     */
    fun generate(content: String, sizePx: Int = 600, margin: Int = 1): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                // 深色主题：黑底白码
                pixels[y * w + x] = if (matrix.get(x, y)) Color.WHITE else Color.BLACK
            }
        }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)

        // 如果目标尺寸和矩阵不一致，缩放到目标
        return if (w != sizePx || h != sizePx) {
            Bitmap.createScaledBitmap(bmp, sizePx, sizePx, true)
        } else {
            bmp
        }
    }
}
