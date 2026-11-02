package com.frederic22.hyperextreme.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 模块设置的唯一来源。hook 侧不直接读这个类，而是通过 [MaskProvider] 的 `call("config")`
 * 拿到同一份数据的快照（跨进程读不了对方的 SharedPreferences）。
 *
 * 所有写入都用 `commit()`：必须保证用户点完开关、紧接着打开编辑器时数据已经落盘。
 */
class MaskPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    val revision: Long
        get() = prefs.getLong(KEY_REVISION, 0L)

    val verboseLog: Boolean
        get() = prefs.getBoolean(KEY_VERBOSE, false)

    /**
     * 设置页是否显示「自检」与「最近操作」两块（默认关）。
     *
     * 这两块都是排查用的，平时用不到、开着界面会长一截，所以塞进「测试」分组里由用户按需打开。
     * 与 [verboseLog] 是两回事：那个控制 hook 往 LSPosed 日志里写多少，这个只管界面显示。
     */
    val showDiagnostics: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DIAGNOSTICS, false)

    /**
     * 解除「主体遮挡时间过多」判定（默认关）。
     *
     * **编辑器和锁屏是两套判定，都要覆盖**（2026-10-01 定案）：
     *
     * | 在哪 | 判定 | 我们怎么处理 |
     * | --- | --- | --- |
     * | 编辑器 `com.miui.aod:keyguardeditor` | `CalculateWorker`（率 0.2）+ 双时钟硬闸门 | hook `HierarchyImageAvoidController#onHierarchyEnableChange` 强制通过 |
     * | **锁屏 `com.android.systemui`** | `DepthAvoidEvaluator.IMAGE_THRESHOLD = Threshold(0.2)` | 把阈值本身抬到 **1.0**（构造函数 hook + 反射改现成对象），顺带在 `KeyguardDepthInteractor` 上补刀 |
     *
     * 只作用于**图像**景深：视频那一档是另一个 `Threshold` 实例（0.5），一概不动。
     */
    val depthOcclusionRemoved: Boolean
        get() = prefs.getBoolean(KEY_DEPTH_OCCLUSION, false)

    /**
     * 空间壁纸「缓存推理分辨率」（推理短边，像素），默认 [SPATIAL_SHORT_DEFAULT]。
     *
     * **只影响 `files/spatial/` 的深度图生成**，与视差链没有任何关系。
     * 合法档位见 `spatial/SpatialDepth.SHORT_SIDES`（768 / 896 / 1024）——
     * 数值越大深度图越细，但推理越慢（DA3-Base@768 预计 20~25s，单张 120 秒预算，2026-11 定案）。
     * 读到的值不在合法档位里时按默认值处理，避免旧数据把界面搞崩。
     */
    val spatialShortSide: Int
        get() = prefs.getInt(KEY_SPATIAL_SHORT_SIDE, SPATIAL_SHORT_DEFAULT)
            .takeIf { it in SPATIAL_SHORT_CHOICES } ?: SPATIAL_SHORT_DEFAULT

    fun setSpatialShortSide(value: Int) {
        prefs.edit().putInt(KEY_SPATIAL_SHORT_SIDE, value).commit()
    }

    /**
     * 空间壁纸「推理模型」（`base` / `small`），默认 [SPATIAL_MODEL_DEFAULT]。
     *
     * 合法取值见 `spatial/SpatialDepth.MODEL_ASSETS`（`base` = DA3-Base 质量优先，
     * `small` = DA3-Small 速度优先）。换模型后旧深度缓存按 `meta.model` 自动失效重算。
     */
    val spatialModel: String
        get() = prefs.getString(KEY_SPATIAL_MODEL, SPATIAL_MODEL_DEFAULT)
            ?.takeIf { it in SPATIAL_MODEL_CHOICES } ?: SPATIAL_MODEL_DEFAULT

    fun setSpatialModel(value: String) {
        prefs.edit().putString(KEY_SPATIAL_MODEL, value).commit()
    }

    /**
     * 空间壁纸「生效范围」，默认 [SPATIAL_SCOPE_LOCK]（锁屏）。
     *
     * | 值 | 含义 |
     * |---|---|
     * | 0 [SPATIAL_SCOPE_LOCK] | 锁屏 —— 挂 `getWallpaperByShowWhenLocked(0, true)` |
     * | 1 [SPATIAL_SCOPE_DESKTOP] | 桌面 —— 挂 `(0, false)`（2026-10-28 之前的唯一行为） |
     * | 2 [SPATIAL_SCOPE_GLOBAL] | 全局 —— 两条 leash 各挂一份，并启用无缝衔接 |
     *
     * **两条壁纸 leash 完全独立**（2026-10-28 真机探针：挂锁屏那条时锁屏可见、桌面不可见）。
     * 与视差的 `KEY_PARALLAX_COVER_DESKTOP` **完全独立**：两套开关各读各的。
     */
    val spatialScope: Int
        get() = prefs.getInt(KEY_SPATIAL_SCOPE, SPATIAL_SCOPE_LOCK)
            .takeIf { it in SPATIAL_SCOPE_CHOICES } ?: SPATIAL_SCOPE_LOCK

    fun setSpatialScope(value: Int) {
        prefs.edit().putInt(KEY_SPATIAL_SCOPE, value).commit()
    }

    /**
     * 空间壁纸「**启用空间壁纸锁屏景深**」（默认关，2026-10-29 用户要求）。
     *
     * 开 = 主体层额外画进 SystemUI 窗口的 `keyguard_foreground_layer`，压在锁屏时钟**之上**
     * ⇒ 时钟被主体遮挡，出现"主体在时钟之前"的景深感。
     *
     * **关 = 渲染逻辑与加这个开关之前逐字一致**（单 SC 画背景 + 主体，
     * `SpatialForegroundHider` 照旧把整层压 INVISIBLE）—— 这条是用户明确定下的，
     * 也是所有后续改动的回退闸。
     *
     * 生效时机与 [spatialScope] **完全一致**：生效中改只写 prefs，下一次「立即替换 /
     * 关闭替换」时随 `SpatialFlag.push` 一起下发 —— 不在渲染中途拆挂载点。
     */
    val spatialLockDepth: Boolean
        get() = prefs.getBoolean(KEY_SPATIAL_LOCK_DEPTH, false)

    fun setSpatialLockDepth(value: Boolean) {
        prefs.edit().putBoolean(KEY_SPATIAL_LOCK_DEPTH, value).commit()
    }

    /**
     * 「本模块未激活」弹窗的「以后不再弹出该弹窗」。
     *
     * `true` = 用户勾选并点过「忽略」⇒ 之后启动体检不再弹这个窗。
     * 没有恢复入口（用户明确定过：他自己勾的）。
     */
    val activationWarnDismissed: Boolean
        get() = prefs.getBoolean(KEY_ACTIVATION_WARN_DISMISSED, false)

    fun setActivationWarnDismissed(value: Boolean) {
        prefs.edit().putBoolean(KEY_ACTIVATION_WARN_DISMISSED, value).commit()
    }

    /**
     * 空间壁纸的**挂载模式**（2026-10-30）：0 = LSPosed（默认），1 = 系统壁纸服务（免 root）。
     *
     * 两种模式**互斥**：同一个时刻只有一条链能渲染。切换由 UI 把关 —— 当前模式还在运行
     * （LSPosed 的 `active=1`，或系统壁纸就是我们）时，下拉不可选，必须先关掉。
     *
     * ⚠️ [spatialScope] / [spatialLockDepth] **与本字段完全独立**：切模式不回写、不重置，
     * 切回 LSPosed 时原来的值还在（用户 2026-10-30 明确要求"值我们仍然保留"）。
     */
    val spatialMountMode: Int
        get() = prefs.getInt(KEY_SPATIAL_MOUNT_MODE, SPATIAL_MOUNT_LSPOSED)
            .takeIf { it in SPATIAL_MOUNT_CHOICES } ?: SPATIAL_MOUNT_LSPOSED

    fun setSpatialMountMode(value: Int) {
        prefs.edit().putInt(KEY_SPATIAL_MOUNT_MODE, value).commit()
    }

    /**
     * 「功能提醒」弹窗的「以后不再弹出该弹窗」。
     *
     * `true` = 用户勾选并确认过 ⇒ 之后再切"系统壁纸服务"直接生效、不再弹窗。
     * 没有"恢复弹窗"的入口（用户 2026-10-30 明确：他自己勾的，就这样）。
     */
    val spatialMountWarned: Boolean
        get() = prefs.getBoolean(KEY_SPATIAL_MOUNT_WARNED, false)

    fun setSpatialMountWarned(value: Boolean) {
        prefs.edit().putBoolean(KEY_SPATIAL_MOUNT_WARNED, value).commit()
    }

    /**
     * 空间壁纸是否生效（SystemUI 侧轮询这个开关来起/停 GL 渲染器）。
     *
     * 与视差的 `parallax_enabled` **完全独立**：两条链各读各的键、各跑各的渲染。
     */
    val spatialActive: Boolean
        get() = prefs.getBoolean(KEY_SPATIAL_ACTIVE, false)

    /** 当前生效的图层缓存键（= 图片 sha256 前 16 位，SystemUI 用它拼 provider uri）。 */
    val spatialKey: String
        get() = prefs.getString(KEY_SPATIAL_KEY, "").orEmpty()

    /** 生效 / 关闭。传 [key] 为 null 表示关闭。 */
    fun setSpatialActive(key: String?) {
        prefs.edit().apply {
            putBoolean(KEY_SPATIAL_ACTIVE, key != null)
            putString(KEY_SPATIAL_KEY, key.orEmpty())
        }.commit()
    }

    /**
     * **最近一次生成成功**的图层缓存键（即使还没生效也记着）。
     *
     * 为什么要持久化：页面里的 `picked` 是 `remember`，一退页面就没了、缓存键也跟着丢，
     * 于是「立即替换」永远判成"没生成过"。把它落盘后，重进页面也能直接替换/关闭。
     */
    val spatialLastKey: String
        get() = prefs.getString(KEY_SPATIAL_LAST_KEY, "").orEmpty()

    fun setSpatialLastKey(key: String?) {
        prefs.edit().putString(KEY_SPATIAL_LAST_KEY, key.orEmpty()).commit()
    }

    /**
     * 最近一次**选中**的图（候选）的缓存键；空串 = 当前没有选中任何图。
     *
     * 「清空选择」清的就是它（以及页面里的预览），**不动** `spatialLastKey` ——
     * 已经生成好的层图仍可以「立即替换」，只是候选框空着。
     */
    val spatialSourceKey: String
        get() = prefs.getString(KEY_SPATIAL_SOURCE_KEY, "").orEmpty()

    fun setSpatialSourceKey(key: String?) {
        prefs.edit().putString(KEY_SPATIAL_SOURCE_KEY, key.orEmpty()).commit()
    }

    /**
     * hook 现在是否还在保护这份注入。
     *
     * 和"有没有注入过"是两件事：注入完成后保护会在一段时间/某个事件后**结束**，
     * 但系统里的文件仍然是替换后的内容，界面还要能显示"已注入"。
     *
     * 老版本升上来时没有这个键 —— 那时"有指纹就等于在保护"，所以按指纹推断，行为不变。
     */
    val protecting: Boolean
        get() = if (prefs.contains(KEY_PROTECTING)) {
            prefs.getBoolean(KEY_PROTECTING, false)
        } else {
            injectedWallpaperMd5.isNotEmpty()
        }

    /**
     * 「替换后自动灭亮屏一次」（替换行为里的开关，**默认关**）。
     *
     * 2026-10-04：注入改成 hook 之后不再依赖灭亮屏（刷新由切 `wallpaper_effect_type_2` 完成），
     * 所以灭亮屏从"固定行为"变成**可选**，配套的「屏幕灭亮提醒」确认弹窗也一并删了。
     */
    val autoScreenFlash: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SCREEN_FLASH, false)

    /**
     * 长按锁屏浮出的「自定义锁屏」按钮，是否要抬到通知 / 播放类控件**之上**（默认开）。
     *
     * 打开后 `com.android.systemui` 侧会在长按菜单出现期间把按钮所在的视图链抬高
     * （见 `hook/LockScreenButton`）；关掉就完全不动 SystemUI。
     * 2026-10-11 起**不再是唯一**作用到 SystemUI 的开关：「强制锁屏元素深浅模式」一族也在
     * SystemUI 侧消费（见 [forceToneMode]）。这个开关本身仍 **默认关**（用户要求）。
     */
    val lockScreenButtonOnTop: Boolean
        get() = prefs.getBoolean(KEY_LOCKSCREEN_BUTTON_TOP, false)

    fun setLockScreenButtonOnTop(value: Boolean) {
        prefs.edit().putBoolean(KEY_LOCKSCREEN_BUTTON_TOP, value).commit()
    }

    /**
     * 「打开多任务和文件夹时隐藏状态栏」（2026-10-16 加，v1.3.0 起覆盖文件夹，**默认关**）。
     *
     * 打开后：多任务页 / 文件夹打开时隐藏状态栏"内容"（时钟 / 系统图标 / 通知图标），回到桌面或打开应用即还原；
     * 下拉（通知面板）不受影响。消费方是 `com.android.systemui` 进程里的 `hook/RecentsStatusBar`
     * （触发信号 = 桌面经 `LauncherProxyService` 通知的 overview shown / hidden）。
     */
    val hideStatusBarInRecents: Boolean
        get() = prefs.getBoolean(KEY_HIDE_STATUSBAR_IN_RECENTS, false)

    fun setHideStatusBarInRecents(value: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_STATUSBAR_IN_RECENTS, value).commit()
    }

    /**
     * 「强制锁屏元素深浅模式」主开关（2026-10-11 加，**默认关**）。
     *
     * 打开后，下面 6 个锁屏元素的「深浅档」不再看系统判定，而是由各自的 `forceTone*` 值决定：
     * 0 = 保持系统判定、1 = 强制浅色、2 = 强制深色（`FORCE_TONE_*`）。
     * 消费方是 `com.android.systemui` 进程里的深浅注入（hook），读取走 `MaskProvider.call("config")`。
     */
    val forceToneMode: Boolean
        get() = prefs.getBoolean(KEY_FORCE_TONE_MODE, false)

    fun setForceToneMode(value: Boolean) {
        prefs.edit().putBoolean(KEY_FORCE_TONE_MODE, value).commit()
    }

    private fun forceToneOf(key: String) = prefs.getInt(key, FORCE_TONE_FOLLOW)

    private fun setForceToneOf(key: String, value: Int) {
        prefs.edit().putInt(key, value).commit()
    }

    /** 「状态栏」。 */
    val forceToneStatusBar: Int
        get() = forceToneOf(KEY_FORCE_TONE_STATUS_BAR)

    fun setForceToneStatusBar(value: Int) = setForceToneOf(KEY_FORCE_TONE_STATUS_BAR, value)

    /** 「时间与锁屏内容」—— 两者在系统里共享同一个时钟级判定，故合成一项（2026-10-11 用户定）。 */
    val forceToneClock: Int
        get() = forceToneOf(KEY_FORCE_TONE_CLOCK)

    fun setForceToneClock(value: Int) = setForceToneOf(KEY_FORCE_TONE_CLOCK, value)

    /** 「快捷方式图标」。 */
    val forceToneShortcut: Int
        get() = forceToneOf(KEY_FORCE_TONE_SHORTCUT)

    fun setForceToneShortcut(value: Int) = setForceToneOf(KEY_FORCE_TONE_SHORTCUT, value)

    /** 「指纹图标」。 */
    val forceToneFingerprint: Int
        get() = forceToneOf(KEY_FORCE_TONE_FINGERPRINT)

    fun setForceToneFingerprint(value: Int) = setForceToneOf(KEY_FORCE_TONE_FINGERPRINT, value)

    /** 「底部提示文字」。 */
    val forceToneBottomHint: Int
        get() = forceToneOf(KEY_FORCE_TONE_BOTTOM_HINT)

    fun setForceToneBottomHint(value: Int) = setForceToneOf(KEY_FORCE_TONE_BOTTOM_HINT, value)

    /** 「锁屏签名/杂志文字」。 */
    val forceToneSignature: Int
        get() = forceToneOf(KEY_FORCE_TONE_SIGNATURE)

    fun setForceToneSignature(value: Int) = setForceToneOf(KEY_FORCE_TONE_SIGNATURE, value)

    /**
     * 记录下来的 root 可用性：null = 还没测过。
     *
     * 只在两个时机更新：用户点主页的「刷新 Root」，或者「立即替换」跑完发现 su 用不了。
     * 不主动定时探测 —— 主页提示和按钮都只看这个记录值。
     */
    val rootAvailable: Boolean?
        get() = if (prefs.contains(KEY_ROOT_AVAILABLE)) {
            prefs.getBoolean(KEY_ROOT_AVAILABLE, false)
        } else {
            null
        }

    fun setRootAvailable(value: Boolean) {
        prefs.edit().putBoolean(KEY_ROOT_AVAILABLE, value).commit()
    }

    /**
     * 诊断行：只记"最近一次和状态判定有关的事"，给界面显示用。
     *
     * 现在有两个写入点：`ScreenDisarm` 收到亮屏广播时（证明广播真的到了）、
     * `MaskUiState.awaitWallpaperSize()`（hook 探测成功 / 超时，写清走的是哪条路）。
     * 这两条链路原来都用 `runCatching` 把失败吞掉了，界面上完全看不出卡在哪 —— 真机上没法排查。
     */
    val diagNote: String
        get() = prefs.getString(KEY_DIAG_NOTE, "").orEmpty()

    fun setDiagNote(value: String) {
        prefs.edit().putString(KEY_DIAG_NOTE, value).commit()
    }

    val themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM

    /**
     * 「设置 → 测试 → 使用自定义背景」的开关（2026-10-06，**测试功能**，默认关）。
     *
     * 打开后整页背景换成用户自选图，用来观察底栏玻璃在真实图案下的表现；
     * 关闭时 [navBgRadiusPx] 归零、`filesDir/navbg/` 整个目录删掉（见 `NavBgState`）。
     */
    val navBgEnabled: Boolean
        get() = prefs.getBoolean(KEY_NAV_BG_ENABLED, false)

    /** 背景图的模糊半径，**单位 px**、0~50 整数档（用户 2026-10-06 定）。 */
    val navBgRadiusPx: Int
        get() = prefs.getInt(KEY_NAV_BG_RADIUS, 0).coerceIn(0, NAV_BG_RADIUS_MAX)

    /** 所选图片的**原相册文件名**，只用于设置页那行 summary 显示。 */
    val navBgFileName: String
        get() = prefs.getString(KEY_NAV_BG_NAME, "").orEmpty()

    // ---------------------------------------------------------------- 视差壁纸效果（2026-10-12 加）

    /**
     * 「视差壁纸效果」是否开启（= 自定义背景层在锁屏上生效中，**默认关**）。
     *
     * 这是**权威状态**：App 侧决定按钮显示「立即替换」还是「关闭替换」，
     * SystemUI 侧（`hook/ParallaxBg`）自愈时按它决定"插不插背景层"。
     * 所以任何一侧改状态都必须先写它、再发信号（广播只是触发，内容以它为准）。
     *
     * 与"候选图还在不在"是两件事：关闭替换后图作为普通候选留到下次启动清理（见 `App`）。
     */
    val parallaxBgActive: Boolean
        get() = prefs.getBoolean(KEY_PARALLAX_ACTIVE, false)

    fun setParallaxBgActive(value: Boolean) {
        prefs.edit().putBoolean(KEY_PARALLAX_ACTIVE, value).commit()
    }

    /** 「视差壁纸效果」开 / 关指令的请求号（App 发显式广播给 SystemUI，ParallaxBg 回执）。 */
    val parallaxToken: Long
        get() = prefs.getLong(KEY_PARALLAX_TOKEN, 0L)

    fun requestParallax(): Long {
        val token = System.currentTimeMillis()
        prefs.edit().putLong(KEY_PARALLAX_TOKEN, token).commit()
        return token
    }

    val parallaxAck: Long
        get() = prefs.getLong(KEY_PARALLAX_ACK, 0L)

    fun setParallaxAck(token: Long) {
        if (token > parallaxAck) prefs.edit().putLong(KEY_PARALLAX_ACK, token).commit()
    }

    // ---------------------------------------------------------------- 视差「参数调整」（2026-10-13 加）
    //
    // 数值参数：开头 5 个（开关 1 + 倍率 2 + 幅度 2），后有「恢复原位等待时长」「最大幅度角度」。
    // App 是唯一写入方、SystemUI 侧（ParallaxBg）经 provider config 快照读取
    //（TTL 3s + 传感器帧轮询 → 参数最迟 ~4s 落到锁屏画面）。

    /** 「参数调整」→ 使主体与背景移动方向相同（v22.5 语义翻转：默认关 = 反方向移动；反向语义 = 只翻背景）。 */
    val parallaxReverse: Boolean
        get() = prefs.getBoolean(KEY_PARALLAX_REVERSE, false)

    fun setParallaxReverse(value: Boolean) {
        prefs.edit().putBoolean(KEY_PARALLAX_REVERSE, value).commit()
    }

    /** 「参数调整」→ 主体放大倍率（1~1.25；默认 1.15 = 2026-10-12 实机调参值）。 */
    val parallaxSubjectScale: Float
        get() = prefs.getFloat(KEY_PARALLAX_SUBJECT_SCALE, PARALLAX_SUBJECT_SCALE_DEFAULT)

    fun setParallaxSubjectScale(value: Float) {
        prefs.edit().putFloat(KEY_PARALLAX_SUBJECT_SCALE, value).commit()
    }

    /** 「参数调整」→ 背景放大倍率（1~1.25；默认 1.10）。 */
    val parallaxBgScale: Float
        get() = prefs.getFloat(KEY_PARALLAX_BG_SCALE, PARALLAX_BG_SCALE_DEFAULT)

    fun setParallaxBgScale(value: Float) {
        prefs.edit().putFloat(KEY_PARALLAX_BG_SCALE, value).commit()
    }

    /** 「参数调整」→ 主体移动最大幅度（px；默认 45）。 */
    val parallaxSubjectAmp: Float
        get() = prefs.getFloat(KEY_PARALLAX_SUBJECT_AMP, PARALLAX_SUBJECT_AMP_DEFAULT)

    fun setParallaxSubjectAmp(value: Float) {
        prefs.edit().putFloat(KEY_PARALLAX_SUBJECT_AMP, value).commit()
    }

    /** 「参数调整」→ 背景移动最大幅度（px；默认 25）。 */
    val parallaxBgAmp: Float
        get() = prefs.getFloat(KEY_PARALLAX_BG_AMP, PARALLAX_BG_AMP_DEFAULT)

    fun setParallaxBgAmp(value: Float) {
        prefs.edit().putFloat(KEY_PARALLAX_BG_AMP, value).commit()
    }

    /**
     * 「参数调整」→ 恢复原位等待时长（**秒**；默认 0.5）。
     *
     * 语义 = 静止判定窗口：传感器读数停下来后，**等待多久**（窗口内变化持续小于阈值）开始归位动画；
     * 对应 SystemUI 侧原先的编译期常量 `STILL_WINDOW_MS`（2026-10-13 起运行时可调）。
     */
    val parallaxHomeWait: Float
        get() = prefs.getFloat(KEY_PARALLAX_HOME_WAIT, PARALLAX_HOME_WAIT_DEFAULT)

    fun setParallaxHomeWait(value: Float) {
        prefs.edit().putFloat(KEY_PARALLAX_HOME_WAIT, value).commit()
    }

    /**
     * 「参数调整」→ 最大幅度角度（**度**；默认 25）—— 2026-10-13 用户加。
     *
     * 语义 = 转动的"满量程角"：绕重力轴转多少度达到满幅（对应 SystemUI 侧原编译期常量
     * `GYRO_FULL_SCALE_DEG`，现运行时可调）。角度单位下与倾斜链的 30° 满程同量级。
     */
    val parallaxGyroDeg: Float
        get() = prefs.getFloat(KEY_PARALLAX_GYRO_DEG, PARALLAX_GYRO_DEG_DEFAULT)

    fun setParallaxGyroDeg(value: Float) {
        prefs.edit().putFloat(KEY_PARALLAX_GYRO_DEG, value).commit()
    }

    /**
     * 「壁纸改变后自动关闭替换」（2026-10-15 加，**默认开**）—— 视差二级页开关。
     *
     * 打开时：视差生效中检测到"换壁纸"（判据见 hook 侧 `HookRuntime` 的视差分支）→ 自动关闭视差替换。
     * 关闭时完全不做这套检测。
     */
    val parallaxAutoOff: Boolean
        get() = prefs.getBoolean(KEY_PARALLAX_AUTO_OFF, true)

    fun setParallaxAutoOff(value: Boolean) {
        prefs.edit().putBoolean(KEY_PARALLAX_AUTO_OFF, value).commit()
    }

    /**
     * 「视差效果覆盖桌面」（2026-10-21 加，**默认关**）—— 视差二级页开关，无 summary。
     *
     * 当前阶段只实现「解锁动画抑制」：开关打开时，miwallpaper / SystemUI 侧的
     * 解锁壁纸缩放、模糊、zoom 一律跳过，从锁屏到桌面看起来像同一张壁纸原地不动；
     * 视差层真正覆盖桌面的逻辑后续再接。
     */
    val parallaxCoverDesktop: Boolean
        get() = prefs.getBoolean(KEY_PARALLAX_COVER_DESKTOP, false)

    fun setParallaxCoverDesktop(value: Boolean) {
        prefs.edit().putBoolean(KEY_PARALLAX_COVER_DESKTOP, value).commit()
    }

    /**
     * 「视差基准」壁纸判据 —— 供「壁纸改变后自动关闭替换」比对用。
     *
     * 与替换链的 [injectedWallpaperMd5] **分开存**：那个绑定保护期、会被立收清空；
     * 视差基准独立于保护生命周期（视差开启时记录、关闭时清掉；空 = 尚未记录，hook 侧惰性补记）。
     */
    val parallaxWallpaperFp: String
        get() = prefs.getString(KEY_PARALLAX_FP, "").orEmpty()

    fun setParallaxWallpaperFp(value: String) {
        if (value == parallaxWallpaperFp) return
        prefs.edit().putString(KEY_PARALLAX_FP, value).commit()
    }

    /**
     * **空间壁纸的「壁纸基准」**（2026-10-29 加）。
     *
     * 用途与 [parallaxWallpaperFp] 完全同构：aod 侧的换壁纸检测拿它当基准，
     * 判据一变就自动关闭空间壁纸（**没有开关，无条件关**，用户 2026-10-29 定）。
     *
     * * 写入时机：点「立即替换」成功时记一次；
     * * 清空时机：手动 / 自动关闭时；
     * * 空 / `FINGERPRINT_WEAK` ⇒ hook 侧**惰性补记、不动作**（老版本升上来的升级保护）。
     */
    val spatialWallpaperFp: String
        get() = prefs.getString(KEY_SPATIAL_FP, "").orEmpty()

    fun setSpatialWallpaperFp(value: String) {
        if (value == spatialWallpaperFp) return
        prefs.edit().putString(KEY_SPATIAL_FP, value).commit()
    }

    /**
     * 「点『选择图片』前的景深检查」的请求号 —— App 写、aod 侧 hook 读
     * （`--arg depth=<请求号>`），hook 读 `wallpaper_effect_type_2` 后带同一个号回写。
     */
    val depthProbeToken: Long
        get() = prefs.getLong(KEY_DEPTH_PROBE_TOKEN, 0L)

    fun requestDepthProbe(): Long {
        val token = System.currentTimeMillis()
        prefs.edit().putLong(KEY_DEPTH_PROBE_TOKEN, token).commit()
        return token
    }

    /** hook 已回应到哪个「景深检查」请求号。 */
    val depthProbeAck: Long
        get() = prefs.getLong(KEY_DEPTH_PROBE_ACK, 0L)

    /**
     * 回写给 App 的 `wallpaper_effect_type_2` 原值：**2 = 景深开着**（系统自己的判定就是 `2 == value`）；
     * `-1` = hook 读取失败（App 视为"未知"，放行不拦）。
     */
    val depthProbeValue: Int
        get() = prefs.getInt(KEY_DEPTH_PROBE_VALUE, -1)

    /** 值 + 回执号**一次 commit 原子写**：App 轮询到 ack 时值一定已经落盘。 */
    fun setDepthProbeResult(value: Int, token: Long) {
        if (token <= depthProbeAck) return
        prefs.edit()
            .putInt(KEY_DEPTH_PROBE_VALUE, value)
            .putLong(KEY_DEPTH_PROBE_ACK, token)
            .commit()
    }

    /** 由 hook 通过 provider 回写的注入状态。 */
    val injectedProcess: String?
        get() = prefs.getString(KEY_INJECTED_PROCESS, null)

    /**
     * 抓到的「壁纸**原图**」尺寸（`宽x高`）—— 也就是 `templates/current/wallpaper` 那一份。
     *
     * 这是选图时的对齐锚点：`subject` / `subject_mask` 和编辑器的裁切矩形都在这个坐标系里。
     * **绝不能**塞显示空间那份的尺寸进来（它会随壁纸缩放/移动变化），所以 provider 只接受
     * 带 `src:` 前缀的回写，见 [MaskProvider.METHOD_WALLPAPER_SIZE]。
     */
    val wallpaperSize: String
        get() = prefs.getString(KEY_WALLPAPER_SIZE, "").orEmpty()

    /** 这份尺寸是哪一个「探测请求号」回写的（`0` = 旧数据 / root 脚本写的）。 */
    val wallpaperSizeToken: Long
        get() = prefs.getLong(KEY_WALLPAPER_SIZE_TOKEN, 0L)

    /** 这份尺寸回写的时刻（只在 `token > 0` 时更新）。 */
    val wallpaperSizeAt: Long
        get() = prefs.getLong(KEY_WALLPAPER_SIZE_AT, 0L)

    /**
     * 「hook 探测壁纸原图」的请求号：App 点「选择图片」时写，hook 在宿主进程（com.miui.aod）里读，
     * 读完把结果带同一个号回写（[wallpaperSizeToken] / [probeAck]），App 侧据此判断"这是我刚才那次的答案"。
     */
    val probeToken: Long
        get() = prefs.getLong(KEY_PROBE_TOKEN, 0L)

    fun requestWallpaperProbe(): Long {
        val token = System.currentTimeMillis()
        prefs.edit().putLong(KEY_PROBE_TOKEN, token).commit()
        return token
    }

    /** hook 已回应到哪个请求号（成功、失败都会推进它）。 */
    val probeAck: Long
        get() = prefs.getLong(KEY_PROBE_ACK, 0L)

    fun setProbeAck(token: Long) {
        if (token > probeAck) prefs.edit().putLong(KEY_PROBE_ACK, token).commit()
    }

    /**
     * 「hook 落盘注入」的请求号：App 点「立即替换」时写，hook 在 aod 进程里收到 provider 调用
     * （`--arg apply=<请求号>`）后当场写 subject / subject_mask / crop_subject 并回执。
     */
    val applyToken: Long
        get() = prefs.getLong(KEY_APPLY_TOKEN, 0L)

    fun requestApply(): Long {
        val token = System.currentTimeMillis()
        prefs.edit().putLong(KEY_APPLY_TOKEN, token).commit()
        return token
    }

    val applyAck: Long
        get() = prefs.getLong(KEY_APPLY_ACK, 0L)

    fun setApplyAck(token: Long) {
        if (token > applyAck) prefs.edit().putLong(KEY_APPLY_ACK, token).commit()
    }

    /** 「替替换后自动灭亮屏」的请求号 / 回执号（App 发广播，SystemUI 侧 hook 回执）。 */
    val flashToken: Long
        get() = prefs.getLong(KEY_FLASH_TOKEN, 0L)

    fun requestFlash(): Long {
        val token = System.currentTimeMillis()
        prefs.edit().putLong(KEY_FLASH_TOKEN, token).commit()
        return token
    }

    val flashAck: Long
        get() = prefs.getLong(KEY_FLASH_ACK, 0L)

    fun setFlashAck(token: Long) {
        if (token > flashAck) prefs.edit().putLong(KEY_FLASH_ACK, token).commit()
    }

    /** 顺手记下的「显示空间」尺寸（裁切矩形那份）。只用于诊断对照，不参与任何判断。 */
    val displayWallpaperSize: String
        get() = prefs.getString(KEY_DISPLAY_WALLPAPER_SIZE, "").orEmpty()

    fun setDisplayWallpaperSize(value: String) {
        if (value.isEmpty() || value == displayWallpaperSize) return
        prefs.edit().putString(KEY_DISPLAY_WALLPAPER_SIZE, value).commit()
    }

    val injectedAt: Long
        get() = prefs.getLong(KEY_INJECTED_AT, 0L)

    val injectedRevision: Long
        get() = prefs.getLong(KEY_INJECTED_REVISION, -1L)

    val injectedDetail: String?
        get() = prefs.getString(KEY_INJECTED_DETAIL, null)

    /** 框架（LSPosed）把服务 binder 送进来的时间，>0 表示模块已被激活。 */
    val frameworkConnectedAt: Long
        get() = prefs.getLong(KEY_FRAMEWORK_AT, 0L)

    /**
     * 注入完成时记录的"当时壁纸内容指纹"—— 换壁纸检测的比对基准。
     *
     * 保护期内换壁纸 → hook 解除保护（兜底；保护主路收尾在替换完成时，由 App 直接结束）。
     * 这样"注入完不长期占着"和"注入结果不被编辑器覆盖"两个诉求能同时满足。
     *
     * 空值会让 hook 侧的换壁纸检测直接失效（而 hook 其实还在守文件 —— 实测踩过），
     * 所以拿不到指纹时也要写 [FINGERPRINT_WEAK]，不能留空。
     */
    val injectedWallpaperMd5: String
        get() = prefs.getString(KEY_INJECTED_WALLPAPER, "").orEmpty()

    fun setVerboseLog(value: Boolean) {
        prefs.edit().putBoolean(KEY_VERBOSE, value).commit()
    }

    fun setShowDiagnostics(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_DIAGNOSTICS, value).commit()
    }

    fun setDepthOcclusionRemoved(value: Boolean) {
        prefs.edit().putBoolean(KEY_DEPTH_OCCLUSION, value).commit()
    }

    fun setProtecting(value: Boolean) {
        prefs.edit().putBoolean(KEY_PROTECTING, value).commit()
    }

    fun setAutoScreenFlash(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SCREEN_FLASH, value).commit()
    }

    fun setThemeMode(id: String) {
        prefs.edit().putString(KEY_THEME_MODE, id).commit()
    }

    fun setNavBgEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_NAV_BG_ENABLED, value).commit()
    }

    fun setNavBgRadiusPx(value: Int) {
        prefs.edit()
            .putInt(KEY_NAV_BG_RADIUS, value.coerceIn(0, NAV_BG_RADIUS_MAX))
            .commit()
    }

    fun setNavBgFileName(value: String) {
        prefs.edit().putString(KEY_NAV_BG_NAME, value).commit()
    }

    /** 图片内容变化后调用，hook 侧靠它判断缓存是否过期。 */
    fun bumpRevision() {
        prefs.edit().putLong(KEY_REVISION, revision + 1L).commit()
    }

    fun recordInjection(processName: String, at: Long, revision: Long, detail: String) {
        prefs.edit()
            .putString(KEY_INJECTED_PROCESS, processName)
            .putLong(KEY_INJECTED_AT, at)
            .putLong(KEY_INJECTED_REVISION, revision)
            .putString(KEY_INJECTED_DETAIL, detail)
            .commit()
    }

    fun recordFrameworkConnected(at: Long) {
        prefs.edit().putLong(KEY_FRAMEWORK_AT, at).commit()
    }

    fun setInjectedWallpaper(md5: String) {
        prefs.edit().putString(KEY_INJECTED_WALLPAPER, md5).commit()
    }

    /**
     * 值没变就**不**写（免得每次 hook 解码壁纸都触发界面刷新）；但带 [token] 的回写一定要落盘，
     * 否则 App 侧等不到"这次请求的答案"。
     */
    fun setWallpaperSize(value: String, token: Long = 0L) {
        if (value.isEmpty()) return
        if (value == wallpaperSize && token <= wallpaperSizeToken) return
        prefs.edit().apply {
            putString(KEY_WALLPAPER_SIZE, value)
            if (token > 0L) {
                putLong(KEY_WALLPAPER_SIZE_TOKEN, token)
                putLong(KEY_WALLPAPER_SIZE_AT, System.currentTimeMillis())
            }
        }.commit()
    }

    /**
     * 监听本进程内的写入，给界面用来"自动对齐状态"。
     *
     * 为什么需要：hook 在别的进程改状态是走 provider 的，而 provider 就在本进程 —— 所以这些写入
     * 在本进程里也一定能收到通知。界面因此不用等一次生命周期变化（用户从锁屏回来时不一定会有
     * ON_RESUME）就能把"保护已结束"显示出来。
     *
     * 注意 [SharedPreferences] 只持弱引用，调用方必须自己留住 listener。
     */
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val NAME = "mask_swap"

        /**
         * 拿不到壁纸内容指纹时的占位值。
         *
         * 注入后**必须**有个非空值：界面靠 `injectedWallpaperMd5.isNotEmpty()` 判断"保护状态"，
         * 空值会让 hook 侧的换壁纸检测直接失效，而 hook 其实还在守文件 —— 实测踩过。
         * 带这个占位值时不自动解除（换壁纸检测不可靠）。
         */
        const val FINGERPRINT_WEAK = "weak"
        const val KEY_REVISION = "revision"
        const val KEY_VERBOSE = "verbose_log"
        const val KEY_SHOW_DIAGNOSTICS = "show_diagnostics"
        /** 「解除景深遮挡限制」——让避让判定恒通过（仅图像景深）。 */
        const val KEY_DEPTH_OCCLUSION = "depth_occlusion_removed"
        /** 空间壁纸的推理短边（像素档位）。 */
        const val KEY_SPATIAL_SHORT_SIDE = "spatial_short_side"
        /** 空间壁纸的推理模型 key（"base"/"small"，见 `SpatialDepth.MODEL_ASSETS`）。 */
        const val KEY_SPATIAL_MODEL = "spatial_model"
        /** 空间壁纸是否生效（SystemUI 侧轮询）。 */
        const val KEY_SPATIAL_ACTIVE = "spatial_active"
        /** 当前生效的图层缓存键（图片 sha256 前 16 位）。 */
        const val KEY_SPATIAL_KEY = "spatial_key"
        /** 最近一次**生成成功**的图层缓存键（不管有没有生效）。 */
        const val KEY_SPATIAL_LAST_KEY = "spatial_last_key"
        /**
         * 最近一次**选中**的图（候选）的缓存键。
         *
         * 与 [KEY_SPATIAL_LAST_KEY] 分开：选图时就能确定（并已在缓存目录里复制了 `source` 原图），
         * 而 `lastKey` 只有「深度 + 层图都生成成功」才有。页面重建后就靠它把预览框恢复成
         * 用户选的那张原图，避免出现「预览显示着、实际却没选中」的状态。
         */
        const val KEY_SPATIAL_SOURCE_KEY = "spatial_source_key"
        /** 空间壁纸生效范围（0 锁屏 / 1 桌面 / 2 全局）。 */
        const val KEY_SPATIAL_SCOPE = "spatial_scope"
        /** 「启用空间壁纸锁屏景深」（默认关；关时渲染逻辑与加它之前完全一致）。 */
        const val KEY_SPATIAL_LOCK_DEPTH = "spatial_lock_depth"

        /**
         * **挂载模式**（2026-10-30）：空间壁纸怎么加载。
         *
         * | 值 | 含义 |
         * |---|---|
         * | 0 [SPATIAL_MOUNT_LSPOSED] | LSPosed —— 原有的 hook 链（覆盖系统壁纸 leash），默认 |
         * | 1 [SPATIAL_MOUNT_SERVICE] | 系统壁纸服务 —— 自己的 `WallpaperService`，**免 root**，但没有锁屏景深 |
         *
         * 两种模式**互斥**：运行中不允许切换（UI 把下拉禁用，直到当前模式关掉）。
         */
        const val KEY_SPATIAL_MOUNT_MODE = "spatial_mount_mode"

        /** 「功能提醒」弹窗的「以后不再弹出」（勾选并确认后置 true）。 */
        const val KEY_SPATIAL_MOUNT_WARNED = "spatial_mount_warned"

        /** 「本模块未激活」弹窗的「以后不再弹出」（勾选并点「忽略」后置 true）。 */
        const val KEY_ACTIVATION_WARN_DISMISSED = "activation_warn_dismissed"

        /**
         * 空间壁纸的「壁纸基准」（2026-10-29）：换壁纸检测的比对值。
         * **没有配套开关** —— 用户定的是"检测到就无条件关"。
         */
        const val KEY_SPATIAL_FP = "spatial_wallpaper_fp"
        const val SPATIAL_SCOPE_LOCK = 0
        const val SPATIAL_SCOPE_DESKTOP = 1
        const val SPATIAL_SCOPE_GLOBAL = 2
        /** 合法档位白名单 —— 读到意外的值就回默认（锁屏），避免下拉栏 index 越界。 */
        private val SPATIAL_SCOPE_CHOICES = intArrayOf(SPATIAL_SCOPE_LOCK, SPATIAL_SCOPE_DESKTOP, SPATIAL_SCOPE_GLOBAL)

        /** 挂载模式：LSPosed（hook 链，默认）。 */
        const val SPATIAL_MOUNT_LSPOSED = 0
        /** 挂载模式：系统壁纸服务（免 root，无锁屏景深）。 */
        const val SPATIAL_MOUNT_SERVICE = 1
        /** 合法档位白名单 —— 读到意外的值就回默认（LSPosed）。 */
        private val SPATIAL_MOUNT_CHOICES = intArrayOf(SPATIAL_MOUNT_LSPOSED, SPATIAL_MOUNT_SERVICE)
        /** 空间壁纸推理短边默认值（合法档位与 `SpatialDepth.SHORT_SIDES` 保持一致）。 */
        const val SPATIAL_SHORT_DEFAULT = 768
        /** 合法档位白名单 —— 读到意外的值就回默认，避免下拉栏 index 越界。 */
        private val SPATIAL_SHORT_CHOICES = intArrayOf(768, 896, 1024)
        /** 空间壁纸推理模型默认值（"base" = DA3-Base，质量优先，与 UI 下拉顺序一致）。 */
        const val SPATIAL_MODEL_DEFAULT = "base"
        /** 合法模型白名单 —— 读到意外的值就回默认（与 `SpatialDepth.MODEL_ASSETS` 的键一致）。 */
        private val SPATIAL_MODEL_CHOICES = listOf("base", "small")
        /** 用户是否确认过「替换时屏幕会自动灭亮一次」（确认过就不再提示）。 */
        /** 「替换后自动灭亮屏一次」（替换行为里的开关，默认关）。 */
        const val KEY_AUTO_SCREEN_FLASH = "auto_screen_flash"
        /** 「使自定义锁屏按钮显示在上层」—— 作用到 `com.android.systemui` 的开关之一。 */
        const val KEY_LOCKSCREEN_BUTTON_TOP = "lockscreen_button_on_top"
        /** 「打开多任务时隐藏状态栏」—— 消费方是 SystemUI 进程里的 `RecentsStatusBar`（默认关）。 */
        const val KEY_HIDE_STATUSBAR_IN_RECENTS = "hide_statusbar_in_recents"
        /** 「强制锁屏元素深浅模式」—— 主开关 + 7 个元素各自的档位（0=保持系统判定/1=浅色/2=深色）。 */
        const val KEY_FORCE_TONE_MODE = "force_tone_mode"
        const val KEY_FORCE_TONE_STATUS_BAR = "force_tone_status_bar"
        const val KEY_FORCE_TONE_CLOCK = "force_tone_clock"
        const val KEY_FORCE_TONE_SHORTCUT = "force_tone_shortcut"
        const val KEY_FORCE_TONE_FINGERPRINT = "force_tone_fingerprint"
        const val KEY_FORCE_TONE_BOTTOM_HINT = "force_tone_bottom_hint"
        const val KEY_FORCE_TONE_SIGNATURE = "force_tone_signature"
        /** [KEY_FORCE_TONE_*] 的取值。 */
        const val FORCE_TONE_FOLLOW = 0
        const val FORCE_TONE_LIGHT = 1
        const val FORCE_TONE_DARK = 2

        /**
         * 「快捷方式图标」专用（2026-10-11 加）：
         * 系统给图标 tint 的白/黑是 **80% 不透明**（`#CCFFFFFF` / `#CC000000`），
         * 这两档把不透明度拉满为 `#FFFFFFFF` / `#FF000000`（其余档位保持系统原样）。
         */
        const val FORCE_TONE_LIGHT_OPAQUE = 3
        const val FORCE_TONE_DARK_OPAQUE = 4

        const val KEY_PROTECTING = "protecting"
        const val KEY_ROOT_AVAILABLE = "root_available"
        const val KEY_DIAG_NOTE = "diag_note"
        const val KEY_THEME_MODE = "theme_mode"
        const val THEME_SYSTEM = "system"
        const val KEY_INJECTED_PROCESS = "injected_process"
        const val KEY_INJECTED_AT = "injected_at"
        const val KEY_INJECTED_REVISION = "injected_revision"
        const val KEY_INJECTED_DETAIL = "injected_detail"
        /** 带 _v2：早期版本在 provider onCreate 里误记过，换 key 让那条脏数据失效。 */
        const val KEY_FRAMEWORK_AT = "framework_connected_v2_at"
        const val KEY_INJECTED_WALLPAPER = "injected_wallpaper_md5"
        const val KEY_WALLPAPER_SIZE = "wallpaper_size"
        const val KEY_DISPLAY_WALLPAPER_SIZE = "display_wallpaper_size"
        /** hook 探测协议：[KEY_PROBE_TOKEN] 请求号 / [KEY_PROBE_ACK] 回执号 / 尺寸属于哪个请求号。 */
        const val KEY_PROBE_TOKEN = "probe_token"
        const val KEY_PROBE_ACK = "probe_ack"
        const val KEY_APPLY_TOKEN = "apply_token"
        const val KEY_APPLY_ACK = "apply_ack"
        const val KEY_FLASH_TOKEN = "flash_token"
        const val KEY_FLASH_ACK = "flash_ack"
        const val KEY_WALLPAPER_SIZE_TOKEN = "wallpaper_size_token"
        const val KEY_WALLPAPER_SIZE_AT = "wallpaper_size_at"
        /** 「测试 → 使用自定义背景」（测试功能，默认关）；下面两个键只在它打开时有意义。 */
        const val KEY_NAV_BG_ENABLED = "nav_bg_enabled"
        const val KEY_NAV_BG_RADIUS = "nav_bg_radius_px"
        const val KEY_NAV_BG_NAME = "nav_bg_file_name"
        /** 背景模糊半径上限（px）—— 用户定的滑块量程 0~50。 */
        const val NAV_BG_RADIUS_MAX = 50

        /** 「视差壁纸效果」：开启状态 + 指令号 / 回执号（SystemUI 侧 ParallaxBg 消费）。 */
        const val KEY_PARALLAX_ACTIVE = "parallax_bg_active"
        const val KEY_PARALLAX_TOKEN = "parallax_token"
        const val KEY_PARALLAX_ACK = "parallax_ack"

        /** 「视差壁纸效果 → 参数调整」（2026-10-13 加）：5 个参数（SystemUI 侧经 config 快照读取）。 */
        const val KEY_PARALLAX_REVERSE = "parallax_reverse"
        const val KEY_PARALLAX_SUBJECT_SCALE = "parallax_subject_scale"
        const val KEY_PARALLAX_BG_SCALE = "parallax_bg_scale"
        const val KEY_PARALLAX_SUBJECT_AMP = "parallax_subject_amp"
        const val KEY_PARALLAX_BG_AMP = "parallax_bg_amp"

        /** 第 6 个参数（2026-10-13 用户加）：「恢复原位等待时长」（秒）——静止判定窗口。 */
        const val KEY_PARALLAX_HOME_WAIT = "parallax_home_wait"

        /** 第 7 个参数（2026-10-13 用户加）：「最大幅度角度」（度）——转动的满量程角。 */
        const val KEY_PARALLAX_GYRO_DEG = "parallax_gyro_deg"

        /** 「壁纸改变后自动关闭替换」（视差页开关，默认开）+ 「视差基准」壁纸判据（2026-10-15 加）。 */
        const val KEY_PARALLAX_AUTO_OFF = "parallax_auto_off_on_wallpaper_change"
        const val KEY_PARALLAX_FP = "parallax_wallpaper_fp"

        /** 「视差效果覆盖桌面」（视差页开关，默认关，2026-10-21 加）。 */
        const val KEY_PARALLAX_COVER_DESKTOP = "parallax_cover_desktop"

        /**
         * 参数默认值（= 2026-10-12 第四轮实机调参值）。
         *
         * 两处共用：① prefs 读取的兜底（老版本升级上来还没写过这 5 个键）；
         * ② `ParallaxBg` 侧"config 还没读到过一次"时的运行兜底 —— 两边必须一致，别各写各的。
         */
        const val PARALLAX_SUBJECT_SCALE_DEFAULT = 1.15f
        const val PARALLAX_BG_SCALE_DEFAULT = 1.10f
        const val PARALLAX_SUBJECT_AMP_DEFAULT = 45f
        const val PARALLAX_BG_AMP_DEFAULT = 25f

        /** 倍率滑块量程（用户定的 1~1.25；步进 0.05）。 */
        const val PARALLAX_SCALE_MIN = 1.0f
        const val PARALLAX_SCALE_MAX = 1.25f

        /** 幅度滑块的下限比例（相对"上限"）：主体 50%、背景 35%（背景 25px 不落界外，用户定）。 */
        const val PARALLAX_SUBJECT_AMP_MIN_RATIO = 0.5f
        const val PARALLAX_BG_AMP_MIN_RATIO = 0.35f

        /** 「恢复原位等待时长」量程（用户定的 0.2s~1s；步进 0.1s）与默认值（= v6 实机值 0.5s）。 */
        const val PARALLAX_HOME_WAIT_MIN = 0.2f
        const val PARALLAX_HOME_WAIT_MAX = 1.0f
        const val PARALLAX_HOME_WAIT_DEFAULT = 0.5f

        /**
         * 「最大幅度角度」量程（**15°~45°**；步进 1°）与默认值（用户定的 **25°**）。
         *
         * 单位 = 度；语义 = 转动的满量程角（绕长边转多少度达到满幅）。
         * v22.6（用户 2026-10-14 定）：上限 30° → **45°**。
         */
        const val PARALLAX_GYRO_DEG_MIN = 15f
        const val PARALLAX_GYRO_DEG_MAX = 45f
        const val PARALLAX_GYRO_DEG_DEFAULT = 25f

        /** 幅度上限公式里的安全边距（px）——「最大余量 − 5px」，用户定。 */
        const val PARALLAX_AMP_MARGIN_PX = 5f

        /**
         * 幅度上限公式（用户 2026-10-13 定）：`半屏宽 ×（倍率 − 1） − 5px`。
         *
         * "最大余量" = 放大后画面在**水平（最紧）方向每侧多出的像素**：半屏宽 ×（倍率−1）。
         * 背景/主体平移超过这个数就会露边，所以幅度上限取它再减 5px 安全边距。
         * 倍率 = 1.0 时上限为 0 → 滑块锁 0（UI 置禁用态）。
         */
        fun parallaxAmpUpperBound(scale: Float, halfScreenWidth: Float): Float =
            (halfScreenWidth * (scale - 1f) - PARALLAX_AMP_MARGIN_PX).coerceAtLeast(0f)

        /** 「点选图前的景深检查」：请求号 / 回执号 / 探测值（`wallpaper_effect_type_2` 原值，-1=读取失败）。 */
        const val KEY_DEPTH_PROBE_TOKEN = "depth_probe_token"
        const val KEY_DEPTH_PROBE_ACK = "depth_probe_ack"
        const val KEY_DEPTH_PROBE_VALUE = "depth_probe_value"
    }
}
