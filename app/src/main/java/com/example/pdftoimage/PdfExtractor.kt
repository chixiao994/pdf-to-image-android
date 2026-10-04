package com.example.pdftoimage

import android.content.Context
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.RandomAccessBufferedFileInputStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.io.OutputStream

/** PDF 整体类型 */
enum class PdfKind {
    UNKNOWN,   // 判定失败，保守处理
    SCANNED,   // 整本扫描件，可无损提取原图
    VECTOR     // 矢量/文本为主，走渲染路径
}

object PdfExtractor {

    /**
     * 整体判定 PDF 类型：抽样前 N 页，检查是否为大尺寸扫描图
     * @param sampleSize     抽样页数（默认 8）
     * @param ratioThreshold 判定为扫描件的比例阈值（默认 0.75）
     */
    fun detectPdfKind(
        context: Context,
        pdfFile: File,
        sampleSize: Int = 8,
        ratioThreshold: Float = 0.75f
    ): PdfKind {
        var randomRead: RandomAccessBufferedFileInputStream? = null
        return try {
            randomRead = RandomAccessBufferedFileInputStream(pdfFile)
            val memSetting = MemoryUsageSetting
                .setupMixed(8L * 1024 * 1024)
                .setTempDir(context.cacheDir)

            PDDocument.load(randomRead, memSetting).use { doc ->
                val total = doc.numberOfPages
                if (total == 0) return PdfKind.UNKNOWN

                val indices = pickSampleIndices(total, sampleSize)
                var scannedCount = 0
                for (i in indices) {
                    val page = doc.getPage(i)
                    if (isFullPageScannedImage(page)) scannedCount++
                }
                val ratio = scannedCount.toFloat() / indices.size
                if (ratio >= ratioThreshold) PdfKind.SCANNED else PdfKind.VECTOR
            }
        } catch (t: Throwable) {
            PdfKind.UNKNOWN
        } finally {
            try { randomRead?.close() } catch (_: Throwable) {}
        }
    }

    /** 均匀抽样页码：含首页、末页 */
    private fun pickSampleIndices(total: Int, sampleSize: Int): List<Int> {
        if (total <= sampleSize) return (0 until total).toList()
        val step = (total - 1).toFloat() / (sampleSize - 1)
        return (0 until sampleSize).map { (it * step).toInt() }.distinct()
    }

    /**
     * 判断一页是否为“整页扫描图”：
     *   页面资源中存在大尺寸位图（宽高均 ≥ 页面点尺寸的 2 倍）
     */
    fun isFullPageScannedImage(page: PDPage): Boolean {
        return try {
            val resources = page.resources ?: return false
            val pageW = page.mediaBox.width
            val pageH = page.mediaBox.height
            for (name in resources.xObjectNames) {
                val obj = resources.getXObject(name)
                if (obj is PDImageXObject) {
                    if (obj.width >= pageW * 2 && obj.height >= pageH * 2) {
                        return true
                    }
                }
            }
            false
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 提取一页中最大的图像对象的原始字节流。
     * 仅处理 DCTDecode (JPEG)，其他编码返回 null（调用方应回退到渲染）。
     * @return 文件扩展名（"jpg"），失败返回 null
     */
    fun extractRawImage(page: PDPage, out: OutputStream): String? {
        return try {
            val resources = page.resources ?: return null

            // 1. 找出最大的图像对象（扫描页可能附带页眉小图标）
            var best: PDImageXObject? = null
            var bestArea = 0L
            for (name in resources.xObjectNames) {
                val obj = resources.getXObject(name)
                if (obj is PDImageXObject) {
                    val area = obj.width.toLong() * obj.height.toLong()
                    if (area > bestArea) {
                        bestArea = area
                        best = obj
                    }
                }
            }
            val target = best ?: return null

            // 2. 检查过滤器：只有 DCTDecode(JPEG) 才能无损直出
            val cos = target.cosObject
            val filter = cos.getCOSName(COSName.FILTER)
            if (COSName.DCT_DECODE == filter) {
                cos.createRawInputStream().use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
                return "jpg"
            }
            null
        } catch (t: Throwable) {
            null
        }
    }
}
