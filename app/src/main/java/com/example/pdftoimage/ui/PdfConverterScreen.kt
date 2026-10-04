package com.example.pdftoimage.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pdftoimage.PdfViewModel

@OptIn(ExperimentalMaterial3Api::class)
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
            } catch (_: SecurityException) { /* 某些设备不支持持久化，忽略 */ }
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
        // ============ 顶部栏：输入 / 输出 / 保存 ============
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            vm.pdfName.ifEmpty { "PDF 无损转图像" },
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (vm.pageCount > 0) {
                            Text(
                                "第 ${vm.currentPage + 1} / ${vm.pageCount} 页",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                actions = {
                    // 输入
                    IconButton(onClick = {
                        pdfPicker.launch(arrayOf("application/pdf"))
                    }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "输入")
                    }
                    // 输出
                    IconButton(
                        onClick = { dirPicker.launch(null) },
                        enabled = vm.pdfUri != null
                    ) {
                        Icon(Icons.Default.DriveFileMove, contentDescription = "输出")
                    }
                    // 保存（整体导出）
                    IconButton(
                        onClick = { vm.saveAllPages(context) },
                        enabled = vm.pdfUri != null &&
                            vm.outputDirUri != null &&
                            !vm.isLoading
                    ) {
                        Icon(Icons.Default.SaveAlt, contentDescription = "保存")
                    }
                }
            )
        },

        // ============ 底部栏：上一张 / 跳过 / 下一张 ============
        bottomBar = {
            if (vm.pdfUri != null) {
                BottomAppBar {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { vm.goPrev(context) },
                            enabled = vm.currentPage > 0 && !vm.isLoading
                        ) {
                            Icon(Icons.Default.ChevronLeft, null)
                            Spacer(Modifier.width(4.dp))
                            Text("上一张")
                        }

                        val isSkipped = vm.currentPage in vm.skippedPages
                        TextButton(
                            onClick = { vm.skipCurrent(context) },
                            enabled = !vm.isLoading
                        ) {
                            Icon(
                                if (isSkipped) Icons.Default.SkipNext
                                else Icons.Default.RemoveCircleOutline,
                                null,
                                tint = if (isSkipped) MaterialTheme.colorScheme.primary
                                else LocalContentColor.current
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (isSkipped) "已跳过" else "跳过")
                        }

                        TextButton(
                            onClick = { vm.goNext(context) },
                            enabled = vm.currentPage < vm.pageCount - 1 && !vm.isLoading
                        ) {
                            Text("下一张")
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                vm.isLoading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    if (vm.statusText.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(vm.statusText)
                    }
                }

                vm.currentBitmap != null -> Image(
                    bitmap = vm.currentBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentScale = ContentScale.Fit
                )

                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.PictureAsPdf, null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Text("请点击左上角「输入」选择 PDF 文件")
                    Text(
                        "再点「输出」选择保存文件夹",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
