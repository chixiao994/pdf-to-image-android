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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfViewModel : ViewModel() {

    // ---------------- 状态 ----------------
    var pdfUri: Uri? by mutableStateOf(null)
        private set
    var outputDirUri: Uri? by mutableStateOf(null)
        private set
    var pdfName: String by mutableStateOf("")
        private set
    var pageCount: Int by mutableStateOf(0)
        private set
    var currentPage: Int by mutableStateOf(0)
        private set
    var currentBitmap: Bitmap? by mutableStateOf(null)
        private set
    var skippedPages: Set<Int> by mutableStateOf(emptySet())
        private set
    var isLoading: Boolean by mutableStateOf(false)
        private set
    var statusText: String by mutableStateOf("")
        private set
    var toastMsg: String? by mutableStateOf(null)

    private var pdfRenderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val exportedPages = mutableSetOf<Int>()

    // PdfRenderer 非线程安全，串行调度
    private val pdfDispatcher = Dispatchers.IO.limitedParallelism(1)

    companion object {
        const val PREVIEW_DPI = 150
        const val EXPORT_DPI = 300
        const val MAX_PIXELS = 8000
    }

    // ==================================================
    // 顶部「输入」：打开 PDF
    // ==================================================
    fun openPdf(context: Context, uri: Uri) {
        viewModelScope.launch(pdfDispatcher) {
            closeInternal()
            isLoading = true
            try {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: error("无法打开文件")
                pdfRenderer = PdfRenderer(pfd!!)
                pdfUri = uri
                pdfName = queryFileName(context, uri) ?: "document.pdf"
                pageCount = pdfRenderer!!.pageCount
                currentPage = 0
                skippedPages = emptySet()
                exportedPages.clear()
                renderPreview()
            } catch (e: Exception) {
                toastMsg = "打开失败：${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // ==================================================
    // 顶部「输出」：设置输出文件夹
    // ==================================================
    fun setOutputDir(uri: Uri) {
        outputDirUri = uri
    }

    // ==================================================
    // 顶部「保存」：整体导出（忽略跳过，导出所有页面）
    // ==================================================
    fun saveAllPages(context: Context) {
        val dirUri = outputDirUri ?: run { toastMsg = "请先选择输出文件夹"; return }
        val renderer = pdfRenderer ?: run { toastMsg = "请先打开 PDF"; return }

        viewModelScope.launch(pdfDispatcher) {
            isLoading = true
            var success = 0
            var fail = 0
            try {
                for (i in 0 until pageCount) {
                    statusText = "正在导出 ${i + 1}/$pageCount …"
                    try {
                        val bmp = renderPage(renderer, i, EXPORT_DPI)
                        withContext(Dispatchers.IO) { saveBitmap(context, dirUri, bmp, i) }
                        bmp.recycle()
                        exportedPages.add(i)
                        success++
                    } catch (e: Exception) {
                        fail++
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
    // 底部「上一张」：先导出当前页（受跳过影响），再翻页
    // ==================================================
    fun goPrev(context: Context) {
        if (currentPage <= 0) return
        viewModelScope.launch(pdfDispatcher) {
            exportCurrentIfNeeded(context)
            currentPage--
            renderPreview()
        }
    }

    // ==================================================
    // 底部「下一张」：先导出当前页（受跳过影响），再翻页
    // ==================================================
    fun goNext(context: Context) {
        if (currentPage >= pageCount - 1) return
        viewModelScope.launch(pdfDispatcher) {
            exportCurrentIfNeeded(context)
            currentPage++
            renderPreview()
        }
    }

    // ==================================================
    // 底部「跳过」：标记当前页，仅影响单张模式
    // ==================================================
    fun skipCurrent(context: Context) {
        skippedPages = skippedPages + currentPage
        if (currentPage < pageCount - 1) {
            viewModelScope.launch(pdfDispatcher) {
                currentPage++
                renderPreview()
            }
        }
    }

    // ==================================================
    // 内部：单张导出（受跳过影响）
    // ==================================================
    private suspend fun exportCurrentIfNeeded(context: Context) {
        val dirUri = outputDirUri ?: return
        val renderer = pdfRenderer ?: return
        val idx = currentPage

        if (idx in skippedPages) {
            toastMsg = "第 ${idx + 1} 页已跳过，未导出"
            return
        }
        if (idx in exportedPages) return

        try {
            val bmp = renderPage(renderer, idx, EXPORT_DPI)
            withContext(Dispatchers.IO) { saveBitmap(context, dirUri, bmp, idx) }
            bmp.recycle()
            exportedPages.add(idx)
            toastMsg = "已导出第 ${idx + 1} 页"
        } catch (e: Exception) {
            toastMsg = "第 ${idx + 1} 页导出失败：${e.message}"
        }
    }

    // ==================================================
    // 内部：渲染预览位图
    // ==================================================
    private fun renderPreview() {
        val renderer = pdfRenderer ?: return
        try {
            currentBitmap?.recycle()
            currentBitmap = renderPage(renderer, currentPage, PREVIEW_DPI)
        } catch (e: Exception) {
            toastMsg = "预览失败：${e.message}"
        }
    }

    // ==================================================
    // 核心：按目标 DPI 渲染一页
    // 像素 = point × (dpi/72)，PDF 默认 72 DPI
    // ==================================================
    private fun renderPage(renderer: PdfRenderer, index: Int, dpi: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val scale = dpi / 72f
            var w = (page.width * scale).toInt().coerceAtLeast(1)
            var h = (page.height * scale).toInt().coerceAtLeast(1)
            val maxSide = maxOf(w, h)
            if (maxSide > MAX_PIXELS) {
                val r = MAX_PIXELS.toFloat() / maxSide
                w = (w * r).toInt()
                h = (h * r).toInt()
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    // ==================================================
    // 内部：保存 Bitmap 为 PNG 到 SAF 目录
    // ==================================================
    private fun saveBitmap(context: Context, dirUri: Uri, bmp: Bitmap, pageIndex: Int) {
        val dir = DocumentFile.fromTreeUri(context, dirUri) ?: error("目录无效")
        val base = pdfName.substringBeforeLast('.', pdfName)
        val fileName = "${base}_page_${pageIndex + 1}.png"
        dir.findFile(fileName)?.delete()
        val file = dir.createFile("image/png", fileName) ?: error("创建文件失败")
        context.contentResolver.openOutputStream(file.uri)?.use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: error("写入失败")
    }

    private fun queryFileName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            } else null
        }

    private fun closeInternal() {
        pdfRenderer?.close(); pdfRenderer = null
        pfd?.close(); pfd = null
    }

    override fun onCleared() {
        super.onCleared()
        closeInternal()
        currentBitmap?.recycle()
        currentBitmap = null
    }
}
