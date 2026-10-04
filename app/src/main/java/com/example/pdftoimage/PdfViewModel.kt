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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.sqrt

class PdfViewModel : ViewModel() {

    // ==================== 预览状态 ====================
    var pdfUri: Uri? by mutableStateOf(null); private set
    var outputDirUri: Uri? by mutableStateOf(null); private set
    var pdfName: String by mutableStateOf(""); private set
    var pageCount: Int by mutableStateOf(0); private set
    var currentPage: Int by mutableStateOf(0); private set
    var currentBitmap: Bitmap? by mutableStateOf(null); private set
    var isPreviewLoading: Boolean by mutableStateOf(false); private set

    // ==================== 导出状态（与预览独立） ====================
    var isSaving: Boolean by mutableStateOf(false); private set
    var saveProgressText: String by mutableStateOf(""); private set
    var saveProgressValue: Float by mutableStateOf(0f); private set

    var toastMsg: String? by mutableStateOf(null)

    // ==================== 预览渲染器（独立线程） ====================
    private var previewRenderer: PdfRenderer? = null
    private var previewPfd: ParcelFileDescriptor? = null
    private val previewExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PreviewRenderThread")
    }
    private val previewDispatcher: CoroutineDispatcher = previewExecutor.asCoroutineDispatcher()

    companion object {
        // 预览：低质量快速渲染，仅供屏幕显示
        const val PREVIEW_DPI = 100
        const val PREVIEW_MAX_SIDE = 1600

        // 导出：无损级，300 DPI + PNG
        const val EXPORT_DPI = 300
        // 单张导出位图内存上限 120MB，超限自动等比缩限（防 OOM）
        const val EXPORT_MAX_BITMAP_BYTES = 120L * 1024 * 1024
    }

    // ==================================================
    // 顶部「输入」：打开 PDF（加载页数 + 渲染首页预览）
    // ==================================================
    fun openPdf(context: Context, uri: Uri) {
        if (isPreviewLoading) return
        val appContext = context.applicationContext
        viewModelScope.launch {
            isPreviewLoading = true
            try {
                withContext(previewDispatcher) {
                    closePreviewInternal()
                    try {
                        val pfd = appContext.contentResolver
                            .openFileDescriptor(uri, "r")
                            ?: throw IllegalStateException("无法打开文件")
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
                    } catch (t: Throwable) {
                        toastMsg = "打开失败：${t.message}"
                        return@withContext
                    }
                    // 渲染首页预览
                    try {
                        val bmp = renderPreviewBitmap(0)
                        currentBitmap = bmp
                    } catch (t: Throwable) {
                        toastMsg = "预览失败：${t.message}"
                    }
                }
            } finally {
                isPreviewLoading = false
            }
        }
    }

    fun setOutputDir(uri: Uri) { outputDirUri = uri }

    // ==================================================
    // 顶部「保存」：整体后台导出全部页面为无损 PNG
    //   完全独立于预览：新建 fd + 新 renderer，在独立 IO 线程运行
    //   用户此期间可继续翻页浏览，互不阻塞
    // ==================================================
    fun saveAllPages(context: Context) {
        val uri = pdfUri ?: run { toastMsg = "请先打开 PDF"; return }
        val dirUri = outputDirUri ?: run { toastMsg = "请先选择输出文件夹"; return }
        if (isSaving) return

        val appContext = context.applicationContext
        val nameSnapshot = pdfName

        isSaving = true
        saveProgressText = "准备中…"
        saveProgressValue = 0f

        viewModelScope.launch {
            var success = 0
            var fail = 0
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                withContext(Dispatchers.IO) {
                    pfd = appContext.contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IllegalStateException("无法打开 PDF")
                    renderer = PdfRenderer(pfd!!)
                    val total = renderer!!.pageCount

                    for (i in 0 until total) {
                        saveProgressText = "正在导出 ${i + 1}/$total …"
                        saveProgressValue = (i + 1).toFloat() / total
                        try {
                            val bmp = renderExportBitmap(renderer!!, i)
                            try {
                                saveBitmap(appContext, dirUri, bmp, i, nameSnapshot)
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
                    append("导出完成：成功 $success 张")
                    if (fail > 0) append("，失败 $fail 张")
                }
            } catch (t: Throwable) {
                toastMsg = "导出失败：${t.message}"
            } finally {
                try { renderer?.close() } catch (_: Throwable) {}
                try { pfd?.close() } catch (_: Throwable) {}
                isSaving = false
                saveProgressText = ""
                saveProgressValue = 0f
            }
        }
    }

    // ==================================================
    // 底部「上一页」：仅更新预览，不触发导出
    // ==================================================
    fun goPrev() {
        if (currentPage <= 0 || isPreviewLoading) return
        launchPreview(currentPage - 1)
    }

    // ==================================================
    // 底部「下一页」：仅更新预览，不触发导出
    // ==================================================
    fun goNext() {
        if (currentPage >= pageCount - 1 || isPreviewLoading) return
        launchPreview(currentPage + 1)
    }

    // ==================================================
    // 底部「跳过」：预留（当前导出为整体导出，跳过不影响）
    //   如后续需要"单张导出模式"，可在此维护 skipped 集合
    // ==================================================
    fun skipCurrent() {
        if (currentPage >= pageCount - 1 || isPreviewLoading) return
        launchPreview(currentPage + 1)
    }

    // ==================================================
    // 内部：翻页只做预览渲染
    // ==================================================
    private fun launchPreview(target: Int) {
        viewModelScope.launch {
            isPreviewLoading = true
            try {
                withContext(previewDispatcher) {
                    currentPage = target
                    try {
                        val bmp = renderPreviewBitmap(target)
                        currentBitmap = bmp
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
    // 预览渲染：低质量快速（100 DPI + RGB_565）
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
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            try { page.close() } catch (_: Throwable) {}
        }
    }

    // ==================================================
    // 导出渲染：无损级（300 DPI + ARGB_8888）
    // ==================================================
    private fun renderExportBitmap(renderer: PdfRenderer, index: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val scale = EXPORT_DPI / 72f
            var w = (page.width * scale).toInt().coerceAtLeast(1)
            var h = (page.height * scale).toInt().coerceAtLeast(1)

            // 内存预算保护
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
    // 保存 PNG（无损压缩）到 SAF 目录
    // ==================================================
    private fun saveBitmap(
        context: Context,
        dirUri: Uri,
        bmp: Bitmap,
        pageIndex: Int,
        nameForFile: String
    ) {
        val dir = DocumentFile.fromTreeUri(context, dirUri) ?: error("目录无效")
        if (!dir.canWrite()) error("无写入权限，请重新选择输出文件夹")
        val base = nameForFile.substringBeforeLast('.', nameForFile).ifBlank { "pdf" }
        val fileName = "${base}_page_${pageIndex + 1}.png"
        try { dir.findFile(fileName)?.delete() } catch (_: Throwable) {}
        val file = dir.createFile("image/png", fileName) ?: error("创建文件失败")
        context.contentResolver.openOutputStream(file.uri, "w")?.use { out ->
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                error("PNG 写入失败")
            }
            out.flush()
        } ?: error("无法打开输出流")
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
        // 预览位图交由 GC，不显式 recycle，避免 Compose 绘制时崩溃
        currentBitmap = null
        try { previewExecutor.shutdown() } catch (_: Throwable) {}
    }
}
