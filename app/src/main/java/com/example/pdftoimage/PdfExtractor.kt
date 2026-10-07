package com.example.pdftoimage

import android.content.Context
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.RandomAccessBufferedFileInputStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.io.OutputStream

/** PDF 整体类型 */
enum class PdfKind {
    UNKNOWN,
    SCANNED,
    VECTOR
}

object PdfExtractor {

    /** 判定类型：抽样前 N 页 */
    fun detectPdfKind(
        context: Context,
        pdfFile: File,
        sampleSize: Int = 8,
        ratioThreshold: Float = 0.75f
    ): PdfKind {
        var randomRead: RandomAccessBufferedFileInputStream? = null
        return try {
            randomRead = RandomAccessBufferedFileInputStream(pdfFile)
            val memSetting = MemoryUsageSetting.setupMixed(8L * 1024 * 1024)
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

    private fun pickSampleIndices(total: Int, sampleSize: Int): List<Int> {
        if (total <= sampleSize) return (0 until total).toList()
        val step = (total - 1).toFloat() / (sampleSize - 1)
        return (0 until sampleSize).map { (it * step).toInt() }.distinct()
    }

    /** 判断一页是否为整页大尺寸扫描图 */
    fun isFullPageScannedImage(page: PDPage): Boolean {
        return try {
            val resources = page.resources ?: return false
            val pageW = page.mediaBox.width
            val pageH = page.mediaBox.height
            for (name in resources.xObjectNames) {
                val obj = try { resources.getXObject(name) } catch (_: Throwable) { null }
                if (obj is PDImageXObject) {
                    if (obj.width >= pageW * 2 && obj.height >= pageH * 2) return true
                }
            }
            false
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 提取页面上最大图像的原始字节流。
     * 仅处理 JPEG（DCTDecode），其他返回 null 由调用方回退到渲染。
     *
     * 改进点：
     *  - 用 PDImageXObject.suffix 判断格式（PDFBox 内部会综合 filter 数组）
     *  - cosObject 做安全 cast，避免类型异常
     */
    fun extractRawImage(page: PDPage, out: OutputStream): String? {
        return try {
            val resources = page.resources ?: return null

            // 找到页面里面积最大的图像（页眉小图标会被跳过）
            var best: PDImageXObject? = null
            var bestArea = 0L
            for (name in resources.xObjectNames) {
                val obj = try { resources.getXObject(name) } catch (_: Throwable) { null }
                if (obj is PDImageXObject) {
                    val area = obj.width.toLong() * obj.height.toLong()
                    if (area > bestArea) {
                        bestArea = area
                        best = obj
                    }
                }
            }
            val target = best ?: return null

            // 用 PDFBox 的 suffix 判断格式
            // suffix = "jpg"/"jpeg" 时说明底层就是 DCTDecode，可以直出原始字节
            val suffix = try { target.suffix } catch (_: Throwable) { null }
            if (suffix == "jpg" || suffix == "jpeg") {
                val cos = target.cosObject as? COSStream ?: return null
                cos.createRawInputStream().use { input ->
                    input.copyTo(out, bufferSize = 128 * 1024)
                }
                return "jpg"
            }
            null
        } catch (t: Throwable) {
            null
        }
    }
}
