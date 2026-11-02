package com.frederic22.hyperextreme.spatial

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.frederic22.hyperextreme.data.RunLog
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 缓存包导入（2026-11-02 用户方案：**深度在 PC 上算**，手机只导入结果）。
 *
 * 背景：DA3-Base 在手机上推理的原生内存峰值实测 5~7GB（DPT 解码器全分辨率宽通道
 * 融合），必被 MIUI lmkd 杀掉（闪退）。PC 算完打成 zip 推到手机导入，彻底绕开尖峰。
 *
 * 包格式（`tools/export_spatial_cache.mjs` 产出，**平铺三件套、无子目录**）：
 * * `source`     —— 图片原始字节（键的来源，也是预览/恢复用的原图副本）
 * * `depth.png`  —— 灰度深度图，越亮 = 越近
 * * `meta.json`  —— 与 App 同 schema（model 以模型文件名开头、shortSide 合法档位）
 *
 * 校验（任何一条不过都拒绝安装）：
 * 1. 条目名只能是上面三个（白名单 ⇒ 天然免疫 zip slip）；
 * 2. 各条目大小上限（source/depth ≤64MB、meta ≤64KB、整包 ≤400MB）；
 * 3. `meta.model` 必须以某个 [SpatialDepth.MODEL_ASSETS] 文件名开头、
 *    `meta.shortSide` ∈ [SpatialDepth.SHORT_SIDES]；
 * 4. 缓存键 = sha256(source) 前 16 位（用 [SpatialDepth.cacheKey] 复算，与选图同一套）；
 * 5. `depth.png` 解码尺寸 == `meta.outW/outH`。
 *
 * 安装 = 整目录落到 `files/spatial/<key>/`（已存在则整目录覆盖）。
 * 层图不在包内：导入后点「生成深度缓存」会命中深度缓存、**只补算层图**（1~2 秒，
 * 走现有分支），再走「立即替换」。
 */
object SpatialImport {

    private const val TAG = "SPATIAL_IMPORT"

    private const val ENTRY_SOURCE = "source"
    private const val ENTRY_DEPTH = "depth.png"
    private const val ENTRY_META = "meta.json"
    private val ALLOWED = setOf(ENTRY_SOURCE, ENTRY_DEPTH, ENTRY_META)

    private const val MAX_ZIP_BYTES = 400L * 1024 * 1024
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val MAX_META_BYTES = 64L * 1024

    /**
     * 安装缓存包，返回缓存键。任何校验失败抛异常（message 为中文说明，直接给 Toast）。
     * **同步阻塞**，调用方放 IO 线程。
     */
    fun install(ctx: Context, uri: Uri): String {
        val work = File(ctx.cacheDir, "spatial-import").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            // 一、把 zip 流式拷进应用私有目录（顺带计数防炸弹）
            val zipFile = File(work, "package.zip")
            var zipBytes = 0L
            (ctx.contentResolver.openInputStream(uri)
                ?: error("无法打开所选文件")).use { ins ->
                FileOutputStream(zipFile).use { outs ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val r = ins.read(buf)
                        if (r <= 0) break
                        zipBytes += r
                        check(zipBytes <= MAX_ZIP_BYTES) { "压缩包过大（>${MAX_ZIP_BYTES / 1024 / 1024}MB）" }
                        outs.write(buf, 0, r)
                    }
                }
            }

            // 二、解包：白名单条目 + 各自大小上限（白名单 ⇒ 条目名无路径分隔符，无 zip slip）
            val found = mutableSetOf<String>()
            java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    check(name in ALLOWED) { "包内含未知文件：$name（只接受 source / depth.png / meta.json）" }
                    check(found.add(name)) { "包内重复条目：$name" }
                    val cap = when (name) {
                        ENTRY_META -> MAX_META_BYTES
                        else -> MAX_FILE_BYTES
                    }
                    val dst = File(work, name)
                    var n = 0L
                    FileOutputStream(dst).use { outs ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val r = zin.read(buf)
                            if (r <= 0) break
                            n += r
                            check(n <= cap) { "$name 过大（>${cap / 1024 / 1024}MB）" }
                            outs.write(buf, 0, r)
                        }
                    }
                    zin.closeEntry()
                }
            }
            val missing = ALLOWED - found
            check(missing.isEmpty()) { "压缩包缺少文件：${missing.joinToString()}" }

            // 三、meta 校验：模型与档位必须是当前 App 认的
            // （meta.model 以**裸文件名**开头 —— 与 SpatialDepth.metaMatches 同口径；
            //   不能用 MODEL_ASSETS.values，那是带 models/ 前缀的 asset 路径）
            val meta = JSONObject(File(work, ENTRY_META).readText())
            val model = meta.optString("model")
            check(SpatialDepth.MODEL_NAMES.values.any { model.startsWith(it) }) {
                "包内模型不受支持：$model"
            }
            val shortSide = meta.optInt("shortSide", -1)
            check(shortSide in SpatialDepth.SHORT_SIDES) { "包内档位非法：$shortSide" }
            val outW = meta.optInt("outW", 0)
            val outH = meta.optInt("outH", 0)
            check(outW > 0 && outH > 0) { "meta.outW/outH 非法" }

            // 四、键：与选图完全同一套（sha256(source) 前 16 位）
            val key = SpatialDepth.cacheKey(ctx, Uri.fromFile(File(work, ENTRY_SOURCE)))
            check(key.length == 16) { "缓存键计算异常" }

            // 五、深度图能解码且尺寸与 meta 对得上
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(File(work, ENTRY_DEPTH).absolutePath, bounds)
            check(bounds.outWidth == outW && bounds.outHeight == outH) {
                "深度图尺寸 ${bounds.outWidth}×${bounds.outHeight} 与 meta ${outW}×$outH 不符（包损坏？）"
            }

            // 六、整目录落位（覆盖旧缓存；层图目录随旧目录一起清掉 ⇒ 会触发补算）
            val target = File(SpatialDepth.cacheRoot(ctx), key)
            if (target.exists()) target.deleteRecursively()
            target.mkdirs()
            for (name in ALLOWED) {
                val src = File(work, name)
                val dst = File(target, name)
                if (!src.renameTo(dst)) {
                    src.copyTo(dst, overwrite = true)
                }
            }
            Log.i(TAG, "导入完成 key=$key $model @$shortSide ${outW}×$outH zip=${zipBytes}B")
            return key
        } finally {
            work.deleteRecursively()
        }
    }

    /** 导入结果写「执行结果」日志（成功/失败都记）。 */
    fun logResult(ctx: Context, ok: Boolean, lines: List<String>) {
        RunLog.append(ctx, "空间壁纸 · 导入缓存包", lines, ok = ok)
    }
}
