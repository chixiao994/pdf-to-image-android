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

        const val EXPORT_DPI = 300
        const val EXPORT_MAX_BITMAP_BYTES = 120L * 1024 * 1024

        // ---------- 内存管理参数 ----------

        // 固定强制重开间隔：每处理这么多页必定重开一次 PDDocument
        // 用户要求 100~200 页范围，取 150 作为默认值
        // 如果觉得太慢，调到 200；如果还闪退，调到 100
        const val FORCE_RESTART_PAGES = 150

        // 内存检查间隔：每处理这么多页检查一次堆使用情况
        // 必须是 FORCE_RESTART_PAGES 的因子，否则强制重开点可能被跳过
        const val MEMORY_CHECK_INTERVAL = 30

        // 堆使用率兜底阈值：超过这个比例也提前重开
        const val MEMORY_RESTART_THRESHOLD = 0.6

        // PDFBox 主内存缓存上限（超出部分自动 spill 到临时文件）
        const val PDFBOX_CACHE_BYTES = 4L * 1024 * 1024
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
                // 1. 复制到 cacheDir
                val file: File = withContext(Dispatchers.IO) {
                    val f = File(appContext.cacheDir, "current.pdf")
                    try { if (f.exists()) f.delete() } catch (_: Throwable) {}
                    appContext.contentResolver.openInputStream(uri)?.use { ins ->
                        f.outputStream().use { outs -> ins.copyTo(outs, 64 * 1024) }
                    } ?: throw IllegalStateException("无法读取文件")
                    f
                }
                cachedPdfFile = file

                // 2. 预览首页
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

                // 3. 后台判定类型
                isDetectingKind = true
                launch(Dispatchers.IO) {
                    try {
                        val kind = PdfExtractor.detectPdfKind(appContext, file)
                        pdfKind = kind
                        when (kind) {
                            PdfKind.SCANNED -> toastMsg = "已识别为扫描件，将无损提取原图"
                            PdfKind.VECTOR -> toastMsg = "已识别为矢量文档，将 300 DPI 渲染"
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
    // 顶部「保存」：按 PDF 类型分流
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
    // 路径 A：扫描件 → 提取原始图像
    //   每 FORCE_RESTART_PAGES 页强制重开 PDDocument
    //   每 MEMORY_CHECK_INTERVAL 页检查内存，超阈值也重开
    // ==================================================
    private suspend fun exportByExtraction(
        context: Context, pdfFile: File, dirUri: Uri, nameSnapshot: String
    ) {
        val dir = DocumentFile.fromTreeUri(context, dirUri) ?: error("目录无效")
        if (!dir.canWrite()) error("输出目录无写入权限")
        val base = nameSnapshot.substringBeforeLast('.', nameSnapshot).ifBlank { "pdf" }

        var fallbackPfd: ParcelFileDescriptor? = null
        var fallbackRenderer: PdfRenderer? = null
        var currentRr: RandomAccessBufferedFileInputStream? = null
        var currentDoc: PDDocument? = null

        var success = 0
        var fail = 0
        var restartCount = 0

        try {
            // ---------- 1. 快速获取总页数 ----------
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
            if (totalPages == 0) {
                toastMsg = "PDF 无页面"
                return
            }

            // ---------- 2. 打开文档 ----------
            withContext(Dispatchers.IO) {
                currentRr = RandomAccessBufferedFileInputStream(pdfFile)
                currentDoc = PDDocument.load(
                    currentRr!!,
                    MemoryUsageSetting.setupMixed(PDFBOX_CACHE_BYTES)
                )
            }

            // ---------- 3. 逐页处理 ----------
            withContext(Dispatchers.IO) {
                var i = 0
                while (i < totalPages) {
                    try {
                        val page = currentDoc!!.getPage(i)
                        saveProgressText = "提取 ${i + 1}/$totalPages …"
                        saveProgressValue = (i + 1).toFloat() / totalPages

                        val jpgName = "${base}_page_${i + 1}.jpg"
                        val pngName = "${base}_page_${i + 1}.png"

                        try { dir.findFile(jpgName)?.delete() } catch (_: Throwable) {}
                        try { dir.findFile(pngName)?.delete() } catch (_: Throwable) {}

                        // 尝试直接提取 JPEG
                        var extracted = false
                        val jpgFile = dir.createFile("image/jpeg", jpgName)
                        if (jpgFile != null) {
                            context.contentResolver
                                .openOutputStream(jpgFile.uri, "w")
                                ?.use { os ->
                                    BufferedOutputStream(os, 64 * 1024).use { bout ->
                                        val ext = PdfExtractor.extractRawImage(page, bout)
                                        if (ext != null) extracted = true
                                    }
                                }
                        }

                        if (extracted) {
                            success++
                        } else {
                            // 回退：删除占位 JPG，改渲染为 PNG
                            try { jpgFile?.delete() } catch (_: Throwable) {}
                            if (fallbackRenderer == null) {
                                fallbackPfd = ParcelFileDescriptor.open(
                                    pdfFile, ParcelFileDescriptor.MODE_READ_ONLY
                                )
                                fallbackRenderer = PdfRenderer(fallbackPfd!!)
                            }
                            val bmp = renderExportBitmap(fallbackRenderer!!, i)
                            try {
                                saveBitmapAsPng(context, dir, bmp, pngName)
                                success++
                            } finally {
                                try { bmp.recycle() } catch (_: Throwable) {}
                            }
                        }
                    } catch (t: Throwable) {
                        fail++
                    }

                    i++

                    // ---------- 内存管理：固定间隔 + 内存兜底 ----------
                    if (i < totalPages && i % MEMORY_CHECK_INTERVAL == 0) {
                        val hitForceInterval = (i % FORCE_RESTART_PAGES == 0)
                        val memoryPressure = shouldRestartByMemory()
                        if (hitForceInterval || memoryPressure) {
                            val reason = if (hitForceInterval) "定期清理" else "内存偏高"
                            saveProgressText = "释放缓存中（$reason）…$i/$totalPages"
                            try { currentDoc?.close() } catch (_: Throwable) {}
                            try { currentRr?.close() } catch (_: Throwable) {}
                            currentDoc = null
                            currentRr = null
                            System.gc()

                            currentRr = RandomAccessBufferedFileInputStream(pdfFile)
                            currentDoc = PDDocument.load(
                                currentRr!!,
                                MemoryUsageSetting.setupMixed(PDFBOX_CACHE_BYTES)
                            )
                            restartCount++
                        }
                    }
                }
            }

            toastMsg = buildString {
                append("提取完成：成功 $success 张")
                if (fail > 0) append("，失败 $fail 张")
                if (restartCount > 0) append("（内存重开 $restartCount 次）")
            }
        } finally {
            try { currentDoc?.close() } catch (_: Throwable) {}
            try { currentRr?.close() } catch (_: Throwable) {}
            try { fallbackRenderer?.close() } catch (_: Throwable) {}
            try { fallbackPfd?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * 判断堆使用是否超过兜底阈值
     */
    private fun shouldRestartByMemory(): Boolean {
        return try {
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            val max = rt.maxMemory()
            used > (max * MEMORY_RESTART_THRESHOLD).toLong()
        } catch (_: Throwable) {
            false
        }
    }

    // ==================================================
    // 路径 B：矢量/文本/未知 → 300 DPI 渲染为 PNG
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
                        val bmp = renderExportBitmap(renderer, i)
                        try {
                            val pngName = buildPngName(nameSnapshot, i)
                            try { dir.findFile(pngName)?.delete() } catch (_: Throwable) {}
                            saveBitmapAsPng(context, dir, bmp, pngName)
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
    // 底部「上一页/下一页/跳过」：只更新预览
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

    // ==================================================
    // 预览渲染
    // ==================================================
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

    // ==================================================
    // 导出渲染
    // ==================================================
    private fun renderExportBitmap(renderer: PdfRenderer, index: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val scale = EXPORT_DPI / 72f
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

    // ==================================================
    // 保存 Bitmap 为 PNG
    // ==================================================
    private fun saveBitmapAsPng(
        context: Context, dir: DocumentFile, bmp: Bitmap, fileName: String
    ) {
        val file = dir.createFile("image/png", fileName) ?: error("创建文件失败")
        context.contentResolver.openOutputStream(file.uri, "w")?.use { os ->
            BufferedOutputStream(os, 64 * 1024).use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    error("PNG 写入失败")
                }
                out.flush()
            }
        } ?: error("无法打开输出流")
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
