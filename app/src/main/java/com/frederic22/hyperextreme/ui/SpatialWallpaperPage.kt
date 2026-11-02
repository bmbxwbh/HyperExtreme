package com.frederic22.hyperextreme.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.SpatialControl
import com.frederic22.hyperextreme.data.RunLog
import com.frederic22.hyperextreme.spatial.SpatialDepth
import com.frederic22.hyperextreme.spatial.SpatialImport
import com.frederic22.hyperextreme.spatial.SpatialLayers
import com.frederic22.hyperextreme.wallpaper.SpatialServiceMode
import com.frederic22.hyperextreme.wallpaper.SpatialServiceTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock
import java.io.File
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 二级页「空间壁纸」（2026-10-22 建立）。
 *
 * ## 与「视差壁纸效果」完全独立
 *
 * 视差 = 两层平面各自按传感器平移（2D 仿射）；空间壁纸 = 深度图分 N 层 + 真 3D 透视转动（GL）。
 * **状态、开关、渲染管线互不影响**。
 *
 * ## 页面结构（按《03-UI与排版规范》）
 *
 * 1. **图片卡**：无标题（用户指定），1:1 预览框 + 「选择/更换图片」「清空选择」**同一行**；
 * 2. **动作卡**：「生成深度缓存」+「立即替换」（后者用主题色，与视差页一致）；
 * 3. **参数调整**：卡外 `SmallTitle` + 一张卡两行 ——「**缓存推理分辨率**」（下拉栏，
 *    768 / 896 / 1024，默认 768，存 `MaskPrefs`）与「清除所有深度缓存」（右侧箭头、**无 summary**），
 *    后者点击弹 `WindowDialog` 二次确认。
 */
@Composable
internal fun SpatialWallpaperDetail(state: MaskUiState) {
    val haptic = rememberClickHaptic()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var picked by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    // 深度推理进行中：按钮禁用，防连点重复跑（DA3-Base 一张可达数十秒，120s 预算；
    // 重复跑会同时占两份内存）。
    var busy by remember { mutableStateOf(false) }
    // 「这张图有没有缓存」只是读几个文件，几百毫秒内就回来。**绝不能翻 busy** ——
    // busy 会同时把「更换图片 / 生成深度缓存」打灰，视觉上就是闪一下（2026-10-29 用户反馈）。
    // 这里单独用一个只做防重入的标志，不参与任何 enabled 判断。
    var checking by remember { mutableStateOf(false) }
    // 功能冲突弹窗（视差壁纸正在生效时点「立即替换」）
    var showConflict by remember { mutableStateOf(false) }

    // 「缓存推理分辨率」：推理短边档位（768 / 896 / 1024），存在 MaskPrefs 里跨页面保留。
    val prefs = remember { MaskPrefs(ctx) }

    /**
     * 当前**候选图**（用户选中的那张）的缓存键；null = 没选中。
     *
     * 与 [lastKey]（已生成）分开：选完图立刻就有值，并已在缓存目录里复制了 `source` 原图。
     * 页面重建后靠它 + `source` 把预览框恢复出来 —— 不会再出现「预览显示着、实际没选中」。
     */
    var sourceKey by remember {
        mutableStateOf(
            prefs.spatialSourceKey.takeIf {
                it.length == 16 &&
                    SpatialDepth.sourceFile(File(SpatialDepth.cacheRoot(ctx), it)).isFile
            },
        )
    }
    var shortSide by remember { mutableStateOf(prefs.spatialShortSide) }
    // 「推理模型」：DA3-Base（默认，质量优先）/ DA3-Small（速度优先），存在 MaskPrefs 里。
    // 切换只影响**下一次真正重算**（强制重算窗口/换档位）；已有缓存（含导入的
    // dav2/da3 异模型包）仍然命中、不会被界面选择覆盖（2026-11-02 语义调整）。
    var modelChoice by remember { mutableStateOf(prefs.spatialModel) }
    // 「空间壁纸生效范围」：0 锁屏（默认）/ 1 桌面 / 2 全局。**不影响层图生成**，只决定渲染器挂哪条 leash。
    var scopeChoice by remember { mutableStateOf(prefs.spatialScope) }
    // 「启用空间壁纸锁屏景深」（2026-10-29）：默认关；关时渲染逻辑与加它之前完全一致。
    // 生效时机与上面的「生效范围」一样 —— 生效中改只记下来，下一次替换才随属性一起推。
    var lockDepthChoice by remember { mutableStateOf(prefs.spatialLockDepth) }

    // 缓存二次确认：命中缓存后记下「哪个哈希 + 窗口截止时间」，10s 内再点才覆盖重算。
    var confirmKey by remember { mutableStateOf<String?>(null) }
    var confirmUntil by remember { mutableStateOf(0L) }

    // 当前图片的缓存键（生成成功时写入）与**正在生效**的键（决定按钮显示「立即替换 / 关闭替换」）。
    // 进页面时若已经生效，直接用 prefs 里的键初始化，这样一进来就能一键关闭。
    // `spatialLastKey` 是落盘的「上次生成成功的键」—— 页面重建后 `picked` 会丢，
    // 但层图还在磁盘上，靠它才能继续替换（不落盘的话「立即替换」永远判成没生成过）。
    var lastKey by remember {
        mutableStateOf(
            prefs.spatialLastKey.takeIf {
                it.length == 16 && SpatialLayers.hasLayers(File(SpatialDepth.cacheRoot(ctx), it))
            },
        )
    }
    var activeKey by remember { mutableStateOf(prefs.spatialKey) }

    // ───────────── 挂载模式（2026-10-30，v1.5.82）─────────────
    //
    // 0 = LSPosed（原有的 hook 链，默认）；1 = 系统壁纸服务（自己的 WallpaperService，免 root）。
    // **两种模式互斥**，且「运行中」不许切换 —— 否则同一张图会被渲染两遍
    // （hook 版和 service 版的姿态/位移可能不一致）。
    var mountMode by remember { mutableStateOf(prefs.spatialMountMode) }
    // 「系统壁纸服务」是不是正在生效。**只在页面前台查**（用户 2026-10-30 明确：
    // 后台查不了就算了）。`WallpaperManager` 没有壁纸变化回调（ACTION_WALLPAPER_CHANGED
    // 是 protected broadcast），所以只能靠 onResume 重读。
    var serviceRunning by remember { mutableStateOf(SpatialServiceMode.isRunning(ctx)) }
    var showMountWarn by remember { mutableStateOf(false) }
    var noMoreWarn by remember { mutableStateOf(false) }

    /**
     * 生效中：选图相关的操作一律禁用（与视差页同一套规则）。
     *
     * 2026-10-31：**系统壁纸服务运行中同样算生效**。那条链不写 `spatialActive` /
     * `spatialKey`（`activeKey` 恒空），原来「更换图片 / 清空选择」在壁纸正渲染时仍可点
     * ⇒ 候选框能被清空 / 换成别的图，与屏幕上看到的完全对不上。现在两种模式的
     * 「生效中锁住候选框」语义一致（用户要求"与 LSPosed 模式逻辑相同"）。
     */
    val locked = activeKey.isNotEmpty() || serviceRunning

    /**
     * 当前模式是否**正在运行** —— 为 true 时「挂载模式」下拉锁死。
     *
     * 两条闭环都成立：
     * * LSPosed 运行中 → 必须点「关闭替换」才能改模式；
     * * 服务模式运行中 → 必须点「恢复到系统默认壁纸」才能改模式。
     */
    val isRunning = if (mountMode == MaskPrefs.SPATIAL_MOUNT_SERVICE) {
        serviceRunning
    } else {
        activeKey.isNotEmpty()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 用户可能在系统壁纸页里换了图/换了范围，回来必须重新对一次
                serviceRunning = SpatialServiceMode.isRunning(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 提交挂载模式（弹窗确认后才走这里）。 */
    fun commitMountMode(next: Int) {
        mountMode = next
        prefs.setSpatialMountMode(next)
        // 切到服务模式时确保 hook 链已停（幂等：本来就没开就什么也不做）
        if (next == MaskPrefs.SPATIAL_MOUNT_SERVICE && prefs.spatialActive) {
            SpatialControl.disable(ctx, "已切换到系统壁纸服务模式")
            activeKey = ""
        }
    }

    // 页面重建后把预览从磁盘读回来：`picked` / `preview` 都是 remember，一退页面就没了。
    // 现在读的是**原图副本** `source`（不再是 preview.webp），所以预览框里看到的就是用户选的那张图。
    //
    // 2026-10-31：改用 `serviceRunning` 做 key，并补一条**服务模式的候选键兜底** ——
    // 服务链不写 `spatialActive` / `spatialKey`，页面里的 `activeKey` 恒为空；
    // 一旦 `spatialSourceKey` 丢过（旧版生效中还能点「清空选择」），重进页面/从系统壁纸页
    // 返回时就不知道在渲染哪张图，预览框会空着。这里按「系统壁纸服务当前目标」补齐候选键，
    // 与 LSPosed 模式从 prefs 恢复预览完全等价。
    LaunchedEffect(serviceRunning) {
        if (preview != null) return@LaunchedEffect
        if (sourceKey == null && serviceRunning) {
            val target = SpatialServiceTarget.readKey(ctx)
            val dir = target?.let { File(SpatialDepth.cacheRoot(ctx), it) }
            if (target != null && dir != null && SpatialDepth.sourceFile(dir).isFile) {
                sourceKey = target
                prefs.setSpatialSourceKey(target)
                if (lastKey == null && SpatialLayers.hasLayers(dir)) {
                    lastKey = target
                    prefs.setSpatialLastKey(target)
                }
            }
        }
        val f = sourceKey?.let { SpatialDepth.sourceFile(File(SpatialDepth.cacheRoot(ctx), it)) }
        if (f != null && f.isFile) {
            preview = withContext(Dispatchers.IO) { SpatialDepth.decodePreview(f) }
        }
    }

    /**
     * 开启空间壁纸（同步）。
     *
     * **关闭不再走这里**：统一交给 [SpatialControl.disable]（改 prefs → 推属性 → toast →
     * 重启 miwallpaper），因为视差页的「功能冲突提醒」也要用同一套关闭动作。
     */
    fun enableSpatial(key: String) {
        prefs.setSpatialActive(key)
        activeKey = key
        // **SystemUI 侧读的是系统属性**（见 SpatialFlag：那里 prefs 通道不可用）。
        // scope 必须一起推 —— 生效中改过范围的话，到这里才真正生效（用户 2026-10-29 定）。
        com.frederic22.hyperextreme.data.SpatialFlag.push(
            true,
            key,
            prefs.spatialScope,
            prefs.spatialLockDepth,
        )
        android.util.Log.i(
            "HyperExtreme",
            "SPATIAL_UI enable key=$key active=${prefs.spatialActive} scope=${prefs.spatialScope}",
        )
        Toast.makeText(
            ctx,
            "空间壁纸已生效（${scopeLabel(prefs.spatialScope)}）",
            Toast.LENGTH_LONG,
        ).show()

        // 记「**壁纸基准**」（2026-10-29）：aod 侧的换壁纸检测拿它当比对值，
        // 判据一变就无条件关闭空间壁纸（没有开关）。判据要读模板文件 ⇒ 放后台、不阻塞点按；
        // 算不出来就留空 —— hook 侧会惰性补记，只是第一次换壁纸时不会自动关（可接受的降级）。
        val app = ctx.applicationContext ?: ctx
        Thread {
            runCatching {
                val version = com.frederic22.hyperextreme.data.WallpaperVersion.compute(app)
                if (version.usable) prefs.setSpatialWallpaperFp(version.value)
            }
        }.apply { isDaemon = true }.start()
    }

    /** 真正干活：深度 →（紧接着）生成层图。分层失败**不阻断**深度结果。 */
    val runGenerate: (Uri, Boolean) -> Unit = { uri, force ->
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                // ③ 深度池用完立刻还：深度图尺寸 = 层图尺寸，两份工作集（20n + 56n）
                // 同时常驻要 168MB，分层再要一块就撞 256MB 堆上限（2026-10-31）。
                runCatching {
                    try {
                        SpatialDepth.generate(ctx, uri, shortSide, modelChoice, force)
                    } finally {
                        SpatialDepth.dropPool()
                    }
                }.map { depth ->
                    // ⑦ OOM 自愈：换图时最缺的是「先回收再分配」，而 ART 只在分配失败时 GC。
                    // 实测（run_log）：同一张图第一次 OutOfMemoryError、紧接着重试就能过。
                    depth to runCatching { SpatialLayers.generate(ctx, depth.dir, uri, force) }
                        .recoverCatching { e ->
                            if (e !is OutOfMemoryError) throw e
                            SpatialLayers.dropPool()
                            System.gc()
                            SpatialLayers.generate(ctx, depth.dir, uri, force)
                        }
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { pair ->
                    val depth = pair.first
                    val layerRes = pair.second
                    lastKey = depth.dir.name
                    prefs.setSpatialLastKey(depth.dir.name)
                    val dSec = "%.1f".format(depth.costMs / 1000f)
                    val layer = layerRes.getOrNull()
                    val totalMs = depth.costMs + (layer?.costMs ?: 0L)
                    val msg = when {
                        layer != null && depth.hitCache && layer.hit ->
                            "已生成（命中缓存）"
                        layer != null ->
                            "已生成（耗时 ${"%.1f".format(totalMs / 1000f)} 秒）"
                        else ->
                            "深度已生成（$dSec 秒），但分层失败：${layerRes.exceptionOrNull()?.message}"
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    RunLog.append(
                        ctx,
                        "空间壁纸 · 生成",
                        buildList {
                            add("缓存目录：${depth.dir.absolutePath}")
                            add("推理短边：$shortSide")
                            add("推理模型：$modelChoice")
                            add(if (depth.hitCache) "深度命中缓存" else "深度耗时 ${depth.costMs} ms")
                            if (layer != null) {
                                add(if (layer.hit) "层图命中" else "8 层耗时 ${layer.costMs} ms → ${layer.dir.absolutePath}")
                            } else {
                                add("分层失败：${layerRes.exceptionOrNull()?.let { "${it.javaClass.name}: ${it.message}" }}")
                            }
                        },
                        ok = layer != null,
                    )
                },
                onFailure = { e ->
                    val msg = e.message ?: e.javaClass.simpleName
                    Toast.makeText(ctx, "处理失败：$msg", Toast.LENGTH_LONG).show()
                    RunLog.append(
                        ctx,
                        "空间壁纸 · 生成",
                        listOf("失败：${e.javaClass.name}: $msg"),
                        ok = false,
                    )
                },
            )
        }
    }

    // 导入缓存包（2026-11-02 用户方案：深度在 PC 上用 `tools/export_spatial_cache.mjs`
    // 算好打 zip 推到手机）—— 彻底绕开 DA3-Base 手机推理的 5~7GB 内存尖峰（lmkd 闪退）。
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { SpatialImport.install(ctx, uri) }
                }
                busy = false
                result.fold(
                    onSuccess = { k ->
                        // 与「选图」同一套状态回写：候选键 + 预览 + lastKey（层图在才认）
                        sourceKey = k
                        prefs.setSpatialSourceKey(k)
                        picked = null
                        preview = withContext(Dispatchers.IO) {
                            SpatialDepth.decodePreview(
                                SpatialDepth.sourceFile(File(SpatialDepth.cacheRoot(ctx), k)),
                            )
                        }
                        val dir = File(SpatialDepth.cacheRoot(ctx), k)
                        if (SpatialLayers.hasLayers(dir)) {
                            lastKey = k
                            prefs.setSpatialLastKey(k)
                        } else {
                            lastKey = null
                        }
                        Toast.makeText(
                            ctx,
                            "导入成功：深度已就绪，点「生成深度缓存」秒级补算层图后即可替换",
                            Toast.LENGTH_LONG,
                        ).show()
                        SpatialImport.logResult(
                            ctx, true,
                            listOf("缓存键：$k", "来源：缓存包导入（PC 计算）"),
                        )
                    },
                    onFailure = { e ->
                        val msg = e.message ?: e.javaClass.simpleName
                        Toast.makeText(ctx, "导入失败：$msg", Toast.LENGTH_LONG).show()
                        SpatialImport.logResult(
                            ctx, false,
                            listOf("失败：${e.javaClass.name}: $msg"),
                        )
                    },
                )
            }
        }
    }

    // **必须 remember**：`Color.luminance()` 有浮点运算，且主题对象每帧读一次没意义。
    val scheme = MiuixTheme.colorScheme
    val previewSurface = remember(scheme.surface) {
        if (scheme.surface.luminance() < 0.5f) Color(0xFF1A1A1A) else scheme.surfaceContainerHigh
    }

    // **必须 remember(preview)**：`asImageBitmap()` 每次调用都会新建 `ImageBitmap` 包装，
    // Compose 认为是新对象 ⇒ **每帧重新上传整张纹理**（原图 3000×4000 ≈ 48MB）⇒ 动画卡死。
    // 换成 remember 后只在图片真正变化时重建一次。
    val previewImage = remember(preview) { preview?.asImageBitmap() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            picked = uri
            scope.launch {
                // 一、算 key + 把**原图原始字节**拷进缓存目录（不重编码）。
                //     拷完就算「选中」了 —— 即使还没生成深度，重进页面也能恢复预览。
                val k = withContext(Dispatchers.IO) {
                    runCatching {
                        val key = SpatialDepth.cacheKey(ctx, uri)
                        SpatialDepth.ensureSource(ctx, uri, File(SpatialDepth.cacheRoot(ctx), key))
                        key
                    }.getOrNull()
                }
                if (k != null) {
                    sourceKey = k
                    prefs.setSpatialSourceKey(k)
                }
                // 二、预览：优先解 `source`（就是原图），复制失败才退回直接解 uri。
                preview = withContext(Dispatchers.IO) {
                    k?.let {
                        SpatialDepth.decodePreview(SpatialDepth.sourceFile(File(SpatialDepth.cacheRoot(ctx), it)))
                    } ?: SpatialDepth.decodePreview(ctx, uri)
                }
                // 三、这张图之前生成过（深度 + 层图都在）⇒ 「立即替换」可以直接用。
                if (k != null && SpatialLayers.hasLayers(File(SpatialDepth.cacheRoot(ctx), k))) {
                    lastKey = k
                    prefs.setSpatialLastKey(k)
                } else {
                    lastKey = null
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // ⚠️ **必须用 [rememberPageScroll]，不能用 `rememberScrollState()`**（2026-10-29 修）。
            // 顶栏的收起量是由 `DetailTabHost` 里的 `snapshotFlow { pageScroll.value }` 1:1 写进
            // `appBarState.heightOffset` 的 —— 内容滚的是**另一个** ScrollState，顶栏就完全
            // 不知道内容滚了多远 ⇒ 大标题永远不收起（用户实测："空间壁纸"大标题不会收）。
            // 其余三个二级页的列都用 `rememberPageScroll()`（见《MaskSwapScreen》LocalPageScroll 说明）。
            .verticalScroll(rememberPageScroll())
            .padding(top = LocalTopBarHeight.current, bottom = 32.dp),
    ) {
        // 一、图片卡（无标题）：预览框 + 同一行的两个按钮。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f / 1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(previewSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    val img = previewImage
                    if (img != null) {
                        Image(
                            bitmap = img,
                            contentDescription = "图片",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(
                            text = "还未选择图片",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = if (preview != null) "更换图片" else "选择图片",
                        onClick = {
                            haptic()
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !locked && !busy,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    TextButton(
                        text = "清空选择",
                        onClick = {
                            haptic()
                            // **只清「选中」**（用户 2026-10-29 定）：预览与候选键一起清，
                            // 但磁盘上的原图/层图、以及 `lastKey` 都不动 —— 已经生成好的
                            // 仍然可以「立即替换」，只是候选框空着。
                            picked = null
                            preview = null
                            sourceKey = null
                            prefs.setSpatialSourceKey(null)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !locked && !busy,
                    )
                    TextButton(
                        text = "导入缓存包",
                        onClick = {
                            haptic()
                            // zip = PC 侧 `tools/export_spatial_cache.mjs` 的产物
                            // （source + depth.png + meta.json 平铺三件套）
                            importPicker.launch(
                                arrayOf("application/zip", "application/octet-stream"),
                            )
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !locked && !busy,
                    )
                }
            }
        }

        // 二、动作卡：「生成深度缓存」+「立即替换」（主题色）。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextButton(
                    text = if (busy) "处理中…" else "生成深度缓存",
                    onClick = {
                        haptic()
                        // 有 picked 用 picked；没有就用缓存目录里的原图（页面重建后的常态）
                        val uri = picked ?: sourceKey
                            ?.let { SpatialDepth.sourceFile(File(SpatialDepth.cacheRoot(ctx), it)) }
                            ?.takeIf { it.isFile }
                            ?.let { Uri.fromFile(it) }
                        when {
                            uri == null -> Toast.makeText(ctx, "请先选择图片", Toast.LENGTH_SHORT).show()
                            busy || checking -> Unit
                            else -> {
                                // **命中有缓存时不直接重算**（2026-10-22 用户要求）：
                                // 先提示「10s 内再点一次」，窗口内再点才覆盖重算。
                                // ⚠️ 这里只翻 `checking`（防重入），**不翻 `busy`** ——
                                // busy 会连带把两个按钮打灰，用户看到的就是「闪一下」。
                                scope.launch {
                                    checking = true
                                    val key = withContext(Dispatchers.IO) {
                                        runCatching {
                                            SpatialDepth.hasCache(ctx, uri, shortSide)
                                        }.getOrNull()
                                    }
                                    checking = false
                                    val now = SystemClock.elapsedRealtime()
                                    // 层图缺失/格式过期（`hasLayers` 会检查版本号）时**直接补算层图**，
                                    // 不走「10 秒内再点一次」那套 —— 那种情况用户根本没得选，
                                    // 深度命中缓存、只重跑分层（1~2 秒）。
                                    val layersReady = key != null && SpatialLayers.hasLayers(
                                        File(SpatialDepth.cacheRoot(ctx), key),
                                    )
                                    if (key != null && layersReady &&
                                        !(confirmKey == key && now <= confirmUntil)
                                    ) {
                                        confirmKey = key
                                        confirmUntil = now + CACHE_CONFIRM_WINDOW_MS
                                        Toast.makeText(
                                            ctx,
                                            "该图片已有缓存，如需重新生成并替换请在10s内再次点击",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    } else {
                                        val force = key != null && layersReady
                                        confirmKey = null
                                        runGenerate(uri, force)
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    // 2026-10-31：生效中（两条链任一在跑）连「生成深度缓存」也一起变灰 ——
                    // 与「更换图片 / 清空选择」同一把 `locked`。生效中重算缓存没有意义，
                    // 且会把正在渲染的那份层图覆盖掉。
                    enabled = !busy && !locked,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    text = when {
                        // 系统壁纸服务模式：两个文案都由「当前系统壁纸是不是我们」决定
                        mountMode == MaskPrefs.SPATIAL_MOUNT_SERVICE ->
                            if (serviceRunning) "恢复到系统默认壁纸" else "跳转到服务页设置"
                        lastKey != null && activeKey == lastKey -> "关闭替换"
                        else -> "立即替换"
                    },
                    onClick = replace@{
                        haptic()
                        // ── 系统壁纸服务模式：**只走系统路径**（2026-10-30）──
                        // 开启 = 写目标 + 拉起系统壁纸页（用户在页面里选生效范围）；
                        // 关闭 = WallpaperManager.clear(both) → 回系统默认壁纸。
                        if (mountMode == MaskPrefs.SPATIAL_MOUNT_SERVICE) {
                            if (serviceRunning) {
                                // `WallpaperManager.clear()` 是同步 binder，HyperOS 上要几百 ms，
                                // 放主线程就是「点一下卡一下」（用户 2026-10-31 反馈）。
                                // 丢到 IO 线程，完成后再回主线程更新状态 / toast。
                                scope.launch {
                                    withContext(Dispatchers.IO) { SpatialServiceMode.disable(ctx) }
                                    serviceRunning = SpatialServiceMode.isRunning(ctx)
                                    Toast.makeText(ctx, "已恢复到系统默认壁纸", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                val k = lastKey
                                val ok = k != null && SpatialLayers.hasLayers(
                                    File(SpatialDepth.cacheRoot(ctx), k),
                                )
                                if (ok) {
                                    SpatialServiceMode.enable(ctx, k!!)
                                    Toast.makeText(
                                        ctx,
                                        "请在系统服务页设置生效范围",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    Toast.makeText(ctx, "请先生成深度缓存", Toast.LENGTH_SHORT).show()
                                }
                            }
                            return@replace
                        }
                        // ── 激活保险（2026-10-31 用户要求）──
                        // LSPosed 模式靠模块激活（属性通道 + hook 落盘）才能生效，未激活时点
                        // 「立即替换」只会静默失败 ⇒ 先拦一句。判据用 `fullyActivated`
                        // （与功能页 `requireActivated()` 同一把闸门）。
                        // ⚠️ **关闭方向不拦**：否则掉激活后用户连关都关不掉。
                        // 位置在服务模式 return 之后 ⇒ 服务模式（免 Root）完全不受影响。
                        val closing = lastKey != null && activeKey == lastKey
                        if (!closing && !state.fullyActivated) {
                            Toast.makeText(
                                ctx,
                                "以LSPosed模式运行时需要激活模块，请先激活",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@replace
                        }
                        // **纯同步**：只用已记下的缓存键 + 一次文件存在性检查。
                        // 之前这里走 `busy` + IO 协程，busy 一翻，两个按钮的文案/可用态跟着变 → 看着在「闪」。
                        val known = lastKey
                        val hasLayers = known != null && SpatialLayers.hasLayers(
                            File(SpatialDepth.cacheRoot(ctx), known),
                        )
                        android.util.Log.i(
                            "HyperExtreme",
                            "SPATIAL_UI click lastKey=$known hasLayers=$hasLayers picked=$picked " +
                                "source=$sourceKey active=$activeKey parallax=${state.parallaxActive}",
                        )
                        when {
                            // 关闭方向：不做互斥检查（关闭永远不会冲突）
                            hasLayers && activeKey == known -> {
                                SpatialControl.disable(ctx)
                                activeKey = ""
                            }
                            // 开启方向：**最后**才查互斥。上面「有没有可替换的图」已经过了，
                            // 这时才轮到「视差正在生效」这个外部条件。
                            hasLayers && state.parallaxActive -> showConflict = true
                            hasLayers -> enableSpatial(known!!)
                            // 键丢了但有图：现算一次（异步，不翻 busy，所以不闪）
                            picked != null || sourceKey != null -> scope.launch {
                                val u = picked ?: sourceKey
                                    ?.let { SpatialDepth.sourceFile(File(SpatialDepth.cacheRoot(ctx), it)) }
                                    ?.takeIf { it.isFile }
                                    ?.let { Uri.fromFile(it) } ?: return@launch
                                val k = withContext(Dispatchers.IO) {
                                    runCatching { SpatialDepth.cacheKey(ctx, u) }.getOrNull()
                                }
                                if (k != null && SpatialLayers.hasLayers(File(SpatialDepth.cacheRoot(ctx), k))) {
                                    lastKey = k
                                    prefs.setSpatialLastKey(k)
                                    if (state.parallaxActive) showConflict = true else enableSpatial(k)
                                } else {
                                    Toast.makeText(
                                        ctx, "请先生成深度缓存", Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                            else -> {
                                android.util.Log.i("HyperExtreme", "SPATIAL_UI click 无图无键，忽略")
                                Toast.makeText(ctx, "请先选择图片", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }

        // 三、参数调整（**卡外标题**，与设置页"外观 / 测试"同一类）。
        SmallTitle("参数调整")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            // 「挂载模式」（2026-10-30 用户要求，**排在「缓存推理分辨率」上面**）：
            // 无 summary。两种模式互斥 ⇒ **运行中锁死**，必须先关掉当前模式才能改。
            DropdownRow(
                title = "挂载模式",
                options = MOUNT_MODE_LABELS,
                selectedIndex = mountMode.coerceIn(0, MOUNT_MODE_LABELS.lastIndex),
                enabled = !isRunning,
                onSelect = { index ->
                    if (index == mountMode) return@DropdownRow
                    // 切到服务模式：先弹「功能提醒」（用户勾过「不再弹」就直接生效）
                    if (index == MaskPrefs.SPATIAL_MOUNT_SERVICE && !prefs.spatialMountWarned) {
                        noMoreWarn = false
                        showMountWarn = true
                    } else {
                        commitMountMode(index)
                    }
                },
            )
            // 「缓存推理分辨率」（2026-10-22 用户要求，**排在「清除所有深度缓存」上面**）：
            // 下拉栏 = 设置页「主题」那一套 `DropdownRow`（官方 `OverlayListPopup` 积木）。
            DropdownRow(
                title = "缓存推理分辨率",
                summary = "数值越大，效果越好，速度越慢",
                options = SpatialDepth.SHORT_SIDES.map { it.toString() },
                selectedIndex = SpatialDepth.SHORT_SIDES.indexOf(shortSide).coerceAtLeast(0),
                onSelect = { index ->
                    val value = SpatialDepth.SHORT_SIDES[index]
                    shortSide = value
                    prefs.setSpatialShortSide(value)
                },
            )
            // 「推理模型」（2026-11-02 DA3 换代，用户要求加入模型选择）：
            // Base = DA3-Base 0.12B（默认，质量优先，768 下预计 20~25s）；
            // Small = DA3-Small（约 5s，与换代前体验相当）。
            // **不锁 busy**：只影响下一次真正重算；已有缓存（含导入的异模型包）照常命中，
            // 要按新选择重算走「10s 内再点一次」的强制窗口。
            DropdownRow(
                title = "推理模型",
                summary = "Base 更准更慢，Small 更快",
                options = listOf("DA3-Base（默认）", "DA3-Small"),
                selectedIndex = if (modelChoice == "small") 1 else 0,
                onSelect = { index ->
                    val value = if (index == 1) "small" else "base"
                    modelChoice = value
                    prefs.setSpatialModel(value)
                },
            )
            // 「空间壁纸生效范围」（2026-10-28 用户要求，**排在「清除所有深度缓存」上面**）：
            // 锁屏（默认）/ 桌面 / 全局。**无 summary**（用户指定）。
            // 只决定渲染器挂哪条壁纸 leash，**不影响层图生成** ⇒ 切换不需要重算缓存。
            // 两条 leash 完全独立（真机探针：挂锁屏那条时锁屏可见、桌面不可见）。
            DropdownRow(
                title = "空间壁纸生效范围",
                options = SPATIAL_SCOPE_LABELS,
                selectedIndex = scopeChoice.coerceIn(0, SPATIAL_SCOPE_LABELS.lastIndex),
                // 系统壁纸服务模式下**无效**（范围由系统壁纸页决定）⇒ 变灰，但 prefs 里的值保留
                enabled = mountMode == MaskPrefs.SPATIAL_MOUNT_LSPOSED,
                onSelect = { index ->
                    scopeChoice = index
                    prefs.setSpatialScope(index)
                    // **不立刻 push**（2026-10-29 用户定）：生效中改范围只记下来，
                    // 下次点「立即替换 / 关闭替换」时按新值一起推 —— 避免正在跑的时候
                    // 拆掉渲染器重建挂载点。提示语说明这一点。
                    if (activeKey.isNotEmpty()) {
                        Toast.makeText(
                            ctx,
                            "应用范围将在下一次替换时生效",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
            // 「启用空间壁纸锁屏景深」（2026-10-29 用户要求，**排在「生效范围」下面**）：
            // 开 = 主体层再画一份进 SystemUI 窗口的 keyguard_foreground_layer，压在锁屏时钟之上；
            // **无 summary**（用户指定）；默认关；关时渲染逻辑与加它之前完全一致。
            // 生效时机同「生效范围」：生效中改只写 prefs，下一次替换才随属性推送 —— 同一条 toast。
            // 「生效范围 = 桌面」时它没有作用对象（桌面没有时钟），按用户要求**照常可点、静默无效果**。
            SwitchRow(
                title = "启用空间壁纸锁屏景深",
                checked = lockDepthChoice,
                // 系统壁纸服务模式下**不可用**（那是 hook 链的能力）⇒ 变灰，值保留
                enabled = mountMode == MaskPrefs.SPATIAL_MOUNT_LSPOSED,
                onCheckedChange = { on ->
                    lockDepthChoice = on
                    prefs.setSpatialLockDepth(on)
                    if (activeKey.isNotEmpty()) {
                        Toast.makeText(
                            ctx,
                            "应用范围将在下一次替换时生效",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }

        // 「清除所有深度缓存」**独立成一张卡**（2026-10-30 用户要求）+ 语义红 + 无箭头。
        // 颜色用 `MiuixTheme.colorScheme.error`（miuix 规范：浅 #E94634 / 深 #F12522），
        // 不自造色值 —— 它会跟着深浅主题自动切换。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            BasicComponent(
                title = "清除所有深度缓存",
                // **无 summary**（用户 2026-10-22 指定）。
                summary = null,
                titleColor = BasicComponentColors(
                    color = MiuixTheme.colorScheme.error,
                    disabledColor = MiuixTheme.colorScheme.disabledOnSurface,
                ),
                onClick = {
                    haptic()
                    showClearConfirm = true
                },
            )
        }
    }

    // 二次确认弹窗：**常驻组合树、用 show 控制**（规范要求，条件包裹会让退出动画播不出来）。
    // 正文按运行态分三档（2026-10-31 用户要求）：服务模式运行中 / LSPosed 运行中 / 未运行。
    // 判据用**实际运行态**（serviceRunning 优先），与下面 onConfirm 的动作保持同一口径。
    ClearCacheDialog(
        show = showClearConfirm,
        summary = when {
            serviceRunning -> "清除后，深度缓存需重新生成。当前空间壁纸正在运行，" +
                "点击“清除”后将自动恢复到系统默认壁纸。"
            activeKey.isNotEmpty() -> "清除后，深度缓存需重新生成。当前空间壁纸正在运行，" +
                "点击“清除”后将自动关闭替换。"
            else -> "清除后，深度缓存需重新生成。"
        },
        onDismissRequest = { showClearConfirm = false },
        onConfirm = {
            // 系统壁纸服务模式下**先恢复到系统默认壁纸**再删 —— 否则壁纸进程读不到层图，
            // 会黑屏或崩。这条也是「功能提醒」弹窗里对用户的承诺（2026-10-30）。
            if (mountMode == MaskPrefs.SPATIAL_MOUNT_SERVICE && serviceRunning) {
                SpatialServiceMode.disable(ctx)
                serviceRunning = SpatialServiceMode.isRunning(ctx)
            }
            // LSPosed 模式正在生效：**统一收口**到 SpatialControl.disable（2026-10-31 用户要求）——
            // 比原来手写的那三行多两件事：清壁纸基准 `spatialWallpaperFp`、400ms 后
            // `killall com.miui.miwallpaper` 复位残留层。`toastText = null` 是静默，
            // 避免和下面的「已清除 N 项深度缓存」叠成两条排队播。
            if (activeKey.isNotEmpty()) {
                SpatialControl.disable(ctx, toastText = null)
                activeKey = ""
            }
            // 删 `files/spatial/` 下所有子目录（逻辑原来在 SpatialDepth 里，这里直接走它）
            val n = SpatialDepth.clearAll(ctx)
            // 层图/原图都没了 ⇒ 记着的键作废（生效态上面已经关掉了）
            lastKey = null
            sourceKey = null
            prefs.setSpatialSourceKey(null)
            Toast.makeText(ctx, "已清除 $n 项深度缓存", Toast.LENGTH_SHORT).show()
            showClearConfirm = false
        },
    )

    // 「功能提醒」弹窗（切到系统壁纸服务模式时弹一次；勾了「不再弹」就不再出现）。
    MountModeWarnDialog(
        show = showMountWarn,
        noMoreWarn = noMoreWarn,
        onNoMoreWarnChange = { noMoreWarn = it },
        onDismissRequest = { showMountWarn = false },
        onConfirm = {
            if (noMoreWarn) prefs.setSpatialMountWarned(true)
            showMountWarn = false
            commitMountMode(MaskPrefs.SPATIAL_MOUNT_SERVICE)
        },
    )

    // 功能冲突弹窗（常驻组合树、用 show 控制 —— 条件包裹会让退出动画播不出来）。
    // 「关闭」只关视差、**不继续**本次替换；要再点一次「立即替换」才生效。
    FeatureConflictDialog(
        show = showConflict,
        summary = "当前视差壁纸已生效，应用空间壁纸需先关闭",
        onDismissRequest = { showConflict = false },
        onConfirmClose = {
            showConflict = false
            state.disableParallax()
        },
    )
}

/** 命中缓存后的二次确认窗口：10 秒内再点一次才覆盖重算（用户 2026-10-22 指定）。 */
private const val CACHE_CONFIRM_WINDOW_MS = 10_000L

/**
 * 生效范围的下拉档位文案，**顺序必须与 `MaskPrefs.SPATIAL_SCOPE_*` 的取值一一对应**
 * （0 锁屏 / 1 桌面 / 2 全局）。Array index 就是存进 prefs 的值。
 */
private val SPATIAL_SCOPE_LABELS = listOf("锁屏", "桌面", "全局")

/**
 * 挂载模式的档位文案，**顺序与 `MaskPrefs.SPATIAL_MOUNT_*` 一一对应**（2026-10-30）。
 *
 * "免Root" 是这一档的核心卖点：它不依赖 LSPosed / 属性通道 / su，普通用户装上就能用。
 */
private val MOUNT_MODE_LABELS = listOf("LSPosed", "系统壁纸服务（免Root）")

/** 取值 → 文案；越界时回退到默认档（锁屏），绝不抛。 */
private fun scopeLabel(v: Int): String =
    SPATIAL_SCOPE_LABELS.getOrElse(v) { SPATIAL_SCOPE_LABELS[0] }

/**
 * 「清除所有深度缓存」的二次确认弹窗。
 *
 * **正文走 `WindowDialog` 的 `summary` 参数**（miuix 官方正文位，自带官方字号 / 颜色 / 与标题的间距），
 * 不要自己在 `content` 里塞 `Text` —— 那样字号、行距、对齐都跟官方不一致。
 *
 * 正文**按运行态分三档**（2026-10-31 用户要求），由调用方算好传进来：
 * 服务模式运行中 / LSPosed 模式运行中 / 未运行。三档与 `onConfirm` 的实际动作一一对应。
 *
 * 按钮区沿用 `RestartDialog` 的写法：`SpaceBetween` + 两个 `TextButtonLike`（确认用 `primary`）。
 */
@Composable
private fun ClearCacheDialog(
    show: Boolean,
    summary: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        title = "请确认",
        summary = summary,
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        val dismissState = LocalDismissState.current
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButtonLike(
                    text = "取消",
                    onClick = {
                        onDismissRequest()
                        dismissState?.invoke()
                    },
                )
                Spacer(Modifier.width(20.dp))
                TextButtonLike(
                    text = "清除",
                    primary = true,
                    onClick = {
                        onConfirm()
                        dismissState?.invoke()
                    },
                )
            }
        }
    }
}
