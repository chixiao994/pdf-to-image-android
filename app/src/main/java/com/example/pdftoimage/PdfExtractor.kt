package com.example.pdftoimage

import android.content.Context
import com.tom_roush.pdfbox.cos.COSArray
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

    private fun readFilterNames(cos: COSStream): List<String> {
        val names = mutableListOf<String>()
        val filterObj = cos.getDictionaryObject(COSName.FILTER)
        when (filterObj) {
            is COSName -> names.add(filterObj.name)
            is COSArray -> {
                for (i in 0 until filterObj.size()) {
                    val item = filterObj.getObject(i)
                    if (item is COSName) names.add(item.name)
                }
            }
        }
        return names
    }

    private fun findLargestImage(page: PDPage): PDImageXObject? {
        val resources = page.resources ?: return null
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
        return best
    }

    /** 判断页面主图能否无损直出 */
    fun detectRawFormat(page: PDPage): String? {
        return try {
            val target = findLargestImage(page) ?: return null
            val cos = target.cosObject as? COSStream ?: return null
            val filters = readFilterNames(cos)
            when {
                filters.contains("DCTDecode") -> "jpg"
                filters.contains("JPXDecode") -> "jp2"
                filters.contains("CCITTFaxDecode") -> "tif"
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 直出原始字节流 */
    fun extractRawBytes(page: PDPage, out: OutputStream): Boolean {
        return try {
            val target = findLargestImage(page) ?: return false
            val cos = target.cosObject as? COSStream ?: return false
            val filters = readFilterNames(cos)
            if (!filters.contains("DCTDecode") &&
                !filters.contains("JPXDecode") &&
                !filters.contains("CCITTFaxDecode")
            ) {
                return false
            }
            cos.createRawInputStream().use { input ->
                input.copyTo(out, bufferSize = 256 * 1024)
            }
            out.flush()
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 估算页面主图对应的原始 DPI。
     *
     * 原理：
     *   PDF 里页面尺寸用 point 表示，1 point = 1/72 英寸
     *   图像像素宽 / (页面宽 point / 72) = 图像每英寸像素数 = 原始 DPI
     *
     * 返回：
     *   - 若页面无主图或计算失败 → 返回 minDpi
     *   - 否则返回 clamp(原图 DPI, minDpi, maxDpi)
     *
     * 用途：让 PdfRenderer 以匹配原图的分辨率渲染，避免降采样导致的模糊。
     */
    fun estimateRequiredDpi(
        page: PDPage,
        minDpi: Int = 300,
        maxDpi: Int = 600
    ): Int {
        return try {
            val image = findLargestImage(page) ?: return minDpi
            val pageWpt = page.mediaBox.width
            val pageHpt = page.mediaBox.height
            if (pageWpt <= 0f || pageHpt <= 0f) return minDpi

            // 图像像素 / (页面点数 / 72) = 原始 DPI
            val dpiW = image.width.toFloat() / pageWpt * 72f
            val dpiH = image.height.toFloat() / pageHpt * 72f
            // 取两者较小值作为有效 DPI，避免非等比缩放
            val effective = minOf(dpiW, dpiH).toInt()

            // 加入 5% 余量取整，避免因浮点误差导致轻微降采样
            val withMargin = (effective * 1.05f).toInt()
            withMargin.coerceIn(minDpi, maxDpi)
        } catch (t: Throwable) {
            minDpi
        }
    }
}
