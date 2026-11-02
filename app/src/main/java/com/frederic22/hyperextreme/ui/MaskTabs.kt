package com.frederic22.hyperextreme.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frederic22.hyperextreme.R
import com.frederic22.hyperextreme.data.AodPackageName
import com.frederic22.hyperextreme.data.MaskRole
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.SpatialControl
import com.frederic22.hyperextreme.data.MiuiHomePackageName
import com.frederic22.hyperextreme.data.MiuiWallpaperPackageName
import com.frederic22.hyperextreme.data.SystemUiPackageName
import com.frederic22.hyperextreme.hook.HookRuntime
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.frederic22.hyperextreme.ui.nav.NavBgState
import com.frederic22.hyperextreme.ui.nav.reportSliderRect
import java.util.Locale
import kotlin.math.roundToInt

// ------------------------------------------------------------------ 主页

/**
 * 主页。
 *
 * 2026-10-29：状态卡（[BigStatusCard]）从关于页**搬回主页最上面**（用户指定）——
 * 「模块有没有激活」是打开 App 第一眼要确认的事，放在关于页要多点一次才看得到。
 * 顺序固定为：状态卡 → 「未检测到Root」卡（条件显示）→ 设备信息卡。
 *
 * 同一天删掉了底部的「去选图替换」按钮：二级页入口在「功能」页签里已经有了，这里是重复路径。
 */
@Composable
internal fun HomeTab(state: MaskUiState) {
    val context = state.context
    val activated = state.activated
    val haptic = rememberClickHaptic()

    TabColumn {
        // 模块激活状态：**永远是第一张**（即使下面的 Root 卡也在）。
        BigStatusCard(
            activated = state.activated,
            hasRoot = state.rootAvailable,
            version = appVersion(context),
            settled = state.activationSettled,
        )

        // 记录里是"无 root"时给这张卡 + 重测入口；记录是"有 root"时这张卡不出现，
        // 只有「立即替换」跑完发现 su 不可用才会把记录改回"无 root"。
        //
        // 配色 2026-09-29 改成**橙**（原来是 `errorContainer / onErrorContainer` 那套红）：
        // 与状态卡的"无 Root"档**同一组色值**（`StatusCardAmber*`，见 MaskSwapScreen），
        // 两张卡放一起时是同一种"缺东西但还能修"的语气，而不是"致命错误"。
        if (activated && state.rootAvailable == false) {
            // 卡面是固定色值、不跟动态取色，所以深浅色得自己判（和关于页状态卡一个套路）。
            val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp),
                insideMargin = PaddingValues(0.dp),
                // 文字颜色**刻意不显式指定**：让它走 Card 默认的 contentColor ——
                // 关于页状态卡那行「模块未激活」也是不指定，两边才真的一致（用户要求"颜色保持一致"）。
                colors = CardDefaults.defaultColors(
                    color = if (dark) StatusCardAmberDark else StatusCardAmberLight,
                ),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "未检测到Root",
                        // 字号字重与关于页「模块未激活」那行**逐字一致**（22.sp / SemiBold），
                        // 不用 `textStyles.title1` —— 那个在两处会算出不同的大小。
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        text = "刷新 Root",
                        onClick = { haptic(); state.refreshRoot() },
                        modifier = Modifier.fillMaxWidth(),
                        // 底色 = 状态卡那个图标的深橙；文字**恒定白色**（2026-10-29 用户指定，
                        // 原来是"浅色黑、深色白"—— 橙底上黑字显得脏）。
                        // ⚠️ 参数名是 `textColor` **不是** `contentColor`
                        //（`TextButtonColors` 四个字段是 color / disabledColor / textColor / disabledTextColor；
                        //  写成 contentColor 编译直接报错，javap + Kotlin 元数据核过）。
                        colors = ButtonDefaults.textButtonColors(
                            color = StatusCardAmberGlyph,
                            textColor = Color.White,
                        ),
                    )
                }
            }
        }

        // 设备信息卡。
        //
        // 2026-10-29：① 砍掉「模块版本」「目标应用」两项；
        // ② 排版改用官方 `BasicComponent` —— 标题 `headline1`(17sp) 就是设置项正文的字号，
        //    summary `body2`(14sp) 就是设置项副标题的字号，行高（≥56dp）与 16dp 内边距
        //    全是官方那套，和设置页的选项逐像素一致（原来的自绘 `InfoRow` 已删除）。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            BasicComponent(title = "设备名称", summary = deviceName(context))
            BasicComponent(title = "设备型号", summary = Build.MODEL)
            BasicComponent(title = "HyperOS 版本", summary = osVersionName())
            BasicComponent(title = "Android 版本", summary = androidVersionName())
        }

        // 捐赠卡（2026-10-29）：整行可点 → 爱发电主页。
        // 排版复用 [LinkRow]（标题 + 右侧箭头，与关于页「开源代码许可」那些行同款）。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            LinkRow(title = "通过捐赠支持开发者", summary = null) {
                openUrl(context, DonateUrl)
            }
        }
    }
}

// ------------------------------------------------------------------ 功能（一级：四张入口卡片）

/**
 * 一级功能页：**一张卡片一个作用域应用**（图标 + 名称 + 包名 + 右箭头），点进去是二级页。
 *
 * 2026-09-29 改版前，这个位置直接就是"选图 + 立即替换"那一整页；
 * 现在那些内容搬进了二级页 [MaskReplaceDetail]，一级只留这几张入口卡片。
 */
@Composable
internal fun FunctionHome(onOpen: (DetailPage) -> Unit) {
    TabColumn {
        AppEntryCard(
            packageName = AodPackageName,
            title = "锁屏遮罩替换",
            onClick = { onOpen(DetailPage.MaskReplace) },
        )
        // 「视差壁纸」（2026-10-31 由「视差壁纸效果」改名）：与「锁屏遮罩替换」同构 ——
        // 同一套系统转场 / 二级页模板。二级页顶栏标题见 `MaskDetailActivity` 的 when。
        AppEntryCard(
            packageName = AodPackageName,
            title = "视差壁纸",
            onClick = { onOpen(DetailPage.ParallaxBg) },
        )
        // 「空间壁纸」（2026-10-22）：**与视差完全独立的另一条链** ——
        // 视差是「两层平面各自按传感器平移」；空间壁纸是「深度图分 N 层 + 真 3D 透视转动」。
        // 两者共用「用背景图」这个前提，但状态、开关、渲染管线**互不影响**，
        // 关掉其中任何一个都不影响另一个。入口紧挨着视差，方便对比。
        AppEntryCard(
            // 2026-10-29：图标/包名改成**壁纸服务**本身（空间壁纸是靠改壁纸层实现的，
            // 挂在 AOD 名下不准确）。
            packageName = MiuiWallpaperPackageName,
            title = "空间壁纸",
            onClick = { onOpen(DetailPage.SpatialWallpaper) },
        )
        AppEntryCard(
            packageName = SystemUiPackageName,
            title = "系统界面",
            onClick = { onOpen(DetailPage.SystemUi) },
        )
        // 「系统桌面」（2026-10-16）：与上面几张同构 —— 「多任务」页的入口。
        // 触发信号由桌面自己发给 SystemUI，模块**不往桌面进程装载任何东西**；
        // 生效逻辑（多任务出现 → 藏状态栏内容、退出还原）见 hook/RecentsStatusBar。
        AppEntryCard(
            packageName = MiuiHomePackageName,
            title = "系统桌面",
            onClick = { onOpen(DetailPage.Desktop) },
        )
    }
}

/**
 * 一级页的「应用入口」卡片。
 *
 * 图标走**系统里真实的那一个**（`PackageManager.getApplicationIcon`），和桌面 / 设置里看到的一致，
 * 不另外自带图。取不到（理论上不会）就只显示文字，不留空位。
 */
@Composable
private fun AppEntryCard(packageName: String, title: String, onClick: () -> Unit) {
    val haptic = rememberClickHaptic()
    val icon = rememberAppIcon(packageName)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 卡片里只有这一行，所以整行可点。
                .clickable { haptic(); onClick() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MiuixTheme.textStyles.headline1)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = packageName,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(8.dp))
            // 箭头与**关于页的 [LinkArrow] 严格一致**：`Image` + `size(16.dp)` + 同一个 tint。
            // 原来这里是 miuix 的 `Icon`（默认 24dp），卡片右侧那个箭头明显偏大（2026-09-29 用户指出）。
            // 两处共用同一份写法，别再各写一套。
            Image(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantActions),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 取系统里那个应用的真实图标；拿不到就返回 null（卡片只显示文字）。 */
@Composable
private fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName)
                .toBitmapOrNull()
                ?.asImageBitmap()
        }.getOrNull()
    }
}

/**
 * `Drawable` → `Bitmap`。
 *
 * 不用 `androidx.core` 那个 `toBitmap()`：本工程没有显式依赖 core-ktx，而这点活儿自己画一遍就够了
 * （自适应图标也能按 bounds 正确渲染出来）。
 */
private fun Drawable.toBitmapOrNull(): Bitmap? = runCatching {
    if (this is BitmapDrawable) {
        bitmap?.let { return@runCatching it }
    }
    val width = intrinsicWidth.takeIf { it > 0 } ?: FallbackIconSize
    val height = intrinsicHeight.takeIf { it > 0 } ?: FallbackIconSize
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, width, height)
    draw(Canvas(result))
    result
}.getOrNull()

/** 图标拿不到内在尺寸时按这个边长画（兜底；这两个系统应用实际上都有尺寸）。 */
private const val FallbackIconSize = 144

// ------------------------------------------------------------------ 功能（二级：锁屏遮罩替换）

/**
 * 二级页「锁屏遮罩替换」的全部内容 —— 就是**原来的整个功能页**，2026-09-29 由一级降为二级。
 *
 * 2026-09-29 又追加了设置页搬来的「替换行为」（只含「替换前备份原文件」）—— 标题沿用设置页那两个字的原文。
 *
 * **刻意不用 [TabColumn]**：那里带着 120dp 的底栏留白，而二级页是**盖住底栏**的全屏页，
 * 留那么多空白没有意义。
 */
@Composable
internal fun MaskReplaceDetail(state: MaskUiState, onPick: (MaskRole) -> Unit) {
    val haptic = rememberClickHaptic()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberPageScroll())
            .padding(top = LocalTopBarHeight.current, bottom = 32.dp),
    ) {
        // 这一页所有按钮都先过 [MaskUiState.requireActivated]：状态不是「模块已激活」时
        // 只提示"模块未激活，请先激活"、什么都不做。闸门放在**外层**，原来的 root 实测
        // （onPick 里的 ensureRoot）保持不动 —— 那道是兜底，防"记录说有 root、其实已经被撤销"。
        // 「主体」和「遮罩」并排，二者互斥：选了其中一个，另一个就得先清空再选。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp)
                // 两张卡的内容高度可能差一行，用 IntrinsicSize.Min 让它们永远等高对齐。
                .height(IntrinsicSize.Min),
        ) {
            RoleCard(
                role = MaskRole.SUBJECT,
                bitmap = state.previews[MaskRole.SUBJECT],
                injected = MaskRole.SUBJECT in state.injectedRoles,
                otherSelected = state.sizes[MaskRole.SUBJECT_MASK] != null,
                otherTitle = MaskRole.SUBJECT_MASK.title,
                // 替换进行中一律禁掉，见 [RoleCard] 的 `enabled`。
                enabled = !state.busy,
                onPick = { if (state.requireActivated()) onPick(MaskRole.SUBJECT) },
                onClear = { if (state.requireActivated()) state.clearRole(MaskRole.SUBJECT) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            Spacer(Modifier.width(12.dp))
            RoleCard(
                role = MaskRole.SUBJECT_MASK,
                bitmap = state.previews[MaskRole.SUBJECT_MASK],
                injected = MaskRole.SUBJECT_MASK in state.injectedRoles,
                otherSelected = state.sizes[MaskRole.SUBJECT] != null,
                otherTitle = MaskRole.SUBJECT.title,
                enabled = !state.busy,
                onPick = { if (state.requireActivated()) onPick(MaskRole.SUBJECT_MASK) },
                onClear = { if (state.requireActivated()) state.clearRole(MaskRole.SUBJECT_MASK) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        // 替换动作放在最下面：立即替换。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextButton(
                    text = if (state.busy) "处理中…" else "立即替换",
                    // 走 requestReplace 而不是 rootReplace：没确认过「屏幕灭亮提醒」时先弹提示，
                    // 确认过（或已点过「确定」）才真的替换。
                    onClick = { haptic(); if (state.requireActivated() && !state.busy) state.requestReplace() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                // 「清除替换记录」按钮去掉了：保护收尾（替换完成时由 App 直接结束）provider 那边
                // 就会自动把候选和注入快照清掉，不需要用户再点一次。
                // 「还原」按钮 2026-10-15 随备份/还原整组逻辑删除（用户要求"删干净"）。
            }
        }

        // 「替换行为」这一节 2026-09-29 从设置页整张卡片搬过来（用户指定，**标题不变**）。
        // 同卡片里另一个开关（详细过程写入LSPosed日志）并进了设置页的「测试」，不在这边。
        // 位置放在最下面（动作卡之后）：页面主体仍是"选图 → 替换"，不让选项把主体顶下去。
        //
        // 间距：**照设置页的规矩** —— `SmallTitle` 后面紧跟的 `Card` 只留 `padding(horizontal = 12.dp)`，
        // **不要再加 `top`**。`SmallTitle` 自己带下方留白，加 top 就成了双份（用户反馈"间距大了点"）。
        SmallTitle(text = "替换行为")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            // 「替换前备份原文件」开关 2026-10-15 随备份/还原整组逻辑删除（用户要求"删干净"）。
            // 「替换后自动灭亮屏一次」（2026-10-04 用户要求）：
            // 灭亮屏从"固定行为"改成可选（注入改走 hook 后，刷新已经由切
            // `wallpaper_effect_type_2` 完成，灭亮屏只是"便于快速查看效果"）。
            SwitchRow(
                title = "替换后自动灭亮屏一次",
                summary = "便于快速查看效果",
                checked = state.autoScreenFlash,
                onCheckedChange = { state.applyAutoScreenFlash(it) },
            )
            // 「解除景深遮挡限制」（2026-10-01）。和"选图 → 替换"主干无关，只管**编辑器里
            // "主体遮挡时间过多"那条判定**，默认关。
            SwitchRow(
                title = "解除景深遮挡限制",
                summary = "适用于主体被判定遮挡时间过多的情况",
                checked = state.depthOcclusionRemoved,
                onCheckedChange = { state.applyDepthOcclusionRemoved(it) },
            )
        }
    }
}

// ------------------------------------------------------------------ 功能（二级：视差壁纸效果）

/**
 * 二级页「视差壁纸效果」的全部内容（2026-10-12）—— 照「锁屏遮罩替换」的模板，
 * 但**只有一列**（「背景」卡）+ 底部**单个**动作按钮。
 *
 * ## 状态机（用户 2026-10-12 定）
 *
 * | 状态 | 按钮 | 选图操作（选择/更换、清空选择） | 预览框 |
 * | --- | --- | --- | --- |
 * | 未替换 | 「立即替换」 | ✅ 可动 | 显示候选（如有） |
 * | 已替换（开启中） | 「关闭替换」 | ❌ 禁用 | 显示已生效背景，**跨重启持久** |
 *
 * * 点「选择图片」时先过**景深检查**（没开 → toast「请先在系统锁屏编辑开启景深效果」并中止）；
 * * 「关闭替换」后图退回普通候选，随「启动清空残留候选」在下次打开 App 时清空（见 `App.onCreate`）；
 *   「壁纸改变后自动关闭替换」（默认开）开着时，视差生效中检测到换壁纸也走同一条关闭路径；
 * * 全程不写系统文件、不需要 root、无「替换行为」小节（备份 / 灭亮屏 / 遮挡限制都属主体链）。
 */
@Composable
internal fun ParallaxBgDetail(state: MaskUiState, onPick: () -> Unit) {
    val haptic = rememberClickHaptic()
    val ctx = LocalContext.current
    val prefs = remember { MaskPrefs(ctx) }
    // 功能冲突弹窗（空间壁纸正在生效时点「立即替换」）
    var showConflict by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberPageScroll())
            .padding(top = LocalTopBarHeight.current, bottom = 32.dp),
    ) {
        // 一列「背景」卡 —— 与主体 / 遮罩卡同款（标题 + summary + 预览框 + 两个按钮）。
        // 「开启替换」期间选图操作禁用（用户指定：只有非替换状态下才能动上面的选图）。
        ParallaxBgCard(
            bitmap = state.parallaxPreview,
            enabled = !state.busy && !state.parallaxActive,
            onPick = { if (state.requireActivated()) onPick() },
            onClear = { if (state.requireActivated()) state.clearParallaxBg() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
        )

        // 动作卡：**单个按钮**（用户指定）——「立即替换」⇄「关闭替换」，不是两个按钮。
        // 位置 = 背景卡与「参数调整」卡的中间（2026-10-13 用户指定）。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextButton(
                    text = if (state.parallaxActive) "关闭替换" else "立即替换",
                    onClick = {
                        haptic()
                        if (state.requireActivated() && !state.busy) {
                            when {
                                state.parallaxActive -> state.disableParallax()
                                // **最后**才查互斥：激活 / busy 这些「本来就能不能开」的条件
                                // 已经过了（它们不满足时自己会弹 toast），这时才轮到外部状态。
                                prefs.spatialActive && prefs.spatialKey.isNotEmpty() ->
                                    showConflict = true
                                else -> state.enableParallax()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }

        // 「参数调整」（2026-10-13 加）：**卡外标题**（SmallTitle，同设置页"外观 / 测试"那类）
        // + 卡内 1 开关 + 4 滑块 —— 调完直接锁屏查看（SystemUI 侧 ~4s 内取用，
        // 无需重启、无需重开替换）。
        ParallaxParamsCard(
            state = state,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // 功能冲突弹窗（常驻组合树、用 show 控制）。「关闭」只关空间壁纸、
    // **不继续**本次替换；要再点一次「立即替换」才生效（用户 2026-10-29 定）。
    FeatureConflictDialog(
        show = showConflict,
        summary = "当前空间壁纸已生效，应用视差壁纸需先关闭",
        onDismissRequest = { showConflict = false },
        onConfirmClose = {
            showConflict = false
            SpatialControl.disable(ctx)
        },
    )
}

/** 「视差壁纸效果」的背景卡 —— 照 `RoleCard` 的样式（标题 / summary / 1:1 预览框 / 两个按钮）。 */
@Composable
private fun ParallaxBgCard(
    bitmap: Bitmap?,
    /** 选图操作是否可用：替换进行中与「开启中」都禁用。 */
    enabled: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberClickHaptic()
    // 预览框底色与 RoleCard 完全同一套判断（注释见那里）：必须和卡片拉得开。
    val previewSurface = if (MiuixTheme.colorScheme.surface.luminance() < 0.5f) {
        Color(0xFF1A1A1A)
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    Card(modifier = modifier, insideMargin = PaddingValues(0.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "背景", style = MiuixTheme.textStyles.body1)
            Text(
                text = "不含主体的背景图片",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    // 1:1 预览框（2026-10-14 用户要求；原 9:16）——只改视差页，遮罩页 RoleCard 保持 9:16。
                    .aspectRatio(1f / 1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(previewSurface),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "背景",
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
            // 「选择/更换图片」与「清空选择」放**同一行**（2026-10-22 用户要求）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = if (bitmap != null) "更换图片" else "选择图片",
                    onClick = { haptic(); onPick() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    enabled = enabled,
                )
                TextButton(
                    text = "清空选择",
                    onClick = { haptic(); onClear() },
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                )
            }
        }
    }
}

/**
 * 「参数调整」—— **卡外标题**（`SmallTitle`）+ 一张控件卡（2 开关 + 6 滑块；首行
 * 「壁纸改变后自动关闭替换」为 2026-10-15 用户指定新增，其余为 2026-10-13 起陆续加入）。
 *
 * * 标题用"卡片外的那种"（与设置页"外观 / 测试"同一类 —— 用户 2026-10-13 指定，
 *   初版是卡内自排标题，已改）；卡片位置在动作卡（「立即替换 / 关闭替换」）之后；
 * * 开关「使主体与背景移动方向相同」（v22.5 语义翻转，用户 2026-10-14）：默认关 = **反方向移动**
 *   （背景取反）；开关打开 = 主体与背景同方向。方向语义 = **只翻背景**（主体保持现状）。
 * * 倍率滑块 1~1.25（步长 0.05）；幅度滑块范围**随倍率动态**：上限 =（半屏宽 ×（倍率−1）− 5px，
 *   见 [MaskPrefs.parallaxAmpUpperBound]）、下限 = 上限 ×（主体 50% / 背景 35%）；
 *   倍率 = 1.0 时上限 0 → 滑块锁 0 + 禁用态；
 * * 拖动中 `persist = false` 只更新内存（`commit()` 每帧写盘会卡拖动 —— NavBg 滑块的同一教训），
 *   抬手（`onValueChangeFinished`）一次性落盘；
 * * SystemUI 侧 3~4 秒内取用（config TTL 3s + 传感器帧轮询），**无需重启、无需重开替换**，
 *   替换关着时也允许调整（下次开启带着生效）。
 */
@Composable
private fun ParallaxParamsCard(state: MaskUiState, modifier: Modifier = Modifier) {
    val subjectRange = state.parallaxSubjectAmpRange()
    val bgRange = state.parallaxBgAmpRange()
    Column(modifier = modifier) {
        // 卡外标题（用户指定；间距由 SmallTitle 自带 —— 与设置页同一用法，不加额外 padding）。
        SmallTitle(text = "参数调整")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            Column {
                // 「壁纸改变后自动关闭替换」（2026-10-15 加，**默认开**）：视差生效中检测到换壁纸
                // → 自动关闭视差替换（无 summary —— 用户指定）。
                SwitchRow(
                    title = "壁纸改变后自动关闭替换",
                    checked = state.parallaxAutoOff,
                    onCheckedChange = { state.applyParallaxAutoOff(it) },
                )
                // 「视差效果覆盖桌面」（2026-10-21 加，**默认关**，无 summary —— 用户指定）。
                // 当前只做「解锁动画抑制」；视差层真正覆盖桌面的逻辑后续再接。
                SwitchRow(
                    title = "视差效果覆盖桌面",
                    checked = state.parallaxCoverDesktop,
                    onCheckedChange = { state.applyParallaxCoverDesktop(it) },
                )
                SwitchRow(
                    title = "使主体与背景移动方向相同",
                    summary = "默认为反方向移动",
                    checked = state.parallaxReverse,
                    onCheckedChange = { state.applyParallaxReverse(it) },
                )
                ParallaxSliderRow(
                    title = "主体放大倍率",
                    text = formatScale(state.parallaxSubjectScale),
                    value = state.parallaxSubjectScale,
                    range = MaskPrefs.PARALLAX_SCALE_MIN..MaskPrefs.PARALLAX_SCALE_MAX,
                    steps = 4,
                    onValueChange = { state.applyParallaxSubjectScale(it, persist = false) },
                    onFinished = { state.persistParallaxParams() },
                )
                ParallaxSliderRow(
                    title = "背景放大倍率",
                    text = formatScale(state.parallaxBgScale),
                    value = state.parallaxBgScale,
                    range = MaskPrefs.PARALLAX_SCALE_MIN..MaskPrefs.PARALLAX_SCALE_MAX,
                    steps = 4,
                    onValueChange = { state.applyParallaxBgScale(it, persist = false) },
                    onFinished = { state.persistParallaxParams() },
                )
                ParallaxSliderRow(
                    title = "主体移动最大幅度",
                    text = "${state.parallaxSubjectAmp.roundToInt()} px",
                    value = state.parallaxSubjectAmp,
                    range = subjectRange,
                    steps = sliderSteps(subjectRange),
                    enabled = subjectRange.endInclusive > 0f,
                    onValueChange = { state.applyParallaxSubjectAmp(it, persist = false) },
                    onFinished = { state.persistParallaxParams() },
                )
                ParallaxSliderRow(
                    title = "背景移动最大幅度",
                    text = "${state.parallaxBgAmp.roundToInt()} px",
                    value = state.parallaxBgAmp,
                    range = bgRange,
                    steps = sliderSteps(bgRange),
                    enabled = bgRange.endInclusive > 0f,
                    onValueChange = { state.applyParallaxBgAmp(it, persist = false) },
                    onFinished = { state.persistParallaxParams() },
                )
                // 「最大幅度角度」（2026-10-13 用户加）：转动的满量程角 —— 绕长边转多少度达到满幅；
                // 量程 15°~45°、步进 1°、默认 25°（用户定；v22.6 上限 30°→45°）。
                // 位置在"背景幅度"与"归位等待"中间（用户指定）。
                ParallaxSliderRow(
                    title = "最大幅度角度",
                    text = "${state.parallaxGyroDeg.roundToInt()}°",
                    value = state.parallaxGyroDeg,
                    range = MaskPrefs.PARALLAX_GYRO_DEG_MIN..MaskPrefs.PARALLAX_GYRO_DEG_MAX,
                    steps = sliderSteps(
                        MaskPrefs.PARALLAX_GYRO_DEG_MIN..MaskPrefs.PARALLAX_GYRO_DEG_MAX,
                    ),
                    onValueChange = { state.applyParallaxGyroDeg(it, persist = false) },
                    onFinished = { state.persistParallaxParams() },
                )
                // 「恢复原位等待时长」（2026-10-13 用户加）：静止判定窗口 —— 停下来后**等多久**
                // 开始归位动画；量程 0.2s~1s、步进 0.1s。排在「背景移动最大幅度」下方（用户指定）。
                ParallaxSliderRow(
                    title = "恢复原位等待时长",
                    text = formatSeconds(state.parallaxHomeWait),
                    value = state.parallaxHomeWait,
                    range = MaskPrefs.PARALLAX_HOME_WAIT_MIN..MaskPrefs.PARALLAX_HOME_WAIT_MAX,
                    steps = 7,
                    onValueChange = { state.applyParallaxHomeWait(it, persist = false) },
                    onFinished = { state.persistParallaxParams() },
                )
            }
        }
    }
}

/**
 * 「参数调整」卡里的一行滑块 —— 标题行右侧实时数值 + 全宽 Slider（照设置页 NavBgOptions 的
 * 模糊半径滑块同款排布 / 字体）。
 */
@Composable
private fun ParallaxSliderRow(
    title: String,
    /** 右侧实时数值（倍率 "1.15" / 幅度 "45 px"）。 */
    text: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    // 范围退化（倍率 = 1.0 → 上限 0）时用安全占位范围：滑块禁用 + 值留在 0，避免 0..0 的除零。
    val safeRange = if (range.endInclusive > range.start) range else 0f..1f
    BasicComponent(
        title = title,
        endActions = {
            Text(
                text = text,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .align(Alignment.CenterVertically),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                textAlign = TextAlign.End,
            )
        },
    )
    Slider(
        value = value.coerceIn(safeRange),
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        valueRange = safeRange,
        steps = if (enabled) steps else 0,
        enabled = enabled,
        onValueChangeFinished = onFinished,
    )
}

/** 滑块的步进数（"步进 1 个单位" = 段数−1；段数按范围跨度取整）——幅度（px）与角度（°）共用。 */
private fun sliderSteps(range: ClosedFloatingPointRange<Float>): Int =
    ((range.endInclusive - range.start).roundToInt() - 1).coerceAtLeast(0)

/** 倍率显示（"1.15"；Locale.US 防止小数点被本地化成逗号）。 */
private fun formatScale(value: Float): String = String.format(Locale.US, "%.2f", value)

/** 秒显示（"0.5 s"；归位等待时长用）。 */
private fun formatSeconds(value: Float): String = String.format(Locale.US, "%.1f s", value)

@Composable
private fun RoleCard(
    role: MaskRole,
    bitmap: Bitmap?,
    injected: Boolean,
    /** 另一边（主体 / 遮罩）已经选过了 —— 两者互斥。 */
    otherSelected: Boolean,
    otherTitle: String,
    /**
     * 两个按钮是否可用。
     *
     * 替换进行中（`MaskUiState.busy` → 「立即替换」显示"处理中…"）时传 `false`：
     * 「选择图片 / 更换图片」与「清空选择」都进入 miuix 的**禁用态**（官方 disabled 配色 = 变灰，
     * 且点击无效）。目的是让用户在整个「替换 + 保护」周期里**选不了新图** ——
     * 保护期内新选的候选本来就不该生效，索性在这个窗口内直接不给入口。
     */
    enabled: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 「选择图片 / 更换图片」和「清空选择」两个按钮：主体卡、遮罩卡共用这一处。
    val haptic = rememberClickHaptic()
    // 预览框的底色必须和卡片**拉得开**。miuix 的色表里：
    // - 浅色：卡片 `surfaceContainer` = 白、`surfaceContainerHigh` = #E8E8E8 → 用它，看得见 ✓
    // - 深色：卡片 `surfaceContainer` 和 `surfaceContainerHigh` **都是 #242424** → 用它就和卡片糊成一片，
    //   所以深色固定用一个比卡片更深的灰 **#1A1A1A**（比纯黑的 `surface` 柔和，用户定的值）。
    // 深浅判断用 `surface` 的亮度、而不是 `isSystemInDarkTheme()`：本应用支持强制深浅色，
    // 必须跟**应用主题**走（深色 surface = #000000 → 0.0；浅色 = #F7F7F7 → 0.93，不会判错）。
    val previewSurface = if (MiuixTheme.colorScheme.surface.luminance() < 0.5f) {
        Color(0xFF1A1A1A)
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    Card(modifier = modifier, insideMargin = PaddingValues(0.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = role.title, style = MiuixTheme.textStyles.body1)
            Text(
                text = role.summary,
                // 和设置页开关下面那行说明同一号（body2 = 14sp），不要再往下缩一档。
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    // 9:16 竖版比例（手机照片的样子），比固定高度好看。
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(previewSurface),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    bitmap != null -> Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = role.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )

                    otherSelected -> Text(
                        text = "已选择$otherTitle",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )

                    injected -> Text(
                        text = "已写入系统",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )

                    else -> Text(
                        text = "还未选择图片",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = if (bitmap != null) "更换图片" else "选择图片",
                onClick = { haptic(); onPick() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                enabled = enabled,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = "清空选择",
                onClick = { haptic(); onClear() },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            )
        }
    }
}

// ------------------------------------------------------------------ 设置

/**
 * 「使用自定义背景」打开后出现的两行：**选择图片** + **模糊半径调整**（2026-10-06）。
 *
 * 位置由调用处决定 —— 夹在「使用自定义背景」与「显示自检与最近操作」**之间**、同一张卡片内
 * （用户指定），所以这里**不再自己开 Card**。
 *
 * 模糊那一行**不能**把滑块塞进 `BasicComponent` 的 `endActions`（那儿只够放一个短文本），
 * 所以拆成「标题行（右侧实时数值）+ 下面整宽滑块」两段，行距由 `BasicComponent` 自带的
 * 16dp 上下 padding 提供（和卡片里其它行一致，官方排版就是这样）。
 */
@Composable
private fun NavBgOptions(navBg: NavBgState) {
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { navBg.onPicked(it) } }

    LinkRow(
        title = "选择图片",
        summary = navBg.fileName.ifEmpty { "未选择图片" },
        onClick = {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
    )
    // 标题行：右侧是**实时**数值（拖动时逐帧跳，纯粹是个 Text、不花算力）。
    BasicComponent(
        title = "模糊半径调整",
        endActions = {
            Text(
                text = "${navBg.radiusPx.roundToInt()} px",
                modifier = Modifier
                    .padding(end = 8.dp)
                    .align(Alignment.CenterVertically),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                textAlign = TextAlign.End,
            )
        },
    )
    // 滑块单独一段：0~50px、步长 1px（`steps = 49` ⇒ 共 51 档）。
    // 量化是为了少算几次 —— 每次数值变化都要解码 + 模糊 + GPU 读回 + 编码落盘，
    // 外面还包了 0.5s 防抖（见 `NavBg.kt`），所以拖动过程中只更新数值、松手后算一次。
    //
    // ⚠️ **它自带的 `draggable` 实际上抢不到事件**（pager 的 PagerSwipeNode 在 Main 阶段
    // 比子节点先拿到水平手势），真正接管拖动的是挂在 pager 祖先上的 `SliderTouchGesture`
    // —— 这里的 `onValueChange` 只是兜底，正常不会触发。
    Slider(
        value = navBg.radiusPx,
        onValueChange = { navBg.setRadius(it) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .reportSliderRect(navBg),
        valueRange = 0f..MaskPrefs.NAV_BG_RADIUS_MAX.toFloat(),
        steps = MaskPrefs.NAV_BG_RADIUS_MAX - 1,
    )
}

@Composable
internal fun SettingsTab(
    state: MaskUiState,
    navBg: NavBgState,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    // 这里只给按钮用：开关的振动由 SwitchRow 自己处理（点整行 → ContextClick；点开关 → miuix 内置的 Toggle）。
    val haptic = rememberClickHaptic()
    TabColumn {
        // 「替换行为」整张卡片 2026-09-29 拆掉了（用户指定）：「替换前备份原文件」搬到二级页
        // 「锁屏遮罩替换」（那张卡片的标题**仍是「替换行为」**；该开关后于 2026-10-15 随备份/
        // 还原整组逻辑删除）；「详细过程写入LSPosed日志」并进下面的「测试」卡片。
        // 下面这两段历史说明留着有据可查：
        //
        // 官方排布：SwitchRow 自带四周 16dp 内边距（纵向那 16dp 就是**行距**）、行高 ≥56dp，
        // 所以卡内既不包 padding、也不在选项之间加 Spacer。
        // 「灭屏后结束保护」这个开关 2026-09-25 就从界面撤了；2026-09-29 用户又要求
        // "逻辑固定在代码里、不留开关"，于是 `releaseOnScreenOn` 连同它的 prefs 键一起删掉 ——
        // 判定点现在**直接看 `prefs.protecting`**（`MaskProvider` / `HookRuntime`），
        // 不再有任何开关参与（保护主路收尾在替换完成时，灭亮屏只是兜底）。
        // 「替换后自动灭亮屏一次」2026-09-29 曾整体删除、后于 2026-10-04 按用户要求以**可选开关**
        // 恢复（在主体链「替换行为」卡里；「屏幕灭亮提醒」弹窗 2026-10-04 一并删除）。
        // 外观：原来是一排三个按钮（跟随系统 / 浅色 / 深色），2026-09-29 用户要求合成**一个下拉**。
        SmallTitle(text = "外观")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            DropdownRow(
                title = "主题",
                summary = "调整应用的主题模式",
                options = ThemeMode.entries.map { it.label },
                selectedIndex = ThemeMode.entries.indexOf(state.themeMode),
                onSelect = { index -> onThemeModeChange(ThemeMode.entries[index]) },
            )
        }

        // 测试：下面那两块是排查用的，默认关着；打开才显示（见 MaskUiState.showDiagnostics）。
        SmallTitle(text = "测试")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            // 「详细过程写入LSPosed日志」2026-09-29 从「替换行为」挪进来，排在自检开关**上面**（用户指定）。
            SwitchRow(
                title = "详细过程写入LSPosed日志",
                summary = "便于排查问题",
                checked = state.verbose,
                onCheckedChange = { state.applyVerbose(it) },
            )
            // 「使用自定义背景」（2026-10-06，**测试功能**）：整页背景换成自选图，专门用来观察
            // 底栏玻璃在真实图案下的表现（纯色底看不出降采样降级那些问题）。默认关；
            // 关掉时半径归零、缓存目录删干净。排在两个既有开关**中间**是用户指定的位置，
            // 打开后出现的两个选项也**插在这中间**（同卡片内，不是另开一张卡）。
            SwitchRow(
                title = "使用自定义背景",
                summary = "仅供测试使用，非正式选项",
                checked = navBg.enabled,
                onCheckedChange = { navBg.applyEnabled(it) },
            )
            if (navBg.enabled) {
                NavBgOptions(navBg)
            }
            SwitchRow(
                title = "显示自检与最近操作",
                summary = "定位问题时开启",
                checked = state.showDiagnostics,
                onCheckedChange = { state.applyShowDiagnostics(it) },
            )
        }

        if (state.showDiagnostics) {
            DiagnosticsSection(state)
        }
    }
}

/**
 * 设置页的「自检」+「最近操作」两块（都是排查用的）。
 *
 * 「上次执行结果」卡片已于 2026-10-04 按用户要求删除：执行结果改为持续写入
 * `/data/user/0/com.frederic22.hyperextreme/run_log.txt`（见 `data/RunLog.kt`）。
 *
 * 抽成单独一个 composable 是因为它们只在「测试 → 显示自检与最近操作」打开时才渲染（默认关）——
 * 把条件写在 [SettingsTab] 里会是嵌一大段缩进的 `if`，抽出来更清楚。
 * 这里面的内容原本就写在设置页里，逻辑一字未改。
 */
// ------------------------------------------------------------------ 系统界面（二级）

/**
 * 二级页「系统界面」的内容 —— 从设置页搬来的「锁屏修改」。
 *
 * 为什么值得一张独立的二级页：这个开关作用在**锁屏（SystemUI）**上，跟"替换模板文件"
 * 那条链完全无关，混在设置页的「替换行为」里名不副实（2026-09-29 用户要求搬过来）。
 */
@Composable
internal fun SystemUiDetail(state: MaskUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberPageScroll())
            .padding(top = LocalTopBarHeight.current, bottom = 32.dp),
    ) {
        SmallTitle(text = "锁屏修改")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            SwitchRow(
                title = "使自定义锁屏按钮显示在上层",
                summary = "防止被通知覆盖的情况",
                checked = state.lockScreenButtonOnTop,
                onCheckedChange = { state.applyLockScreenButtonOnTop(it) },
            )
            // 「强制锁屏元素深浅模式」（2026-10-11 加）—— 主开关打开后，下面出现 6 个元素的档位下拉。
            // （2026-10-11 撤掉「通知」项：该系统版本玻璃材质下深浅两档渲染代码完全相同，切档无视觉变化。）
            SwitchRow(
                title = "强制锁屏元素深浅模式",
                summary = "开启后可分别调整",
                checked = state.forceToneMode,
                onCheckedChange = { state.applyForceToneMode(it) },
            )
            if (state.forceToneMode) {
                DropdownRow(
                    title = "状态栏",
                    options = ForceToneOptions,
                    selectedIndex = state.forceToneStatusBar,
                    onSelect = { state.applyForceToneStatusBar(it) },
                )
                DropdownRow(
                    title = "时间与锁屏内容",
                    options = ForceToneOptions,
                    selectedIndex = state.forceToneClock,
                    onSelect = { state.applyForceToneClock(it) },
                )
                DropdownRow(
                    title = "快捷方式图标",
                    options = ForceToneOptionsShortcut,
                    selectedIndex = state.forceToneShortcut,
                    onSelect = { state.applyForceToneShortcut(it) },
                )
                DropdownRow(
                    title = "指纹图标",
                    options = ForceToneOptions,
                    selectedIndex = state.forceToneFingerprint,
                    onSelect = { state.applyForceToneFingerprint(it) },
                )
                DropdownRow(
                    title = "底部提示文字",
                    options = ForceToneOptions,
                    selectedIndex = state.forceToneBottomHint,
                    onSelect = { state.applyForceToneBottomHint(it) },
                )
                DropdownRow(
                    title = "锁屏签名/杂志文字",
                    options = ForceToneOptions,
                    selectedIndex = state.forceToneSignature,
                    onSelect = { state.applyForceToneSignature(it) },
                )
            }
        }
    }
}

/** 「强制锁屏元素深浅模式」的下拉三档 —— 索引与 `MaskPrefs.FORCE_TONE_*`（0/1/2）一一对应。 */
private val ForceToneOptions = listOf("保持系统判定", "浅色", "深色")

/**
 * 「快捷方式图标」专用五档（2026-10-11 加「不透明」两档）——
 * 索引对应 `MaskPrefs.FORCE_TONE_*`（0/1/2/3/4，"不透明" = 把系统 80% 的图标 tint 拉满）。
 */
private val ForceToneOptionsShortcut =
    listOf("保持系统判定", "浅色", "深色", "浅色（不透明）", "深色（不透明）")

// ------------------------------------------------------------------ 系统桌面（二级）

/**
 * 二级页「系统桌面」的内容 —— 「多任务」（2026-10-16 加）。
 *
 * 只有一张卡、一个开关；真正的逻辑全在 SystemUI 侧（`hook/RecentsStatusBar`）：
 * 桌面经 `LauncherProxyService` 把「多任务显示 / 隐藏」通知 SystemUI，那边按本开关
 * 设 / 清状态栏 disable 位（内容隐藏、下拉保留；滑入过渡瞬间的时机口径见该文件注释）。
 * 默认关（用户定的）—— 打开后进出一次多任务即可看到效果。
 */
@Composable
internal fun DesktopDetail(state: MaskUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberPageScroll())
            .padding(top = LocalTopBarHeight.current, bottom = 32.dp),
    ) {
        SmallTitle(text = "多任务与文件夹")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            SwitchRow(
                title = "打开多任务和文件夹时隐藏状态栏",
                checked = state.hideStatusBarInRecents,
                onCheckedChange = { state.applyHideStatusBarInRecents(it) },
            )
        }
    }
}

@Composable
private fun DiagnosticsSection(state: MaskUiState) {
    val haptic = rememberClickHaptic()

    SmallTitle(text = "自检")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "检查模块内部功能是否正常。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { haptic(); state.runSelfTest() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (state.busy) "运行中…" else "运行自检",
                    style = MiuixTheme.textStyles.button,
                )
            }
            state.selfTestLines.forEach { line ->
                Text(
                    text = "· $line",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    // 最近一次注入 / 保护状态变化（hook 或 root 写回来的记录）—— 保护有没有真的结束就看这里。
    // （主页那张"替换状态"卡片删掉之后，这条记录一度没有任何地方显示。）
    // 下面再加一行"诊断"：ScreenDisarm 收到亮屏广播、壁纸尺寸探测的结果都写在这里，
    // 这两条链路原来都把失败静默吞掉了，真机上没法判断卡在哪一步。
    val hookDetail = state.hookStatus.detail
    val diagNote = MaskPrefs(state.context).diagNote
    if (!hookDetail.isNullOrBlank() || diagNote.isNotBlank()) {
        SmallTitle(text = "最近操作")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (!hookDetail.isNullOrBlank()) {
                    Text(
                        text = hookDetail,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    Text(
                        text = listOf(state.hookStatus.process.orEmpty(), formatTime(state.hookStatus.at))
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (diagNote.isNotBlank()) {
                    Text(
                        text = diagNote,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    // 「上次执行结果」卡片已按用户要求删除（2026-10-04）——
    // 执行结果现在持续写入 `/data/user/0/com.frederic22.hyperextreme/run_log.txt`
    // （带时间戳、最多 500 行，见 data/RunLog.kt），不再占设置页。
}

// ------------------------------------------------------------------ 关于

// 开发者主页链接（酷安）2026-10-31 随「选择平台」弹窗搬去 `PlatformDialog.kt`
//（见那里的 `DeveloperCoolapkUrl`）—— 开发者卡现在先弹窗、由弹窗分发到 Github / B 站 / 酷安。

/** 捐赠入口 —— 主页那张「通过捐赠支持开发者」卡点一下打开这个链接（2026-10-29）。 */
private const val DonateUrl = "https://afdian.com/a/Frederic22"

/** 一条开源许可：[author] 与 [license] 会拼成"开发者 | 协议"，显示在名字下面。 */
private data class OpenSourceEntry(
    val name: String,
    val author: String,
    val license: String,
    val url: String,
)

/**
 * 用到的开源项目。**这里是唯一权威清单** —— 改它不需要同步别处（`agent.md` 没有副本）。
 *
 * 两大类：
 * - **依赖与权重**：miuix / Kyant 两个库 / libxposed API / AndroidX / Compose Multiplatform /
 *   ONNX Runtime（深度推理的执行引擎）/ Depth Anything 3 **Small + Base**
 *   （`assets/models/da3_small_int8.onnx` + 构建期下载的 `da3_base_int8.onnx`）；
 * - **移植进工程的代码**：HyperChanger 的悬浮底栏（`ui/nav/`）、PatchMatch 的 C 移植
 *   （`cpp/patchmatch/`，编成 `libspatial_fill.so`）、Material Icons 的两个图标（`ui/icons/`）。
 *
 * ⚠️ **带版本号的那两条不是随手写的**：
 * - `HyperChanger v1.1.3` 是我们实际引用的 tag（commit `a01dc46`，2026-09-18），该版本是 Apache-2.0；
 *   上游后来把协议换成了 **AGPL-3.0-or-later**。Apache-2.0 授权不可撤销，我们那份永久有效，
 *   但版本号必须留着 —— 它划清"我们用的是换协议之前的那一版"。
 * - 深度模型的协议（2026-11-02 DA3 换代时查证，**修正了旧注释的错误**）：
 *   Depth Anything 2 只有 **Small** 是 Apache-2.0（Base/Large 实为 CC-BY-NC-4.0）；
 *   Depth Anything 3 只有 **Small/Base** 是 Apache-2.0（Large/Giant/Nested 是 CC-BY-NC-4.0）。
 *   我们只用 DA3 的 Small/Base 两档，均为 Apache-2.0。
 */
private val OpenSourceLicenses = listOf(
    OpenSourceEntry(
        "miuix", "compose-miuix-ui（YuKongA）", "Apache-2.0",
        "https://github.com/compose-miuix-ui/miuix",
    ),
    OpenSourceEntry(
        "Backdrop", "Kyant（Kyant0）", "Apache-2.0",
        "https://github.com/Kyant0/AndroidLiquidGlass",
    ),
    OpenSourceEntry(
        "Shapes", "Kyant（Kyant0）", "Apache-2.0",
        "https://github.com/Kyant0/Shapes",
    ),
    // 官方仓库（不是 LSPosed 主仓库）：libxposed API 单独发布在 libxposed/api，
    // 我们 `compileOnly` 的 `libs/libxposed-api-102.jar` 就出自 `io.github.libxposed:api:102.0.0`。
    OpenSourceEntry(
        "libxposed API", "LSPosed", "Apache-2.0",
        "https://github.com/libxposed/api",
    ),
    OpenSourceEntry(
        "AndroidX Activity Compose", "Google / AndroidX", "Apache-2.0",
        "https://github.com/androidx/androidx",
    ),
    OpenSourceEntry(
        "Compose Multiplatform", "JetBrains", "Apache-2.0",
        "https://github.com/JetBrains/compose-multiplatform",
    ),
    // 空间壁纸的深度推理模型（2026-11-02 从 DA2 换代为 DA3 双模型）：
    // * `assets/models/da3_small_int8.onnx`（29MB，HF `en970/depth-anything-v3-small-onnx`，q8 动态量化）；
    // * `assets/models/da3_base_int8.onnx`（123MB，HF `jlampel/depth-anything-3-base-onnx-int8`，
    //   MatMulNBits int8，构建时由 Gradle 下载 + SHA-256 校验，不入库）。
    // 论文：Depth Anything 3，arXiv:2511.10647。**只有 Small/Base 是 Apache-2.0**
    // （Large/Giant/Nested 是 CC-BY-NC-4.0，我们不用 —— 换模型前先看协议）。
    OpenSourceEntry(
        "Depth Anything 3 Small", "ByteDance-Seed", "Apache-2.0",
        "https://github.com/ByteDance-Seed/Depth-Anything-3",
    ),
    OpenSourceEntry(
        "Depth Anything 3 Base", "ByteDance-Seed", "Apache-2.0",
        "https://github.com/ByteDance-Seed/Depth-Anything-3",
    ),
    // 深度推理的执行引擎（`implementation(libs.onnxruntime.android)`，1.31.0）。MIT。
    OpenSourceEntry(
        "ONNX Runtime", "Microsoft", "MIT",
        "https://github.com/microsoft/onnxruntime",
    ),
    // ⚠️ **版本号必须留**（2026-11-02 查证）：我们引用的是 **v1.1.3**（commit `a01dc46`，2026-09-18），
    //    该 tag 是 Apache-2.0；上游后来换成 **AGPL-3.0-or-later**。
    //    Apache-2.0 不可撤销 ⇒ 我们的副本永久有效，但标出版本才能划清边界。
    //    引用点：`ui/nav/Os4FloatingNavigationBar.kt`（文件头有 SPDX + 原版权）。
    OpenSourceEntry(
        "HyperChanger v1.1.3", "btm_m（ColdP）", "Apache-2.0",
        "https://github.com/ColdP/HyperChanger",
    ),
    // `cpp/patchmatch/` 的 C 移植（编成 `libspatial_fill.so`，负责底图填充）。
    // MIT，Copyright (c) 2012 Younesse ANDAM —— LICENSE 文件是上游 2025-05 才补的，我们 2026-10 移植。
    OpenSourceEntry(
        "PatchMatch", "Younesse ANDAM", "MIT",
        "https://github.com/younesse-cv/PatchMatch",
    ),
    // 状态卡那两个大图标（`check_circle_outline` / `error_outline`）的路径数据是从这里搬的，
    // 落在 `ui/icons/StatusIcons.kt`（见那个文件顶部的说明）。
    OpenSourceEntry(
        "Material Icons", "Google", "Apache-2.0",
        "https://github.com/google/material-design-icons",
    ),
)

/**
 * 打开外部链接。没装浏览器之类的异常直接吞掉 —— 不能因为点一下就把应用崩了。
 *
 * `internal`（2026-10-31）：`PlatformDialog` 也要用，同包可见。
 */
internal fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
internal fun AboutTab(state: MaskUiState) {
    val context = state.context
    val haptic = rememberClickHaptic()
    // 「选择平台」弹窗的显隐（2026-10-31）：开发者卡不再直接跳酷安，先弹三选一。
    var showPlatform by remember { mutableStateOf(false) }
    TabColumn {
        // 状态卡已于 2026-10-29 搬回主页最上面（见 [HomeTab]）—— 关于页从开发者卡开始。
        // 开发者卡：头像 + 名字 + 一句话 + 右侧箭头，整行可点（打开 Coolapk 主页）。
        // 排版用 miuix 官方的 `BasicComponent`：`startAction` 放头像、`endActions` 放右侧箭头，
        // 官方那套 16dp 内边距与行高照旧。箭头照官方 `ArrowPreference` 的画法
        // （`onSurfaceVariantActions` 描色、在行内垂直居中），只是图标换成 `ChevronForward` ——
        // 我们依赖的 miuix-icons 0.9.4 里还没有官方那个 `MiuixIcons.Basic.ArrowRight`。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            BasicComponent(
                title = "Frederic22",
                summary = "相逢的人会再相逢。",
                startAction = {
                    Image(
                        painter = painterResource(R.drawable.profile_photo),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            // BasicComponent 里头像与文字之间固定 8dp，这里再补 6dp（共 14dp），
                            // 让间距宽一点点。padding 必须放在 size **之前**，否则会被 clip 压成 42dp。
                            .padding(end = 6.dp)
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                },
                endActions = { LinkArrow() },
                onClick = {
                    haptic()
                    showPlatform = true
                },
            )
        }

        // 开源代码许可：一条一条列（名字 + "开发者 | 协议" + 右侧箭头），整行可点 → 对应仓库。
        // 排版和开发者卡一致（官方 `BasicComponent`），每条自带 16dp 内边距与行高。
        SmallTitle(text = "开源代码许可")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            OpenSourceLicenses.forEach { entry ->
                LinkRow(
                    title = entry.name,
                    summary = "${entry.author} | ${entry.license}",
                    onClick = { openUrl(context, entry.url) },
                )
            }
        }
    }

    // 「选择平台」弹窗：**常驻组合树、用 show 控制**（条件包裹会让 miuix 的退出动画播不出来）。
    PlatformDialog(
        show = showPlatform,
        onDismissRequest = { showPlatform = false },
        onPick = { openUrl(context, it) },
    )
}

/** 一条可点的链接行：标题 + 说明 + 右侧箭头，整行可点。[summary] 传 null 则不画副标题。 */
@Composable
private fun LinkRow(title: String, summary: String?, onClick: () -> Unit) {
    val haptic = rememberClickHaptic()
    BasicComponent(
        title = title,
        summary = summary,
        endActions = { LinkArrow() },
        onClick = {
            haptic()
            onClick()
        },
    )
}

/** 右侧那个 ">"：颜色与尺寸照官方 `ArrowPreference` 的 endAction 画法（开发者卡也用它）。 */
@Composable
private fun RowScope.LinkArrow() {
    Image(
        imageVector = MiuixIcons.ChevronForward,
        contentDescription = null,
        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantActions),
        modifier = Modifier
            .size(16.dp)
            .align(Alignment.CenterVertically),
    )
}

/**
 * 一个开关选项：用 miuix 官方的 `BasicComponent`（= 官方 `SwitchPreference` 的排版）——
 * 官方字号（标题 `headline1` = 17sp、说明 `body2` = 14sp）、文字列与右侧开关自动垂直居中、
 * 行高是官方的 `heightIn(min = 56.dp)`；按下时整行"变深"来自 miuix 主题给 `LocalIndication`
 * 提供的 `MiuixIndication`。
 *
 * **`insideMargin` 用官方默认值（四周 16dp）**，这也是官方设置页那种"行距舒展"的来源：
 * 纵向的 16dp 上下 padding 合起来就是相邻两行之间的间距（所以卡片里不需要再加 `Spacer`），
 * 横向的 16dp 是左右留白；而 `clickable` 在 `padding` 的**外层**，按压高亮正好铺满整个卡片宽度。
 * （踩过两次：① 设成 0 + 由卡片 `Column(padding(16.dp))` 提供边距 → 高亮左右缩进、看着怪；
 * ② 只留横向 → 行距又太紧。回到官方默认就对了。）
 *
 * 唯一的差异是**振动分两条路**，点哪儿都只振一次：点**开关**走 `Switch` 内置的
 * `ToggleOn` / `ToggleOff`（事件被子组件消费，整行的 clickable 不触发）；
 * 点**整行其它位置**走我们的 `haptic()`（`ContextClick`）。官方 `SwitchPreference` 把两条路
 * 合成同一个 `onCheckedChange`，在那儿补振动会让点开关时连振两下。
 */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    /** false = 整行变灰、不可点（2026-10-30：系统壁纸服务模式下属选项无效用）。 */
    enabled: Boolean = true,
) {
    val haptic = rememberClickHaptic()
    BasicComponent(
        title = title,
        summary = summary,
        enabled = enabled,
        endActions = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
        onClick = {
            if (enabled) {
                haptic()
                onCheckedChange(!checked)
            }
        },
        role = Role.Switch,
    )
}

/**
 * 一个**下拉选择**选项：整行可点，右侧显示「当前值 + 上下箭头」，点开弹出官方那套展开栏。
 *
 * 2026-09-29 加（用户给了 MIUI 设置页「设置图标」那张截图，要求把设置页原来的三个主题按钮
 * 合成这样一个下拉）。
 *
 * ## ⚠️ 骨架必须照官方源码，不能自己想 —— 首版显示错误，就是想当然写的
 *
 * 官方出处：`compose-miuix-ui/miuix` 仓库（`miuix-preference/…/OverlayDropdownPreference.kt`、
 * `…/popup/OverlayDropdownPopup.kt`、`…/popup/DropdownEntriesContent.kt`）。三个要点：
 *
 * 1. **弹出层要写在 `endActions` 里**（官方就是这么做的 —— 它是 `RowScope` 里的一个可组合项），
 *    这样它才**锚在这一行**上。放到 `BasicComponent` 外面当兄弟就是错的（没有锚点）。
 * 2. **内容必须包一层 `ListPopupColumn`** —— 列表的宽度（200~288dp）与滚动都由它管。
 *    少了这一层正是"展开栏显示错误"的直接原因（首版就是漏了它）。
 * 3. `alignment = PopupPositionProvider.Align.End`（对齐行尾）；点击走 `DropdownItem` 自带的
 *    `onClick`，选中态看 `item.selected` —— 不是自己把 `isSelected` 算出来传进去。
 *
 * > ⚠️ **miuix 的成品下拉不在 `miuix-ui` 里**：`OverlayDropdownPreference` / `ArrowPreference`
 * > 那些在**另一个模块 `miuix-preference`**，本工程没引（也不打算引：本地缓存只有 `0.9.4-rc01`，
 * > 与其余 miuix 模块的 `0.9.4` 不是同一版本，混用有风险）。所以这里用 `miuix-ui` 里
 * > 官方提供的**积木**、照上面那份源码接线 —— 观感、动画、配色仍然是官方那套。
 * > 部件全是官方的：`BasicComponent` / `OverlayListPopup` / `ListPopupColumn` / `DropdownImpl` /
 * > `DropdownItem` / `DropdownEntry` / `DropdownArrowEndAction` / `DropdownDefaults`。
 */
@Composable
internal fun DropdownRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    summary: String? = null,
    /** false = 整行变灰、不可点（2026-10-30：空间壁纸「挂载模式」在运行中锁死用）。 */
    enabled: Boolean = true,
) {
    val haptic = rememberClickHaptic()
    val hapticFeedback = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val expanded = remember { mutableStateOf(false) }
    val holdDown = remember { mutableStateOf(false) }
    val actionColor = MiuixTheme.colorScheme.onSurfaceVariantActions
    val dropdownColors = DropdownDefaults.dropdownColors()
    // **照官方**：一组 `DropdownEntry`，每个 `DropdownItem` 自带 `selected` 与 `onClick`。
    val entries = remember(options, selectedIndex, onSelect) {
        listOf(
            DropdownEntry(
                items = options.mapIndexed { index, text ->
                    DropdownItem(
                        text = text,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                    )
                },
            ),
        )
    }

    BasicComponent(
        title = title,
        summary = summary,
        interactionSource = interactionSource,
        enabled = enabled,
        endActions = {
            // 当前值：官方用 `body2` 字号 + `onSurfaceVariantActions`，右对齐。
            Text(
                text = options.getOrElse(selectedIndex) { "" },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .align(Alignment.CenterVertically)
                    .weight(1f, fill = false),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = actionColor,
                textAlign = TextAlign.End,
            )
            DropdownArrowEndAction(actionColor = actionColor)
            // 弹出层**就在 endActions 里**（要点 1）。
            OverlayListPopup(
                show = expanded.value,
                alignment = PopupPositionProvider.Align.End,
                onDismissRequest = { expanded.value = false },
                onDismissFinished = { holdDown.value = false },
            ) {
                // 要点 2：`ListPopupColumn` 管宽度与滚动，漏了就显示错。
                ListPopupColumn {
                    entries.forEach { entry ->
                        val last = entry.items.lastIndex
                        entry.items.forEachIndexed { index, item ->
                            DropdownImpl(
                                item = item,
                                optionSize = entry.items.size,
                                isSelected = item.selected,
                                index = index,
                                dropdownColors = dropdownColors,
                                enabled = entry.enabled && item.enabled,
                                isFirst = index == 0,
                                isLast = index == last,
                                onSelectedIndexChange = { picked ->
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                    entry.items.getOrNull(picked)?.onClick?.invoke()
                                    expanded.value = false
                                },
                            )
                        }
                    }
                }
            }
        },
        onClick = {
            // 禁用态整行不可点（`BasicComponent` 自己也会拦，这里双保险）
            if (enabled) {
                // 官方：展开时振一下、并把整行置成"按住"态（`holdDownState`）。
                val next = !expanded.value
                expanded.value = next
                if (next) {
                    holdDown.value = true
                    haptic()
                }
            }
        },
        role = Role.DropdownList,
        holdDownState = holdDown.value,
    )
}
