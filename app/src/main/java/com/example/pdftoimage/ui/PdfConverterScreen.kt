package com.example.pdftoimage.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pdftoimage.PdfKind
import com.example.pdftoimage.PdfViewModel

@Composable
fun PdfConverterScreen(vm: PdfViewModel = viewModel()) {
    val context = LocalContext.current

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.openPdf(context, it) } }

    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Throwable) {}
            vm.setOutputDir(it)
            Toast.makeText(context, "输出目录已选择", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(vm.toastMsg) {
        vm.toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.toastMsg = null
        }
    }

    Scaffold(
        // ============================================================
        // 顶部栏：标题居中 + 输入/输出/保存 三按钮均分
        // ============================================================
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    // 标题居中
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "PDF无损转图像",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    // 三个文字按钮均分
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        TopBarButton(
                            text = "输入",
                            enabled = !vm.isPreviewLoading,
                            onClick = { pdfPicker.launch(arrayOf("application/pdf")) }
                        )
                        TopBarButton(
                            text = "输出",
                            enabled = vm.pdfUri != null && !vm.isPreviewLoading,
                            onClick = { dirPicker.launch(null) }
                        )
                        TopBarButton(
                            text = if (vm.isSaving) "保存中…" else "保存",
                            enabled = vm.pdfUri != null &&
                                vm.outputDirUri != null &&
                                !vm.isSaving &&
                                !vm.isDetectingKind,
                            onClick = { vm.saveAllPages(context) }
                        )
                    }
                    // 类型判定提示
                    if (vm.isDetectingKind) {
                        Text(
                            "正在识别 PDF 类型…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(
                                horizontal = 12.dp, vertical = 4.dp
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else if (vm.pdfUri != null && vm.pdfKind != PdfKind.UNKNOWN) {
                        val label = when (vm.pdfKind) {
                            PdfKind.SCANNED -> "扫描件 · 将无损提取原图"
                            PdfKind.VECTOR -> "矢量文档 · 将 300 DPI 渲染"
                            PdfKind.UNKNOWN -> ""
                        }
                        if (label.isNotEmpty()) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(
                                    horizontal = 12.dp, vertical = 4.dp
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    // 保存进度
                    if (vm.isSaving) {
                        Column(Modifier.fillMaxWidth()) {
                            LinearProgressIndicator(
                                progress = { vm.saveProgressValue },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                vm.saveProgressText,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(
                                    horizontal = 12.dp, vertical = 4.dp
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        },

        // ============================================================
        // 底部栏：上一页 / 跳过 / 下一页 三个按钮均分
        // ============================================================
        bottomBar = {
            if (vm.pdfUri != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .height(56.dp)
                    ) {
                        BottomBarButton(
                            text = "上一页",
                            enabled = vm.currentPage > 0 && !vm.isPreviewLoading,
                            onClick = { vm.goPrev() }
                        )
                        BottomBarButton(
                            text = "跳过",
                            enabled = !vm.isPreviewLoading,
                            onClick = { vm.skipCurrent() }
                        )
                        BottomBarButton(
                            text = "下一页",
                            enabled = vm.currentPage < vm.pageCount - 1 &&
                                !vm.isPreviewLoading,
                            onClick = { vm.goNext() }
                        )
                    }
                }
            }
        }
    ) { padding ->
        // ============================================================
        // 中间：预览区
        // ============================================================
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                vm.currentBitmap != null -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "第 ${vm.currentPage + 1} / ${vm.pageCount} 页",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = vm.currentBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        if (vm.isPreviewLoading) {
                            CircularProgressIndicator()
                        }
                    }
                }

                vm.isPreviewLoading -> CircularProgressIndicator()

                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "请点击顶部「输入」选择 PDF 文件",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "再点「输出」选择保存文件夹",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

// ============================================================
// 顶部栏按钮（RowScope 扩展，配合 weight 均分）
// ============================================================
@Composable
private fun RowScope.TopBarButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

// ============================================================
// 底部栏按钮
// ============================================================
@Composable
private fun RowScope.BottomBarButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
