package com.frederic22.hyperextreme.hook

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.MaskProvider
import com.frederic22.hyperextreme.data.MaskRole
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method

/**
 * LSPosed 入口，基于 libxposed **API 102**。
 *
 * API 102 规定：目标 102 的模块不能再调用旧的 `de.robv.android.xposed` API，所以这里统一使用
 * 拦截器链：[XposedInterface.Hooker.intercept] 里不调用 `chain.proceed()` 就等于短接原方法。
 * 作用域写在 `META-INF/xposed/scope.list`（`com.miui.aod`，覆盖其 `:keyguardeditor` 子进程）。
 *
 * 工作方式（没有开关，设了图就生效）：
 * * 主路径是**真正替换文件**：`HookRuntime.syncFiles` 把 subject / subject_mask / crop_subject
 *   写进系统实际使用的位置（**只写不删**；"替换前备份 / 还原"整组逻辑 2026-10-15 已按用户要求删除）；
 * * 下面这些 hook 只是兜底：编辑器重新生成这些文件时立刻改写回我们的内容，防止被覆盖回去。
 */
class HookEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        HookRuntime.installLogger(param.processName) { message -> log(Log.INFO, TAG, message) }
        HookRuntime.log(
            "模块加载：framework=${getFrameworkName()} ${getFrameworkVersion()}," +
                " api=${getApiVersion()}," +
                " systemServer=${param.isSystemServer()}",
        )
        // 版本自证：排查"到底装的是哪一版"用。每次改动都更新 [BUILD_TAG]。
        HookRuntime.log("模块构建：$BUILD_TAG（进程=${param.processName}）")
        // ⚠️⚠️ **这里不要装 SystemUI 的 hook** —— 2026-09-28 真机日志实测的结论：
        //
        // `ModuleLoadedParam` **不提供宿主的 classLoader**，而 `javaClass.classLoader` 是
        // LSPosed 给模块自己用的**独立 classLoader**（parent 是 BootClassLoader，日志里显示为 `n1`）。
        // 用它去 `Class.forName("com.android.systemui.…")` 四处都是"未找到 xxx"，
        // 一个 hook 都装不上 —— 而且它会把幂等位占掉，等 `onPackageReady` 带着**正确的**
        // `param.classLoader` 进来时被挡回，于是永远装不上。
        //
        // 宿主的 classLoader 只有 `PackageReadyParam.classLoader` 这一个正规来源，所以主路在
        // [onPackageReady]。这里只做两件事：记一条日志（它是"模块确实进了 SystemUI"的第一手证据），
        // 以及起一个兜底线程（万一 onPackageReady 不来）。
        if (param.processName == SYSTEM_UI_PACKAGE) {
            HookRuntime.log("SystemUI 进程已加载模块，等 onPackageReady 提供宿主 classLoader")
            startSystemUiLoaderFallback()
        }
    }

    /**
     * `onPackageReady` 万一不触发时的兜底：等 Application 起来，用它的 classLoader 再试。
     *
     * 只在**主路还没成功**时才动手（[systemUiHooksInstalled] 是"成功才置位"，
     * 见 [installSystemUiHooks]），一旦主路装上了这个线程立刻退出。
     */
    private fun startSystemUiLoaderFallback() {
        Thread {
            repeat(SYSUI_LOADER_FALLBACK_ATTEMPTS) {
                Thread.sleep(SYSUI_LOADER_FALLBACK_INTERVAL_MS)
                if (systemUiHooksInstalled.get()) return@Thread
                val loader = HookRuntime.appContext()?.classLoader
                if (loader != null) {
                    HookRuntime.log("SystemUI 兜底：@onPackageReady 未到，用 Application 的 classLoader 重试")
                    installSystemUiHooks(loader)
                    return@Thread
                }
            }
        }.start()
    }

    /**
     * app classloader 就绪、Application 还没创建。这里只挂 hook；
     * 真正的文件替换在第一次读配置时做（那时 Application 已经可用）。
     */
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // **在这里读模块偏好**（不是 onModuleLoaded）—— HyperChanger 就是这么做的：
        // 那时框架已经完全就绪，`getRemotePreferences` 一定拿得到；而我们原先放在 onModuleLoaded，
        // 一旦那次拿到 null，SystemUI 里又没有可靠的 Context，整个「景深」功能就会**静默失效**。
        bindPreferences()
        if (param.packageName == MODULE_PACKAGE) {
            // LSPosed 会把模块加载进它自己的进程：勾选即"已激活"，不必等目标应用先跑。
            HookRuntime.markSelfInjected()
            HookRuntime.reportSelfActivationAsync()
            return
        }
        if (param.packageName == SYSTEM_UI_PACKAGE) {
            // 这是**唯一**能拿到宿主 classLoader 的地方，SystemUI 那条链的主路就在这。
            HookRuntime.log(
                "SystemUI 包就绪（onPackageReady）：loader=${param.classLoader.javaClass.simpleName}",
            )
            installSystemUiHooks(param.classLoader)
            return
        }
        if (param.packageName == WALLPAPER_PACKAGE) {
            // 【只读探针】miwallpaper「是否同一张壁纸」判定（2026-10-21）。
            // 只打印、不修改任何返回值；用途见 WallpaperProbe 类注释。
            HookRuntime.log("miwallpaper 包就绪：安装探针 + 视差覆盖桌面（动画抑制）")
            guard("壁纸探针") { WallpaperProbe.install(this, param.classLoader) }
            guard("视差覆盖桌面-壁纸侧") { ParallaxDesktopGuard.installWallpaper(this, param.classLoader) }
            // 空间壁纸：把系统壁纸的**真实 alpha** 桥给 SystemUI（解锁淡出不猜时长）。
            // 真值来源 = `UniversalWallpaper$UniversalEngine#h(float, SurfaceControl)` 里的 setAlpha。
            guard("空间壁纸-壁纸alpha") { SpatialWallpaperAlpha.install(this, param.classLoader) }
            // 【已停用 2026-10-21】GL 帧追加绘制（WallpaperGlOverlay）：静态壁纸只出 1 帧，
            // 外部驱动 refresh 会落到没有 EGL context 的线程（glError=0x501，壁纸全黑）。
            // 改为 SystemUI 的 TYPE_WALLPAPER 窗口实现（见 ParallaxWallpaperWindow）。
            return
        }
        if (param.packageName != TARGET_PACKAGE) return
        val loader = param.classLoader
        HookRuntime.log("目标包就绪：${param.packageName}，开始安装 hook")
        guard("WallpaperInfo#getSubjectBitmap") { hookSubjectBitmapCache(loader) }
        guard("BitmapUtil 读") { hookBitmapReaders(loader) }
        guard("BitmapUtil 写") { hookBitmapWriters(loader) }
        guard("模板文件复制") { hookFileCopies(loader) }
        guard("模板配置写入") { hookTemplateInfoWriter(loader) }
        guard("TemplateFileProvider") { hookTemplateProvider(loader) }
        // 编辑器（aod）侧的景深整套 —— **与 HyperChanger 的 AOD 分支一一对应**，
        // 见 [installDepthEffectHook] / [installAodThirdPartyWallpaperDepthHook]。
        // 与"替换文件"那条链**完全无关**：只看开关、只改编辑器状态，不改任何文件，
        // 所以都不过 [mayReplace]（那个闸门要求 `protecting`），也不参与保护期。
        guard("编辑器景深-阈值与避让") { installDepthEffectHook(loader) }
        guard("编辑器景深-壁纸能力位") { installAodThirdPartyWallpaperDepthHook(loader) }
        // 只读诊断：把编辑器算出来的景深按钮状态打出来（0=系统判不支持 / 4=遮挡被打回）。
        guard("编辑器景深-按钮状态诊断") { hookHierarchyStatusDiagnostic(loader) }
        guard("灭亮屏结束保护") { HookRuntime.installScreenDisarmAsync() }
        // 「点选图 → hook 探测壁纸原图」：App 在模块界面点「选择图片」时会写一个请求号并唤醒本进程，
        // 这里在启动点先试一次（provider 那条路见 [hookTemplateProvider]）。
        guard("壁纸探测（启动即响应）") { HookRuntime.probeWallpaperOnStart() }
        // 「强制锁屏元素深浅 → 时间与锁屏内容」的编辑器扩展（2026-10-11 用户拍板）：
        // 只在锁屏编辑进程（:keyguardeditor）装 —— 编辑界面里的时钟预览跟随强制档；
        // 主进程（AOD 息屏）与 :tinykeyguardeditor（轻量版）都不装（保住"息屏不受影响"既定边界）。
        val isEditorProcess = isKeyguardEditorProcess()
        HookRuntime.log(
            "锁屏编辑扩展：本进程=${runCatching { android.app.Application.getProcessName() }.getOrNull()}，" +
                (if (isEditorProcess) "安装「时间与锁屏内容」时钟深浅 hook" else "跳过（非锁屏编辑进程）"),
        )
        if (isEditorProcess) {
            guard("锁屏编辑-时钟深浅") { LockScreenTone.installEditor(this, loader) }
        }
    }

    /** 当前是否在 `com.miui.aod:keyguardeditor`（锁屏编辑）进程 —— 判定方式与编辑器自身一致。 */
    private fun isKeyguardEditorProcess(): Boolean =
        runCatching { android.app.Application.getProcessName() }
            .getOrNull() == KEYGUARD_EDITOR_PROCESS

    /**
     * `com.android.systemui` 侧的链之一：让锁屏长按浮出的「自定义锁屏」按钮压在下沉的通知之上（本进程另有 `LockScreenTone` / `ParallaxBg` / `RecentsStatusBar` 三条独立链，互不依赖）。
     *
     * 跟"替换模板文件"那条链完全无关（作用域 2026-09-28 扩到 SystemUI 就是为了它），
     * 具体怎么做见 [LockScreenButton]。六处 hook 各司其职：
     *
     * | hook | 作用 |
     * | --- | --- |
     * | `View#onAttachedToWindow` | **认人**：按 id 名 / 文本认出按钮并记下来（这一步不搬动） |
     * | `View#onVisibilityChanged` | 按钮**变可见 → 搬动**（菜单弹出、布局参数齐了）；**变不可见 → 复原** |
     * | `View#onDetachedFromWindow` | 按钮被移出窗口 → 复原（有些实现用 `removeView` 而不是改 visibility） |
     * | `KeyguardSettingsViewBinder#bind` | 兼容路：参数里若有 View 就用它（真机上第一个参数是 ViewModel，取不到） |
     * | `KeyguardTouchHandlingInteractor#onLongPress` | 菜单出现 → 再抬一次（真机上这个回调**不会被触发**，只作兜底） |
     * | `KeyguardTouchHandlingInteractor#hideMenu` | 菜单收起 → 复原（同样可能不触发，所以上面两条复原才是主路） |
     */
    @Synchronized
    private fun installSystemUiHooks(loader: ClassLoader) {
        if (systemUiHooksInstalled.get()) return
        HookRuntime.log("SystemUI 就绪：安装锁屏按钮层级 hook（loader=${loader.javaClass.simpleName}）")
        // 诊断（2026-10-22）：确认 SystemUI 进程里能否建 EGL / 跑 GL —— 空间壁纸架构的前提。
        // 只读探针，不接入任何逻辑；日志前缀 GPUPROBE。
        runCatching { GpuProbe.run("systemui") }
        // GL 渲染探针（2026-10-22 验证完成）：已确认「自建 SurfaceControl + EGL + glClear + swapBuffers
        // + reparent 到壁纸层」全链路可用，屏幕上能看到半透明洋红块。
        // **平时保持关闭**，需要重验时把下面这行改成 true。
        if (false) {
            Thread {
                val dm = android.content.res.Resources.getSystem().displayMetrics
                if (GlLayerProbe.start(loader, dm.widthPixels, dm.heightPixels)) {
                    Thread.sleep(20000)
                    GlLayerProbe.stop()
                }
            }.apply { isDaemon = true }.start()
        }
        // 8 层渲染探针（2026-10-22 验证完成）：已确认「8 个 quad + 每层独立 MVP + 传感器参数
        // + 合成 + 挂载」全部可用（屏幕上能看到网格状的 8 层叠加）。**平时关闭**。
        if (false) {
            Thread {
                val dm = android.content.res.Resources.getSystem().displayMetrics
                if (SpatialProbe.start(loader, dm.widthPixels, dm.heightPixels)) {
                    Thread.sleep(25000)
                    SpatialProbe.stop()
                }
            }.apply { isDaemon = true }.start()
        }
        // 「空间壁纸」渲染器开关轮询（2026-10-22）：App 点「立即替换」把 key 写进 prefs，
        // 这边每秒看一次（走 `remoteBoolean` / `remoteString`，prefs 文件原文优先）。
        // **独立链**：不读不写任何 parallax 字段，也不碰视差的 View 树。
        Thread {
            var lastKey: String? = null
            var lastScope = Int.MIN_VALUE
            var ctxWarn = 0
            var tick = 0
            HookRuntime.log("SPATIAL 轮询已启动（监听属性 ${com.frederic22.hyperextreme.data.SpatialFlag.PROP_ACTIVE}）")
            while (true) {
                try {
                    Thread.sleep(1000)
                    tick++
                    // **主通道 = 系统属性**（2026-10-23）：SystemUI 里读模块 prefs 不可靠 ——
                    // 实测 `getRemotePreferences` 是陈旧快照（prefs 里 true，读出来 false），
                    // `openRemoteFile` 干脆读不到任何文件。属性所有进程都能读、不需要 Context。
                    val propActive = HookRuntime.systemProperty(
                        com.frederic22.hyperextreme.data.SpatialFlag.PROP_ACTIVE,
                    )
                    val propKey = HookRuntime.systemProperty(
                        com.frederic22.hyperextreme.data.SpatialFlag.PROP_KEY,
                    )
                    // 生效范围：属性缺失（旧版本 / 手机刚重启属性还没写上）按**锁屏**处理，
                    // 与 App 侧默认档一致；读到非法值也一样。
                    val propScope = HookRuntime.systemProperty(
                        com.frederic22.hyperextreme.data.SpatialFlag.PROP_SCOPE,
                    )
                    val scope = propScope.trim().toIntOrNull()
                        ?.takeIf { it in 0..2 } ?: MaskPrefs.SPATIAL_SCOPE_LOCK
                    // 属性没写时回退 prefs 两条通道（哪天框架通道修好了也不用改回来）
                    val active = if (propActive.isNotEmpty()) propActive == "1"
                    else (HookRuntime.remoteBoolean(MaskPrefs.KEY_SPATIAL_ACTIVE) ?: false)
                    val key = if (propKey.isNotEmpty()) propKey
                    else HookRuntime.remoteString(MaskPrefs.KEY_SPATIAL_KEY).orEmpty()
                    if (tick <= 3 || tick % 60 == 0) {
                        HookRuntime.log(
                            "SPATIAL 轮询 tick=$tick prop=[$propActive/$propKey/$propScope] ⇒ " +
                                "active=$active key=$key scope=$scope last=$lastKey/$lastScope",
                        )
                    }
                    if (active && key.length == 16) {
                        // scope 变了也要重建：挂载点（锁屏 / 桌面 leash）不一样
                        if (key != lastKey || scope != lastScope) {
                            val ctx = HookRuntime.appContext()
                            if (ctx != null) {
                                HookRuntime.log("SPATIAL 开关=开 key=$key scope=$scope → 启动渲染器")
                                SpatialRenderer.start(ctx, key, scope)
                                lastKey = key
                                lastScope = scope
                            } else if (ctxWarn++ % 30 == 0) {
                                HookRuntime.log("SPATIAL 开关=开 key=$key，但拿不到 Context（第 ${ctxWarn} 次）")
                            }
                        }
                    } else if (lastKey != null) {
                        HookRuntime.log("SPATIAL 开关=关 → 停止渲染器")
                        SpatialRenderer.stop()
                        lastKey = null
                        lastScope = Int.MIN_VALUE
                    }
                } catch (t: Throwable) {
                    // 轮询线程不许死
                    HookRuntime.log("SPATIAL 轮询异常：${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }.apply { isDaemon = true }.start()
        // 「视差效果覆盖桌面」第二步：桌面主体层（TYPE_WALLPAPER 窗口）。
        // 拿 Context 的时机不稳定（SystemUI 里 currentApplication 有时还没就绪），
        // 所以后台重试几次；ParallaxBg 每帧也会兜底再试一次。
        Thread {
            repeat(20) {
                Thread.sleep(1000)
                val ctx = HookRuntime.appContext() ?: return@repeat
                if (ParallaxWallpaperWindow.ensure(ctx)) return@Thread
            }
        }.apply { isDaemon = true }.start()
        // 逐处记下"挂上没有"，交给 LockScreenButton 连同第一次拿到视图时的诊断一起回写 ——
        // 用户一眼就能看到卡在哪一处（清单见 §3.10）。
        val results = linkedMapOf<String, Boolean>()
        // **路 0（真正管用的那条）**：按 id 名认人。
        //
        // `addViews` / `bind` 都是"窗口构建时只调用一次"的方法，而那一刻 hook 还没装上
        // （真机日志：四处 hook 全 `已挂`，却连一条 `已拿到…` 都没有）。所以主路改成
        // 监听 **View 进窗口** —— 不管按钮是谁建的、什么时候建的，只要它进了窗口就能认出来。
        // `View#onAttachedToWindow` 在 SystemUI 里非常高频，所以里面第一件事是
        // `ready()` 快速返回（拿到按钮之后立即短路）。
        results["视图入窗认人"] = attempt("View#onAttachedToWindow 按 id 认人") {
            hookAll(
                VIEW_CLASS,
                loader,
                "onAttachedToWindow",
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val view = chain.getThisObject() as? View
                        // 「替换后自动灭亮屏」的接收器：借高频的视图入窗顺手装一次（幂等）。
                        view?.let { runCatching { ScreenFlash.install(it.context) } }
                        if (LockScreenButton.ready()) return chain.proceed()
                        val result = chain.proceed()
                        LockScreenButton.attachIfTarget(view)
                        return result
                    }
                },
            )
        }
        // 路 1：锁屏根视图 —— `addViews(ConstraintLayout)` 的参数就是 `keyguard_root_view`，
        // 按钮也正是在这个方法里被 addView 进去的（所以要在 proceed 之后再去搜）。
        // 它是"拿不到按钮"时的兜底起点，比只靠 bind 稳。
        results["根视图addViews"] = attempt("锁屏根视图 addViews") {
            hookAll(
                KEYGUARD_SETTINGS_SECTION,
                loader,
                "addViews",
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        LockScreenButton.attachKeyguardRoot(chain.getArgs().getOrNull(0) as? View)
                        return result
                    }
                },
            )
        }
        // 路 2：按钮本体。
        //
        // ⚠️ 真机日志写明这个 `bind` 的**第一个参数不是 View**（是 `KeyguardSettingsViewModel`），
        // 所以不能按位置取 —— 在参数里找第一个 View 类型，找不到就算了（主路是 onAttachedToWindow）。
        results["按钮bind"] = attempt("锁屏设置按钮 bind") {
            hookAll(
                SETTINGS_VIEW_BINDER,
                loader,
                "bind",
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        val view = chain.getArgs().firstOrNull { it is View } as? View
                        LockScreenButton.attach(view, "KeyguardSettingsViewBinder#bind")
                        return result
                    }
                },
            )
        }
        // 路 3：按钮可见性变化 —— 按钮真正变可见时视图树才齐全，这时重算 Z 最准（见 LockScreenButton）。
        // 高频方法，但 `onVisibilityChanged` 里第一步就是引用比对、不命中立即返回。
        results["按钮显隐"] = attempt("按钮可见性变化") {
            hookAll(
                VIEW_CLASS,
                loader,
                "onVisibilityChanged",
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        // 按钮还没拿到时直接返回 —— 免得为了一个必然命不中的比对去取参数。
                        if (LockScreenButton.hasButton()) {
                            val args = chain.getArgs()
                            LockScreenButton.onVisibilityChanged(
                                args.getOrNull(0) as? View,
                                args.getOrNull(1) as? Int ?: -1,
                            )
                        }
                        return result
                    }
                },
            )
        }
        // 路 4：按钮被移出窗口 —— 有些实现用 `removeView` 而不是改 visibility，那样只会走这里。
        // 同样是「菜单收起就复原」的保险（不复原会让抬起来的那一支压住解锁密码层）。
        results["按钮移除"] = attempt("按钮移除复原") {
            hookAll(
                VIEW_CLASS,
                loader,
                "onDetachedFromWindow",
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        if (LockScreenButton.hasButton()) {
                            LockScreenButton.onButtonDetached(chain.getThisObject() as? View)
                        }
                        return result
                    }
                },
            )
        }
        results["菜单显隐"] = attempt("锁屏长按菜单显隐") {
            val longPress = hookAll(
                TOUCH_INTERACTOR,
                loader,
                "onLongPress",
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        LockScreenButton.onMenuShown()
                        return result
                    }
                },
            )
            val hideMenu = hookAll(
                TOUCH_INTERACTOR,
                loader,
                "hideMenu",
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        LockScreenButton.onMenuHidden()
                        return result
                    }
                },
            )
            longPress && hideMenu
        }
        // 锁屏侧的景深：编辑器和锁屏是**两套**判定/状态，这里补的是锁屏那套（见 [hookDepthLockScreen]）。
        guard("锁屏景深图像阈值") { hookDepthImageThreshold(loader) }
        guard("锁屏景深整套") { hookDepthLockScreen(loader) }
        results["景深阈值"] = runCatching {
            Class.forName(DEPTH_THRESHOLD_CLASS, false, loader)
            true
        }.getOrDefault(false)
        // 「强制锁屏元素深浅模式」（2026-10-11，需求一）：7 个元素 × 9 个消费端覆盖点，全部在
        // SystemUI 进程。与「锁屏按钮置顶」「景深」两条链完全无关（不改文件、不看保护期、
        // 不动判定源字段），装不上也不影响它们；实现与全部注释见 [LockScreenTone]。
        results["强制深浅"] = attempt("强制锁屏元素深浅") { LockScreenTone.install(this, loader) }
        // 「视差壁纸效果」（2026-10-12，需求三）：在锁屏树最底插背景层（只盖系统壁纸）+
        // 主体/背景的相对位移。同样**自成一条链**：不改文件、不看保护期、不碰前三条链的覆盖点；
        // 开启状态与图片都从 provider 读（App 是权威）。实现与全部设计见 [ParallaxBg]。
        results["视差壁纸"] = attempt("视差壁纸效果") { ParallaxBg.install(this, loader) }
        // 「空间壁纸 · 锁屏前景层」（2026-10-28）：空间壁纸的 GL 层挂在**锁屏壁纸 leash** 上，
        // 全屏不透明 ⇒ 系统锁屏壁纸已被盖住；只剩 SystemUI 窗口里的 `keyguard_foreground_layer`
        // （景深主体 + 色带 + 装饰）还压在上面。这一条负责在锁屏/全局档把它撤掉。
        // 与视差**完全独立**：不读写任何 parallax 字段、不碰 ParallaxBg 的 View 树。
        results["空间壁纸前景层"] = attempt("空间壁纸-锁屏前景层") {
            SpatialForegroundHider.install(this, loader)
        }
        // 「空间壁纸 · 解锁淡出」（2026-10-28）：系统原生过渡里模糊/放大我们能跟上
        // （前者是窗口后处理、后者是 leash 的 setMatrix），唯独 alpha 跟不上 ——
        // 真正的淡出由 miwallpaper 对自己那两个 Surface 做，我们是 leash 的兄弟层。
        // 这一条在「壁纸开始退场」时给我们的 SC 补一条 alpha 渐隐。
        results["空间壁纸解锁淡出"] = attempt("空间壁纸-解锁淡出") {
            SpatialUnlockFade.install(this, loader)
        }
        // 「空间壁纸 · AOD 适配」（2026-10-30）：系统的压暗/全黑走壁纸专有通道
        // （`setWallPaperAnimProcess`），不辐射我们的子层 ⇒ 自己补压暗；
        // 前景主体在 AOD 里要"同步缩小 + 淡出"；进 AOD 先归位再冻结传感器。
        results["空间壁纸AOD"] = attempt("空间壁纸-AOD") {
            SpatialAod.install(this, loader)
        }
        // 「打开多任务时隐藏状态栏」（2026-10-16，需求：系统桌面 → 多任务）：hook 桌面发给 SystemUI 的
        // overview 通知（`LauncherProxyService$1#onTransact` 的 case 62/63），生效动作在 SystemUI 进程内
        // （自家 token 设状态栏位、退出还原）。与上面几条链零耦合；实现与全部设计见 [RecentsStatusBar]。
        results["多任务隐藏状态栏"] = attempt("多任务隐藏状态栏") { RecentsStatusBar.install(this, loader) }
        // **WMShell 多任务状态只读探针**（v1.2.2，纯诊断）：hook WMShell 自己按 display 追踪
        // 多任务动画的两处 `onTransitionStateChanged(int,int)` + 注册入口 `addAnimationStateListener`，
        // 只读两个 int 打日志、**不改任何行为**。目的是查清 shade 操作会不会污染这套信号
        // （桌面侧 T3 就是被 shade 误发的），以及两个 int 的确切取值。
        // ⚠ 刻意**不并入 results**（不参与 [anyOk] / 幂等位）：它是可选项，
        // 装不上也不能拖累上面几条既有链的状态判定。
        runCatching { attempt("WMShell 状态探针") { RecentsStateProbe.install(this, loader) } }
        // **一处都没挂上就不占幂等位** —— 关键：上一版是"进函数就置位"，结果用错的 loader
        // 试一次（四处全"未找到"）之后，`onPackageReady` 带着正确 loader 再来就被挡回了。
        // 现在改成"成功才置位"，错的 loader 试过还能等对的。
        val anyOk = results.values.any { it }
        if (anyOk) systemUiHooksInstalled.set(true)
        val summary = "loader=${loader.javaClass.simpleName}｜" +
            results.entries.joinToString("，") { (name, ok) -> "$name=${if (ok) "ok" else "失败"}" }
        // 安装结果先存着：等第一次真的拿到视图（那时才**一定**有 Context）再随诊断一起回写。
        LockScreenButton.recordInstall(summary)
        HookRuntime.log("锁屏按钮 hook 安装结果：$summary（本轮${if (anyOk) "已生效" else "全部失败，等下一个 loader"}）")
    }

    /**
     * 替换型 hook 的统一闸门。
     *
     * 保护结束时（主路 = 替换完成时 App 直接结束；灭亮屏 / 换壁纸检测兜底）就该放行 —— 那时
     * 系统文件已经是替换后的内容、景深层也重新解码过了，继续替换没有意义：让编辑器自己写，
     * 用户在编辑器里再点应用就该听编辑器的。
     * 所有替换点都必须过这一关，否则"结束保护"只写在设置里、实际还在改文件。
     */
    private fun mayReplace(chain: XposedInterface.Chain): Pair<Context, HookRuntime.HookConfig>? {
        val context = HookRuntime.appContext() ?: return null
        val config = HookRuntime.config(context) ?: return null
        if (!config.protecting) return null
        return context to config
    }

    /**
     * `crop_subject` 是**派生文件**，一律放行。
     *
     * 编辑器每次应用都会用"当下的壁纸裁切矩形"把 subject 裁一刀来生成它
     * （`TemplateApiImpl.applyEditPageTemplate` → `cropBitmap(bitmap, positionInfo, false)`，
     * 而 SystemUI 侧是不带任何变换直接全屏画的）。我们要是也去改写它，就等于拿没裁过的图
     * 顶掉了系统自己算对的那份 —— 壁纸一旦被缩放/移动，抠像层就跟不上，实测就是这个问题。
     *
     * 主体（`subject`）仍然由 hook 换成我们的图，编辑器拿它当输入、自己裁，结果天然正确。
     * root 那条路没有编辑器可用，所以 [MaskComposer.cropToDisplay] 在模块里复刻了同一步裁切。
     */
    private fun isDerived(role: MaskRole): Boolean = role == MaskRole.CROP_SUBJECT

    /**
     * 绑定"读模块偏好"的两条通道，并把读到的开关值打一条日志（**自证用的，排查看这一行**）。
     *
     * 为什么需要：`DepthAvoidEvaluator$Threshold` 的构造函数 hook、`getSupportSubject()` 这些地方
     * **拿不到 Context**，而 `HookRuntime.appContext()` 的反射 hidden API 在 SystemUI 里并不可靠
     * （2026-09-28 踩过）。所以开关值只能走框架提供的通道 —— 这也是 HyperChanger 的做法。
     *
     * 两条通道：
     *
     * 1. `getRemotePreferences("mask_swap")`（首选，和 HyperChanger 一样）；
     * 2. `openRemoteFile("shared_prefs/mask_swap.xml")` 直接读文件原文（兜底 ——
     *    万一第 1 条拿到 null，SystemUI 里就没有别的办法读到开关了，功能会**静默失效**）。
     *
     * **只在 `onPackageReady` 里绑**（HyperChanger 也是在这一步现取 `getRemotePreferences` 的）：
     * 那时框架已经完全就绪。曾经放在 `onModuleLoaded`，拿到的可能是 null。
     */
    private var preferencesBound = false

    private fun bindPreferences() {
        if (preferencesBound) return
        preferencesBound = true
        val prefs = runCatching { getRemotePreferences(MaskPrefs.NAME) }.getOrNull()
        HookRuntime.bindRemotePreferences(prefs)
        HookRuntime.bindRemotePreferencesFile {
            openRemoteFile("shared_prefs/${MaskPrefs.NAME}.xml")
                .use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText() }
        }
        val visibleFiles = runCatching { listRemoteFiles()?.joinToString("、") }.getOrNull()
        HookRuntime.log(
            "景深开关通道：getRemotePreferences=${if (prefs != null) "ok" else "null"}；" +
                "可读文件=[$visibleFiles]；「解除景深遮挡限制」当前=${
                    HookRuntime.occlusionRemovedRemote()
                }",
        )
    }

    /** 「解除景深遮挡限制」每一步"只打一次"的 INFO 日志 —— 用户看 LSPosed 日志就能确认哪几步真的跑了。 */
    private val depthLogged = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun depthLogOnce(key: String, message: String) {
        if (depthLogged.putIfAbsent(key, true) == null) {
            HookRuntime.log("解除景深遮挡限制：$message")
        }
    }

    /** [hookHierarchyStatusDiagnostic] 用：已经打过日志的 `status` 值（同一个值只记一次）。 */
    private val buttonStatusLogged = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    private fun guard(name: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            HookRuntime.log("hook[$name] 安装失败: $error")
        }
    }

    /**
     * 按类名 + 方法名挂上所有同名重载，找不到就记一条日志。
     *
     * 返回"至少挂上一个"—— SystemUI 那条链要靠它判断哪一处没挂上（见 [installSystemUiHooks]）。
     */
    private fun hookAll(
        className: String,
        loader: ClassLoader,
        methodName: String,
        hooker: XposedInterface.Hooker,
    ): Boolean {
        // ⚠️ 只认传进来的 loader。曾经在这里"兜底"试过 `javaClass.classLoader` ——
        // 那是 LSPosed 给模块自己的独立 loader（parent 是 BootClassLoader），
        // 看不见 `com.android.systemui.*`，真机日志里四处"未找到 xxx"就是这么来的。
        // 调用方给 loader 的地方只有 `PackageReadyParam.classLoader`（宿主 loader）。
        val clazz = runCatching { Class.forName(className, false, loader) }.getOrNull()
        if (clazz == null) {
            HookRuntime.log("未找到 $className，跳过 $methodName")
            return false
        }

        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val declared = current.declaredMethods.filter { it.name == methodName }
            if (declared.isNotEmpty()) {
                var any = false
                declared.forEach { method ->
                    runCatching { hook(method).intercept(hooker) }
                        .onSuccess {
                            HookRuntime.log("已挂 ${describe(method)}")
                            any = true
                        }
                        .onFailure { HookRuntime.log("挂 ${describe(method)} 失败: $it") }
                }
                return any
            }
            current = current.superclass
        }
        HookRuntime.log("$className 里没有 $methodName")
        return false
    }

    /** 与 [guard] 同类，但把结果返回给调用方（SystemUI 的安装报告要它）。 */
    private fun attempt(name: String, block: () -> Boolean): Boolean =
        try {
            block()
        } catch (error: Throwable) {
            HookRuntime.log("hook[$name] 安装失败: $error")
            false
        }

    private fun describe(method: Method): String =
        "${method.declaringClass.simpleName}#${method.name}(" +
            method.parameterTypes.joinToString(",") { it.simpleName } + ")"

    /**
     * `WallpaperInfo#getSubjectBitmap()` —— **只读放行，不补位、不顶替**。
     *
     * 这个 getter 决定"要不要再多画一层抠像"。谁来设、设的是哪份图，各处差别很大：
     *
     * | 场景 | 谁设的 | 设的是什么 |
     * | --- | --- | --- |
     * | 一级「我的搭配」当前模板卡 | 谁也不设（图片景深除外） | 卡片背景是**锁屏截图**，主体已经烘焙在图里了 |
     * | 一级历史卡 / 编辑页 | `TemplateApplyController#setTemplateConfigForHistoryBean` | `subject` 文件（原图空间），并且按采样比缩放过 `matrixArr` |
     * | 视频景深 | `CurrentTemplateApi#requireCurrentTemplate` | `WallpaperSource#getVideoDeepSubject()` |
     *
     * 之前我们无条件返回模块的图（原图空间），一级当前模板卡就多出一层"未裁切的主体"叠在已经正确的
     * 截图上面 —— 用户实测的错位就是这个；进编辑页点一次应用后卡片改走历史分支（两份图配套），所以又好了。
     *
     * 所以现在的规则很简单：**调用方设了就用它的**，没设就老实返回 null，让编辑器/预览自己决定
     * （编辑器会用自己的 AI 抠图或 `composeSubjectImage` 重新合成，而它读到的 `subject_mask`/
     * `subject` 文件都已经是我们的图了）。磁盘那一侧另有保障：`BitmapUtil` 读/写、`copyFile`、
     * provider 四条路都返回模块的图，内存里的旧图没有机会把文件写回去。
     */
    private fun hookSubjectBitmapCache(loader: ClassLoader) {
        hookAll(
            WALLPAPER_INFO,
            loader,
            "getSubjectBitmap",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val current = chain.proceed()
                    if (current != null) {
                        HookRuntime.vlog("getSubjectBitmap -> 放行调用方设好的图")
                    }
                    return current
                }
            },
        )
    }

    /** composeSubjectImage / loadCurrentTemplateSubjectCompat 读模板图都经过这两个方法。 */
    private fun hookBitmapReaders(loader: ClassLoader) {
        val reader = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val (context, config) = mayReplace(chain) ?: return chain.proceed()
                val path = chain.getArgs().getOrNull(0) as? String ?: return chain.proceed()
                val role = HookRuntime.roleOfPath(path) ?: return chain.proceed()
                if (isDerived(role)) return chain.proceed()

                val bitmap = HookRuntime.desiredBitmap(context, config, role)
                    ?: return chain.proceed()
                HookRuntime.vlog("读取 ${role.id} <- 替换后的图")
                return bitmap
            }
        }
        hookAll(BITMAP_UTIL, loader, "fromFile", reader)
        hookAll(BITMAP_UTIL, loader, "fromFile2", reader)
    }

    /** subject / subject_mask / crop_subject 的落盘最终都会走到 BitmapUtil#toFile。 */
    private fun hookBitmapWriters(loader: ClassLoader) {
        val writer = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val path = chain.getArgs().getOrNull(1) as? String ?: return chain.proceed()
                // 历史搭配目录不归我们管：直接放行，避免把"当前这套"顶进别的搭配里。
                if (HookRuntime.isHistoryPath(path)) return chain.proceed()
                val (context, config) = mayReplace(chain) ?: return chain.proceed()
                val role = HookRuntime.roleOfPath(path) ?: return chain.proceed()
                if (isDerived(role)) return chain.proceed()

                val bitmap = HookRuntime.desiredBitmap(context, config, role)
                    ?: return chain.proceed()
                if (HookRuntime.writeBitmap(path, bitmap)) {
                    HookRuntime.vlog("落盘 ${role.id} -> 替换后的图")
                    return true
                }
                return chain.proceed()
            }
        }
        listOf("toFile", "toWebp", "toPNG", "toJPEG").forEach {
            hookAll(BITMAP_UTIL, loader, it, writer)
        }
    }

    /** subject_mask 是字节级复制出来的（预设 mask -> templates/current/subject_mask），不会经过编码器。 */
    private fun hookFileCopies(loader: ClassLoader) {
        val copier = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                val path = chain.getArgs().getOrNull(1) as? String ?: return result
                // 历史搭配目录不归我们管：放行系统写的内容（不替换、不回写）。
                if (HookRuntime.isHistoryPath(path)) return result
                val (context, config) = mayReplace(chain) ?: return result
                val role = HookRuntime.roleOfPath(path) ?: return result
                if (isDerived(role)) return result

                val bitmap = HookRuntime.desiredBitmap(context, config, role) ?: return result
                HookRuntime.writeBitmap(path, bitmap)
                return result
            }
        }
        hookAll(FILE_UTIL, loader, "copyFile", copier)
        hookAll(FILE_UTIL, loader, "fastCopy", copier)
        hookAll(TEMPLATE_API_IMPL, loader, "copyFile", copier)
    }

    /**
     * 编辑器每次「应用」都会把整份模板配置写进 `Settings.Secure.constant_template_editor_info`
     * （`TemplateApiImpl.writeTemplateInfo`）。这是"用户动了壁纸"的唯一咽喉：
     *
     * * 换一张图 → 配置里的 source / imagePath / sha1 必变 → [HookRuntime.onTemplateInfoWritten]
     *   立刻解除保护，不用等下一次读图的 hook 点；
     * * 只是重新应用同一张 → 判据不变，保护继续（这正是要的：保护就要扛住编辑器的重写）。
     */
    private fun hookTemplateInfoWriter(loader: ClassLoader) {
        hookAll(
            TEMPLATE_API_IMPL,
            loader,
            "writeTemplateInfo",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val json = chain.getArgs().getOrNull(0) as? String
                    runCatching { HookRuntime.onTemplateInfoWritten(json) }
                        .onFailure { HookRuntime.log("壁纸判据检查失败: $it") }
                    return result
                }
            },
        )
    }

    /** 模板文件是通过 provider 发给另一个进程的，这里把句柄换成替换后的图。 */
    private fun hookTemplateProvider(loader: ClassLoader) {
        hookAll(
            TEMPLATE_FILE_PROVIDER,
            loader,
            "call",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    // 模块 App 可能正点着「选择图片」在等一次探测：借这次 provider 调用顺手做掉
                    // （只读，不受 protecting 闸门限制，也不改任何文件）。
                    // 优先用调用方带的 `arg`（App 用 `--arg <请求号>` 直接点名，最可靠）；
                    // 没有再看 prefs 里的请求号（启动那条兜底路）。
                    runCatching {
                        // ⚠️ 不能假定参数下标：`call(method, arg, extras)` 在部分框架实现里
                        // `getArgs()` 会把接收者/别的参数也算进来。这里直接**扫所有 String 参数**：
                        // `apply=<号>` 是注入请求；`depth=<号>` 是"景深检查"请求；
                        // 纯数字是"壁纸探测"请求号。其余（方法名等）不匹配。
                        val arg = chain.getArgs()
                            .filterIsInstance<String>()
                            .map { it.trim() }
                            .firstOrNull {
                                it.startsWith(APPLY_ARG_PREFIX) ||
                                    it.startsWith(DEPTH_ARG_PREFIX) ||
                                    it.toLongOrNull() != null
                            }
                            .orEmpty()
                        val applyToken = arg
                            .takeIf { it.startsWith(APPLY_ARG_PREFIX) }
                            ?.removePrefix(APPLY_ARG_PREFIX)
                            ?.toLongOrNull()
                        val depthToken = arg
                            .takeIf { it.startsWith(DEPTH_ARG_PREFIX) }
                            ?.removePrefix(DEPTH_ARG_PREFIX)
                            ?.toLongOrNull()
                        // context 允许为 null：景深检查要能在"进程刚被这条 call 拉起"时也进得来
                        //（probeDepthState 自己会轮询等 Application 就绪，再读设置回写）。
                        val context = HookRuntime.appContext()
                        when {
                            // 「立即替换」：App 点名让 hook 当场落盘（不再走 root 脚本）。
                            applyToken != null && context != null ->
                                HookRuntime.applyInjection(context, applyToken)
                            // 「点『选择图片』前的景深检查」：读 wallpaper_effect_type_2 回写。
                            depthToken != null -> HookRuntime.probeDepthState(context, depthToken)
                            context != null -> {
                                val probeToken = arg.toLongOrNull()
                                if (probeToken != null) {
                                    HookRuntime.probeWallpaperForToken(context, probeToken)
                                } else {
                                    HookRuntime.probeWallpaperIfRequested(context)
                                }
                            }
                        }
                    }
                    val (context, config) = mayReplace(chain) ?: return result
                    val method = chain.getArgs().getOrNull(0) as? String ?: return result
                    val argument = chain.getArgs().getOrNull(1) as? String ?: return result
                    val role = when (method) {
                        METHOD_BY_NAME -> MaskRole.entries.firstOrNull { it.id == argument }
                        METHOD_BY_PATH -> HookRuntime.roleOfPath(argument)
                        else -> null
                    } ?: return result
                    if (isDerived(role)) return result

                    val bitmap = HookRuntime.desiredBitmap(context, config, role) ?: return result
                    val bundle = result as? Bundle ?: return result

                    runCatching {
                        val cache = java.io.File(context.cacheDir, "hook_${role.id}.png")
                        java.io.FileOutputStream(cache).use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        }
                        android.os.ParcelFileDescriptor
                            .open(cache, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                            ?.let { descriptor ->
                                bundle.putParcelable(KEY_PARCEL_FILE_DESCRIPTOR, descriptor)
                                HookRuntime.vlog("provider 句柄 ${role.id} -> 替换后的图")
                            }
                    }.onFailure { HookRuntime.log("替换句柄失败: $it") }
                    return bundle
                }
            },
        )
    }

    /**
     * 编辑器（`com.miui.aod`）侧的**阈值 + 避让** —— 照抄 HyperChanger 的 AOD 分支
     * （`HyperSystemUiModule.kt` 的 `installDepthEffectHook`），不加自己的东西。
     *
     * 对应关系（一一对应，方便以后回去比对）：
     *
     * | # | HyperChanger | 我们 |
     * | --- | --- | --- |
     * | 1 | hook `DepthAvoidEvaluator$Threshold(double)`，`0.2 → 1.0` | [hookDepthImageThreshold] |
     * | 2 | `hookClassInitializer` 之后再反射确认一次 `IMAGE_THRESHOLD.rate` | [ensureDepthThresholdRaised] |
     * | 3 | `HierarchyImageAvoidController#isHierarchyEnable()` | [hookDepthAvoidanceBypass] |
     * | 4 | `HierarchyImageAvoidController#onHierarchyEnableChange(boolean, boolean)` | 同上 |
     */
    private fun installDepthEffectHook(loader: ClassLoader) {
        val ok = mutableListOf<String>()
        if (hookDepthImageThreshold(loader)) ok += "阈值ctor"
        if (hookDepthAvoidanceBypass(loader)) ok += "避让"
        HookRuntime.log("编辑器景深：阈值+避让 装上了 ${ok.size}/2（${ok.joinToString("、")}）")
    }

    /**
     * 编辑器侧的**壁纸能力位** —— 与 `installAodThirdPartyWallpaperDepthHook` 一一对应。
     *
     * 我们本来就是给**系统判定"不支持景深"的图**注入遮罩的：元数据不翻过来，后面的渲染根本不启动。
     *
     * | # | 对应 | 我们 |
     * | --- | --- | --- |
     * | 5 | `editor.data.bean.WallpaperInfo#getSupportSubject()` → `true` | [hookWallpaperDepthCapability] |
     * | 6 | 同上 `#getLargeScreenHierarchyEnable()` → 无参构造的实例 | 同上 |
     * | 7 | `WallpaperController$Companion#getPickWallpaperColorInfo(String, Integer, Integer)` | [hookPickWallpaperColorInfo] |
     * | 8 | `WallpaperController#needResetMagicType()` → `false` | [hookAodDepthExtras] |
     */
    private fun installAodThirdPartyWallpaperDepthHook(loader: ClassLoader) {
        val ok = mutableListOf<String>()
        if (hookWallpaperDepthCapability(loader, aod = true)) ok += "能力位"
        if (hookPickWallpaperColorInfo(loader)) ok += "配色/时钟样式"
        if (hookAodDepthExtras(loader)) ok += "needResetMagicType"
        HookRuntime.log("编辑器景深：壁纸能力位 装上了 ${ok.size}/3（${ok.joinToString("、")}）")
    }

    /**
     * 编辑器侧的避让判定 bypass —— 照抄 HyperChanger 的 `installDepthAvoidanceBypass`。
     *
     * 两个 hook 都要求 `isUserOpenHierarchy == true`（**用户自己已经把景深开关打开**），
     * 我们只是不让"遮挡时间过多"那条判定把他打回去：
     *
     * * `isHierarchyEnable()` —— 它是"要不要跑避让判定"的总闸，有些路径直接读它；
     * * `onHierarchyEnableChange(boolean, boolean)` —— 判定结果的唯一出口，
     *   一次覆盖 `CalculateWorker` 的像素校验、**双时钟**那条硬闸门、以及初始化阶段那一次判定。
     *
     * ⚠️ 与 HyperChanger 的唯一差别：**视频景深一个字节都不碰**（用户明确要求过）——
     * 第二个参数就是 `isDepthVideo`，为 true 时直接放行。HyperChanger 是把 `(true, arg1)`
     * 原样透传（等于也改了视频那一路），这里多一道短路。
     */
    private fun hookDepthAvoidanceBypass(loader: ClassLoader): Boolean {
        val controllerClass = runCatching {
            Class.forName(HIERARCHY_AVOID_CONTROLLER, false, loader)
        }.getOrNull()
        if (controllerClass == null) {
            HookRuntime.log("未找到 $HIERARCHY_AVOID_CONTROLLER，跳过编辑器避让 bypass")
            return false
        }
        val userOpenField = runCatching {
            controllerClass.getDeclaredField(USER_OPEN_HIERARCHY_FIELD).apply { isAccessible = true }
        }.getOrNull()
        if (userOpenField == null) {
            HookRuntime.log("$HIERARCHY_AVOID_CONTROLLER 里没有 $USER_OPEN_HIERARCHY_FIELD")
        }
        val userOpened = { instance: Any? ->
            userOpenField != null &&
                runCatching { userOpenField.getBoolean(instance) }.getOrDefault(false)
        }

        val hookedA = hookAll(
            HIERARCHY_AVOID_CONTROLLER,
            loader,
            "isHierarchyEnable",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    // 首次调用无条件留痕（判据一起打出来）—— 否则"日志里什么都没有"无法区分
                    // "根本没被调用"和"调用了但条件不过"。
                    depthLogOnce(
                        "enter-isHierarchyEnable",
                        "编辑器避让：isHierarchyEnable() 第 1 次被调用（开关=" +
                            "${HookRuntime.depthUnlockEnabled()}，isUserOpen=${userOpened(chain.thisObject)}，" +
                            "原值=$result）",
                    )
                    if (!HookRuntime.depthUnlockEnabled()) return result
                    if (!userOpened(chain.thisObject)) return result
                    depthLogOnce("editor-enable", "编辑器：isHierarchyEnable() 已强制 true")
                    return true
                }
            },
        )
        val hookedB = hookAll(
            HIERARCHY_AVOID_CONTROLLER,
            loader,
            "onHierarchyEnableChange",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val args = chain.getArgs()
                    depthLogOnce(
                        "enter-onHierarchyEnableChange",
                        "编辑器避让：onHierarchyEnableChange(${args.getOrNull(0)}, " +
                            "${args.getOrNull(1)}) 第 1 次被调用（开关=" +
                            "${HookRuntime.depthUnlockEnabled()}，isUserOpen=" +
                            "${userOpened(chain.thisObject)}）",
                    )
                    if (!HookRuntime.depthUnlockEnabled()) return chain.proceed()
                    if (!userOpened(chain.thisObject)) return chain.proceed()
                    // **视频景深一个字节都不碰**：第二个参数就是 `isDepthVideo`。
                    if (args.getOrNull(1) as? Boolean == true) return chain.proceed()

                    // 顺带把"根上那刀"补上：用户是在**进程起来之后**才打开这个开关的话，
                    // `DepthAvoidEvaluator` 的 `<clinit>` 早就跑完了（那时阈值还是 0.2）。
                    ensureDepthThresholdRaised(loader)

                    val replaced = args.toTypedArray()
                    replaced[0] = true
                    depthLogOnce("editor-enable-change", "编辑器：避让回调 false -> true 已生效")
                    return chain.proceed(replaced)
                }
            },
        )
        return hookedA || hookedB
    }

    /**
     * ★ HyperChanger `installAodThirdPartyWallpaperDepthHook` 里的**第三件 —— 我们此前一直漏掉的那一处**。
     *
     * `WallpaperController$Companion#getPickWallpaperColorInfo(String, Integer, Integer)`
     * 是编辑器取"壁纸配色 / 时钟样式"的地方，反编译产物原样长这样：
     *
     * ```java
     * PickWallpaperColorInfo info = new PickWallpaperColorInfo(style == null ? "classic" : style, null, null, 6, null);
     * if (APPLY_WITH_CLOCK_STYLE_TEMPLATE.contains(style)) {      // ← 只有这几个模板才带上
     *     info.setClockInfoStyle(num);  info.setSignatureAlignment(num2);  return info;
     * }
     * info.setClockInfoStyle(null);  info.setSignatureAlignment(null);  return info;  // ← 其它一律清空
     * ```
     *
     * `clockInfoStyle` / `signatureAlignment` 就是**时钟的样式与对齐**，景深那套避让矩形正是按它算的。
     * 我们注入遮罩的图走的不是那几个模板 ⇒ 原实现会把这两项**清成 null** ⇒ 时钟信息丢了、
     * 避让矩形对不上 ⇒ 编辑器里判"遮挡"或者干脆不画景深层。
     *
     * HyperChanger 的做法：`proceed()` 之后**无条件**把调用方自己的两个入参写回去。**原样照抄。**
     */
    private fun hookPickWallpaperColorInfo(loader: ClassLoader): Boolean =
        hookAll(
            WALLPAPER_CONTROLLER_COMPANION,
            loader,
            "getPickWallpaperColorInfo",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val args = chain.getArgs()
                    depthLogOnce(
                        "enter-pick-color",
                        "编辑器配色：getPickWallpaperColorInfo(style=${args.getOrNull(0)}, " +
                            "clockStyle=${args.getOrNull(1)}, align=${args.getOrNull(2)}) " +
                            "第 1 次被调用（开关=${HookRuntime.depthUnlockEnabled()}）",
                    )
                    if (!HookRuntime.depthUnlockEnabled()) return result
                    runCatching {
                        val type = result?.javaClass ?: return@runCatching
                        type.getMethod("setClockInfoStyle", Integer::class.java)
                            .invoke(result, args.getOrNull(1))
                        type.getMethod("setSignatureAlignment", Integer::class.java)
                            .invoke(result, args.getOrNull(2))
                        depthLogOnce(
                            "clock-info-style",
                            "编辑器配色：已写回时钟样式 / 签名对齐",
                        )
                    }.onFailure { HookRuntime.log("编辑器景深：写回时钟样式失败 $it") }
                    return result
                }
            },
        )

    /**
     * **只读**诊断：编辑器每次把景深按钮画出来时，记一下它的 `status`。
     *
     * 状态语义（`EffectsTemplateView#updateHierarchyButton`）：
     *
     * | status | 含义 | 按钮 |
     * | --- | --- | --- |
     * | **0** | `DISABLE` = **系统判"不支持景深"** | `disable()` + 未勾选（点不动） |
     * | 2 | 可点、未开 | `enable()` + 未勾选 |
     * | 1 / 3 / 5 | 已开 | `enable()` + 勾选 |
     * | 4 | `AVOID_CLOSE` = 判遮挡被打回 | `disable()` + 未勾选 |
     *
     * **只读**：不改参数、不改返回值、不碰任何文件 —— 存在的意义是下次日志能一句话告诉我们
     * "编辑界面不行"到底是 0（元数据问题，避让那套 hook 帮不上）还是 4（避让问题）。
     * 同一个 status 只记一次。
     */
    private fun hookHierarchyStatusDiagnostic(loader: ClassLoader) {
        hookAll(
            EFFECTS_TEMPLATE_VIEW,
            loader,
            "updateHierarchyButton",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val status = chain.getArg(0) as? Int
                    if (status != null && buttonStatusLogged.putIfAbsent(status, true) == null) {
                        val meaning = when (status) {
                            0 -> "DISABLE=系统判不支持景深（点不动）"
                            1, 3, 5 -> "已开启"
                            2, 6 -> "可点、未开"
                            4 -> "AVOID_CLOSE=被避让判定打回（遮挡时间过多）"
                            else -> "未知"
                        }
                        HookRuntime.log("编辑器景深按钮：status=$status（$meaning）")
                    }
                    return chain.proceed()
                }
            },
        )
    }

    /**
     * 「解除景深遮挡限制」的**根上那一刀**：把 `com.miui.clock.utils.avoid.DepthAvoidEvaluator` 的
     * **图像**阈值 `0.2 → 1.0`。
     *
     * ## 为什么非改这里不可（2026-10-01 用户报"编辑页正常、应用后锁屏把景深关了"）
     *
     * 编辑器和**锁屏**各有一套避让判定，我们原先只覆盖了编辑器那一套：
     *
     * | 在哪 | 判定 | 出口 |
     * | --- | --- | --- |
     * | 编辑器（`com.miui.aod:keyguardeditor`） | `CalculateWorker.calculateUnAlphaPercent`（率 0.2） | `HierarchyImageAvoidController#onHierarchyEnableChange` ← **[hookDepthAvoidanceBypass] 挂的就是它** |
     * | **锁屏（`com.android.systemui`）** | `KeyguardDepthInteractor` → `DepthBitmapAvoidConcurrent` → **`DepthAvoidEvaluator.IMAGE_THRESHOLD = Threshold(0.2)`** | 判为遮挡就 `setDepthTransitionAlpha(0f)` —— **把景深整层淡出** |
     *
     * 用户看到的"编辑页好好的、一应用锁屏就没景深"就是后者：**阈值本身还是 0.2**。
     * 两个 APK 里都有这个类（`深度 → 锁屏` 共用同一份 `com.miui.clock.utils.avoid`），
     * 所以**抬阈值这一处，两边一起解决**，这也是 HyperChanger「去除景深限制」的做法。
     *
     * ## 两条腿，缺一不可
     *
     * 1. **hook `Threshold(double)` 构造函数** —— 类的静态字段 `IMAGE_THRESHOLD` 是在 `<clinit>` 里
     *    `new Threshold(0.2d)` 出来的，钩住构造函数才能"出生就是 1.0"（应对类还没被加载的情况）。
     * 2. **反射把已经建好的那个对象的 `rate` 字段改成 1.0** —— 我们的 hook 通常**晚于** `<clinit>`
     *    （类很早就被加载了），这时构造函数那条腿根本不会触发，只能直接改对象。
     *    注意改的是 `Threshold` 的**实例**字段 `rate`（`setAccessible(true)` 后对非静态 final 是允许的），
     *    **不是**去替换 `public static final IMAGE_THRESHOLD` 那个静态字段（静态 final 反射改不了）。
     *
     * ## 只碰图像那一档
     *
     * 判定 `0.2`：`VIDEO_THRESHOLD = Threshold(0.5)` 是**另一个实例**，值不是 0.2 ⇒ 不会被我们改写。
     * **视频景深一行不动**（用户明确要求过）。
     */
    private fun hookDepthImageThreshold(loader: ClassLoader): Boolean {
        val thresholdClass = runCatching {
            Class.forName(DEPTH_THRESHOLD_CLASS, false, loader)
        }.getOrNull()
        if (thresholdClass == null) {
            HookRuntime.log("未找到 $DEPTH_THRESHOLD_CLASS，跳过景深图像阈值")
            return false
        }
        val constructor = runCatching {
            thresholdClass.getDeclaredConstructor(Double::class.javaPrimitiveType)
        }.getOrNull()
        if (constructor == null) {
            HookRuntime.log("$DEPTH_THRESHOLD_CLASS 里没有 (double) 构造函数，跳过")
            return false
        }
        hook(constructor).intercept(
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val original = chain.getArg(0) as? Double
                    depthLogOnce(
                        "enter-threshold-ctor",
                        "编辑器阈值：Threshold($original) 第 1 次被构造（开关=" +
                            "${HookRuntime.depthUnlockEnabled()}）",
                    )
                    if (original != DEFAULT_DEPTH_IMAGE_THRESHOLD) return chain.proceed()
                    if (!HookRuntime.depthUnlockEnabled()) return chain.proceed()
                    HookRuntime.vlog(
                        "解除景深遮挡限制：图像阈值 $DEFAULT_DEPTH_IMAGE_THRESHOLD -> $UNLIMITED_DEPTH_IMAGE_THRESHOLD",
                    )
                    return chain.proceed(arrayOf<Any?>(UNLIMITED_DEPTH_IMAGE_THRESHOLD))
                }
            },
        )
        HookRuntime.log("已挂 $DEPTH_THRESHOLD_CLASS(double)")
        // 第二条腿：类多半早就加载完了，直接把现成的那个对象的 rate 改掉。
        ensureDepthThresholdRaised(loader)
        return true
    }

    /** 只打一次日志用（这条是"到底抬上去没有"的唯一证据，所以用 `log` 而不是 `vlog`）。 */
    private var depthThresholdLogged = false

    /** 把 `DepthAvoidEvaluator.IMAGE_THRESHOLD.rate` 抬到 1.0（幂等：已经是 1.0 就直接返回）。 */
    private fun ensureDepthThresholdRaised(loader: ClassLoader) {
        if (!HookRuntime.depthUnlockEnabled()) return
        runCatching {
            val evaluator = Class.forName(DEPTH_EVALUATOR_CLASS, false, loader)
            // 注意 `get(null)` 读静态字段会**触发 `<clinit>`** ——
            // 也就是说"类还没初始化"的情况下，`<clinit>` 里的 `new Threshold(0.2)` 会走到我们挂的
            // 构造函数 hook 上，出生就是 1.0；下面这段只是**兜住"类早就初始化过了"**的情况。
            val threshold = evaluator.getDeclaredField(IMAGE_THRESHOLD_FIELD).get(null) ?: return
            val rate = threshold.javaClass.getDeclaredField(THRESHOLD_RATE_FIELD)
                .apply { isAccessible = true }
            if (rate.getDouble(threshold) == UNLIMITED_DEPTH_IMAGE_THRESHOLD) {
                if (!depthThresholdLogged) {
                    depthThresholdLogged = true
                    HookRuntime.log("解除景深遮挡限制：图像遮挡阈值 = $UNLIMITED_DEPTH_IMAGE_THRESHOLD（已生效）")
                }
                return
            }
            rate.setDouble(threshold, UNLIMITED_DEPTH_IMAGE_THRESHOLD)
            depthThresholdLogged = true
            HookRuntime.log(
                "解除景深遮挡限制：图像遮挡阈值 $DEFAULT_DEPTH_IMAGE_THRESHOLD -> " +
                    "$UNLIMITED_DEPTH_IMAGE_THRESHOLD（视频阈值 $DEFAULT_DEPTH_VIDEO_THRESHOLD 不动）",
            )
        }.onFailure { HookRuntime.log("解除景深遮挡限制：抬阈值失败 $it") }
    }

    /**
     * **锁屏（SystemUI）侧的整套**「解除景深遮挡限制」—— 照抄 HyperChanger「去除景深限制」的确定做法。
     *
     * ## 为什么光抬阈值不够（2026-10-01 第二次踩）
     *
     * 只把 `DepthAvoidEvaluator.IMAGE_THRESHOLD` 抬到 1.0 **不会**让"编辑页正常、锁屏没景深"变好，
     * 因为锁屏要不要画景深层**不是只由避让判定决定的**。HyperChanger 那份实现里一共动了四处，
     * 后三处才是我们缺的：
     *
     * | # | 挂在哪 | 干什么 | 解决什么 |
     * | --- | --- | --- | --- |
     * | 1 | `DepthAvoidEvaluator$Threshold(double)` + 反射改 `rate` | 图像阈值 0.2 → 1.0 | 避让判定永远"不遮挡" |
     * | 2 | `KeyguardDepthInteractor#updateAvoidStatus()` | 开关开就 `_avoidState = false` 并**整段跳过** | 判定里还有别的早退分支（屏幕尺寸/矩形非法直接判遮挡），光抬阈值盖不住 |
     * | 3 | `KeyguardDepthInteractor#setDepthTransitionAlpha(...)` | 调之前先把 `_avoidState` 清成 false | `_avoidState` 一旦是 true 会**一直把景深淡出**，阈值改了也不会自己复位 |
     * | 4 | `KeyguardPanelViewController#updateShowDepthState()` | 强制 `depthEffectEnable = true`、`depthEffectEnableInner = true`；proceed 之后若 `isActualDisplayDepth` 还是 false 就置 true 并 `updateKeyguardElementsVisibility()` | **"锁屏把景深关了"最直接的那两个状态位** |
     * | 5 | `WallpaperInfo#getSupportSubject()` → `true`、`getLargeScreenHierarchyEnable()` → 全 true 的实例 | 壁纸模型自报"支持主体/支持景深" | 我们本来就是给**系统判定"不支持景深"的图**注入遮罩的 —— 元数据不翻过来，后面的渲染根本不启动 |
     *
     * **视频景深一行不动**：这些点全是图像景深那条链（`depthVideoEnable` / `VIDEO_THRESHOLD` 一概没碰）。
     */
    private fun hookDepthLockScreen(loader: ClassLoader) {
        val interactor = runCatching {
            Class.forName(KEYGUARD_DEPTH_INTERACTOR, false, loader)
        }.getOrNull()
        if (interactor == null) {
            HookRuntime.log("未找到 $KEYGUARD_DEPTH_INTERACTOR，跳过锁屏景深 hook")
        } else {
            val clearAvoid = buildAvoidStateClearer(interactor)
            // #2 判定入口：跳过 + 清状态（照抄 HyperChanger：return null 表示不执行原实现）
            hookAll(
                KEYGUARD_DEPTH_INTERACTOR,
                loader,
                "updateAvoidStatus",
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (!HookRuntime.depthUnlockEnabled()) return chain.proceed()
                        runCatching { ensureDepthThresholdRaised(loader) }
                        runCatching { clearAvoid?.invoke(chain.thisObject) }
                        depthLogOnce("avoid-skip", "锁屏：已跳过遮挡判定（updateAvoidStatus）")
                        return null
                    }
                },
            )
            // #3 淡出动画：调之前先清状态，免得 _avoidState 还是 true 又把景深淡出去
            hookAll(
                KEYGUARD_DEPTH_INTERACTOR,
                loader,
                "setDepthTransitionAlpha",
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (HookRuntime.depthUnlockEnabled()) {
                            runCatching { clearAvoid?.invoke(chain.thisObject) }
                        }
                        return chain.proceed()
                    }
                },
            )
        }
        hookPanelDepthState(loader)
        hookWallpaperDepthCapability(loader)
    }

    /**
     * 构造"把 `KeyguardDepthInteractor._avoidState` 置回 false"的反射调用器。
     *
     * `_avoidState` 是 `StateFlowImpl`，写它要走编译器生成的 `updateState$1(Object, Object)`。
     * 名字是合成的（带 `$1` 后缀），所以按"参数两个、名字匹配"去找；找不到就返回 null（各 hook 自行跳过）。
     */
    private fun buildAvoidStateClearer(interactor: Class<*>): ((Any?) -> Unit)? = runCatching {
        val field = interactor.getDeclaredField(AVOID_STATE_FIELD).apply { isAccessible = true }
        val stateClass = field.type
        val update = stateClass.methods.firstOrNull {
            it.name == AVOID_STATE_UPDATE_METHOD && it.parameterCount == 2
        } ?: error("找不到 ${stateClass.name}.$AVOID_STATE_UPDATE_METHOD")
        val read = stateClass.methods.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
            ?: error("找不到 ${stateClass.name}.getValue")
        val clearer: (Any?) -> Unit = { instance ->
            val state = field.get(instance)
            // ① 先**原样照抄 HyperChanger** 来一次（CAS 令牌传 null）；
            runCatching { update.invoke(state, null, false) }
            // ② 再兜一层：`updateState$1(旧值, 新值)` 是 **CAS**，状态早就被写过一次时 ① 不成立，
            //    所以读回当前值当令牌重写（读回还不是 false 就再来）。
            var attempts = 0
            while (attempts++ < 4) {
                val current = read.invoke(state)
                if (current == false) break
                update.invoke(state, current, false)
            }
        }
        clearer
    }.onFailure {
        HookRuntime.log("解除景深遮挡限制：拿不到 _avoidState 写入器（$it）")
    }.getOrNull()

    /** #4：`KeyguardPanelViewController#updateShowDepthState()` —— "锁屏把景深关了"最直接的那两个状态位。 */
    private fun hookPanelDepthState(loader: ClassLoader) {
        val panel = runCatching {
            Class.forName(KEYGUARD_PANEL_VIEW_CONTROLLER, false, loader)
        }.getOrNull()
        if (panel == null) {
            HookRuntime.log("未找到 $KEYGUARD_PANEL_VIEW_CONTROLLER，跳过锁屏景深显示状态 hook")
            return
        }
        val depthEnabled = runCatching { panel.getDeclaredField("depthEffectEnable").apply { isAccessible = true } }.getOrNull()
        val interactorField = runCatching { panel.getDeclaredField("keyguardDepthInteractor").apply { isAccessible = true } }.getOrNull()
        val innerEnabled = runCatching {
            interactorField?.type?.getDeclaredField("depthEffectEnableInner")?.apply { isAccessible = true }
        }.getOrNull()
        val actualDisplay = runCatching {
            interactorField?.type?.getDeclaredField("isActualDisplayDepth")?.apply { isAccessible = true }
        }.getOrNull()
        val updateElements = runCatching { panel.getMethod("updateKeyguardElementsVisibility") }.getOrNull()
        if (depthEnabled == null || interactorField == null || innerEnabled == null || actualDisplay == null) {
            HookRuntime.log("解除景深遮挡限制：锁屏景深状态字段不全，跳过该 hook")
            return
        }
        hookAll(
            KEYGUARD_PANEL_VIEW_CONTROLLER,
            loader,
            "updateShowDepthState",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val self = chain.thisObject
                    if (HookRuntime.depthUnlockEnabled()) {
                        runCatching {
                            depthEnabled.setBoolean(self, true)
                            innerEnabled.setBoolean(interactorField.get(self), true)
                            depthLogOnce("display-state", "锁屏：已强制 depthEffectEnable=true（updateShowDepthState）")
                        }.onFailure { HookRuntime.log("解除景深遮挡限制：写 depthEffectEnable 失败（$it）") }
                    }
                    val result = chain.proceed()
                    if (HookRuntime.depthUnlockEnabled()) {
                        runCatching {
                            val depth = interactorField.get(self)
                            // `isActualDisplayDepth` 才是"这一帧到底画不画景深"，它若为 false 就硬翻过来，
                            // 然后让面板自己重算一次可见性（照抄 HyperChanger）。
                            if (!actualDisplay.getBoolean(depth)) {
                                actualDisplay.setBoolean(depth, true)
                                updateElements?.invoke(self)
                                depthLogOnce("actual-display", "锁屏：isActualDisplayDepth 已翻成 true")
                            }
                        }.onFailure { HookRuntime.log("解除景深遮挡限制：写 isActualDisplayDepth 失败（$it）") }
                    }
                    return result
                }
            },
        )
    }

    /**
     * #5：让**壁纸模型**自报"支持主体 / 支持景深"。
     *
     * 两套模型各挂一次：SystemUI 的 `com.android.keyguard.wallpaper.entity.WallpaperInfo`
     * 和编辑器（aod）的 `com.miui.keyguard.editor.data.bean.WallpaperInfo` ——
     * 两个类的**方法名一样**，所以用同一个函数分别调。
     *
     * `getLargeScreenHierarchyEnable()` 返回的是一个 Kotlin data class，构造一次后**复用同一个实例**
     * （每次 new 一个没必要，而且它的字段全是 val）。
     */
    private fun hookWallpaperDepthCapability(loader: ClassLoader, aod: Boolean = false): Boolean {
        val className = if (aod) AOD_WALLPAPER_INFO_CLASS else SYSUI_WALLPAPER_INFO_CLASS
        val info = runCatching { Class.forName(className, false, loader) }.getOrNull()
        if (info == null) {
            HookRuntime.log("未找到 $className，跳过壁纸景深能力 hook")
            return false
        }
        val hierarchy = runCatching { forcedHierarchyEnable(loader, aod) }.getOrNull()
        if (hierarchy == null) {
            HookRuntime.log("解除景深遮挡限制：构造 ${if (aod) AOD_HIERARCHY_CLASS else SYSUI_HIERARCHY_CLASS} 失败")
        }
        val hookedSubject = hookAll(
            className,
            loader,
            "getSupportSubject",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    depthLogOnce(
                        "enter-support-subject",
                        "壁纸能力位：getSupportSubject() 第 1 次被调用（开关=" +
                            "${HookRuntime.depthUnlockEnabled()}，原值=$result）",
                    )
                    if (!HookRuntime.depthUnlockEnabled()) return result
                    depthLogOnce("support-subject", "壁纸能力位：getSupportSubject() 已强制 true")
                    return true
                }
            },
        )
        val hookedHierarchy = if (hierarchy == null) {
            false
        } else {
            hookAll(
                className,
                loader,
                "getLargeScreenHierarchyEnable",
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        depthLogOnce(
                            "enter-hierarchy-enable",
                            "壁纸能力位：getLargeScreenHierarchyEnable() 第 1 次被调用（开关=" +
                                "${HookRuntime.depthUnlockEnabled()}，原值=${result?.javaClass?.simpleName}）",
                        )
                        if (!HookRuntime.depthUnlockEnabled()) return result
                        depthLogOnce("hierarchy-enable", "壁纸能力位：getLargeScreenHierarchyEnable() 已强制")
                        return hierarchy
                    }
                },
            )
        }
        return hookedSubject || hookedHierarchy
    }

    /** aod 侧的第三件：`WallpaperController#needResetMagicType()` → false（别把 `magicType` 重置掉）。 */
    private fun hookAodDepthExtras(loader: ClassLoader): Boolean =
        hookAll(
            WALLPAPER_CONTROLLER,
            loader,
            "needResetMagicType",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    depthLogOnce(
                        "enter-need-reset",
                        "编辑器：needResetMagicType() 第 1 次被调用（开关=" +
                            "${HookRuntime.depthUnlockEnabled()}，原值=$result）",
                    )
                    if (!HookRuntime.depthUnlockEnabled()) return result
                    depthLogOnce("no-reset-magic", "编辑器：needResetMagicType() 已强制 false")
                    return false
                }
            },
        )

    /**
     * 造一个"全 true"的 `LargeScreenHierarchyEnable`。
     *
     * 两个类形状不同（都是 Kotlin data class，编译器生成的构造器不一样）：
     * * SystemUI 版：`(boolean × 9)`；
     * * aod 版：**无参**构造器，默认值本身就是 true/false 混合（等价于"都支持"）。
     */
    private fun forcedHierarchyEnable(loader: ClassLoader, aod: Boolean): Any? {
        val clazz = Class.forName(if (aod) AOD_HIERARCHY_CLASS else SYSUI_HIERARCHY_CLASS, false, loader)
        return if (aod) {
            clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        } else {
            val nine = Array(9) { Boolean::class.javaPrimitiveType }
            clazz.getDeclaredConstructor(*nine).apply { isAccessible = true }
                .newInstance(*Array(9) { true })
        }
    }

    private companion object {
        const val TAG = "HyperExtreme"
        const val TARGET_PACKAGE = "com.miui.aod"
        const val MODULE_PACKAGE = "com.frederic22.hyperextreme"

        /**
         * 手工维护的构建标记 —— 只为了在日志里一眼看出"装的到底是哪一版"。
         *
         * 起因：排查时连续几轮都只能看到旧的「已挂」日志，无法区分"没装新包"和"新逻辑没跑到"。
         * **每次改动都更新它。**
         */
        const val BUILD_TAG = "2026-11-02.19-50 v1.6.0（深度模型换代 DA3 双模型：Small/Base 内置可选、界面加「推理模型」下拉、预算 10s→120s、缓存按 meta 失效、层图 FORMAT 66、GitHub Actions 构建）"

        /** 第二个作用域（2026-09-28 起）：只为了锁屏那个「自定义锁屏」按钮的层级。 */
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** 第三个作用域（2026-10-21 起）：壁纸进程，只装「同一张壁纸」只读探针（WPROBE）。 */
        const val WALLPAPER_PACKAGE = "com.miui.miwallpaper"

        /** 锁屏编辑子进程（`com.miui.aod:keyguardeditor`）—— 编辑器扩展（时钟深浅）只装这里，见 [isKeyguardEditorProcess]。 */
        const val KEYGUARD_EDITOR_PROCESS = "com.miui.aod:keyguardeditor"

        /**
         * SystemUI 那条链的"装好了"标记。
         *
         * **只有真的挂上至少一处 hook 才会置位**（见 [installSystemUiHooks]）——
         * 曾经是"进函数就置位"，结果一个用错 loader 的调用把机会用掉了，后面拿对 loader 的
         * 那次反而被挡回，四处 hook 全都没装上。
         */
        private val systemUiHooksInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 等 `onPackageReady` 的兜底时长：500ms × 24 ≈ 12 秒。 */
        const val SYSUI_LOADER_FALLBACK_ATTEMPTS = 24
        const val SYSUI_LOADER_FALLBACK_INTERVAL_MS = 500L
        /** `View` 本身 —— hook 它声明的 `onAttachedToWindow`，用来"按 id 名认人"。 */
        const val VIEW_CLASS = "android.view.View"
        const val SETTINGS_VIEW_BINDER =
            "com.android.systemui.keyguard.ui.binder.KeyguardSettingsViewBinder"
        const val KEYGUARD_SETTINGS_SECTION =
            "com.android.systemui.keyguard.ui.view.layout.sections.DefaultSettingsPopupMenuSection"
        const val TOUCH_INTERACTOR =
            "com.android.systemui.keyguard.domain.interactor.KeyguardTouchHandlingInteractor"
        const val WALLPAPER_INFO = "com.miui.keyguard.editor.data.bean.WallpaperInfo"
        const val BITMAP_UTIL = "com.miui.keyguard.editor.utils.BitmapUtil"
        const val FILE_UTIL = "com.miui.keyguard.editor.utils.FileUtil"
        const val TEMPLATE_API_IMPL = "com.miui.keyguard.editor.data.template.TemplateApiImpl"
        const val TEMPLATE_FILE_PROVIDER =
            "com.miui.keyguard.editor.data.template.TemplateFileProvider"
        const val METHOD_BY_NAME = "callGetFileDescriptorByFileName"
        const val METHOD_BY_PATH = "callGetFileDescriptor"

        /** provider 调用的 `--arg` 前缀：「立即替换」的落盘请求（`apply=<请求号>`）。 */
        const val APPLY_ARG_PREFIX = "apply="

        /** provider 调用的 `--arg` 前缀：「点选图前的景深检查」请求（`depth=<请求号>`）。 */
        const val DEPTH_ARG_PREFIX = "depth="
        const val KEY_PARCEL_FILE_DESCRIPTOR = "parcelFileDescriptor"

        /** 「解除景深遮挡限制」的挂载点：**编辑器侧**避让判定的唯一出口。 */
        const val HIERARCHY_AVOID_CONTROLLER =
            "com.miui.keyguard.editor.utils.HierarchyImageAvoidController"

        // ---------------------------------------------------------------- 景深避让阈值（两边共用同一个类）

        /**
         * 避让评估器。`com.miui.aod` 与 `com.android.systemui` **两个 APK 里都有这个类** ——
         * 编辑器和锁屏各跑自己进程里的那一份，所以阈值要在两个进程里各抬一次。
         */
        const val DEPTH_EVALUATOR_CLASS = "com.miui.clock.utils.avoid.DepthAvoidEvaluator"

        /** 阈值载体：`DepthAvoidEvaluator$Threshold`，静态字段 `IMAGE_THRESHOLD` 是 `Threshold(0.2)`。 */
        const val DEPTH_THRESHOLD_CLASS = "com.miui.clock.utils.avoid.DepthAvoidEvaluator\$Threshold"

        /** 锁屏侧的运行时避让判定（补刀用，见 [hookDepthLockScreen]）。 */
        const val KEYGUARD_DEPTH_INTERACTOR = "com.android.keyguard.depth.KeyguardDepthInteractor"

        /** 锁屏面板控制器：`depthEffectEnable` / `keyguardDepthInteractor` / `updateShowDepthState()`。 */
        const val KEYGUARD_PANEL_VIEW_CONTROLLER = "com.android.keyguard.panel.KeyguardPanelViewController"

        /** SystemUI 侧的壁纸模型与"大屏景深支持位"。 */
        const val SYSUI_WALLPAPER_INFO_CLASS = "com.android.keyguard.wallpaper.entity.WallpaperInfo"
        const val SYSUI_HIERARCHY_CLASS = "com.android.keyguard.wallpaper.entity.LargeScreenHierarchyEnable"

        /** 编辑器（aod）侧的同名两件套 —— 类名不同、方法名一样。 */
        const val AOD_WALLPAPER_INFO_CLASS = "com.miui.keyguard.editor.data.bean.WallpaperInfo"
        const val AOD_HIERARCHY_CLASS = "com.miui.keyguard.editor.data.bean.LargeScreenHierarchyEnable"

        /** 编辑器侧的壁纸控制器：`needResetMagicType()`（返回 true 会把 `magicType` 重置掉 = 景深没了）。 */
        const val WALLPAPER_CONTROLLER = "com.miui.keyguard.editor.edit.wallpaper.WallpaperController"

        /** 编辑器页 —— 只读诊断 [hookHierarchyStatusDiagnostic] 的挂载点（`updateHierarchyButton`）。 */
        const val EFFECTS_TEMPLATE_VIEW =
            "com.miui.keyguard.editor.edit.base.EffectsTemplateView"

        /**
         * `WallpaperController` 的伴生对象 —— `getPickWallpaperColorInfo(String, Integer, Integer)`
         * 在这里面（HyperChanger `installAodThirdPartyWallpaperDepthHook` 的第三件）。
         *
         * 原实现只对 `APPLY_WITH_CLOCK_STYLE_TEMPLATE` 里的模板保留 `clockInfoStyle` /
         * `signatureAlignment`，其它一律清成 null ⇒ 时钟样式丢了、景深避让矩形对不上。
         */
        const val WALLPAPER_CONTROLLER_COMPANION =
            "com.miui.keyguard.editor.edit.wallpaper.WallpaperController\$Companion"

        /** `HierarchyImageAvoidController` 里"用户是否自己开着景深"那个字段。 */
        const val USER_OPEN_HIERARCHY_FIELD = "isUserOpenHierarchy"

        /** `KeyguardDepthInteractor._avoidState`（`StateFlowImpl`）与它的写入方法（合成名，按形状找）。 */
        const val AVOID_STATE_FIELD = "_avoidState"
        const val AVOID_STATE_UPDATE_METHOD = "updateState\u00241"

        const val IMAGE_THRESHOLD_FIELD = "IMAGE_THRESHOLD"
        const val THRESHOLD_RATE_FIELD = "rate"

        /** 系统原值：时间区域里"非透明像素占比 < 20%"才算不遮挡。改成 [UNLIMITED_DEPTH_IMAGE_THRESHOLD] 等于永不遮挡。 */
        const val DEFAULT_DEPTH_IMAGE_THRESHOLD = 0.2

        /**
         * **视频**那一档的原值，只用于日志里说明"没动它"。
         *
         * `VIDEO_THRESHOLD` 是**另一个** `Threshold` 实例，值不是 0.2 ⇒ 构造函数 hook 的判据
         * （`original == 0.2`）天然不会碰它，视频景深一个字节不动。
         */
        const val DEFAULT_DEPTH_VIDEO_THRESHOLD = 0.5

        /** 抬到 1.0 = 允许 100% 被主体遮挡 ⇒ 评估器永远判"不遮挡"。 */
        const val UNLIMITED_DEPTH_IMAGE_THRESHOLD = 1.0
    }
}
