package com.example.pdftoimage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.RandomAccessBufferedFileInputStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

class PdfViewModel : ViewModel() {

    // ==================== 状态 ====================
    var pdfUri: Uri? by mutableStateOf(null); private set
    var outputDirUri: Uri? by mutableStateOf(null); private set
    var pdfName: String by mutableStateOf(""); private set
    var pageCount: Int by mutableStateOf(0); private set
    var currentPage: Int by mutableStateOf(0); private set
    var currentBitmap: Bitmap? by mutableStateOf(null); private set
    var isPreviewLoading: Boolean by mutableStateOf(false); private set

    var pdfKind: PdfKind by mutableStateOf(PdfKind.UNKNOWN); private set
    var isDetectingKind: Boolean by mutableStateOf(false); private set

    var isSaving: Boolean by mutableStateOf(false); private set
    var saveProgressText: String by mutableStateOf(""); private set
    var saveProgressValue: Float by mutableStateOf(0f); private set

    var toastMsg: String? by mutableStateOf(null)

    // ==================== 私有资源 ====================
    private var previewRenderer: PdfRenderer? = null
    private var previewPfd: ParcelFileDescriptor? = null
    private var cachedPdfFile: File? = null

    private val previewExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PreviewRenderThread")
    }
    private val previewDispatcher: CoroutineDispatcher = previewExecutor.asCoroutineDispatcher()

    companion object {
        const val PREVIEW_DPI = 100
        const val PREVIEW_MAX_SIDE = 1600

        // 渲染导出的 DPI 范围：
        //   minDpi = 300（矢量 PDF 最低质量保证）
        //   maxDpi = 600（扫描件最高质量匹配）
        const val EXPORT_MIN_DPI = 300
        const val EXPORT_MAX_DPI = 600

        // 单张导出位图内存上限：256MB
        // 600 DPI A4（4960×7016 ≈ 35M 像素）= 140MB，留出余量
        const val EXPORT_MAX_BITMAP_BYTES = 256L * 1024 * 1024

        const val FORCE_RESTART_PAGES = 150
        const val MEMORY_CHECK_INTERVAL = 30
        const val MEMORY_RESTART_THRESHOLD = 0.6

        const val PDFBOX_CACHE_BYTES = 64L * 1024 * 1024
    }

    // ==================================================
    // 顶部「输入」：打开 PDF
    // ==================================================
    fun openPdf(context: Context, uri: Uri) {
        if (isPreviewLoading) return
        if (isSaving) { toastMsg = "正在导出，请稍候"; return }
        val appContext = context.applicationContext

        viewModelScope.launch {
            isPreviewLoading = true
            pdfKind = PdfKind.UNKNOWN
            isDetectingKind = false
            try {
                val file: File = withContext(Dispatchers.IO) {
                    val f = File(appContext.cacheDir, "current.pdf")
                    try { if (f.exists()) f.delete() } catch (_: Throwable) {}
                    appContext.contentResolver.openInputStream(uri)?.use { ins ->
                        f.outputStream().use { outs -> ins.copyTo(outs, 256 * 1024) }
                    } ?: throw IllegalStateException("无法读取文件")
                    f
                }
                cachedPdfFile = file

                withContext(previewDispatcher) {
                    closePreviewInternal()
                    try {
                        val pfd = ParcelFileDescriptor.open(
                            file, ParcelFileDescriptor.MODE_READ_ONLY
                        )
                        val renderer = try {
                            PdfRenderer(pfd)
                        } catch (t: Throwable) {
                            pfd.close(); throw t
                        }
                        previewPfd = pfd
                        previewRenderer = renderer
                        pdfUri = uri
                        pdfName = queryFileName(appContext, uri) ?: "document.pdf"
                        pageCount = renderer.pageCount
                        currentPage = 0
                        currentBitmap = renderPreviewBitmap(0)
                    } catch (t: Throwable) {
                        toastMsg = "打开失败：${t.message}"
                    }
                }

                isDetectingKind = true
                launch(Dispatchers.IO) {
                    try {
                        val kind = PdfExtractor.detectPdfKind(appContext, file)
                        pdfKind = kind
                        when (kind) {
                            PdfKind.SCANNED -> toastMsg = "已识别为扫描件，将无损提取原图"
                            PdfKind.VECTOR -> toastMsg = "已识别为矢量文档，将 300~600 DPI 渲染"
                            PdfKind.UNKNOWN -> toastMsg = "类型未知，将按渲染方式导出"
                        }
                    } catch (_: Throwable) {
                        pdfKind = PdfKind.UNKNOWN
                    } finally {
                        isDetectingKind = false
                    }
                }
            } catch (t: Throwable) {
                toastMsg = "打开失败：${t.message}"
            } finally {
                isPreviewLoading = false
            }
        }
    }

    fun setOutputDir(uri: Uri) { outputDirUri = uri }

    // ==================================================
    // 顶部「保存」
    // ==================================================
    fun saveAllPages(context: Context) {
        val file = cachedPdfFile ?: run { toastMsg = "请先打开 PDF"; return }
        val dirUri = outputDirUri ?: run { toastMsg = "请先选择输出文件夹"; return }
        if (isSaving) return

        val appContext = context.applicationContext
        val kind = pdfKind
        val nameSnapshot = pdfName

        isSaving = true
        saveProgressText = "准备中…"
        saveProgressValue = 0f

        viewModelScope.launch {
            try {
                when (kind) {
                    PdfKind.SCANNED -> exportByExtraction(
                        appContext, file, dirUri, nameSnapshot
                    )
                    else -> exportByRendering(
                        appContext, file, dirUri, nameSnapshot
                    )
                }
            } catch (t: Throwable) {
                toastMsg = "导出失败：${t.message}"
            } finally {
                isSaving = false
                saveProgressText = ""
                saveProgressValue = 0f
            }
        }
    }

    // ==================================================
    // 路径 A：扫描件 → 无损提取原图；不可提取的页用「匹配原图 DPI」渲染
    // ==================================================
    private suspend fun exportByExtraction(
        context: Context, pdfFile: File, dirUri: Uri, nameSnapshot: String
    ) {
        val dir = DocumentFile.fromTreeUri(context, dirUri) ?: error("目录无效")
        if (!dir.canWrite()) error("输出目录无写入权限")
        val base = nameSnapshot.substringBeforeLast('.', nameSnapshot).ifBlank { "pdf" }

        // 清理旧文件
        withContext(Dispatchers.IO) {
            try {
                val prefix = "${base}_page_"
                dir.listFiles().forEach { f ->
                    val n = f.name ?: return@forEach
                    if (n.startsWith(prefix)) {
                        try { f.delete() } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        var fallbackPfd: ParcelFileDescriptor? = null
        var fallbackRenderer: PdfRenderer? = null
        var currentRr: RandomAccessBufferedFileInputStream? = null
        var currentDoc: PDDocument? = null

        var success = 0
        var fail = 0
        var directCount = 0
        var renderCount = 0
        var restartCount = 0
        var maxDpiUsed = 0

        try {
            val totalPages: Int = withContext(Dispatchers.IO) {
                var rr: RandomAccessBufferedFileInputStream? = null
                try {
                    rr = RandomAccessBufferedFileInputStream(pdfFile)
                    PDDocument.load(rr, MemoryUsageSetting.setupMixed(2L * 1024 * 1024))
                        .use { it.numberOfPages }
                } finally {
                    try { rr?.close() } catch (_: Throwable) {}
                }
            }
            if (totalPages == 0) { toastMsg = "PDF 无页面"; return }

            withContext(Dispatchers.IO) {
                currentRr = RandomAccessBufferedFileInputStream(pdfFile)
                currentDoc = PDDocument.load(
                    currentRr!!, MemoryUsageSetting.setupMixed(PDFBOX_CACHE_BYTES)
                )
            }

            withContext(Dispatchers.IO) {
                var i = 0
                while (i < totalPages) {
                    try {
                        val page = currentDoc!!.getPage(i)

                        val rawExt = PdfExtractor.detectRawFormat(page)
                        var handled = false

                        if (rawExt != null) {
                            val fileName = "${base}_page_${i + 1}.$rawExt"
                            val mime = when (rawExt) {
                                "jpg" -> "image/jpeg"
                                "jp2" -> "image/jp2"
                                "tif" -> "image/tiff"
                                else -> "application/octet-stream"
                            }
                            val file = dir.createFile(mime, fileName)
                            if (file != null) {
                                var ok = false
                                try {
                                    context.contentResolver
                                        .openOutputStream(file.uri, "w")
                                        ?.use { os ->
                                            BufferedOutputStream(os, 256 * 1024).use { bout ->
                                                ok = PdfExtractor.extractRawBytes(page, bout)
                                            }
                                        }
                                } catch (_: Throwable) { ok = false }
                                if (ok) {
                                    handled = true
                                    directCount++
                                    success++
                                } else {
                                    try { file.delete() } catch (_: Throwable) {}
                                }
                            }
                        }

                        // 回退渲染：按原图 DPI 匹配渲染，不降采样
                        if (!handled) {
                            if (fallbackRenderer == null) {
                                fallbackPfd = ParcelFileDescriptor.open(
                                    pdfFile, ParcelFileDescriptor.MODE_READ_ONLY
                                )
                                fallbackRenderer = PdfRenderer(fallbackPfd!!)
                            }
                            // 关键：为该页动态计算匹配 DPI
                            val matchedDpi = PdfExtractor.estimateRequiredDpi(
                                page,
                                minDpi = EXPORT_MIN_DPI,
                                maxDpi = EXPORT_MAX_DPI
                            )
                            if (matchedDpi > maxDpiUsed) maxDpiUsed = matchedDpi

                            val bmp = renderExportBitmap(fallbackRenderer!!, i, matchedDpi)
                            try {
                                val pngName = "${base}_page_${i + 1}.png"
                                val pngFile = dir.createFile("image/png", pngName)
                                    ?: error("创建 PNG 失败")
                                context.contentResolver
                                    .openOutputStream(pngFile.uri, "w")
                                    ?.use { os ->
                                        BufferedOutputStream(os, 256 * 1024).use { out ->
                                            if (!bmp.compress(
                                                    Bitmap.CompressFormat.PNG, 100, out
                                                )
                                            ) {
                                                error("PNG 写入失败")
                                            }
                                            out.flush()
                                        }
                                    }
                                renderCount++
                                success++
                            } finally {
                                try { bmp.recycle() } catch (_: Throwable) {}
                            }
                        }
                    } catch (t: Throwable) {
                        fail++
                    }

                    i++
                    saveProgressText =
                        "导出 $i/$totalPages（直出 $directCount · 渲染 $renderCount）"
                    saveProgressValue = i.toFloat() / totalPages

                    if (i < totalPages && i % MEMORY_CHECK_INTERVAL == 0) {
                        val hitForce = (i % FORCE_RESTART_PAGES == 0)
                        val memPressure = shouldRestartByMemory()
                        if (hitForce || memPressure) {
                            val reason = if (hitForce) "定期清理" else "内存偏高"
                            saveProgressText = "释放缓存（$reason）…$i/$totalPages"
                            try { currentDoc?.close() } catch (_: Throwable) {}
                            try { currentRr?.close() } catch (_: Throwable) {}
                            currentDoc = null; currentRr = null
                            System.gc()

                            currentRr = RandomAccessBufferedFileInputStream(pdfFile)
                            currentDoc = PDDocument.load(
                                currentRr!!, MemoryUsageSetting.setupMixed(PDFBOX_CACHE_BYTES)
                            )
                            restartCount++
                        }
                    }
                }
            }

            toastMsg = buildString {
                append("完成：直出 $directCount · 渲染 $renderCount")
                if (maxDpiUsed > 0) append("（最高 ${maxDpiUsed} DPI）")
                if (fail > 0) append(" · 失败 $fail")
                if (restartCount > 0) append("（重开 $restartCount 次）")
            }
        } finally {
            try { currentDoc?.close() } catch (_: Throwable) {}
            try { currentRr?.close() } catch (_: Throwable) {}
            try { fallbackRenderer?.close() } catch (_: Throwable) {}
            try { fallbackPfd?.close() } catch (_: Throwable) {}
        }
    }

    private fun shouldRestartByMemory(): Boolean {
        return try {
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            val max = rt.maxMemory()
            used > (max * MEMORY_RESTART_THRESHOLD).toLong()
        } catch (_: Throwable) { false }
    }

    // ==================================================
    // 路径 B：矢量/文本 → 渲染 PNG（固定高 DPI）
    // ==================================================
    private suspend fun exportByRendering(
        context: Context, pdfFile: File, dirUri: Uri, nameSnapshot: String
    ) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            var success = 0
            var fail = 0

            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val total = renderer.pageCount

            val dir = DocumentFile.fromTreeUri(context, dirUri) ?: error("目录无效")
            if (!dir.canWrite()) error("输出目录无写入权限")

            withContext(Dispatchers.IO) {
                for (i in 0 until total) {
                    saveProgressText = "渲染 ${i + 1}/$total …"
                    saveProgressValue = (i + 1).toFloat() / total
                    try {
                        val bmp = renderExportBitmap(renderer, i, EXPORT_MIN_DPI)
                        try {
                            val pngName = buildPngName(nameSnapshot, i)
                            val file = dir.createFile("image/png", pngName)
                                ?: error("创建 PNG 失败")
                            context.contentResolver
                                .openOutputStream(file.uri, "w")
                                ?.use { os ->
                                    BufferedOutputStream(os, 256 * 1024).use { out ->
                                        if (!bmp.compress(
                                                Bitmap.CompressFormat.PNG, 100, out
                                            )
                                        ) {
                                            error("PNG 写入失败")
                                        }
                                        out.flush()
                                    }
                                }
                            success++
                        } finally {
                            try { bmp.recycle() } catch (_: Throwable) {}
                        }
                    } catch (t: Throwable) {
                        fail++
                    }
                }
            }

            toastMsg = buildString {
                append("渲染完成：成功 $success 张")
                if (fail > 0) append("，失败 $fail 张")
            }
        } finally {
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }
    }

    // ==================================================
    // 底部按钮
    // ==================================================
    fun goPrev() {
        if (currentPage <= 0 || isPreviewLoading) return
        launchPreview(currentPage - 1)
    }

    fun goNext() {
        if (currentPage >= pageCount - 1 || isPreviewLoading) return
        launchPreview(currentPage + 1)
    }

    fun skipCurrent() {
        if (currentPage >= pageCount - 1 || isPreviewLoading) return
        launchPreview(currentPage + 1)
    }

    private fun launchPreview(target: Int) {
        viewModelScope.launch {
            isPreviewLoading = true
            try {
                withContext(previewDispatcher) {
                    currentPage = target
                    try {
                        currentBitmap = renderPreviewBitmap(target)
                    } catch (t: Throwable) {
                        toastMsg = "预览失败：${t.message}"
                    }
                }
            } finally {
                isPreviewLoading = false
            }
        }
    }

    private fun renderPreviewBitmap(index: Int): Bitmap {
        val renderer = previewRenderer ?: throw IllegalStateException("PDF 未打开")
        val page = renderer.openPage(index)
        try {
            val scale = PREVIEW_DPI / 72f
            var w = (page.width * scale).toInt().coerceAtLeast(1)
            var h = (page.height * scale).toInt().coerceAtLeast(1)
            val maxSide = maxOf(w, h)
            if (maxSide > PREVIEW_MAX_SIDE) {
                val r = PREVIEW_MAX_SIDE.toFloat() / maxSide
                w = (w * r).toInt().coerceAtLeast(1)
                h = (h * r).toInt().coerceAtLeast(1)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            try { page.close() } catch (_: Throwable) {}
        }
    }

    /**
     * 按指定 DPI 渲染一页为 Bitmap。
     * 若内存超限，会等比缩限（但至少保持 300 DPI 的等效分辨率）。
     */
    private fun renderExportBitmap(
        renderer: PdfRenderer, index: Int, targetDpi: Int
    ): Bitmap {
        val page = renderer.openPage(index)
        try {
            val scale = targetDpi / 72f
            var w = (page.width * scale).toInt().coerceAtLeast(1)
            var h = (page.height * scale).toInt().coerceAtLeast(1)

            val maxPixels = EXPORT_MAX_BITMAP_BYTES / 4L
            val pixels = w.toLong() * h.toLong()
            if (pixels > maxPixels) {
                val r = sqrt(maxPixels.toDouble() / pixels.toDouble())
                w = (w * r).toInt().coerceAtLeast(1)
                h = (h * r).toInt().coerceAtLeast(1)
            }

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            try { page.close() } catch (_: Throwable) {}
        }
    }

    private fun buildPngName(base: String, pageIndex: Int): String {
        val stem = base.substringBeforeLast('.', base).ifBlank { "pdf" }
        return "${stem}_page_${pageIndex + 1}.png"
    }

    private fun queryFileName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            } else null
        }
    } catch (_: Throwable) { null }

    private fun closePreviewInternal() {
        try { previewRenderer?.close() } catch (_: Throwable) {}
        previewRenderer = null
        try { previewPfd?.close() } catch (_: Throwable) {}
        previewPfd = null
    }

    override fun onCleared() {
        super.onCleared()
        closePreviewInternal()
        currentBitmap = null
        try { cachedPdfFile?.delete() } catch (_: Throwable) {}
        cachedPdfFile = null
        try { previewExecutor.shutdown() } catch (_: Throwable) {}
    }
}
