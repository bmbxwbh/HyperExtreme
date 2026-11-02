package com.frederic22.hyperextreme.spatial

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.frederic22.hyperextreme.data.RunLog
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 空间壁纸：深度图生成（2026-11-02 换代 Depth Anything 3 双模型）。
 *
 * ## 用的什么
 *
 * **Depth Anything 3（DA3，ByteDance Seed，arXiv:2511.10647）**，跑在
 * **ONNX Runtime 完整版 1.31.0**，两档可选（UI「推理模型」下拉，默认 base）：
 *
 * * `models/da3_base_int8.onnx` —— DA3-Base，123MB，MatMulNBits int8 + fp16 存储
 *   （HF `jlampel/depth-anything-3-base-onnx-int8`，构建时由 Gradle 下载+SHA 校验，不入库）；
 * * `models/da3_small_int8.onnx` —— DA3-Small q8，29MB，动态量化
 *   （HF `en970/depth-anything-v3-small-onnx`，入库）。
 *
 * 两者均 **Apache-2.0**（DA3 只有 Small/Base 是；Large/Giant/Nested 是 CC-BY-NC，不用）。
 * 官方预处理与旧 DA2 完全一致：resize 到 14 倍数 → /255 → ImageNet mean/std（gamma 空间）。
 *
 * ⚠️ **别把依赖换回 `onnxruntime-mobile`**：mobile 的 so 缺 `ConvInteger`（动态量化模型），
 * 而 DA3-Base 用的 `MatMulNBits` 也只有完整版带全 kernel（2026-10-22 实测教训 + 本次换代沿用）。
 *
 * ## 定死的参数（2026-11-02 更新）
 *
 * * 推理短边从 `SHORT_SIDES = [768, 896, 1024]` 里选，**默认 768**，对齐到 14 的倍数；
 * * **输出尺寸 = 所选档位对齐后的尺寸，不放大** —— 深度是低频图，放大会插值出假细节；
 * * 单张 **120 秒预算**（2026-11 用户定案，替换原 10 秒上限；DA3-Base@768 预计 20~25s），
 *   超预算/质量不达标 = 直接失败，**不做降级**（代码里没有超时看门狗，仅是约定）。
 *
 * ## 缓存
 *
 * `files/spatial/<sha256前16位>/depth.png`（灰度，**越亮 = 越近**，min-max 归一化到 0~255）
 * + 同目录 `meta.json`。缓存键 = **图片内容 sha256 前 16 位**；命中还必须
 * **`meta.model` 与 `meta.shortSide` 都对得上**（换模型/换档位自动重算，DA2 旧缓存作废）。
 *
 * ## 三个必须照做的坑（见《07-坑点台账.md》§7.13）
 *
 * 1. **模型不能 `assets.open().readBytes()` 建 session**：ORT 内部再复制一份 ⇒ 峰值翻倍，
 *    低内存设备上 Activity 被回收（现象是「点按钮后自动退回功能页」，不是崩溃）。
 *    这里先把资产**流式拷到 `files/models/`**，再用路径 `createSession(path)`。
 * 2. **`ai.onnxruntime.**` 必须整包 keep**（`proguard-rules.pro`），native 层按类名反射，
 *    改名就 `SIGABRT`。
 * 3. **线程数**：按 `min(6, availableProcessors)`（8 Gen 3 全开 8 线程会抖到小核，更慢）。
 */
internal object SpatialDepth {

    private const val TAG = "SPATIAL_DEPTH"

    /**
     * 可选模型（key = UI/偏好的取值，value = asset 路径）。**顺序即 UI 下拉顺序**，`base` 在前 = 默认。
     * 必须与 `MaskPrefs.SPATIAL_MODEL_CHOICES` 一致。
     */
    val MODEL_ASSETS: Map<String, String> = linkedMapOf(
        "base" to "models/da3_base_int8.onnx",
        "small" to "models/da3_small_int8.onnx",
    )

    /** 模型 key → 文件名（缓存 meta 校验、日志用）。 */
    private val MODEL_NAMES: Map<String, String> = MODEL_ASSETS.mapValues { (_, asset) ->
        asset.substringAfterLast('/')
    }

    /**
     * DA3 输出方向翻转：官方 `predicted_depth` 是**距离**（越大越远），而本管线约定
     * 灰度**越亮 = 越近** ⇒ 需要翻转。
     *
     * 由 `tools/check_da3_model.mjs` 离线验证（2026-11-02，1280×853 风景照 @756×1148）：
     * 原向上 30% 行（天空）平均 128 vs 下 30%（地面）32 ⇒ 原向是「远=亮」，
     * 翻转后地面 223 > 天空 127，符合「越亮=越近」。两个模型（Small q8 / Base int8）
     * 结论一致。若上游模型方向变化，改这一个常量即可。
     */
    private const val INVERT_DEPTH = true

    /**
     * 可选档位（推理短边，像素）。**必须与 `MaskPrefs.SPATIAL_SHORT_CHOICES` 一致**。
     *
     * 下限 768：518 那档发丝会断线；上限 1024。旧 DA2 实测数字已随换代作废，
     * DA3 在 120 秒预算（2026-11 定案）下三档都放得下，具体耗时以真机为准。
     */
    val SHORT_SIDES = intArrayOf(768, 896, 1024)

    /** ViT 的 patch 尺寸：宽高必须是它的整数倍。 */
    private const val MULT = 14

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * 缓存目录里的**原图副本**（用户选中图的原始字节，不重编码、不缩放）。
     *
     * 2026-10-29 起取代原来的 `preview.webp`（半尺寸 WEBP 88）：二级页的 1:1 预览框
     * 直接解码它 ⇒ 看到的就是用户选的那张图；页面重建后也能靠它把「选中」状态恢复回来，
     * 不会再出现「预览显示着、实际没选中」。选图时写一份，`generate` 里补写一份。
     */
    const val SOURCE_NAME = "source"

    /** 生成结果。[hitCache] = true 时 [costMs] 为 0。 */
    data class Result(val dir: File, val costMs: Long, val hitCache: Boolean)

    private val sessionLock = Any()
    private var session: OrtSession? = null

    /** 当前会话对应的模型 key（切模型时先 close 旧会话，释放 123MB native 权重）。 */
    private var sessionModel: String? = null

    /** 上一次建会话用的后端（仅诊断：每次生成都会打印，别再靠"首次创建"那行日志）。 */
    private var sessionNnapi = false
    private var sessionIntra = 0

    /** 深度缓存根目录。 */
    fun cacheRoot(ctx: Context): File = File(ctx.filesDir, "spatial")

    /** 图片内容哈希（缓存键）。UI 用它判断「这张图有没有缓存」。 */
    fun cacheKey(ctx: Context, uri: Uri): String = sha16(ctx, uri)

    /**
     * 该图是否已有**与当前参数匹配**的深度缓存：有则返回缓存键，没有返回 null。
     *
     * 命中条件 = `depth.png`/`meta.json` 存在 **且** `meta.model` 以当前模型文件名开头
     * **且** `meta.shortSide` 等于目标档位 —— 换模型、换档位、DA2 旧缓存都会走重算。
     *
     * ⚠️ 会**完整读一遍图片算 sha256**（几十到几百毫秒），调用方放 IO 线程。
     */
    fun hasCache(ctx: Context, uri: Uri, shortSide: Int, model: String): String? {
        val key = sha16(ctx, uri)
        val dir = File(cacheRoot(ctx), key)
        if (!File(dir, "depth.png").isFile || !File(dir, "meta.json").isFile) return null
        return if (metaMatches(File(dir, "meta.json"), model, shortSide)) key else null
    }

    /** meta 是否与本次请求的模型/档位一致（读失败一律视为不命中 → 重算）。 */
    private fun metaMatches(metaFile: File, model: String, shortSide: Int): Boolean {
        val fileName = MODEL_NAMES[model] ?: return false
        return runCatching {
            val meta = JSONObject(metaFile.readText())
            meta.optString("model").startsWith(fileName) && meta.optInt("shortSide") == shortSide
        }.getOrDefault(false)
    }

    /** 缓存目录里的原图文件（可能不存在）。 */
    fun sourceFile(depthDir: File): File = File(depthDir, SOURCE_NAME)

    /**
     * 确保 `<dir>/source` 存在：把 [uri] 的**原始字节**拷进去（不重编码、不缩放）。
     *
     * 两个调用点：
     * * 用户选完图 —— 立刻写一份，这样即使还没生成深度，重进页面预览框也能恢复；
     * * [generate] 开头 —— 补写（旧缓存 / 上次复制失败）。
     *
     * 失败只记日志、返回 null（不影响深度生成）。
     */
    fun ensureSource(ctx: Context, uri: Uri, dir: File): File? {
        val out = File(dir, SOURCE_NAME)
        if (out.isFile && out.length() > 0L) return out
        return runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$SOURCE_NAME.tmp")
            (ctx.contentResolver.openInputStream(uri)
                ?: error("openInputStream 返回 null（uri=$uri）")).use { ins ->
                FileOutputStream(tmp).use { fos -> ins.copyTo(fos, 1 shl 16) }
            }
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            out
        }.onFailure {
            Log.w(TAG, "原图副本写入失败：${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    /**
     * 解码预览图：按短边 [maxShort] 做 `inSampleSize` 采样。
     *
     * 不采样的话 3000×4000 的图一解码就是 48MB，Compose 再上传一次纹理 —— 二级页会卡死
     * （`SpatialWallpaperPage` 里那条「必须 remember(preview)」的注释说的就是同一个坑）。
     */
    fun decodePreview(file: File, maxShort: Int = 1200): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        var sample = 1
        while (min(sw, sh) / (sample * 2) >= maxShort) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.onFailure {
        Log.w(TAG, "预览解码失败：${it.message}")
    }.getOrNull()

    /** 从 uri 直接解码预览（`source` 还没复制好时的兜底；同样按短边采样）。 */
    fun decodePreview(ctx: Context, uri: Uri, maxShort: Int = 1200): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (ctx.contentResolver.openInputStream(uri) ?: return null).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        var sample = 1
        while (min(sw, sh) / (sample * 2) >= maxShort) sample *= 2
        (ctx.contentResolver.openInputStream(uri) ?: return null).use {
            BitmapFactory.decodeStream(
                it, null, BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }
    }.onFailure {
        Log.w(TAG, "预览解码失败（uri）：${it.message}")
    }.getOrNull()

    /** 清空全部深度缓存，返回删掉的条目数。 */    fun clearAll(ctx: Context): Int =
        cacheRoot(ctx).listFiles()?.count { it.deleteRecursively() } ?: 0

    /**
     * 生成（或命中）深度缓存。**同步阻塞**，调用方负责放到 IO 线程。
     *
     * @param shortSide 推理短边（取 [SHORT_SIDES] 里的一档）。**深度图的短边就是这个值**
     *   （对齐到 14 的倍数，例如 768 → 756），**不再统一放大到长边 1920**。
     * @param model 模型 key（[MODEL_ASSETS] 的键，非法值回落 `base`）。
     * @param force 为 true 时**忽略已有缓存**、重算并覆盖（UI 的「10 秒内再点一次」走这条）。
     *
     * 任何失败都抛异常 —— 上层统一按「处理失败」处理，不做降级。
     */
    fun generate(
        ctx: Context,
        uri: Uri,
        shortSide: Int = 768,
        model: String = "base",
        force: Boolean = false,
    ): Result {
        val target = shortSide.takeIf { it in SHORT_SIDES } ?: 768
        val modelKey = model.takeIf { it in MODEL_ASSETS } ?: "base"
        val fileName = MODEL_NAMES.getValue(modelKey)
        Log.i(
            TAG,
            "generate 开始：uri=$uri 短边=$target 模型=$fileName force=$force  mem=${memStat()}",
        )
        val key = sha16(ctx, uri)
        val dir = File(cacheRoot(ctx), key)
        val depthFile = File(dir, "depth.png")
        val metaFile = File(dir, "meta.json")
        // 原图副本：缓存命中也补写一次（旧缓存只有 preview.webp，用户要求换成原图）
        ensureSource(ctx, uri, dir)
        // 命中必须模型+档位都对（换模型/换档位/DA2 旧缓存 → 自动重算）
        if (!force && depthFile.isFile && metaFile.isFile && metaMatches(metaFile, modelKey, target)) {
            Log.i(TAG, "缓存命中 $key（$fileName @$target）")
            return Result(dir, 0L, true)
        }

        // 起始记录落 run_log（2026-11-02）：logcat 里的 SPATIAL 日志有时抓不到（原因未明），
        // 而进程若在推理中被 lmkd 杀掉，文件里会留下这条「开始」而没有「完成」⇒ 能定位崩溃阶段。
        RunLog.append(
            ctx,
            "空间壁纸 · 推理开始",
            listOf("模型：$fileName", "短边：$target", "force=$force", "键：$key"),
        )

        val t0 = SystemClock.elapsedRealtime()

        // 一、解码（先按 inSampleSize 粗降采样，避免 3000×4000 原图直接占 48MB）
        val src = decodeScaled(ctx, uri, target)
        val srcW = src.width
        val srcH = src.height

        // 二、算推理尺寸：短边 = 档位值，宽高对齐 14（768 → 756、896 → 882、1024 → 1008）
        val scale = target.toFloat() / min(srcW, srcH)
        var iw = max(MULT, (srcW * scale).roundToInt() / MULT * MULT)
        var ih = max(MULT, (srcH * scale).roundToInt() / MULT * MULT)

        val infer = if (iw == srcW && ih == srcH) src else {
            // 双立方 + 抗混叠（PIL 口径），替代无低通的 `createScaledBitmap`。
            // DA 对锐度不敏感（patch14 化后三种插值的差异只剩 16%），这里跟着换只是
            // 为了和层图共用一套算法；实测深度 RMSE 2.97→1.15、bodyMask IoU 0.9904→0.9976。
            SpatialResample.bitmap(src, iw, ih, cubic = true)
        }

        // 三、转 NCHW（ImageNet 归一化）
        val n = iw * ih
        val buf = obtainBuf(n)
        val pixels = buf.px
        infer.getPixels(pixels, 0, iw, 0, 0, iw, ih)
        infer.recycle()
        val chw = buf.chw
        if (chw.size != 3 * n) {  // 尺寸变了（池未重建的极端情况）
            error("NCHW 缓冲尺寸不符：${chw.size} != ${3 * n}")
        }
        for (i in 0 until n) {
            val c = pixels[i]
            chw[i] = (((c shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            chw[n + i] = (((c shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            chw[2 * n + i] = ((c and 0xFF) / 255f - MEAN[2]) / STD[2]
        }

        // 四、推理
        //
        // ⚠️ DA3 是**多视图导出**：`pixel_values` 是 `[N, S, C, H, W]`（batch × 视图数 × 通道），
        // 单图 = S=1。**第 4 维是 H、第 5 维是 W**，而 `iw` 是宽、`ih` 是高；
        // 曾经 DA2 时代这里写成 `[1, 3, iw, ih]`（宽高颠倒）就整幅糊掉过（2026-10-22）。
        val env = OrtEnvironment.getEnvironment()
        val sess = ensureSession(ctx, modelKey)
        // **每次生成都打**：不要靠「首次建会话」那一行（进程复用时会话已存在就直接跳过了）
        Log.i(
            TAG,
            "session: model=$fileName nnapi=$sessionNnapi intra=$sessionIntra " +
                "cores=${Runtime.getRuntime().availableProcessors()}",
        )
        // DA3 只有一个输入 `pixel_values`（`[N,S,3,H,W]` 多视图，单图 S=1）；
        // 输出有 `predicted_depth` + `confidence` + `extrinsics`/`intrinsics`（后两者忽略），
        // **必须按名取**（输出顺序不保证），形状见下面的 rank 分支。
        check(sess.inputNames.size == 1) { "期望单输入，实际：${sess.inputNames}" }
        val inputName = sess.inputNames.first()
        val depth: FloatArray
        var ow: Int
        var oh: Int
        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(chw),
            longArrayOf(1, 1, 3, ih.toLong(), iw.toLong()),
        ).use { input ->
            sess.run(mapOf(inputName to input)).use { out ->
                // ORT 1.31 的 get(String) 返回 Optional<OnnxValue>，得安全转成 OnnxTensor
                // 后才能拿 floatBuffer / info（直接用会 Unresolved，2026-11-02 CI 实测）。
                val tensor = (out.get("predicted_depth").orElse(null) as? OnnxTensor)
                    ?: (out[0] as OnnxTensor)
                val info = tensor.info as TensorInfo
                // DA3 导出是 rank-4 `[1,1,H,W]`；旧 DA2 是 rank-3 `[1,H,W]`。两种都认。
                when (info.shape.size) {
                    4 -> {
                        oh = info.shape[2].toInt()
                        ow = info.shape[3].toInt()
                    }
                    3 -> {
                        oh = info.shape[1].toInt()
                        ow = info.shape[2].toInt()
                    }
                    else -> error("推理输出维度异常：${info.shape.contentToString()}")
                }
                check(ow > 0 && oh > 0) { "推理输出尺寸异常：${ow}×$oh" }
                Log.i(TAG, "推理输出 ${ow}×$oh（输入 ${iw}×$ih）")
                val fb = tensor.floatBuffer
                depth = if (ow * oh == buf.n) buf.depth else FloatArray(ow * oh)
                fb.get(depth)
            }
        }

        // 五、min-max 归一化 → 灰度（越亮 = 越近）
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in depth) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val range = (hi - lo).takeIf { it > 1e-6f } ?: 1f
        // 灰度复用 px（NCHW 已经转完，那块内存不再需要）
        val gray = if (ow * oh == buf.n) buf.px else IntArray(ow * oh)
        for (i in gray.indices) {
            // INVERT_DEPTH：DA3 的 predicted_depth 是距离（越大越远），翻转成「越亮=越近」
            val norm = (depth[i] - lo) / range
            val near = if (INVERT_DEPTH) 1f - norm else norm
            val g = (near * 255f).roundToInt().coerceIn(0, 255)
            gray[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(gray, 0, ow, 0, 0, ow, oh)

        // 六、直接存盘：**深度图短边就是所选档位对齐后的值，不再放大到 1920**
        // （2026-10-22 用户定案「短边就是所选值」，避免 512 档被插值成 1920 的假细节）。
        val outW = ow
        val outH = oh
        dir.mkdirs()
        FileOutputStream(depthFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()

        val costMs = SystemClock.elapsedRealtime() - t0
        val meta = JSONObject()
            .put("srcW", srcW)
            .put("srcH", srcH)
            .put("shortSide", target)
            .put("inferW", iw)
            .put("inferH", ih)
            .put("outW", outW)
            .put("outH", outH)
            .put("costMs", costMs)
            .put("depthMin", lo.toDouble())
            .put("depthMax", hi.toDouble())
            .put("model", "$fileName (int8, ort-android 1.31.0)")
            .put("createdAt", System.currentTimeMillis())
        metaFile.writeText(meta.toString())

        Log.i(
            TAG,
            "生成完成 key=$key 源 ${srcW}×$srcH 推理 ${iw}×$ih 输出 ${outW}×$outH " +
                "耗时 ${costMs}ms  mem=${memStat()}",
        )
        return Result(dir, costMs, false)
    }

    /**
     * **大数组复用池**（和 `SpatialLayers.Buf` 同一个思路）。
     *
     * 这里的三块是 Java 堆里最大的：`chw` 是 3n 个 float（1024 档 n≈1.59M ⇒ **19MB**），
     * `px`/`depth` 各 6.4MB。ART 把它们放进 **Large Object Space**（单独 mmap、
     * 不压缩、空页不还给系统），每次生成重新分配 ⇒ 碎片 + full GC。
     *
     * `px` 在转成 `chw` 之后就没用了，所以 `gray` 直接复用 `px`（省一块 6.4MB）。
     */
    private class DepthBuf(val n: Int) {
        /** 解码后的 ARGB → 转完 NCHW 后复用为【灰度 PNG 的 IntArray】。 */
        val px = IntArray(n)

        /** NCHW 输入（3n 个 float）。 */
        val chw = FloatArray(n * 3)

        /** 模型输出（与输入同尺寸时直接复用）。 */
        val depth = FloatArray(n)
    }

    private var pool: DepthBuf? = null

    /**
     * 丢掉复用池（2026-10-31）。
     *
     * 深度图尺寸 = 层图尺寸 ⇒ 这 20n（1022×2268 ⇒ 44.2MB）和 `SpatialLayers.Buf`
     * 是**同一张图的两份工作集**。深度跑完就还回去，分层阶段才腾得出空间
     * （原来两份常驻 168MB，分层再要一块就撞 256MB 上限，见 run_log 的 OOM）。
     */
    fun dropPool() {
        synchronized(this) { pool = null }
    }

    private fun obtainBuf(n: Int): DepthBuf = synchronized(this) {
        var cur: DepthBuf? = pool
        if (cur != null && cur.n == n) {
            cur
        } else {
            // 同 `SpatialLayers.obtainBuf`：先断引用（含局部变量）再建，避免两份叠加。
            cur = null
            pool = null
            System.gc()
            DepthBuf(n).also { pool = it }
        }
    }

    /**
     * 内存快照（诊断用）：java=已用/上限、native=已分配/堆大小、pool=复用池常驻。
     *
     * 判读方法：
     * - **native 涨、java 平** ⇒ Bitmap 像素 + PatchMatch（两者都在 native heap）；
     * - **java 每次都涨到同一高度再回落** ⇒ 还有没池化的大数组；
     * - **java 的「已用」在一次生成后不回落** ⇒ 就是复用池本身在常驻（预期行为）。
     */
    private fun memStat(): String {
        val rt = Runtime.getRuntime()
        val javaUsed = (rt.totalMemory() - rt.freeMemory()) shr 20
        val javaMax = rt.maxMemory() shr 20
        val nativeUsed = android.os.Debug.getNativeHeapAllocatedSize() shr 20
        val nativeSize = android.os.Debug.getNativeHeapSize() shr 20
        val poolMb = (pool?.let { it.n * 4L * 5 } ?: 0L) shr 20
        return "java=${javaUsed}/${javaMax}MB native=${nativeUsed}/${nativeSize}MB pool=${poolMb}MB"
    }

    /**
     * 懒加载 session：模型加载 ~1s，重复生成时复用。
     *
     * [model] 与当前会话不一致时**先 close 旧会话再建新的** —— DA3-Base 权重 123MB，
     * 常驻两份会把低内存设备打出局。
     */
    private fun ensureSession(ctx: Context, model: String): OrtSession = synchronized(sessionLock) {
        if (session != null && sessionModel != model) {
            runCatching { session?.close() }
                .onFailure { Log.w(TAG, "旧会话关闭失败：${it.message}") }
            session = null
            sessionModel = null
            Log.i(TAG, "模型切换，旧会话已释放（新模型=$model）")
        }
        session ?: run {
            val fileName = MODEL_NAMES.getValue(model)
            val modelFile = ensureModelFile(ctx, MODEL_ASSETS.getValue(model), fileName)
            val env = OrtEnvironment.getEnvironment()
            val cores = Runtime.getRuntime().availableProcessors()
            // 8 Gen 3 是 1+3+4。开满 8 线程时调度器会把一部分线程丢到小核，抖动大、总体更慢；
            // 甜点通常在 4~6（2026-10-23 用户提出调调度，先按 6 试）。
            val intraCpu = minOf(6, cores)

            fun build(nnapi: Boolean, intra: Int): OrtSession {
                val opts = OrtSession.SessionOptions().apply {
                    if (nnapi) addNnapi()
                    setIntraOpNumThreads(intra)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    // **关掉 CPU Arena**（2026-10-26 实测定位，DA3 换代后同样保留）。
                    //
                    // DA 系的 DPT 解码器最后几层在**全分辨率**上融合特征：
                    //   1022×1554 × 256ch × 4B ≈ 1.6GB，× 128ch ≈ 0.8GB
                    // 实测 `dumpsys meminfo` 的 Native Heap 从 32MB 涨到 **2016MB**（8 秒推理），
                    // 而 ORT 的 CPU Arena **在推理结束后不把内存还给系统**（session 常驻，
                    // arena 就常驻）⇒ 2GB 一直占着 ⇒ 系统换页 ⇒ 卡顿（用户反馈「生成完不缓解」）。
                    //
                    // 关掉 arena 后大块分配直接走 mmap，free 时 munmap 归还。
                    // 代价：每次推理多几次 malloc（实测影响很小，见日志耗时）。
                    setCPUArenaAllocator(false)
                }
                return env.createSession(modelFile.absolutePath, opts)
            }

            // NNAPI 已关闭（2026-10-23 实测：nnapi=true 但耗时没有收益，白绕一层）
            val nnapi = false
            val sess = build(false, intraCpu)
            sessionNnapi = nnapi
            sessionIntra = intraCpu
            sess.also {
                session = it
                sessionModel = model
                Log.i(
                    TAG,
                    "session 就绪：model=$fileName nnapi=$nnapi " +
                        "intra=${if (nnapi) 4 else intraCpu}/$cores " +
                        "opt=${OrtSession.SessionOptions.OptLevel.ALL_OPT}",
                )
            }
        }
    }

    /**
     * 把模型流式拷到 `files/models/`（**不要 readBytes**，会双份内存）。
     *
     * 用 `<name>.ok` 记录上次拷贝的字节数：长度对不上就重拷，避免模型换版本后读到旧文件。
     * （内容完整性由构建期的 SHA-256 校验保证，这里只管落盘。）
     */
    private fun ensureModelFile(ctx: Context, assetPath: String, fileName: String): File {
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        val dst = File(dir, fileName)
        val okMark = File(dir, "$fileName.ok")
        if (dst.isFile && okMark.isFile && okMark.readText().trim().toLongOrNull() == dst.length()) {
            return dst
        }
        val tmp = File(dir, "$fileName.tmp")
        ctx.assets.open(assetPath).use { ins ->
            FileOutputStream(tmp).use { outs -> ins.copyTo(outs) }
        }
        if (dst.exists()) dst.delete()
        check(tmp.renameTo(dst)) { "模型落盘失败：${dst.absolutePath}" }
        okMark.writeText(dst.length().toString())
        Log.i(TAG, "模型已拷到 ${dst.absolutePath}（${dst.length()} 字节）")
        return dst
    }

    /** 图片内容 sha256 前 16 位（16 个 hex 字符）。 */
    private fun sha16(ctx: Context, uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = ctx.contentResolver.openInputStream(uri)
            ?: error("无法打开图片（openInputStream 返回 null，uri=$uri）")
        input.use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val read = ins.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        val hex = StringBuilder(64)
        for (b in md.digest()) hex.append("%02x".format(b))
        return hex.substring(0, 16)
    }

    /** 解码并按 [wantShort] 做整数倍降采样（`inSampleSize` 只认 2 的幂）。 */
    private fun decodeScaled(ctx: Context, uri: Uri, wantShort: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = ctx.contentResolver.openInputStream(uri)
            ?: error("无法打开图片（bounds openInputStream 返回 null，uri=$uri）")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        Log.i(TAG, "decode: uri=$uri 原图 ${bounds.outWidth}×${bounds.outHeight} 目标短边=$wantShort")
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片解码失败" }
        // 同 `SpatialLayers.decodeToSize`：跳采样只控内存（不做低通，本身就是混叠源），
        // 精确缩放交给 `SpatialResample`（有抗混叠）。`wantShort` 只用于日志对照。
        val sample = SpatialResample.decodeSample(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("图片解码失败（openInputStream 返回 null，uri=$uri）")
    }
}
