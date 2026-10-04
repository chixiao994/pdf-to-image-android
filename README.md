# PDF 无损转图像（Android）

将 PDF 每一页无损导出为 PNG 图像的安卓应用，支持**整体导出**与**逐页导出**两种模式。

## 功能

| 位置 | 按钮 | 行为 |
|---|---|---|
| 顶部 | **输入** | 选择 PDF 文件 |
| 顶部 | **输出** | 选择输出文件夹（SAF，兼容 Android 10+） |
| 顶部 | **保存** | 一键导出**整个 PDF** 所有页面为 PNG（不受跳过影响） |
| 底部 | **上一张** | 先导出当前页（跳过标记生效）→ 翻到上一页 |
| 底部 | **跳过** | 标记当前页不参与单张导出 → 自动翻到下一页 |
| 底部 | **下一张** | 先导出当前页（跳过标记生效）→ 翻到下一页 |

## 无损原理

- 使用 Android 系统 `PdfRenderer`（基于 PDFium，Chrome 同款引擎）
- 按目标 DPI 等比放大位图：`像素 = PDF point × (DPI / 72)`
- 默认导出 **300 DPI**（A4 约 2480×3508 像素，印刷级画质）
- 以 **PNG** 格式保存（无损压缩，无 JPEG 压缩伪影）
- 单边像素超过 8000 时自动等比缩限，防止 OOM

## 环境要求

- Android Studio Hedgehog 或更高
- JDK 17
- Android SDK 34
- 最低支持 Android 8.0（API 26）

## 构建

```bash
git clone https://github.com/<your-name>/pdf-to-image-android.git
cd pdf-to-image-android
./gradlew assembleDebug
```

生成的 APK：`app/build/outputs/apk/debug/app-debug.apk`

## 生成 Gradle Wrapper（首次）

如果仓库中缺少 `gradle/wrapper/gradle-wrapper.jar`，执行：

```bash
gradle wrapper --gradle-version 8.7
```

## 自动构建

项目包含 GitHub Actions 工作流（`.github/workflows/build.yml`）：

- **push / PR 到 main**：自动构建 Debug APK 并上传为 artifact
- **打 tag（如 `v1.0`）**：额外构建 Release APK，并创建 GitHub Release

## 输出文件命名

```
{PDF文件名}_page_{页码}.png
```

例如 `report.pdf` 导出为 `report_page_1.png`、`report_page_2.png`……

## 权限说明

**无需任何存储权限**。文件选择与目录选择均使用系统 Storage Access Framework（SAF），天然兼容 Android 10 及以上的分区存储（Scoped Storage）。

## 已知限制

- 不支持加密 PDF（打开时抛 `SecurityException`）
- 不支持 PDF 表单/注释层
- 超大页面（如海报）会自动缩限到单边 8000 像素

## License

MIT
