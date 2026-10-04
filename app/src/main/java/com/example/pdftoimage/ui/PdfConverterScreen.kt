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
        // 顶部栏：标题居中 + 输入/输出/保存 三个按钮均分
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
                    // 标题（居中）
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
                            enabled = !vm.isLoading,
                            onClick = { pdfPicker.launch(arrayOf("application/pdf")) }
                        )
                        TopBarButton(
                            text = "输出",
                            enabled = vm.pdfUri != null && !vm.isLoading,
                            onClick = { dirPicker.launch(null) }
                        )
                        TopBarButton(
                            text = "保存",
                            enabled = vm.pdfUri != null &&
                                vm.outputDirUri != null &&
                                !vm.isLoading,
                            onClick = { vm.saveAllPages(context) }
                        )
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
                            enabled = vm.currentPage > 0 && !vm.isLoading,
                            onClick = { vm.goPrev(context) }
                        )
                        val isSkipped = vm.currentPage in vm.skippedPages
                        BottomBarButton(
                            text = if (isSkipped) "已跳过" else "跳过",
                            enabled = !vm.isLoading,
                            highlighted = isSkipped,
                            onClick = { vm.skipCurrent(context) }
                        )
                        BottomBarButton(
                            text = "下一页",
                            enabled = vm.currentPage < vm.pageCount - 1 && !vm.isLoading,
                            onClick = { vm.goNext(context) }
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
                vm.isLoading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    if (vm.statusText.isNotEmpty()) {
                        Text(vm.statusText)
                    }
                    if (vm.pageCount > 0) {
                        Text(
                            "第 ${vm.currentPage + 1} / ${vm.pageCount} 页",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                vm.currentBitmap != null -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "第 ${vm.currentPage + 1} / ${vm.pageCount} 页",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Image(
                        bitmap = vm.currentBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                }

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
// 底部栏按钮（RowScope 扩展）
// ============================================================
@Composable
private fun RowScope.BottomBarButton(
    text: String,
    enabled: Boolean,
    highlighted: Boolean = false,
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
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            color = if (highlighted) MaterialTheme.colorScheme.primary
            else LocalContentColor.current
        )
    }
}
