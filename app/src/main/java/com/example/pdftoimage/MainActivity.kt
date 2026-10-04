package com.example.pdftoimage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.pdftoimage.ui.PdfConverterScreen
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PDFBox 在 Android 上必须先初始化资源加载器
        PDFBoxResourceLoader.init(applicationContext)
        setContent {
            MaterialTheme {
                Surface { PdfConverterScreen() }
            }
        }
    }
}
