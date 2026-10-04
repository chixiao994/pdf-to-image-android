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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var skippedPages: Set<Int> by mutableStateOf(emptySet()); private set
    var isLoading: Boolean by mutableStateOf(false); private set
    var statusText: String by mutableStateOf(""); private set
    var toastMsg: String? by mutableStateOf(null)

    private var pdfRenderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val exportedPages = mutableSetOf<Int>()

    // 单线程调度器 —— 保证 PdfRenderer 串行访问
    private val pdfExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PdfRenderThread")
    }
    private val pdfDispatcher: CoroutineDispatcher = pdfExecutor.asCoroutineDispatcher()

    companion object {
        const val PREVIEW_DPI = 150
        const val EXPORT_DPI = 300
        // 单张位图内存上限（80MB），超限等比缩限，防止 OOM
        const val MAX_BITMAP_BYTES = 80L * 1024 * 1024
    }

    // ==================================================
    // 顶部「输入」：打开 PDF
    // ==================================================
    fun openPdf(context: Context, uri: Uri) {
        if (isLoading) return
        viewModelScope.launch {
            isLoading = true
            try {
                withContext(pdfDispatcher) {
                    closeInternal()
                    try {
                        val newPfd = context.contentResolver.openFileDescriptor(uri, "r")
                            ?: throw IllegalStateException("无法打开文件")
                        val newRenderer = try {
                            PdfRenderer(newPfd)
                        } catch (t: Throwable) {
                            newPfd.close()
                            throw t
                        }
                        pfd = newPfd
                        pdfRenderer = newRenderer
                        pdfUri = uri
                        pdfName = queryFileName(context, uri) ?: "document.pdf"
                        pageCount = newRenderer.pageCount
                        currentPage = 0
                        skippedPages = emptySet()
                        exportedPages.clear()
                    } catch (t: Throwable) {
                        toastMsg = "打开失败：${t.message}"
                        return@withContext
                    }
                    // 渲染首页
                    try {
                        currentBitmap = renderPageInternal(0, PREVIEW_DPI)
                    } catch (t: Throwable) {
                        toastMsg = "预览失败：${t.message}"
                    }
                }
            } finally {
                isLoading = false
            }
        }
    }

    fun setOutputDir(uri: Uri) { outputDirUri = uri }

    // ==================================================
    // 顶部「保存」：整体导出（忽略跳过，全部页面都导出）
    // ==================================================
    fun saveAllPages(context: Context) {
        val dirUri = outputDirUri ?: run { toastMsg = "请先选择输出文件夹"; return }
        if (pdfRenderer == null) { toastMsg = "请先打开 PDF"; return }
        if (isLoading) return

        viewModelScope.launch {
            isLoading = true
            var success = 0
            var fail = 0
            try {
                withContext(pdfDispatcher) {
                    for (i in 0 until pageCount) {
                        statusText = "正在导出 ${i + 1}/$pageCount …"
                        try {
                            val bmp = renderPageInternal(i, EXPORT_DPI)
                            try {
                                saveBitmap(context, dirUri, bmp, i)
                                exportedPages.add(i)
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
                    append("整体导出完成：成功 $success 张")
                    if (fail > 0) append("，失败 $fail 张")
                }
            } finally {
                statusText = ""
                isLoading = false
            }
        }
    }

    // ==================================================
    // 底部「上一页」：先导出当前页（受跳过影响），再翻页
    // ==================================================
    fun goPrev(context: Context) {
        if (currentPage <= 0 || isLoading) return
        viewModelScope.launch {
            isLoading = true
            try {
                withContext(pdfDispatcher) {
                    exportCurrentInternal(context)
                    val newPage = currentPage - 1
                    currentPage = newPage
                    currentBitmap = renderPageInternal(newPage, PREVIEW_DPI)
                }
            } catch (t: Throwable) {
                toastMsg = "翻页失败：${t.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // ==================================================
    // 底部「下一页」：先导出当前页（受跳过影响），再翻页
    // ==================================================
    fun goNext(context: Context) {
        if (currentPage >= pageCount - 1 || isLoading) return
        viewModelScope.launch {
            isLoading = true
            try {
                withContext(pdfDispatcher) {
                    exportCurrentInternal(context)
                    val newPage = currentPage + 1
                    currentPage = newPage
                    currentBitmap = renderPageInternal(newPage, PREVIEW_DPI)
                }
            } catch (t: Throwable) {
                toastMsg = "翻页失败：${t.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // ==================================================
    // 底部「跳过」：标记当前页不参与单张导出，自动翻到下一页
    // ==================================================
    fun skipCurrent(context: Context) {
        if (isLoading) return
        skippedPages = skippedPages + currentPage
        if (currentPage < pageCount - 1) {
            viewModelScope.launch {
                isLoading = true
                try {
                    withContext(pdfDispatcher) {
                        val newPage = currentPage + 1
                        currentPage = newPage
                        currentBitmap = renderPageInternal(newPage, PREVIEW_DPI)
                    }
                } catch (t: Throwable) {
                    toastMsg = "翻页失败：${t.message}"
                } finally {
                    isLoading = false
                }
            }
        }
    }

    // ==================================================
    // 内部：单张导出（受「跳过」影响）
    // ==================================================
    private fun exportCurrentInternal(context: Context) {
        val dirUri = outputDirUri ?: return
        if (pdfRenderer == null) return
        val idx = currentPage

        if (idx in skippedPages) {
            toastMsg = "第 ${idx + 1} 页已跳过，未导出"
            return
        }
        if (idx in exportedPages) return

        try {
            val bmp = renderPageInternal(idx, EXPORT_DPI)
            try {
                saveBitmap(context, dirUri, bmp, idx)
                exportedPages.add(idx)
                toastMsg = "已导出第 ${idx + 1} 页"
            } finally {
                try { bmp.recycle() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            toastMsg = "第 ${idx + 1} 页导出失败：${t.message}"
        }
    }

    // ==================================================
    // 核心：渲染一页为 Bitmap（带内存预算保护）
    // 像素 = PDF point × (dpi / 72)
    // ==================================================
    private fun renderPageInternal(index: Int, dpi: Int): Bitmap {
        val renderer = pdfRenderer ?: throw IllegalStateException("PDF 未打开")
        if (index < 0 || index >= renderer.pageCount) {
            throw IllegalArgumentException("页码越界：$index")
        }
        val page = renderer.openPage(index)
        try {
            val scale = dpi / 72f
            var w = (page.width * scale).toInt().coerceAtLeast(1)
            var h = (page.height * scale).toInt().coerceAtLeast(1)

            // 内存预算保护：ARGB_8888 每像素 4 字节
            val maxPixels = MAX_BITMAP_BYTES / 4L
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
    // 保存 Bitmap 为 PNG 到 SAF 目录（PNG 为无损格式）
    // ==================================================
    private fun saveBitmap(context: Context, dirUri: Uri, bmp: Bitmap, pageIndex: Int) {
        val dir = DocumentFile.fromTreeUri(context, dirUri) ?: error("目录无效")
        if (!dir.canWrite()) error("无写入权限，请重新选择输出文件夹")
        val base = pdfName.substringBeforeLast('.', pdfName).ifBlank { "pdf" }
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

    private fun closeInternal() {
        try { pdfRenderer?.close() } catch (_: Throwable) {}
        pdfRenderer = null
        try { pfd?.close() } catch (_: Throwable) {}
        pfd = null
    }

    override fun onCleared() {
        super.onCleared()
        closeInternal()
        // 关键：不显式 recycle currentBitmap，交给 GC，避免 Compose 绘制时崩溃
        currentBitmap = null
        try { pdfExecutor.shutdown() } catch (_: Throwable) {}
    }
}
