package com.readtool.app

import android.Manifest
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.compose.ui.unit.sp
import com.readtool.app.audio.SoundPlayer
import com.readtool.app.ocr.OcrEngine
import com.readtool.app.data.AppData
import com.readtool.app.data.Assets
import com.readtool.app.data.PageSpec
import com.readtool.app.data.Store
import com.readtool.app.render.PageRenderer
import com.readtool.app.ui.Bg
import com.readtool.app.ui.CaptureScreen
import com.readtool.app.ui.HistoryScreen
import com.readtool.app.ui.HomeScreen
import com.readtool.app.ui.Ink2
import com.readtool.app.ui.LookupScreen
import com.readtool.app.ui.ReadToolTheme
import com.readtool.app.ui.ResultScreen
import com.readtool.app.ui.SettingsScreen
import com.readtool.app.ui.VocabScreen
import com.readtool.domain.ContextJudge
import com.readtool.domain.PageModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { Home, Capture, Result, Lookup, Vocab, History, Settings }

data class PageUi(
    val page: PageModel,
    val bitmap: Bitmap,
    val image: ImageBitmap,
    val spec: PageSpec,
    /** 照片底图；为 null 时显示样张渲染图 */
    val photo: Bitmap?,
    val photoImage: ImageBitmap?,
    /** true 表示字框坐标已对齐照片像素（OCR 结果），无需再校准 */
    val aligned: Boolean = false,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        setContent {
            var data by remember { mutableStateOf<AppData?>(null) }
            LaunchedEffect(Unit) {
                data = withContext(Dispatchers.IO) { Assets.load(assets) }
            }
            ReadToolTheme {
                val d = data
                if (d == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = com.readtool.app.ui.Primary)
                            Text("正在装入字库与发音包…", color = Ink2, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                } else {
                    val player = remember { SoundPlayer(applicationContext, d.audio) }
                    DisposableEffect(Unit) { onDispose { player.release() } }
                    ReadToolApp(d, store, player)
                }
            }
        }
    }
}

@Composable
fun ReadToolApp(data: AppData, store: Store, player: SoundPlayer) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.Home) }
    var pageUi by remember { mutableStateOf<PageUi?>(null) }
    var lookupSeed by remember { mutableStateOf("") }
    var capturedPath by remember { mutableStateOf<String?>(null) }
    var recognizing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ocr = remember { OcrEngine(context.assets, data.terms.ritual + data.terms.formulas) }

    // 模型较大，启动后后台预热，避免首次拍照卡住
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ocr.init() } }
    DisposableEffect(Unit) { onDispose { ocr.close() } }

    // 相机拍出来的图先落到 cacheDir/captures，再用 FileProvider 提供给系统相机写入
    fun newCaptureFile(): File {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        // 历史记录会引用这些图片，所以不能立刻删；按「最多 20 张 + 7 天」回收，避免无限堆积
        val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        runCatching {
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.forEachIndexed { i, f -> if (i >= 20 || f.lastModified() < cutoff) f.delete() }
        }
        return File(dir, "cap_${System.currentTimeMillis()}.jpg")
    }

    /** OCR 直接生成结果页：字框坐标已对齐照片像素，不需要再校准 */
    fun openOcr(path: String, bmp: Bitmap, page: PageModel, record: Boolean = true) {
        val text = page.chars.filter { !it.punct }.joinToString("") { it.c }
        val spec = PageSpec(
            id = "ocr-" + System.currentTimeMillis(),
            title = "拍照页",
            kind = if (page.vertical) "竖排识别页" else "横排识别页",
            vertical = page.vertical,
            text = text,
            note = "",
            fontSize = 96,
            cols = 5,
            charsPerCol = 14,
            lineChars = 18,
            imagePath = path,
        )
        ContextJudge.judgePage(page, data.terms, data.lexicon)
        val img = bmp.asImageBitmap()
        pageUi = PageUi(
            page = page,
            bitmap = bmp,
            image = img,
            spec = spec,
            photo = bmp,
            photoImage = img,
            aligned = true,
        )
        if (record) store.pushHistory(spec, page.chars.count { !it.punct })
        screen = Screen.Result
    }

    /** 拍完/选完图：先跑端侧 OCR，识别不出再回退手动录入 */
    fun handleImage(path: String, record: Boolean = true) {
        capturedPath = path
        scope.launch {
            recognizing = true
            try {
                val bmp = withContext(Dispatchers.IO) { PageRenderer.loadPhoto(path) }
                val page = if (bmp != null) {
                    withContext(Dispatchers.Default) { runCatching { ocr.recognize(bmp) }.getOrNull() }
                } else null
                if (bmp != null && page != null) {
                    openOcr(path, bmp, page, record)
                } else {
                    screen = Screen.Capture
                }
            } finally {
                recognizing = false
            }
        }
    }

    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingCaptureFile?.absolutePath?.let { handleImage(it) }
        }
    }

    val requestCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val file = newCaptureFile()
            pendingCaptureFile = file
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            runCatching { takePictureLauncher.launch(uri) }
                .onFailure { Toast.makeText(context, "当前设备没有可用的相机应用", Toast.LENGTH_SHORT).show() }
        } else {
            Toast.makeText(context, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
        }
    }

    // 相册走系统选择器，拿到 Uri 后复制一份到自己的缓存目录
    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val file = newCaptureFile()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { out -> input.copyTo(out) }
                }
                handleImage(file.absolutePath)
            }.onFailure { Toast.makeText(context, "读取图片失败", Toast.LENGTH_SHORT).show() }
        }
    }

    fun open(spec: PageSpec) {
        val rendered = PageRenderer.render(spec)
        ContextJudge.judgePage(rendered.page, data.terms, data.lexicon)
        // 照片存在则作为底图加载；加载失败（文件被清理）时回退到样张图
        val photo = spec.imagePath?.let { PageRenderer.loadPhoto(it) }
        pageUi = PageUi(
            page = rendered.page,
            bitmap = rendered.bitmap,
            image = rendered.bitmap.asImageBitmap(),
            spec = spec,
            photo = photo,
            photoImage = photo?.asImageBitmap(),
        )
        store.pushHistory(spec, rendered.page.chars.count { !it.punct })
        screen = Screen.Result
    }

    Box(Modifier.fillMaxSize()) {
        when (screen) {
            Screen.Home -> HomeScreen(
                data = data,
                store = store,
                onCapture = { requestCameraPermission.launch(Manifest.permission.CAMERA) },
                onPickImage = { pickImageLauncher.launch("image/*") },
                onLookup = { q -> lookupSeed = q; screen = Screen.Lookup },
                onVocab = { screen = Screen.Vocab },
                onHistory = { screen = Screen.History },
                onSettings = { screen = Screen.Settings },
                onSample = { open(it) },
            )

            Screen.Capture -> CaptureScreen(
                data = data,
                imagePath = capturedPath,
                onBack = { screen = Screen.Home },
                onBuild = { open(it) },
            )

            Screen.Result -> pageUi?.let { ui ->
                ResultScreen(
                    ui = ui,
                    data = data,
                    store = store,
                    player = player,
                    onBack = { screen = Screen.Home },
                )
            }

            Screen.Lookup -> LookupScreen(
                data = data,
                store = store,
                player = player,
                seed = lookupSeed,
                onBack = { screen = Screen.Home },
            )

            Screen.Vocab -> VocabScreen(
                store = store,
                onBack = { screen = Screen.Home },
            )

            Screen.History -> HistoryScreen(
                store = store,
                onBack = { screen = Screen.Home },
                // 拍照页重新识别一次，才能拿回对齐照片的字框；样张页直接按 spec 渲染
                onOpen = { spec ->
                    val p = spec.imagePath
                    if (p != null) handleImage(p, record = false) else open(spec)
                },
            )

            Screen.Settings -> SettingsScreen(
                data = data,
                store = store,
                onBack = { screen = Screen.Home },
            )
        }

        if (recognizing) {
            Box(
                Modifier.fillMaxSize().background(Color(0xB3000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        "正在识别本页文字…",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
}
