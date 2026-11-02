package com.frederic22.hyperextreme.spatial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 空间壁纸：**层图与位移场生成**（app 进程，同步阻塞，调用方放 IO 线程）。
 *
 * ## 输入 / 输出
 *
 * 输入 = `files/spatial/<hash>/depth.png`（灰度，越亮越近）+ 原图 URI；
 * 输出 = 同目录 `layers/`：
 *
 * ```
 * layer0.png   背景：fillRegion 内已被 PatchMatch 填掉，全幅不透明
 * layer1.png   主体：RGBA，alpha = softAlpha × 羽化（2026-10-28 起**恒定生成**）
 * depth.png    双通道位移场：R = 背景场 kBg，G = 主体场 kFg，A 恒 255（Bitmap 预乘，不能省）
 * meta.json    版本 / 层数 / 尺寸 / hasBody / bodyDepth
 * ```
 *
 * ## 算法（推导过程与实测数据见《具体规范/999-当前进度.md》）
 *
 * ```
 * depth.png ──median3──> depthMed
 *           ──otsu + 直方图谷底──> lo/hi ──> softAlpha ──grayDilate(2)──> bodyMask
 *           ──fillHoles + dropFarHoles──> fillRegion（"要抹掉的区域"）
 *           ├─ layer0：fillRegion 内 PatchMatch 补全
 *           ├─ layer1：alpha = softAlpha × featherBox(4)
 *           └─ depthBg：nearestFill + harmonicFill 做背景化
 *                       ──> kBg ──greyClosing(2)──> limitSlope
 *                       ──> kFg（5px 跨度定标 + 40px 轮廓延拓）──> 写入 R / G
 * ```
 *
 * ## 几条硬约束（动之前先读）
 *
 * * 改生成算法必须 `FORMAT_VERSION +1`，否则 `hasLayers()` 会放行旧缓存、改动不生效；
 * * `fillRegion` **只用于"要抹掉的区域"**，主体层 alpha 永远只用 `softAlpha`；
 * * 闭运算必须做在 **k 场**、且必须在 `limitSlope` **之前**（理由见 `GREY_CLOSE_R`）；
 * * `grayDilate(BODY_DILATE)` 会让 `fgRatio` 系统性偏大约 1.3 个百分点 —— 它是分支判据之一。
 */
internal object SpatialLayers {

    private const val TAG = "SPATIAL_LAYERS"


    /** 层数上限。当前实际只有 1~2 层（背景 + 主体），这个上限是旧分层方案的遗留；
     *  渲染器按 `meta.layers` 动态分配，不写死。 */
    const val MAX_LAYERS = 10

    /** 软 alpha 的过渡半宽（深度值）。**无谷兜底时** `band = 2×这一值 = 0.06`。
     *  正常路径（找到谷底）`band` 是 0.018，与此无关。 */
    private const val SOFT_DELTA = 0.03f

    /** 主体 mask 额外外扩的像素数（兜底色阶边界残留的主体描边）。2026-10-27 由 1 提到 2。 */
    private const val BODY_DILATE = 2

    /** alpha 羽化半径（像素）。层号来自 1/4 分辨率（双线性后仍有 ~1px 残余台阶），
     *  2px 盖不住 ⇒ 提到 4px（2026-10-23 用户实测 layer1 边缘锯齿）。 */
    private const val FEATHER = 4


    /** 二级页预览图已改为直接解码缓存目录里的原图（`SpatialDepth.SOURCE_NAME`），不再写 webp。 */

    data class Result(val dir: File, val layers: Int, val costMs: Long, val hit: Boolean)

    /** 层图目录。 */
    fun layersDir(depthDir: File): File = File(depthDir, "layers")

    /**
     * 层图格式版本：**改动填充/分层算法后必须 +1**，旧缓存才会被判过期重算。
     * 39 = 底图填充换成 C 版 PatchMatch（旧的 Kotlin Telea+PatchMatch 实现整体删除）。
     * 40 = 位移改成「渲染器按深度图逐像素」：层数降到 1~2 层（背景 + 主体），
     *      新增 layers/depth.png 位移场。
     * 41 = depth.png 从「原始深度」改成「塑形后的 k 场」（锚点 ε + 梯度限幅），
     *      渲染器不再自己算 1−K·d。
     * 42 = 位移场拆成**两条**（R=背景场 / G=主体场）：背景场先做深度「背景化」补全
     *      （主体区用周边背景深度填掉），主体场用原始深度逐像素形变；K_MIN 0.12→0.35，
     *      限幅上限 1.0→0.5（最多放大 2×）。
     * 43 = 主体场的形变跨度按像素定标压到 5px（原来实测 8.7px，观感太夸张）。
     * 44 = 主体场向轮廓外延拓 40px（原来掩码外借用了背景深度，轮廓外 6px 处有一道
     *      Δk=0.17 的硬跳，转动时表现为"第二个主体"的一圈边）。
     * 45 = 位移量按深度图标准差缩放（只缩小不放大）：σ=0.32 为 1.0，下限 0.3。
     * 46 = 位移量改成 min(σ/0.32, range/5)：range 是归一化前的原始深度范围，更直接。
     * 47 = 背景场的层间形变加 WARP_GAIN=0.55（跨度 21.9px → 12px，前景位移不变）。
     * 48 = K_MIN 0.35 → 0.15：枢轴处从 13.4px 降到 5.7px，做回真正的"不动的支点"。
     * 49 = lo/hi 切点改成「直方图谷底 + 窄带」：band 0.4→0.018，实心 11%→49%，轮廓不再内缩。
     * 50 = 深度先中值 3×3（去掉被限幅扩散成菱形的孤立噪点）+ 背景化从 push-pull 换成最近邻传播。
     * 51 = 背景化改成「Laplace 调和插值 + 多尺度松弛」：最近邻做初值，消除 Voronoi 多边形碎块。
     * 52 = 填充域改用 fillHoles(bodyMask)（外轮廓 + 内部洞）：修掉 layer0 的主体残留
     *      与 harmonicFill 把内部洞当边界条件导致的黑斑。
     * 53 = 背景场加 despeckle：抹掉 min-plus 腐蚀扩散出的 7px 菱形（位移差 2.2px）。
     * 54 = 去斑的基准从「局部均值」改成「局部最大值」——均值会被斑点自己拉低，判据失效。
     * 55 = 去斑参数按实测重定：斑点其实是 35~45px（不是 7px），R 7→12、面积上限 200→3200、
     *      基准改 (dilate+erode)/2（近似局部中值）。
     * 56 = BODY_DILATE 1 → 2：主体 mask 灰度膨胀半径提到 2px。
     * 57 = 删掉 despeckle（实测对 L1 锥面修复率仅 6.2%，且跑在限幅之后会重新引入不合规）
     *      + k 场限幅前加闭运算 r=2（锥面 1.02% → 0.54%，range 不减）
     *      + dAnchorBg 下限 0.30（放大率 1.8 → 1.6，坑深 −12%）。
     * 58 = fillHoles 之后剔除「洞内深度比周围主体低 0.15 以上」的连通洞：实测 5.37% 画面
     *      全是真实背景缝隙，被误填后主体层盖不住 ⇒ 假背景直接显示
     *      + dAnchorBg 下限 0.30 → 0.45（锥面归零，range −42%）。
     * 59 = 分支判据加「过渡带归一化宽度 ≤ 0.03」（边缘太糊就不分离），
     *      同时删掉恒真的 `band < 0.20`（band 只可能是 0.018 或 0.060）。
     * 60 = **取消分支**：所有图恒定 2 层（无条件填充 + 无条件背景化 + 无条件出 kFg）。
     *      `hasBody` 降级为纯标记（不再控制流水线），留给未来「锁屏景深资格」用。
     * 61 = **主体场加「边缘环带低通」**（σ=10，3×box r=20，只压环带 0..5px、10px 淡出）：
     *      治「大角度下边缘移动参差不齐」。实测参差 p99 2.92px → 0.91px，
     *      内部跨度 5.10px → 5.81px（柔性不降反升）。
     * 62 = 61 的**归一化卷积修正**：61 直接全图模糊，把轮廓外的背景 k（比主体高 0.10）
     *      混进环带 ⇒ 环带被系统抬高（实测 +2.2px、内部跨度涨到 7.07px、参差只降一半）。
     *      改成 `blur(k·m)/blur(m)` 后轮廓外不参与平均。
     * 63 = **幅度翻倍 + 各向同性**（渲染侧 PAN_NDC → PAN_RAD，两向各 100px）：
     *      `PAN_UV` 拆成 `PAN_UV_X/Y`（0.0730/0.0329，uv 系数两向不再相同）；
     *      `FG_EXTEND_PX` 40 → 50（最大位移 23.8 → 46.3px，延拓半径必须覆盖）。
     * 64 = **主体场深度融合**（`DD_FUSE=0.12`）：按深度差 `|d_raw − d_bg|` 把「深度就是背景」
     *      的那圈 k 拉回 `kBg` —— 此前两套映射同深度差 0.225（22.5px），
     *      导致 mask 带进来的背景跟主体一起动、和真背景错开一整圈。
     *      ⚠️ **已回退**（v1.5.30）：融合跑在 `limitSlope` 之后，制造的 0.25 台阶让
     *      `|∇k|` p99 涨到 0.0156（限幅上限 0.0067）⇒ du/du₀ = −0.165 ⇒ 采样折叠，
     *      主体形变异常。要救只能"融合后重新限幅"，而 min-plus 会把台阶摊成 37px 斜坡，
     *      等价于「按到轮廓距离渐变」——不如直接做那个。
     * 65 = **回退 64**。注意版本号必须**往前走**：`hasLayers()` 判的是 `>= FORMAT_VERSION`，
     *      退回 63 会让 v1.5.29 生成的 64 缓存被放行。
     * 66 = **深度源换 DA3**（Small/Base 二选一，2026-11-02）：直方图分布与 DA2 不同，
     *      谷底/σ/极差定标阈值（VALLEY_MAX、DEPTH_STD_REF、DEPTH_RANGE_REF）**暂不动**，
     *      待真机验证主体分离与视差幅度后再调；强制重算全部层图。
     * 67 = **DA3 视差幅度重定标**（66 的"待真机验证"收口）：实测 DA3 的两个定标判据
     *      全部失真 —— 极差 0.78 vs REF 5.0 直接触底 0.3、σ 0.19~0.25 vs REF 0.32
     *      再压一节，合成幅度只有 DA2 时代的 **31%**（用户反馈「效果没 DA2 好」，
     *      DA2 参照实测 kBg 均值 0.56 / kFg 0.21、σ 0.307）。改为按 meta.model 分流：
     *      DA3 用 DEPTH_STD_REF_DA3=0.25 且**跳过极差判据**（距离制 range 是场景尺度量）；
     *      DA2 路径与阈值原样保留。VALLEY_MAX 未动（DA3 主体分离实测 hasBody 正常）。
     */
    private const val FORMAT_VERSION = 67

    /**
     * **枢轴处的残余位移** = 位移场的最小系数。
     *
     * 物理上绕枢轴公转时，枢轴本身位移是 0（`s = φ(1 − Zp/Z)`，`Z = Zp` ⇒ 0）。
     * `K_MIN` 就是「枢轴处故意保留多少位移」——**它决定了画面有没有"不动的支点"**。
     *
     * 演变：
     * - 0.12（2026-10-25 之前）：主体走**刚性**、整层一个 k，内部完全无形变 ⇒
     *   看起来像贴在玻璃上的贴纸，用户反馈「主体几乎不动」。
     * - 0.35（2026-10-25）：抬上去让主体动起来。但代价是**整幅至少平移 13.4px、没有锚点**
     *   ⇒ 观感变成「一张图在滑」，而不是「相机在转」（2026-10-26 用户反馈）。
     * - **0.15（2026-10-26）**：主体已经是**逐像素 + 5px 内部形变**，
     *   「活」由形变负责、不再靠整体位移撑 ⇒ 可以把枢轴降回去做回真正的支点。
     *   实测（1022 画布）：最近处 5.7px（几乎钉住）、最远处 21.5px。
     */
    private const val K_MIN = 0.15f

    /**
     * **满量程位移在 uv 空间的系数** —— 两个字（X/Y）分开，因为渲染侧已改成
     * 「弧度口径 + 各向同性」（2026-10-28 v1.5.28）。
     *
     * 渲染器：`uShift = (nx·PAN_RAD/spanX, np·PAN_RAD/spanY)`，`q = q0 − uShift·k`，
     * `uv = q·0.5+0.5` ⇒ **uv 位移 = `PAN_RAD·k / (2·span)`**，两向 span 不同。
     *
     * 本机（屏幕 1200×2670、层图 1022×2268）：`spanX=0.1667`、`spanY=0.3699`、
     * `PAN_RAD=0.02434` ⇒ **X 0.0730 / Y 0.0329**。
     * 各自乘边长都等于 **74.6**，所以 `limX` 与 `limY` 数值相同 —— 这正是"各向同性"的表现。
     *
     * ⚠️ **依赖屏幕比例**。竖屏手机 aspect 0.44~0.46，`spanX` 变化 <1%，可当常量；
     * 平板 / 横屏 / 折叠屏展开必须重算，否则限幅会过松（折叠）或过紧（形变被压平）。
     */
    private const val PAN_UV_X = 0.0730f
    private const val PAN_UV_Y = 0.0329f

    /**
     * 限幅的**目标**局部放大率倒数的下界：`du/du₀ = 1 − PAN_UV_X·∂k/∂u ≥ LIM_SLOPE`。
     *
     * 取 1.0（= 数学上"刚好不折叠"）时，带中心的放大率是**无穷大**（内容被捏成一条线），
     * 看起来像一道疤（2026-10-25 实测）。0.5 ⇒ 最多放大 2×，带宽翻一倍，
     * 形态变成"能看出是运动模糊的带"。
     */
    private const val LIM_SLOPE = 0.5f

    /**
     * **位移场的闭运算半径**（像素）—— 在 `limitSlope` **之前**填掉孤立的暗坑。
     *
     * 暗坑的来历（2026-10-27 实测）：深度图在主体边缘留下 1~2px 的"半值尾随"
     * （核 0.1255，周围 0.0353），`1 − d/dAnchor` 把它放大成 35 灰阶的 k 落差
     * （核 67，周围 148）。而 `limitSlope` 是 4 邻域曼哈顿距离变换，
     * 会把这个孤立暗坑扩成 L1 菱形锥（纵向半径 = 横向的 1.78 倍 = h/w）。
     *
     * 闭运算（先膨胀后腐蚀）正好填暗坑、不动亮峰 ⇒ k 的 range 一点不减。
     * 实测锥面像素 1.02% → 0.54%。**必须在限幅之前**：
     * 放在后面只能削掉小菱形，184×130 的大菱形填不平。
     *
     * ⚠️ 这个必须做在 **k 场**上，不能做在深度图上：深度图里那条尾随是**亮峰**
     * （亮 = 近），闭运算填的是暗坑 ⇒ 实测 `grey_closing(深度, r=1/2/3)` 对核
     * 的改动是 **0%**；深度图侧要用的是开运算。
     */
    private const val GREY_CLOSE_R = 2

    /**
     * **低段再切的判据**：低段 Otsu 的分离度 / 主 Otsu 的分离度，超过它才认为
     * 主阈值以下还藏着第二个「前后景交界」（2026-10-31）。
     *
     * 起因：`otsu()` 只返回**一个**阈值，它选的是类间方差最大的那一刀。剪影类插画里
     * 「草丛 vs 其余」的方差比「人物 vs 天空」大 ⇒ 切在草丛（bin 99），人物（bin 39~99）
     * 整段落在 `lo` 以下被判成背景 ⇒ 背景场保留人物轮廓的 k 沟（实测人物边缘
     * 单像素 Δk = 0.267，换算满量程 26.7px 位移差，转动时撕成一道带）。
     *
     * 实测三张深度图（`s2/s1`）：**0.177**（该切：人物 15.1% + 草丛 27.8%）/ 0.065 / 0.031
     * （不该切，是"天空 + 一个主体"的两层图）。取 0.12 能把两类分开 2.7 倍；
     * 要更保守可提到 0.15（0.177 仍能过）。
     */
    private const val SPLIT_RATIO = 0.12f

    /**
     * 低段再切时，低段「近侧」（会被判成主体的那部分）至少要占全图这么大比例。
     * 防止在一小撮像素里硬切出个"主体"（5%；实测该切的那张是 15.1%）。
     */
    private const val SPLIT_MIN_AREA = 0.05f

    /**
     * 低段再切的**否决闸**：切出来的中景层命中画面左右各 3 列的比例上限（2026-11-01）。
     *
     * 低段再切的目标是「把被主 Otsu 漏掉的中景主体捞出来」。主体是独立物体、被背景包围，
     * 不该横跨整个画面宽度；一旦贴到左右边缘，那层是墙 / 雾 / 地面 —— **背景的另一半**。
     *
     * 实测（15 张深度图，左/右边命中率）：
     * - 该切：`2.png` 4%/7%、`6.png` 0%/0% ⇒ 最大 7%
     * - 误切：`8.png` 36%/28%、`9.png` 36%/19%、`10.png` 24%/95%
     *   以及本来就该判无主体的 `3.png` 30%/25%、`4.png` 35%/28%、`2b93` 30%/46%
     * 空档 7% ~ 19%，取 15% 落在中间；余量比「面积上限」那条（15.1% vs 24.6%，1.6×）宽得多（5×）。
     *
     * ⚠️ 只调 `SPLIT_RATIO` 解决不了：`2.png`(0.177) 与 `9.png`(0.168) 的比值几乎相等，
     *   提高阈值会同时漏掉正样本。
     */
    private const val SPLIT_EDGE_MAX = 0.15f

    /**
     * **主体可信度阈值**：深度直方图里「谷底 ÷ 两侧较矮的峰」的上限（2026-10-31）。
     *
     * 语义 = 「主体和背景在深度上是不是两坨」：谷深说明切得干净，谷浅说明深度是连续过渡，
     * 切哪一刀都有歧义 ⇒ 不该硬切，退化成「单层 + 整幅连续位移场」。
     *
     * 实测（**模块真实口径**：不拉伸 + median3 + ±2 平滑 + Otsu±40 找谷，12 张图）：
     * - 有主体：0.011 / 0.034 / 0.081 / 0.090 / 0.211 / 0.214 / 0.217
     * - 无主体：**0.378** / 0.589 / 0.800
     * 空档 = 0.217 ~ 0.378，取 0.30 落在正中，两侧各留 1.38 / 1.26 倍。
     *
     * ⚠️ **不要用 PC 分类脚本里的 0.40**：那边多做了 `p1..p99` 对比度拉伸，
     * 而 `depth.png` 在 `SpatialDepth.generate` 里已经 min-max 归一化到 0..255，
     * 再拉伸会改变分布（同一张图 0.378 → 0.530）。
     */
    private const val VALLEY_MAX = 0.30f

    /**
     * 判定谷深时，左右峰要避开谷底 ±这么多 bin。
     *
     * 谷底常常是「一个 bin 的尖坑」，如果不排除，`min(左峰, 右峰)` 可能取到紧邻谷底的
     * 小值 ⇒ ratio 被抬高到 1.0 ⇒ 明明有深谷却判成「无主体」。
     */
    private const val VALLEY_PEAK_GUARD = 3

    /**
     * **背景场 `dAnchorBg` 的下限**。它是 `depthBg` 的 95 分位，实测这张图只有 0.26 ——
     * 于是 `1 − d/dAnchor` 的放大率高达 `A/dAnchor ≈ 1.8 /单位深度`，深度上 0.09 的差
     * 就能变成 0.17 的 k 差。
     *
     * 取值是 trade-off（2026-10-27 实测，range 基准 0.467）：
     * 0.30 → 0.408（−12%，锥面不变）；0.35 → 0.350（−25%）；0.40 → 0.306（−34%，锥面 −69%）；
     * 0.45 → 0.272（−42%，**锥面归零**）。
     * **用户定 0.45**：优先把菱形彻底清掉，接受立体感压缩。
     */
    private const val D_ANCHOR_MIN = 0.45f

    /**
     * **内部洞的深度判据**：洞内平均深度比周围主体低多少，就判为「真实背景缝隙」。
     *
     * `fillHoles` 从画面四边 flood fill，把「填不到」的非 mask 像素当内部洞并入填充域。
     * 但主体围成一个闭合环时（手臂贴身体、头发贴肩膀），环里的**真实远景**也填不到 ⇒
     * 被误判成"主体内部的洞" ⇒ PatchMatch 用周围内容填掉；而主体层在那里 alpha = 0、
     * 盖不住 ⇒ 假背景直接显示出来（2026-10-27 实测占画面 5.37%，10 个连通块全部误判）。
     *
     * 实测这 10 个洞的深度差是 0.170~0.346，取 0.15 全部剔除。
     * 阈值太小会误伤"主体自身的纹理凹陷"—— 那种洞内深度接近周围，只有细微起伏。
     */
    private const val HOLE_DEPTH_GAP = 0.15f

    /**
     * **过渡带统计的半透明宽度**（深度值）：`lo ~ lo + 这一段` 的像素算"边缘过渡"。
     *
     * 为什么不直接用 `band = hi - lo`：那个只有 0.018，只覆盖"切点两侧的窄带"，
     * 而边缘糊的图是从 lo 一路渐变到主体内部，0.018 的量根本反映不出来。
     */
    private const val SOFT_BAND_W = 0.12f

    /**
     * **过渡带归一化宽度上限**。超过它 ⇒ 主体边缘太糊、切出来的 mask 不可靠。
     *
     * ⚠️ 2026-10-28（FORMAT 60）起它**只影响 `hasBody` 标记**，不再改变层数 ——
     * 层数已恒定 2 层，超限的图照样按同一个 mask 切，只是 meta 里 `hasBody=false`。
     *
     * 2026-10-28 三张深度图实测：0.0234 / 0.0156 判为有主体，0.0361 判为无主体。
     */
    private const val SOFT_BAND_MAX = 0.03f

    /**
     * **主体内部的位移跨度上限（画布像素）** —— 决定主体"形变"看起来有多明显。
     *
     * 主体走逐像素场之后，身体不同部位（头/肩/手/脚）的 k 不同 ⇒ 整块内容被不均匀地伸缩。
     * 跨度就是「最近部位与最远部位在满转角下的位移差」，也就是形变的量纲。
     *
     * 2026-10-26 实测：按 p95 归一化时实际跨到 **8.7px**（全范围 13.1px），用户反馈
     * 「主体的变形怪怪的，幅度太大了，轻微但有感知就可以」。这里改成**按像素定标**：
     * 先算原始跨度，再等比压到 [FG_WARP_SPAN_PX]，与图片分辨率、主体深度范围都无关。
     *
     * 只压缩主体的**内部跨度**，不动 K_MIN（整体位移量）与背景场。
     */
    private const val FG_WARP_SPAN_PX = 5f

    /**
     * **主体场向轮廓外的延拓半径（画布像素）**。
     *
     * 为什么需要：主体场只能用自己的数据。之前掩码（`bodyMask` 膨胀 6px）之外用的是
     * 「原始深度 = 背景深度」算的 k，比轮廓值大 0.17 ⇒ 在轮廓外 6px 处形成一道硬跳
     * （位移 18px → 24.4px）。那一圈会把主体的内容"压缩"着显示出来，看起来像第二个主体。
     *
     * 延拓之后，轮廓外 0..R 像素的 k 恒等于最近的轮廓值 ⇒ 位移恒定 ⇒ 主体是纯粹的
     * 整体平移，没有任何压缩带；采样点越过轮廓时 alpha 已经为 0，自然透明。
     *
     * 取值必须 ≥ 主体的最大位移（`k_max · PAN_UV_X · w`）。2026-10-28 幅度翻倍后：
     * `0.62 × 0.0730 × 1022 = **46.3px**`（原来是 23.8px）⇒ **40 不够，提到 50**。
     */
    private const val FG_EXTEND_PX = 50

    /**
     * **边缘环带低通的 sigma（画布像素）** —— 治「主体边缘在大角度下移动参差不齐」。
     *
     * 现象（2026-10-28 实测，1022×1694 缓存 / 屏幕 1200×2670，主体 k≈0.26）：
     * 主体层按 `kFg` 逐像素位移，而 `kFg` 在轮廓处**沿切向也不一致** ——
     * 同一圈 `alpha≈0.5` 等值线上 k 的 `p95−p5 = 0.055`（垂直满量程 **5.5px**），
     * 高频抖动 `p99 = 0.041`（**4.1px**），是主体内部的 **6.5 倍**（内部 0.63px）。
     * 位移 ∝ 角度 ⇒ 小角度看不出来；大角度时"参差"就出来了。
     * **垂直是重灾区**：满量程 100px/k，水平只有 45px/k（屏幕 2670/1200）⇒ 俯仰比左右明显 2.2 倍。
     *
     * **为什么整条链路都没挡住它**：
     * - `median3` 保边（中值滤波本来就不治边缘的半值尾随）；
     * - `limitSlope` 只限**梯度幅度**（≤ 0.0131/px）—— 高频抖动幅度小、梯度也小，全部合法通过；
     * - `extendField` 用最近邻延拓 ⇒ 把轮廓上的抖动**原样复制**到轮廓外。
     * ⇒ 必须单独加这一趟，且只能加在"沿轮廓方向"上。
     *
     * 实测收敛（参差量 = 位移相对局部低频的偏差，满量程垂直口径）：
     * ```
     * sigma   0     3     5     6     8     10    12
     * p99    2.92  2.12  1.64  1.43  1.12  0.91  0.75    (px)
     * 内部跨度 5.10  5.19  5.49  5.54  5.70  5.81  5.89    (px)
     * ```
     * ⇒ **10 是 p99 首次跌破 1px 的档**，且内部跨度不降反升（主体的柔性一点没损失）。
     *
     * ⚠️ 只压**高频抖动**。轮廓上约 5px 的低频跨度（近处动得多、远处动得少）是**物理正确**的，
     * 必须保留 —— 压掉主体就成纸片了。所以不能靠全图降 `FG_WARP_SPAN_PX` 来"平滑"。
     */
    private const val EDGE_SMOOTH_SIGMA = 10f

    /** 环带范围：到 `solid`(alpha ≥ 0.9) 边界 0..IN 权重 1，IN..OUT 线性降到 0。 */
    private const val EDGE_SMOOTH_RING_IN = 5f
    private const val EDGE_SMOOTH_RING_OUT = 10f

    /**
     * box 半径 —— **3 趟 box blur 近似高斯**（`EDGE_SMOOTH_SIGMA = 10`）。
     *
     * 为什么不用真高斯：半径 30、核长 61，1.73M 像素 ⇒ 约 2.1 亿次乘加，Kotlin 侧要几秒。
     * 3 趟滑动窗口 box 是 O(n)，6 遍约 1000 万次加法，实测 < 100ms。
     *
     * 方差：k 趟半径 r 的 box ⇒ `σ² = k(r²−1)/12`。k=3, r=20 ⇒ 99.75 ⇒ σ = 9.99。
     */
    private const val EDGE_SMOOTH_BOX_R = 20

    /**
     * **深度图标准差参考值** —— 位移量随「这张图立体感有多强」等比缩放。
     *
     * 为什么要它：`SpatialDepth` 输出前做了 min-max 归一化，所以 d 总是铺满 [0,1]，
     * 「深度跨度」的信息被抹掉了。真正的立体感差异体现在**分布形状**上（标准差）。
     *
     * 实测四张真机深度图：σ = 0.241 / 0.281 / 0.313 / 0.329。取 0.32 为参考 ⇒
     * 立体感最强的那张 = 1.0，最平的降到 0.75。**只缩小不放大**（放大要动 SPHERE_SAFE、
     * 牺牲构图，用户 2026-10-26 明确选"只缩小"）。
     *
     * 下限 0.3 兜底：再平的图也留一点位移，不至于像静态壁纸。
     */
    private const val DEPTH_STD_REF = 0.32f

    /**
     * **原始深度范围的参考值** —— `SpatialDepth` 的 `depthMax − depthMin`，实测 4.62 / 4.99 / 5.47。
     *
     * 这是「相对深度」的**直接**度量（前后景差多少），而 `σ_norm` 是间接的：
     * 如果某张图模型输出范围很窄，min-max 归一化会把噪声放大几十倍 ⇒ σ_norm 虚高
     * ⇒ 误判成立体感强。所以两个判据取**小值**，任何一个说"平"就压下去。
     */
    private const val DEPTH_RANGE_REF = 5.0f

    /**
     * **DA3 专用 σ 参考值**（2026-11-02 换代实测，方法论同 [DEPTH_STD_REF]）。
     *
     * DA3 输出的是「距离」而非视差：min-max 后分布整体比 DA2 平，
     * 实测四张 σ = 0.195（猫特写）/ 0.219（人像）/ 0.220（风景-small）/ 0.255（风景-base）。
     * 取 0.25 为参考（实测最大值 ⇒ 最立体的 = 1.0、其余 0.78~0.88），
     * 手感与 DA2（REF 0.32 ⇒ 0.75~1.0）对齐。
     *
     * ⚠️ 同时 DA3 **跳过极差判据**：它的 `depthMax−depthMin` 随场景尺度乱变
     * （猫特写 0.78、风景 2.9，而 REF 5.0 是按 DA2 视差标定的 ⇒ 一用就触底 0.3，
     * 实测把视差压到 DA2 时代的 31%，用户反馈「效果没 DA2 好」）。
     * 判据按 `meta.model` 以 `da3_` 开头分流（见 [generate] 里 depthScale 的计算）。
     */
    private const val DEPTH_STD_REF_DA3 = 0.25f

    /**
     * **层间形变增益**：乘在 `(1 − K_MIN)` 上，只压「不同深度之间的位移差」。
     *
     * 和另外三个旋钮的区别（2026-10-26 用户定案 WARP_GAIN=0.55）：
     * - `PAN_RAD`（渲染器）等比缩放一切 ⇒ 转动手感也变；
     * - `K_MIN` 提高是「把近处推出去」⇒ 前景反而动得更多；
     * - **本参数**：`K_MIN` 不动（最近处的位移一模一样），只把远处收回来 ⇒
     *   跨度 21.9px → 12px，前景位移完全不变。
     *
     * 只作用于**背景场**。主体场有自己的 `FG_WARP_SPAN_PX` 定标（永远 5px），
     * 两个缩放叠在一起会互相抵消，所以互不干扰：主体照旧，背景变平。
     */
    private const val WARP_GAIN = 0.55f

    /** 已生成过层图？**必须是当前格式版本**，否则当过期 —— 升版后旧缓存必须重算。 */
    fun hasLayers(depthDir: File): Boolean {
        val f = File(layersDir(depthDir), "meta.json")
        if (!f.isFile) return false
        return runCatching {
            org.json.JSONObject(f.readText()).optInt("version", 1) >= FORMAT_VERSION
        }.getOrDefault(false)
    }

    /**
     * 生成层图 + 双通道位移场。**同步阻塞**，调用方放 IO 线程。
     *
     * @param depthDir `SpatialDepth` 返回的缓存目录（里面要有 `depth.png`）
     * @param force 为 true 时忽略已有层图、重算覆盖
     */
    fun generate(ctx: Context, depthDir: File, uri: Uri, force: Boolean = false): Result {
        val outDir = layersDir(depthDir)
        val metaFile = File(outDir, "meta.json")
        // 注意：这里必须用 hasLayers()（会校验 FORMAT_VERSION），不能只判断 meta.json 存在。
        // 之前只判断存在 ⇒ 升版本后旧缓存依然被跳过，UI 那条「过期就补算」的路径是死的。
        if (!force && hasLayers(depthDir)) {
            val n = runCatching {
                org.json.JSONObject(metaFile.readText()).optInt("layers", MAX_LAYERS)
            }.getOrDefault(MAX_LAYERS)
            Log.i(TAG, "层图已存在，跳过：${outDir.absolutePath}（$n 层）")
            return Result(outDir, n, 0L, true)
        }
        val t0 = SystemClock.elapsedRealtime()
        Log.i(TAG, "layers 开始 mem=${memStat()}")

        // 一、深度图（尺寸就是层图尺寸）
        val depthBmp = BitmapFactory.decodeFile(File(depthDir, "depth.png").absolutePath)
            ?: error("深度图读取失败（${depthDir}/depth.png）")
        val w = depthBmp.width
        val h = depthBmp.height
        val n = w * h
        val buf = obtainBuf(n)
        val px = buf.ints
        depthBmp.getPixels(px, 0, w, 0, 0, w, h)
        depthBmp.recycle()
        val depth = buf.depth
        for (i in 0 until n) depth[i] = (px[i] and 0xFF) / 255f

        // 二、原图按同尺寸解码（层图要拿原图 RGB）
        val rgbBmp = decodeToSize(ctx, uri, w, h)
        val rgbPixels = buf.rgb
        rgbBmp.getPixels(rgbPixels, 0, w, 0, 0, w, h)
        // 取完像素立即还 —— 这块是 4n 字节（1022×2268 ⇒ 8.8MB），原来一直挂到 GC。
        // 连续生成时它和下一张图的池抢同一份 256MB 堆，是 2026-10-31 OOM 的放大项之一。
        rgbBmp.recycle()

        // 三、**主体 mask 在全分辨率算**（2026-10-23 用户实测结论）
        //
        // 深度图本身就是很干净的遮罩：全分辨率直接阈值，剪影边缘锐利、发丝一根根在。
        // 之前的「降采样到 1/4 → 上采样」会把 <4px 的发丝抹掉、轮廓变成方块；
        // 再叠上中值 3 和开运算（腐蚀 1），边缘被削得更烂。现在全部去掉。
        // **不再做引导滤波**（2026-10-23 用户对比 PS 截图后定案）。
        // 原来 `guidedFilter(r=4)` 为了去噪，但它同时把边缘糊了 4px；
        // 用户在 PS 里是**直接对原始深度图拉色阶**，边缘 1~2px 锐利。
        // 深度图本身噪声不大（8bit PNG 量化），色阶的谷又落在真实空隙里，不需要预平滑。
        // **中值 3×3 去孤立噪点**（2026-10-26 实测后加）
        //
        // 为什么要：深度图上孤立的"近"点会让 k 极小，再被 `limitSlope` 的 min-plus 腐蚀
        // **向外扩散成菱形暗斑**（`f(p)=min_q(k(q)+D(p,q))` 会把局部极小值感染到周围一圈，
        // gx≠gy 所以压成扁菱形）。实测 R 通道有 **221 个 ≤40px 的碎块**，每个在画面上
        // 是 11px 的局部塌陷 —— 这是"大量黑点"的直接来源。
        // 3×3 中值能把孤立点完全抹掉，代价是边缘模糊 ≤1px（相对 band 0.018 可接受）。
        val depthMed = buf.tmpF
        median3(depth, depthMed, w, h)

        val filteredFull = depthMed
        val otsuFull = otsu(filteredFull)

        // ══════════════════════════════════════════════════════════════════════
        // **主体遮罩 = 深度图的「色阶」重映射**（2026-10-23 用户定案）
        //
        // PS 里拉输入色阶的黑点/白点，本质就是 `α = clamp((d − lo)/(hi − lo))`。
        // 之前用固定半宽 `SOFT_DELTA`，写死且只用了深度谷的一小段；现在让每张图自己定 lo/hi：
        //
        //   Otsu 求分界 T → 从 T 向两侧扫直方图，第一个「密度 < 0.1%」的位置就是谷的边界
        //   ⇒ lo = 谷左边界（纯背景），hi = 谷右边界（纯主体）
        //
        // 好处：过渡带完整落在深度谷里，不会切到真实像素；主体内部（d > hi）天然全 1，
        // 不会因为深度起伏掉成半透明（之前要靠 solidCore 硬保底，现在是自然结果）。
        // ══════════════════════════════════════════════════════════════════════
        val hist = IntArray(256)
        for (v in filteredFull) hist[(v * 255f).toInt().coerceIn(0, 255)]++
        // **切点 = 直方图的谷底**（2026-10-26 用户 PC 实测后定案）
        //
        // 旧做法是「从 Otsu 向两侧扫，直到密度 < n×0.1%」。问题：这个阈值是**绝对像素数**
        // （1022×1820 时是 1860 个），谷底一点点噪声就把它超了，扫描一路向外扩
        // ⇒ 实测 lo/hi 落在 103/205、band=0.4 ⇒ **只有 11.2% 是实心、32.7% 是半透明**，
        // alpha=0.5 的等值线落在主体内部很深处 ⇒ 轮廓整体向内缩、边缘发虚。
        //
        // 改成找**谷底**：两类之间"真正没有像素"的位置。平滑后在 Otsu 附近 ±40 bin 找最小密度。
        // PC 实测（同一张图）：切在 bin 66、band 0.018 ⇒ 实心 48.6%、半透明 0.23%，
        // 轮廓贴合主体（头发/外套/裙摆/花瓣都在）。
        //
        // ⚠️ 踩过：先试过"找下降沿最陡的位置"，切到 bin 122（比 Otsu 还高，落在主体内部）。
        // 因为直方图有多处陡降，最陡的那个是主体内部的小起伏；而真正的前后景交界在谷底，
        // 那里曲线是**平的**——「最陡」和「交界」在这一步恰好矛盾。
        val tBin = (otsuFull.threshold * 255f).toInt().coerceIn(0, 255)
        val smHist = FloatArray(256)
        for (i in 0..255) {
            var acc = 0f
            var cnt = 0
            for (d in -2..2) {
                val j = i + d
                if (j in 0..255) { acc += hist[j]; cnt++ }
            }
            smHist[i] = acc / cnt
        }
        val scanLo = max(1, tBin - 40)
        val scanHi = min(254, tBin + 40)
        var kBin = scanLo
        var valley = Float.MAX_VALUE
        for (i in scanLo..scanHi) {
            if (smHist[i] < valley) { valley = smHist[i]; kBin = i }
        }

        // ── 主体可信度：谷深比（2026-10-31，见 [VALLEY_MAX]）──
        //
        // `valley` 是谷底的**绝对**高度（像素数），随图片尺寸 / 主体面积比漂移
        // ⇒ 除以两侧较矮那座峰的高度，归一化成 0..1 的「谷深比」。
        //
        // ⚠️ 判定放在这里（而不是等 mask 算完）：因为它的结果要**决定** softAlpha 生不生成，
        //    而 softAlpha 又是 bodyMask 的来源 —— 放在后面会逻辑倒置。
        //    用的量全是直方图的，不依赖 mask，逻辑上是自洽的。
        val peakLo = run {
            var m = 0f
            for (i in 0..(kBin - VALLEY_PEAK_GUARD).coerceAtLeast(0)) if (smHist[i] > m) m = smHist[i]
            m
        }
        val peakHi = run {
            var m = 0f
            for (i in (kBin + VALLEY_PEAK_GUARD).coerceAtMost(255)..255) if (smHist[i] > m) m = smHist[i]
            m
        }
        val valleyRatio = valley / max(min(peakLo, peakHi), 1e-6f)
        val hasBody = valleyRatio <= VALLEY_MAX

        // ── 低段再切（2026-10-31，见 [SPLIT_RATIO]）──
        // 主 Otsu 只切一刀、且选的是方差最大的那一刀（这张图切在草丛）；
        // 对 `[0, tBin]` 再求一次 Otsu，如果它自己也有明确的二分结构，说明低段里
        // 还藏着第二个交界（人物 vs 天空）⇒ 把切点下移过去，人物才会进 bodyMask。
        // 一旦进了 bodyMask，背景层的 `nearestFill` 会把人物挖掉补平 ⇒ 那条 k 沟
        // 根本不产生（比事后熨平更彻底）。
        var lowCut = -1
        val otsuLow = otsu(filteredFull, toBin = tBin)
        // 用 `rawSep`（未除方差）比较：`separation` 对双峰总是≈1，三张图都会通过（实测 1.13/0.73/0.67）。
        // `rawSep` 的比值才能反映「低段那一刀值不值得切」——实测 0.177 / 0.065 / 0.031。
        val lowRatio = if (otsuFull.rawSep > 1e-6f) {
            otsuLow.rawSep / otsuFull.rawSep
        } else {
            0f
        }
        val lowThr = (otsuLow.threshold * 255f).toInt().coerceIn(0, 255)
        var lowNear = 0
        for (i in (lowThr + 1)..tBin) lowNear += hist[i]
        val lowNearRatio = lowNear.toFloat() / n
        if (lowRatio > SPLIT_RATIO && lowNearRatio > SPLIT_MIN_AREA) {
            val s0 = max(1, lowThr - 20)
            val s1b = min(254, lowThr + 20)
            var kb = s0
            var vv = Float.MAX_VALUE
            for (i in s0..s1b) if (smHist[i] < vv) { vv = smHist[i]; kb = i }

            // **中景层不能贴左右边**（2026-11-01，见 [SPLIT_EDGE_MAX]）。
            // 低段再切是「把被主 Otsu 漏掉的中景主体捞出来」，主体是独立物体、被背景包围；
            // 如果切出来的那层横跨整个画面宽度，它是墙 / 雾 / 地面 —— 背景的另一半，不该并进主体。
            val loF = kb / 255f
            val hiF = tBin / 255f
            var edgeHit = 0
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until 3) {
                    val vl = filteredFull[row + x]
                    if (vl > loF && vl <= hiF) edgeHit++
                    val vr = filteredFull[row + w - 1 - x]
                    if (vr > loF && vr <= hiF) edgeHit++
                }
            }
            val edgeRatio = edgeHit.toFloat() / (6f * h)
            if (edgeRatio < SPLIT_EDGE_MAX) {
                lowCut = kb
                Log.i(
                    TAG,
                    "低段再切：kBin $kBin → $kb（ratio=${"%.3f".format(lowRatio)}" +
                        " 近侧=${"%.1f".format(lowNearRatio * 100)}%" +
                        " 边触=${"%.1f".format(edgeRatio * 100)}%<${"%.0f".format(SPLIT_EDGE_MAX * 100)}%）",
                )
            } else {
                Log.i(
                    TAG,
                    "低段再切被否决：中景层贴左右边 ${"%.1f".format(edgeRatio * 100)}%" +
                        "（上限 ${"%.0f".format(SPLIT_EDGE_MAX * 100)}%）" +
                        "——那是背景的一部分（墙/雾/地面），不是独立主体",
                )
            }
        }

        // 谷底不够深（前景背景深度重叠、压根没有谷）⇒ 退回 T ± 固定半宽
        val hasValley = valley < n * 0.01f
        var lo: Float
        var hi: Float
        if (lowCut >= 0) {
            lo = (lowCut / 255f - 0.006f).coerceIn(0f, 1f)
            hi = (lowCut / 255f + 0.012f).coerceIn(0f, 1f)
        } else if (hasValley) {
            lo = (kBin / 255f - 0.006f).coerceIn(0f, 1f)
            hi = (kBin / 255f + 0.012f).coerceIn(0f, 1f)
        } else {
            lo = otsuFull.threshold - SOFT_DELTA
            hi = otsuFull.threshold + SOFT_DELTA
        }
        if (hi <= lo + 1e-4f) {
            lo = otsuFull.threshold - SOFT_DELTA
            hi = otsuFull.threshold + SOFT_DELTA
        }
        Log.i(
            TAG,
            "levels: T=${"%.3f".format(otsuFull.threshold)} 谷底=bin$kBin" +
                "(${"%.2f".format(valley / n * 100f)}%${if (hasValley) "" else " 无谷→兜底"})" +
                " 低段=${if (lowCut >= 0) "切@bin$lowCut" else "不切"}" +
                " lo=${"%.3f".format(lo)} hi=${"%.3f".format(hi)} band=${"%.3f".format(hi - lo)}",
        )

        // 四、**直接压遮罩，不做连通域过滤**（2026-10-23 用户定案）。
        // 连通域按面积删会把飞散的发丝、耳饰、指缝里的小块一起删掉 —— 那是「断发丝」的元凶之一。
        // 作为 alpha 遮罩根本不需要连通性：每个像素独立判断就够了，边缘平滑交给 matting。
        // ⚠️ **必须和 softAlpha 用同一个边界（`lo`）**（2026-10-23 用户实测「背景层有主体残留」）。
        // 之前 bodyMask 用 Otsu 阈值 T、softAlpha 用色阶的 lo，而 lo < T ——
        // 于是 `lo < d < T` 那一圈被算成「背景层」，direct=true 保留原图 RGB，
        // 背景层 alpha 又是 1−softAlpha（0.5~1，不透明）⇒ 主体边缘被背景层画了出来。
        // ══════════════════════════════════════════════════════════════════════
        // 四、**主体 alpha（灰度）+ 灰度膨胀**（2026-10-24 分层 v2 用户定案）
        //
        // 主体层向外多切 1~2px，但用**灰度膨胀**（max filter）而不是二值膨胀 ——
        // 这样原遮罩的软过渡还在，不会被削成硬边。
        // ══════════════════════════════════════════════════════════════════════
        // softAlphaRaw 借用 scratch；grayDilate 的中间行缓冲借用 kBg（此时都还没用）
        val softAlphaRaw = buf.scratch
        if (hasBody) {
            for (i in 0 until n) softAlphaRaw[i] = ((depthMed[i] - lo) / (hi - lo)).coerceIn(0f, 1f)
        } else {
            // **无主体降级**（2026-10-31，`VALLEY_MAX` 判据命中时走这里）。
            //
            // 整块置 0 ⇒ 下游全部自动退化，不需要改渲染器一行：
            //   bodyMask   = softAlpha > 1e-3      ⇒ 全 false
            //   fillRegion = fillHoles(bodyMask)   ⇒ 全 false ⇒ 底图不填、深度不背景化
            //   bodyCount  = 0                     ⇒ 走 L~990 的 `kFg = kBg` 分支
            //   层 1 的 alpha = softAlpha = 0      ⇒ 一张全透明 PNG，画了等于没画
            // 最终效果 = 「单层 + 整幅连续位移场」，即这张图不该硬切时的体面降级。
            java.util.Arrays.fill(softAlphaRaw, 0f)
        }
        val softAlpha = grayDilate(softAlphaRaw, w, h, BODY_DILATE, buf.alpha, buf.kBg)
        // 主体实心内部保底 1.0（深度凹陷不打穿主体）
        val solidCore = buf.bool
        for (i in 0 until n) solidCore[i] = softAlpha[i] > 0f
        erodeBool(solidCore, w, h, 2)
        for (i in 0 until n) if (solidCore[i]) softAlpha[i] = 1f

        val bodyMask = buf.bodyMask
        for (i in 0 until n) bodyMask[i] = softAlpha[i] > 1e-3f

        // **填充域 = bodyMask 的孔洞填充**（2026-10-26 定案，2026-10-27 修正）。
        //
        // `fillHoles` 把「从画面四边 flood fill 到不了」的非 mask 像素并入填充域。
        // 这些洞分两类，必须区别对待：
        //   (a) 主体自身的纹理凹陷（洞内深度 ≈ 周围主体）⇒ **该填**：否则 layer0 主体残留，
        //       且 harmonicFill 把"近"值当 Dirichlet 边界注入 ⇒ 黑斑；
        //   (b) **被主体围住的真实背景缝隙**（洞内深度远小于周围主体）⇒ **不该填**：
        //       主体层在那里 alpha = 0、盖不住，填进去只会变成一块假背景。
        // ⚠️ 2026-10-27 实测：这张图的内部洞 **10 个全部是 (b)**，合计占画面 5.37%
        //    （最大一个 403×250、44957px）。此前"视觉上属于主体轮廓"的判断是错的。
        //
        // ⚠️ **只用于"要抹掉的区域"**：主体层的 alpha 仍然只用 softAlpha。
        // （2026-10-24 曾把 fillHoles 并进主体 mask，导致主体变胖 —— 那是误用。）
        val fillRegion = buf.fillRegion
        fillHoles(bodyMask, w, h, fillRegion, buf.bytes, buf.ints)
        // 把"洞内明显更远"的连通洞从填充域摘出去（判据见 HOLE_DEPTH_GAP）
        val droppedHolePx = dropFarHoles(
            fillRegion, bodyMask, depthMed, w, h, HOLE_DEPTH_GAP, buf.bytes, buf.ints,
        )
        if (droppedHolePx > 0) {
            Log.i(TAG, "填充域：剔除远景洞 $droppedHolePx px（真实背景缝隙，不填）")
        }

        // 统计（判据 + 自适应层数）
        var fgPx = 0
        var fgSum = 0.0
        var fgSq = 0.0
        var bgSum = 0.0
        var bgSq = 0.0
        var bgMin = 1f
        var bgMax = 0f
        for (i in 0 until n) {
            val d = depthMed[i]
            if (bodyMask[i]) {
                fgPx++
                fgSum += d
                fgSq += d.toDouble() * d
            } else {
                bgSum += d
                bgSq += d.toDouble() * d
                if (d < bgMin) bgMin = d
                if (d > bgMax) bgMax = d
            }
        }
        val bgPx = n - fgPx
        val fgRatio = fgPx.toFloat() / n
        val fgSpread = stdOf(fgSum, fgSq, fgPx)
        val bgSpread = stdOf(bgSum, bgSq, bgPx)
        val bgRange = bgMax - bgMin
        val separation = otsuFull.separation
        val band = hi - lo

        // **过渡带归一化宽度**：衡量主体边缘有多"糊"。
        //
        // 定义 = 半透明带像素数（`lo ~ lo + SOFT_BAND_W`）÷ 轮廓周长 ÷ min(w, h)。
        // 边缘越软，深度图在交界处越没有台阶，切出来的 mask 越不可靠 ⇒ 不值得分离。
        //
        // 2026-10-28 实测三张深度图（归一化值 / 期望分支）：
        //   0.0234 → A（边缘锐利，梯度 0.068）
        //   0.0156 → A（梯度 0.049）
        //   0.0361 → **B**（边缘糊，梯度 0.021，只有前两张的 1/3）
        // 阈值取 0.03：前两张余量 0.006 / 0.014，第三张超 0.006。
        //
        // ⚠️ 口径：统计的是**全图**的半透明像素，不是只看轮廓内 30px 环。
        //    环版会把值压低 2~4 倍（0.0234→0.0060、0.0361→0.0179），与 0.03 不匹配。
        //    全图口径会混入主体内部的深度渐变 —— 但那本身也说明"这张图深度不平整"，
        //    对"该不该分离"同样是负面信号，所以不区分。
        var softBandPx = 0
        for (i in 0 until n) {
            val d = depthMed[i]
            if (d > lo && d < lo + SOFT_BAND_W) softBandPx++
        }
        var perim = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (!bodyMask[i]) continue
                if (x == 0 || x == w - 1 || y == 0 || y == h - 1) {
                    perim++
                } else if (!bodyMask[i - 1] || !bodyMask[i + 1] ||
                    !bodyMask[i - w] || !bodyMask[i + w]
                ) {
                    perim++
                }
            }
        }
        val softBandNorm = if (perim > 0) softBandPx.toFloat() / perim / min(w, h) else 0f

        // ⚠️ **`hasBody` 的判定已上移**到「三、切点」之后（2026-10-31，见 [VALLEY_MAX]）：
        // 改用深度直方图的**谷深比**（`valleyRatio <= 0.30`），不再用下面这三个量。
        //
        // 为什么要挪：三个旧条件（`separation` / `fgRatio` / `softBandNorm`）全都建立在
        // `bodyMask` 上，而 mask 又来自 `softAlpha` —— 判定和使用是倒序的，
        // 想「判定为无主体就不生成 mask」在旧位置根本做不到。
        // 谷深比只用直方图，可以在生成 softAlpha **之前**算出，逻辑才是顺的。
        //
        // 这三个量保留计算：日志里仍然打出来，方便和旧判据对照。
        // （无主体时 bodyMask 全空 ⇒ fg=0、perim=0，打出来没意义，所以只在有主体时打。）
        if (hasBody) {
            Log.i(
                TAG,
                "body 旧三量（仅诊断）：sep=${"%.2f".format(separation)} " +
                    "fg=${"%.1f".format(fgRatio * 100f)}% 过渡带=${"%.2f".format(softBandNorm * 100f)}%",
            )
        }

        // ══════════════════════════════════════════════════════════════════════
        // 五、层数：**恒定 2 层（背景 + 前景）**，不再按 hasBody 分叉（2026-10-28）
        //
        // 旧实现是「分支 A = 2 层 / 分支 B = 1 层」。两条路径的**步骤本来就一样**，
        // 差别只有「要不要跑」。现在取消分叉：所有图都填底图、都背景化、都出 kFg。
        // 收益 = 只剩**一条代码路径**，调参面从两条降到一条；hasBody 退化成纯标记。
        //
        // ⚠️ 已知代价（用户 2026-10-28 明确接受「不加保险，先看效果」）：
        //   hasBody=false 时 mask 不对应真实前后关系 ⇒
        //   静止画面**完全正常**（layer0 已填充 + layer1 原图覆盖 = 原图），
        //   但转动时 mask 区会**凭空错开约 28px**（|kFg−kBg| × 满量程 100px，
        //   实测 k 差 p50 = 0.282 ⇒ 0.282 × 100px）。那是假裂缝，不是 bug。
        //   若 fillRegion 在极端图上失控（洞过大），日志 `底图填充：…（N%）` 会直接暴露。
        //
        // **2026-10-25 用户定案**：位移不再由「多层」承担，改成渲染器**逐像素**按深度图做
        // （片元里 `q = q0 − uShift · (1 − K·depth(uv0))`）。层只负责"断崖"：
        // 主体和背景的深度差就是那道断崖，所以两层就够。
        //
        // 之前 8~10 层是 k-means **按深度值**切出来的，切点会落在连续渐变中间 ⇒
        // 转动时层与层之间必然割裂（层是刚性平移，深度只在层边界跳变一次）。
        // 位移连续之后，连"缝"都不需要了，只保留主体/背景这一道真实的前后关系。
        // ══════════════════════════════════════════════════════════════════════
        val bgLayerCount = 0                       // 仅留给 meta 旧字段
        val fgLayerCount = 1
        val layerCount = 2
        Log.i(
            TAG,
            "branch: hasBody=$hasBody 谷深比=${"%.3f".format(valleyRatio)}" +
                "（上限 $VALLEY_MAX，谷@bin$kBin 左峰=${peakLo.toInt()} 右峰=${peakHi.toInt()}）" +
                if (hasBody) {
                    " sep=${"%.2f".format(separation)} band=${"%.3f".format(band)} " +
                        "fg=${"%.1f".format(fgRatio * 100f)}% " +
                        "过渡带=${"%.2f".format(softBandNorm * 100f)}%（上限 ${"%.1f".format(SOFT_BAND_MAX * 100f)}%" +
                        "，半透明 ${softBandPx}px ÷ 周长 ${perim}px ÷ ${min(w, h)}）"
                } else {
                    " ⇒ 无主体降级：主体层全透明，整幅走连续位移场"
                } +
                " => layers=$layerCount",
        )

        // 主体平均深度：渲染器对主体层用**刚性**系数 `1 − K·d`。主体的 d 更大
        // ⇒ 系数更小 ⇒ 动得更少，"主体当锚、背景流动"的相对关系自动保留。
        var bodyDepthSum = 0.0
        var bodyCount = 0
        for (i in 0 until n) {
            if (bodyMask[i]) { bodyDepthSum += depthMed[i]; bodyCount++ }
        }
        val bodyDepthMean = if (bodyCount > 0) bodyDepthSum / bodyCount else 1.0

        outDir.mkdirs()
        // 预览图不再写 `preview.webp`（2026-10-29 用户要求）：二级页的 1:1 预览框直接解码
        // 缓存目录里的 `source` 原图（见 `SpatialDepth.SOURCE_NAME` / `ensureSource`）。
        // 遮罩（灰度 PNG，0=背景 1=主体），供对比
        runCatching {
            val mb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val mp = buf.ints
            for (i in 0 until n) {
                val a = (softAlpha[i] * 255f).toInt().coerceIn(0, 255)
                mp[i] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
            }
            mb.setPixels(mp, 0, w, 0, 0, w, h)
            FileOutputStream(File(depthDir, "mask.png")).use {
                mb.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            mb.recycle()
        }.onFailure { Log.w(TAG, "mask.png 保存失败：${it.message}") }

        // ══════════════════════════════════════════════════════════════════════
        // 六、背景底图：把 fillRegion 填掉（前景层移开后露出的就是这块）
        //
        // **2026-10-28 起无条件执行**（旧实现只有分支 A 填、分支 B 直接拿原图）。
        // ⚠️ 对 hasBody=false 的图，这一步是**净损失** —— 没有前景要移开，
        //    填进去等于把真实内容换成 PatchMatch 编的内容。属于「统一流水线」的已知代价。
        // ══════════════════════════════════════════════════════════════════════
        var holeCount = 0
        val baseFill = buf.baseFill
        System.arraycopy(rgbPixels, 0, baseFill, 0, n)
        var fillCostMs = 0L
        val baseHole = buf.bytes          // fillHoles 已经用完这块（visit 标记）
        for (i in 0 until n) {
            if (fillRegion[i]) { holeCount++; baseHole[i] = 255.toByte() } else baseHole[i] = 0
        }
        val tFill = SystemClock.elapsedRealtime()
        // **没有洞就别跑**（2026-10-31）：无主体降级时 `fillRegion` 全空，
        // 但 `pm_inpaint` 照样会建金字塔、跑 EM —— 1022×2268 上是几秒的纯浪费。
        val fillMs = if (holeCount > 0) SpatialInpaint.fill(baseFill, baseHole, w, h) else 0L
        fillCostMs = SystemClock.elapsedRealtime() - tFill
        if (holeCount == 0) {
            Log.i(TAG, "底图填充：跳过（无洞，主体层为空）")
        } else if (fillMs < 0) {
            Log.e(TAG, "底图填充失败（libspatial_fill 不可用？）—— 主体区会保留原像素")
        } else {
            Log.i(TAG, "底图填充：${w}x$h 洞 $holeCount px（${holeCount * 100 / n}%），${fillMs}ms")
        }
        Log.i(TAG, "layers 填充后 mem=${memStat()}（fill=${fillCostMs}ms）")

        // ══════════════════════════════════════════════════════════════════════
        // 七、每层出图
        //   层 0 = 背景：全幅不透明（fillRegion 内已被填掉）
        //   层 1 = 前景：alpha = softAlpha（2026-10-28 起恒定存在；
        //                softAlpha 全空时它就是一张全透明图，视觉上等于单层）
        // ══════════════════════════════════════════════════════════════════════
        val layerMeta = JSONArray()
        for (layer in 0 until layerCount) {
            val isBase = layer == 0
            val out = buf.ints          // px 早已不用，直接复用
            for (i in 0 until n) {
                val rgb = if (isBase) baseFill[i] else rgbPixels[i]
                val a: Float = if (isBase) 1f else softAlpha[i]
                out[i] = if (a > 0f) {
                    ((a * 255f).toInt().coerceIn(0, 255) shl 24) or (rgb and 0x00FFFFFF)
                } else {
                    0
                }
            }

            // 主体层的 cover 边界羽化（底层全幅，没有边界）
            if (!isBase) {
                val maskCover = buf.bytes      // baseHole 填充已用完，借这块当蒙版
                for (i in 0 until n) {
                    maskCover[i] = if (((out[i] ushr 24) and 0xFF) > 0) 255.toByte() else 0
                }
                val soft = featherBox(maskCover, w, h, FEATHER)
                for (i in 0 until n) {
                    val a0 = (out[i] ushr 24) and 0xFF
                    if (a0 == 0) continue
                    val a = a0 * (soft[i].toInt() and 0xFF) / 255
                    out[i] = (a shl 24) or (out[i] and 0x00FFFFFF)
                }
            }

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(out, 0, w, 0, 0, w, h)
            FileOutputStream(File(outDir, "layer$layer.png")).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bmp.recycle()
            layerMeta.put(
                JSONObject()
                    .put("index", layer)
                    // 背景层用不到（它走逐像素）；主体层拿它算刚性系数
                    .put("depthMean", if (isBase) 0.0 else bodyDepthMean)
                    .put("pixels", if (isBase) n else bodyCount)
                    // 1 = 主体（渲染器据此切成刚性位移）
                    .put("fg", if (isBase) 0 else 1),
            )
        }

        // ══════════════════════════════════════════════════════════════════════
        // 八、位移场：**两条**塑形好的 k 场（同一张 PNG 的 R / G 通道）
        //
        //   k = K_MIN + (1−K_MIN) · clamp(1 − d / d_anchor, 0, 1)
        //
        //   R = 背景场：深度先「背景化」—— 把主体区挖掉、用周边背景深度补全，再算 k
        //   G = 主体场：直接用原始深度算 k（只给主体层用）
        //
        // **为什么背景场必须背景化**：位移场里留着主体轮廓，等于在背景上挖了一条沟，
        // 转动时那条沟被摊开成一道拉伸带（2026-10-25 实测）。iPhone 的主体是独立
        // cut-out，背景层里没有这道沟 —— 「和 iPhone 差在这」就差在这。
        //
        // **为什么主体也要自己的场**：主体走刚性（整层一个 k）时内部完全不形变，
        // 看起来像贴在玻璃上的一张贴纸。而主体层是独立 alpha 层画在背景之上，
        // 轮廓由 alpha 决定、不由位移场决定，所以两条场各自独立，不会互相制造裂缝。
        //
        // **锚点**：d_anchor = 各场自己的 95 分位（最近处）；K_MIN = 0.15 是枢轴处的残余位移。
        //   0.12 太小（实测主体几乎不动，被 pin 死了），0.35 是「还能明显动」的下限。
        //
        // **限幅（Lipschitz）**：|∂k/∂u| ≤ LIM_SLOPE / PAN_UV_X（垂直用 Y）。
        //   折叠判据 du/du₀ = 1 − PAN_UV_X·∂k/∂u；LIM_SLOPE = 1.0 时带中心放大率无穷大
        //   （内容被捏成一条线 = 一道疤），0.5 ⇒ 最多放大 2×，形态是「运动模糊带」。
        //   背景场全图限幅；主体场**只在其掩码内**限幅 —— 否则主体轮廓会被背景的 k
        //   拉过去，边缘被拉变形。
        // ══════════════════════════════════════════════════════════════════════
        val tField = SystemClock.elapsedRealtime()
        val limX = LIM_SLOPE / PAN_UV_X / w
        val limY = LIM_SLOPE / PAN_UV_Y / h

        // ── 背景场 ──
        // **深度标准差 → 位移量缩放**（只缩小不放大）
        //
        // `SpatialDepth` 在输出前做了 min-max 归一化，所以 d 总是铺满 [0,1]，
        // 「这张图深度跨度多大」的信息已经被抹掉了。立体感的差异只剩**分布形状**：
        // 标准差大 ⇒ 前后景分明、位移可以大；标准差小 ⇒ 图像本来就平，强行拉大反而假。
        var dSum = 0.0
        var dSq = 0.0
        for (i in 0 until n) {
            val v = depthMed[i]
            dSum += v
            dSq += v.toDouble() * v
        }
        val depthStd = stdOf(dSum, dSq, n)
        // meta 一次读出：模型名（分流 DA2/DA3 定标）+ 原始极差。
        // 老缓存没有 meta ⇒ model=""、range=0 ⇒ 走 DA2 路径的 σ-only，与旧行为一致。
        val meta = runCatching { JSONObject(File(depthDir, "meta.json").readText()) }.getOrNull()
        val isDa3 = (meta?.optString("model") ?: "").startsWith("da3_")
        val scaleByStd = (depthStd / (if (isDa3) DEPTH_STD_REF_DA3 else DEPTH_STD_REF))
            .coerceIn(0.3f, 1f)
        // 原始范围（归一化之前的值）—— DA2 视差下才是「相对深度」的直接度量。
        // DA3 跳过（=1f）：距离制输出的 range 是场景尺度量，REF 5.0 按 DA2 标定的，
        // DA3 一用就触底 0.3（见 DEPTH_STD_REF_DA3 注释，2026-11-02 实测对比）。
        val depthRange = (meta?.let {
            (it.optDouble("depthMax", 0.0) - it.optDouble("depthMin", 0.0)).toFloat()
        }) ?: 0f
        val scaleByRange = if (isDa3 || depthRange <= 0.1f) {
            1f
        } else {
            (depthRange / DEPTH_RANGE_REF).coerceIn(0.3f, 1f)
        }
        // 取小：σ 会被归一化的噪声骗高，range 会被画面角落的极值骗高 —— 谁保守听谁的
        val depthScale = min(scaleByStd, scaleByRange)

        // **背景化 = 最近邻传播**（2026-10-26 换掉 push-pull）
        //
        // push-pull 本质是加权平均：主体占 51% 时洞太大，洞中心趋向"整幅平均"而不是
        // "边界延伸" ⇒ 补全区留下一个渐变的主体形状（实测轮廓内外差 0.047，PS 里看得出）。
        // 深度是**分段平滑**的，洞里的值就该等于最近的那个背景像素 ⇒ 用 chamfer 距离变换
        // 做最近邻传播。洞再大也不会趋向全局平均。
        //
        // **2026-10-28 起无条件执行**：只要底图填了，深度就必须跟着填 ——
        // 内容与深度必须一致，否则「真背景内容 + 主体深度」会在 k 场上互相打架。
        nearestFill(depthMed, fillRegion, w, h, buf.scratch, buf.dist(), buf.near(), buf.bool)
        // 最近邻只给"边界精确"的初值，真正的解由 Laplace 多尺度松弛给出（见 harmonicFill）
        harmonicFill(buf.scratch, fillRegion, w, h)
        val depthBg = buf.scratch
        val dAnchorBg = percentile(depthBg, 0.95f).coerceAtLeast(D_ANCHOR_MIN)
        val kBg = buf.kBg
        for (i in 0 until n) {
            kBg[i] = K_MIN + (1f - K_MIN) * WARP_GAIN * depthScale *
                (1f - (depthBg[i] / dAnchorBg).coerceIn(0f, 1f))
        }
        // **闭运算（必须在限幅之前）**：填掉孤立的暗坑 —— 否则 limitSlope 的 min-plus
        // 会把它沿 4 邻域扩散成 L1 菱形锥（纵向半径 = 横向 1.78 倍）。
        // 此处 depthBg 已经用完（dAnchorBg 与 kBg 都已取走），`buf.scratch` 可借来当行缓冲。
        greyClosing(kBg, w, h, GREY_CLOSE_R, buf.tmpF2, buf.scratch)
        limitSlope(kBg, w, h, limX, limY)

        // ── 主体场 ──
        //
        // 与背景场同样归一化，但**多一道按像素定标**：先算出主体区内的原始跨度，
        // 再等比压缩到 FG_WARP_SPAN_PX。这样「形变有多明显」是一个可以直接读的数（像素），
        // 不再随图片分辨率、主体深度范围漂移。
        var dAnchorFg = 0f
        var fgWarp = 1f
        var fgSpanPx = 0f
        val kFg: FloatArray
        if (bodyCount > 64) {
            dAnchorFg = percentile(depth, 0.95f, bodyMask).coerceAtLeast(0.05f)
            // 归一化项 s = 1 − d/d_anchor ∈ [0,1]
            // 直接复用 scratch（此时 depthBg 已经算完 kBg，不再需要）
            val sFg = buf.scratch
            for (i in 0 until n) sFg[i] = 1f - (depthMed[i] / dAnchorFg).coerceIn(0f, 1f)
            // 主体区内的跨度（p1..p99，避开深度图毛刺）
            val sLo = percentile(sFg, 0.01f, bodyMask)
            val sHi = percentile(sFg, 0.99f, bodyMask)
            // 跨度里要带上 depthScale —— 定标的目标是「最终 k 场的像素跨度 = 5px」
            fgSpanPx = ((sHi - sLo) * (1f - K_MIN) * depthScale) * PAN_UV_X * w
            fgWarp = if (fgSpanPx > 0.05f) {
                (FG_WARP_SPAN_PX / fgSpanPx).coerceIn(0.02f, 1f)
            } else {
                1f
            }
            // 原地变换（sFg 与 kFg 是同一块缓冲，同下标读写安全）
            for (i in 0 until n) {
                sFg[i] = K_MIN + (1f - K_MIN) * depthScale * fgWarp * sFg[i]
            }
            kFg = sFg
            // 掩码膨胀到羽化边界外：羽化区（alpha > 0 但 bodyMask = false）也得用主体的场，
            // 否则那几像素的 k 会是背景的（大约 3 倍），轮廓外挂一圈拖影。
            limitSlope(kFg, w, h, limX, limY, dilateMask(bodyMask, w, h, FEATHER + 2))

            // **边缘环带低通**（见 EDGE_SMOOTH_SIGMA 注释）—— 治"大角度下边缘参差不齐"。
            //
            // 必须在 extendField **之前**：延拓用的是"最近的轮廓值"，先在环带上把抖动抹平，
            // 延拓出去的值才是干净的。放在延拓之后就白做了（抖动已经被复制到轮廓外 40px）。
            // 也必须在 limitSlope **之后**：限幅会重新引入梯度，先平滑再限幅等于没平滑。
            val smoothT = SystemClock.elapsedRealtime()
            val solid = buf.bool
            for (i in 0 until n) solid[i] = softAlpha[i] >= 0.9f
            val dtEdge = buf.dist()
            distToSolid(solid, dtEdge, w, h)

            // **归一化卷积**（2026-10-28 用户定案「保结构，多算就多算」）。
            //
            // 为什么必须归一化：轮廓外（alpha=0）的 `kFg` 是"用背景深度 + 主体锚点"算的，
            // 比主体内**高 0.102**（实测 0.271 vs 0.169 = +10.2px 位移）。box 核半径 30，
            // 直接全图模糊会把那一圈混进环带 ⇒ 环带被**系统性抬高**：
            // 实测环带均值 17.43→19.59px、内部跨度 5.10→7.07px、参差只降一半（4.11→1.98）。
            // 分子分母同乘 mask 后，轮廓外不参与平均 ⇒ 环带只会"变平"，不会"变亮"。
            //
            // 代价：多 2 块 n 大小的 FloatArray（各 6.9MB @1022×1694）+ 多一次 box 模糊。
            val numIn = buf.tmpF      // tmpF：median3 之后空闲，这里当"分子输入"，之后复用为"分母输入"
            val numOut = buf.blurNum()
            val denOut = buf.blurDen()
            val bTmp = buf.tmpF2      // tmpF2：greyClosing 之后空闲，当 box 的水平中间
            for (i in 0 until n) numIn[i] = if (softAlpha[i] > 0.05f) kFg[i] else 0f
            boxBlur3(numIn, numOut, w, h, EDGE_SMOOTH_BOX_R, bTmp)
            for (i in 0 until n) numIn[i] = if (softAlpha[i] > 0.05f) 1f else 0f
            boxBlur3(numIn, denOut, w, h, EDGE_SMOOTH_BOX_R, bTmp)

            var ringPx = 0
            var denMin = 1f
            for (i in 0 until n) {
                if (softAlpha[i] <= 0.05f) continue
                val t = ((EDGE_SMOOTH_RING_OUT - dtEdge[i]) /
                    (EDGE_SMOOTH_RING_OUT - EDGE_SMOOTH_RING_IN)).coerceIn(0f, 1f)
                if (t <= 0f) continue
                val d = denOut[i]
                if (d <= 1e-3f) continue
                if (d < denMin) denMin = d
                val blurred = numOut[i] / d
                kFg[i] = kFg[i] * (1f - t) + blurred * t
                ringPx++
            }
            Log.i(
                TAG,
                "边缘低通：σ=$EDGE_SMOOTH_SIGMA（box r=$EDGE_SMOOTH_BOX_R ×3，归一化卷积）" +
                    " 环带 $ringPx px（${ringPx * 100 / n}%）den_min=${"%.3f".format(denMin)}" +
                    " 耗时 ${SystemClock.elapsedRealtime() - smoothT}ms",
            )

            // **最后一步：向轮廓外延拓**（见 FG_EXTEND_PX 注释）。
            // 必须放在限幅之后 —— 延拓区要的是一个"恒定值"，被限幅再压一次就又不平了。
            extendField(kFg, bodyMask, w, h, FG_EXTEND_PX, buf.dist(), buf.near())
        } else {
            // 掩码几乎为空（bodyCount ≤ 64）：G 通道填同一份。
            // ⚠️ 2026-10-28 起 layer1 **总是存在**，所以这条只表示「前景层是全透明的」，
            //    G 通道虽然会被采样，但采样到的区域 alpha=0，不会显示出来。
            kFg = kBg
        }

        Log.i(
            TAG,
            "位移场：σ=${"%.3f".format(depthStd)} range=${"%.2f".format(depthRange)}" +
                " scale=min(${"%.2f".format(scaleByStd)},${"%.2f".format(scaleByRange)})" +
                "=${"%.2f".format(depthScale)}" +
                " bg dAnchor=${"%.3f".format(dAnchorBg)}" +
                (if (dAnchorFg > 0f) {
                    " fg dAnchor=${"%.3f".format(dAnchorFg)}" +
                        " 形变跨度=${"%.1f".format(fgSpanPx)}px -> ${"%.1f".format(fgSpanPx * fgWarp)}px" +
                        "（warp=${"%.2f".format(fgWarp)}，目标 $FG_WARP_SPAN_PX px）" +
                        " 轮廓外延拓 ${FG_EXTEND_PX}px"
                } else "") +
                " K_MIN=$K_MIN GAIN=$WARP_GAIN CLOSE=${GREY_CLOSE_R}px LIM=$LIM_SLOPE" +
                " 限幅 gx=${"%.4f".format(limX)}/px" +
                " gy=${"%.4f".format(limY)}/px 耗时 ${SystemClock.elapsedRealtime() - tField}ms" +
                " mem=${memStat()}",
        )

        // R = 背景场，G = 主体场。**A 必须 255**：Bitmap 内部是预乘的，alpha < 255 时
        // setPixels 会把 RGB 乘上 alpha，两条场直接报废。
        runCatching {
            val db = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val dp = buf.ints
            for (i in 0 until n) {
                val r = (kBg[i] * 255f).toInt().coerceIn(0, 255)
                val g = (kFg[i] * 255f).toInt().coerceIn(0, 255)
                dp[i] = (0xFF shl 24) or (r shl 16) or (g shl 8)
            }
            db.setPixels(dp, 0, w, 0, 0, w, h)
            FileOutputStream(File(outDir, "depth.png")).use {
                db.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            db.recycle()
        }.onFailure { Log.w(TAG, "depth.png 保存失败：${it.message}") }

        // 清掉旧的多余层图（层数从 10 降到 2 时残留会干扰）
        runCatching {
            outDir.listFiles()?.forEach { f ->
                val nm = f.name
                if (nm.startsWith("layer") && nm.endsWith(".png")) {
                    val idx = nm.removePrefix("layer").removeSuffix(".png").toIntOrNull()
                    if (idx != null && idx >= layerCount) f.delete()
                }
            }
        }

        FileOutputStream(File(outDir, "meta.json")).use {
            it.write(
                JSONObject()
                    .put("version", FORMAT_VERSION)
                    .put("layers", layerCount)
                    .put("width", w)
                    .put("height", h)
                    // **资格标记，不描述层结构**（2026-10-28）：层数恒为 2，与本字段无关。
                    // 未来「空间壁纸锁屏景深」读它判断这组壁纸能否开景深：
                    // true = 切分基于可信主体；false = 切分是猜的，开景深会露馅。
                    .put("hasBody", hasBody)
                    .put("bgLayers", bgLayerCount)
                    .put("fgLayers", fgLayerCount)
                    .put("bodyDepth", bodyDepthMean)
                    // 没有位移场时主体层的兜底刚性系数（正常路径下走 G 通道，不用它）
                    .put("bodyK", K_MIN)
                    .put("layerInfo", layerMeta)
                    .toString().toByteArray(),
            )
        }
        val costMs = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "layers 出图后 mem=${memStat()}")
        Log.i(
            TAG,
            "分层完成：$layerCount 层（hasBody=$hasBody）耗时 ${costMs}ms" +
                "（其中底图填充 ${fillCostMs}ms）",
        )
        return Result(outDir, layerCount, costMs, false)
    }

    /**
     * **大数组复用池**：同一尺寸只分配一次，之后每次生成零分配。
     *
     * 为什么必须这么做：单次生成要十几个 7.4MB 的数组（n=1.86M × 4B）。ART 把 >12KB 的对象
     * 放进 **Large Object Space**（单独 mmap、**不压缩、空页不还给系统**）。反复分配释放
     * ⇒ mmap 碎片 + 频繁 full GC（全线程暂停）。用户实测三个症状：
     * 同一张图也偶发卡顿 / 偶尔卡死 / 生成完 RSS 不降 —— 全部对得上。
     *
     * 复用后常驻约 69MB，换取「第二次起零分配」。
     *
     * 字段的复用路径（不要随意调换顺序，会踩到彼此）：
     * ```
     * ints    : px（深度解码）→ maskPx → out（出图）→ dp（位移场输出）
     * scratch : softAlphaRaw → depthBg → sFg → kFg
     * kBg     : grayDilate 的中间行缓冲 → 背景场
     * ```
     */
    private class Buf(val n: Int) {
        val ints = IntArray(n)
        val depth = FloatArray(n)
        val rgb = IntArray(n)
        val alpha = FloatArray(n)

        /** 背景场（最终写进 depth.png 的 R 通道）。 */
        val kBg = FloatArray(n)

        /** 依次充当：softAlphaRaw → depthBg → sFg → kFg（主体场的 G 通道）。 */
        val scratch = FloatArray(n)

        /** 通用临时：中值滤波的输出。 */
        val tmpF = FloatArray(n)

        /** 背景场闭运算的膨胀结果。 */
        val tmpF2 = FloatArray(n)

        /** 布尔 scratch：solidCore。 */
        val bool = BooleanArray(n)

        /** 字节 scratch：baseHole（填充洞）→ maskCover（主体层羽化）。 */
        val bytes = ByteArray(n)

        val bodyMask = BooleanArray(n)

        /** **填充域** = `bodyMask` 的孔洞填充结果（外轮廓 + 内部洞）。只用于"要抹掉的区域"，
         *  与主体层 alpha 无关（alpha 始终用 softAlpha）。 */
        val fillRegion = BooleanArray(n)

        /** 底图（背景层 RGB）。 */
        val baseFill = IntArray(n)

        var distBuf: FloatArray? = null
        var srcBuf: IntArray? = null

        /** 归一化卷积的**分子**模糊输出（`blur(k·m)`）。懒分配。 */
        private var numBuf: FloatArray? = null

        /** 归一化卷积的**分母**模糊输出（`blur(m)`）。懒分配。 */
        private var denBuf: FloatArray? = null

        fun dist(): FloatArray = distBuf ?: FloatArray(n).also { distBuf = it }
        fun near(): IntArray = srcBuf ?: IntArray(n).also { srcBuf = it }
        fun blurNum(): FloatArray = numBuf ?: FloatArray(n).also { numBuf = it }
        fun blurDen(): FloatArray = denBuf ?: FloatArray(n).also { denBuf = it }

        /**
         * 丢掉四块懒分配的 scratch（各 `4n`，1022×2268 ⇒ 合计 35.4MB）。
         *
         * 它们只在**流水线跑的时候**需要，跑完就是白占。换尺寸重建池之前调一次，
         * 待回收的体积从 `56n` 降到 `40n`（2026-10-31 OOM）。
         */
        fun releaseLazy() {
            distBuf = null
            srcBuf = null
            numBuf = null
            denBuf = null
        }
    }

    private var pool: Buf? = null

    /** 丢掉整个复用池（OOM 自愈用）。下次 `obtainBuf` 会重建。 */
    fun dropPool() {
        synchronized(this) {
            pool?.releaseLazy()
            pool = null
        }
    }

    /**
     * 内存快照（诊断用）：java=已用/上限、native=已分配/堆大小、pool=复用池常驻。
     *
     * 判读方法：
     * - **native 涨、java 平** ⇒ Bitmap 像素 + PatchMatch（都在 native heap，池化管不到）；
     * - **java 每次都涨到同一高度再回落** ⇒ 还有没池化的大数组；
     * - **java 一次生成后不回落** ⇒ 就是复用池本身（预期行为）。
     */
    private fun memStat(): String {
        val rt = Runtime.getRuntime()
        val javaUsed = (rt.totalMemory() - rt.freeMemory()) shr 20
        val javaMax = rt.maxMemory() shr 20
        val nativeUsed = android.os.Debug.getNativeHeapAllocatedSize() shr 20
        val nativeSize = android.os.Debug.getNativeHeapSize() shr 20
        // 池常驻：ints/depth/rgb/alpha/kBg/scratch/baseFill 各 4B + bodyMask/bool/bytes 各 1B
        val poolMb = (pool?.let { it.n * 33L } ?: 0L) shr 20
        return "java=${javaUsed}/${javaMax}MB native=${nativeUsed}/${nativeSize}MB pool=${poolMb}MB"
    }

    /** 取同尺寸缓冲；尺寸变了就重建。 */
    private fun obtainBuf(n: Int): Buf = synchronized(this) {
        var cur: Buf? = pool
        if (cur != null && cur.n == n) {
            cur
        } else {
            // **换图（n 变了）时的顺序很重要**（2026-10-31）：
            //   1. 先丢懒分配 ⇒ 待回收体积从 56n 降到 40n；
            //   2. 断引用 + 显式 GC ⇒ 分配新池之前把旧的那份真的还回去。
            // 不做这两步时「旧池 123.8MB + 新池 123.8MB」直接撞 256MB 堆上限
            // （实测 1022×2268 → 1022×2212 必挂，见 run_log 的 OutOfMemoryError）。
            //
            // ⚠️ 必须把局部变量也置 null：只清 `pool` 时栈上还握着引用，
            //    `System.gc()` 会判定它可达、什么都不回收。
            cur?.releaseLazy()
            cur = null
            pool = null
            System.gc()
            Buf(n).also { pool = it }
        }
    }

    /**
     * 深度直方图分位（[p] ∈ 0..1）。
     *
     * @param sample 非 null 时只统计 `sample[i] == true` 的像素（例如「主体区内的 p95」）
     */
    private fun percentile(src: FloatArray, p: Float, sample: BooleanArray? = null): Float {
        val hist = IntArray(256)
        var total = 0
        for (i in src.indices) {
            if (sample != null && !sample[i]) continue
            hist[(src[i] * 255f).toInt().coerceIn(0, 255)]++
            total++
        }
        if (total == 0) return 1f
        val target = (total * p).toInt()
        var acc = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= target) return i / 255f
        }
        return 1f
    }

    /**
     * **二值掩码膨胀**（方形结构元，半径 [r]）。用「记录上一个 true 的位置」实现，
     * 横竖各扫两趟 ⇒ O(N)，与 r 无关。
     *
     * 用途：主体场的限幅范围要盖住羽化边界（`alpha > 0` 但 `bodyMask = false` 的那几圈），
     * 否则那些像素的 k 来自背景（数量级差 3 倍），轮廓外会挂一圈拖影。
     */
    private fun dilateMask(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            var last = -1000000
            for (x in 0 until w) {
                if (src[row + x]) last = x
                if (x - last <= r) tmp[row + x] = true
            }
            last = 1000000
            for (x in w - 1 downTo 0) {
                if (src[row + x]) last = x
                if (last - x <= r) tmp[row + x] = true
            }
        }
        val out = BooleanArray(src.size)
        for (x in 0 until w) {
            var last = -1000000
            for (y in 0 until h) {
                if (tmp[y * w + x]) last = y
                if (y - last <= r) out[y * w + x] = true
            }
            last = 1000000
            for (y in h - 1 downTo 0) {
                if (tmp[y * w + x]) last = y
                if (last - y <= r) out[y * w + x] = true
            }
        }
        return out
    }

    /**
     * **把标量场向掩码外"延拓"**：轮廓外 [radius] 像素内，每个像素取**最近主体像素**的值。
     *
     * 目的：让主体层的位移场在轮廓两侧连续。掩码外如果留着「按背景深度算出来的 k」，
     * 就会在掩码边界形成一道硬跳（实测 Δk=0.169 ⇒ 位移 18px → 24.4px），
     * 转起来那圈会把主体内容压缩着显示 ⇒ 看起来像第二个主体。
     *
     * 做法：两趟 8 邻域 chamfer 扫描（正交步长 1、对角 √2），每像素维护
     * `dist`（到最近种子距离）+ `src`（最近种子的下标）。第二趟结束后按下标回填。
     *
     * 注意：`src` 永远指向掩码内（不会被写），所以边回填边读 `k[src[i]]` 是安全的。
     * O(N)，1.86M 像素约 100ms。
     */
    /**
     * **孔洞填充**：把被 [mask] **包围**的非 mask 区域并进结果（只填洞，不膨胀外轮廓）。
     *
     * 为什么需要：主体内部有一批"深度比阈值更远"的像素（实测占画面 3.1%、73 个连通块），
     * 它们 `softAlpha = 0` ⇒ 不在 `bodyMask` 里，但**视觉上属于主体的轮廓**。
     * 不并进"填充域"会有两条后果：(a) 背景层保留原图 ⇒ 主体残留；(b) Laplace 把它们当边界 ⇒ 黑斑。
     *
     * 做法：从画面四边 flood fill 标出"外部可达的背景"，剩下没被访问到的非 mask 像素就是洞。
     * O(N)，1.86M 像素约 60~100ms。
     *
     * @param visit 临时标记（长度 ≥ n，内容会被覆盖）
     * @param queue 临时队列（长度 ≥ n）
     */
    private fun fillHoles(
        mask: BooleanArray,
        w: Int,
        h: Int,
        out: BooleanArray,
        visit: ByteArray,
        queue: IntArray,
    ) {
        val n = w * h
        java.util.Arrays.fill(visit, 0)
        var head = 0
        var tail = 0
        // 四边入队（只走非 mask）
        for (x in 0 until w) {
            val a = x
            val b = (h - 1) * w + x
            if (!mask[a] && visit[a].toInt() == 0) { visit[a] = 1; queue[tail++] = a }
            if (!mask[b] && visit[b].toInt() == 0) { visit[b] = 1; queue[tail++] = b }
        }
        for (y in 1 until h - 1) {
            val a = y * w
            val b = y * w + w - 1
            if (!mask[a] && visit[a].toInt() == 0) { visit[a] = 1; queue[tail++] = a }
            if (!mask[b] && visit[b].toInt() == 0) { visit[b] = 1; queue[tail++] = b }
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) {
                val j = i - 1
                if (!mask[j] && visit[j].toInt() == 0) { visit[j] = 1; queue[tail++] = j }
            }
            if (x < w - 1) {
                val j = i + 1
                if (!mask[j] && visit[j].toInt() == 0) { visit[j] = 1; queue[tail++] = j }
            }
            if (y > 0) {
                val j = i - w
                if (!mask[j] && visit[j].toInt() == 0) { visit[j] = 1; queue[tail++] = j }
            }
            if (y < h - 1) {
                val j = i + w
                if (!mask[j] && visit[j].toInt() == 0) { visit[j] = 1; queue[tail++] = j }
            }
        }
        for (i in 0 until n) out[i] = mask[i] || visit[i].toInt() == 0
    }

    /**
     * **剔除"真实背景缝隙"型内部洞** —— 从填充域里摘掉，让背景层保留原始像素。
     *
     * 判据：`洞内平均深度 < 洞周围 bodyMask 的平均深度 − [gap]`。
     * 深度图"越亮越近"，所以差得大 = 洞里是远景 = 真实背景，不是主体的洞。
     *
     * 这里必须按**连通洞**判，不能逐像素判：一个洞内部深度本来就有起伏，
     * 逐像素会把同一个洞切成"填一半留一半"，反而造出人工边界。
     *
     * 保护：周围 body 像素少于 32 个时不判定（孤洞/贴边洞没法比，保持原样）。
     *
     * @param visit 临时标记（内容会被覆盖）
     * @param queue 临时队列
     * @return 被摘掉的像素数（0 = 没有洞满足条件）
     */
    private fun dropFarHoles(
        fillRegion: BooleanArray,
        bodyMask: BooleanArray,
        depth: FloatArray,
        w: Int,
        h: Int,
        gap: Float,
        visit: ByteArray,
        queue: IntArray,
    ): Int {
        val n = w * h
        java.util.Arrays.fill(visit, 0)
        var droppedPx = 0
        for (seed in 0 until n) {
            if (bodyMask[seed] || !fillRegion[seed] || visit[seed].toInt() != 0) continue
            // ── BFS 收集这个连通洞 ──
            var tail = 0
            queue[tail++] = seed
            visit[seed] = 1
            var head = 0
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) {
                    val j = p - 1
                    if (!bodyMask[j] && fillRegion[j] && visit[j].toInt() == 0) { visit[j] = 1; queue[tail++] = j }
                }
                if (x < w - 1) {
                    val j = p + 1
                    if (!bodyMask[j] && fillRegion[j] && visit[j].toInt() == 0) { visit[j] = 1; queue[tail++] = j }
                }
                if (y > 0) {
                    val j = p - w
                    if (!bodyMask[j] && fillRegion[j] && visit[j].toInt() == 0) { visit[j] = 1; queue[tail++] = j }
                }
                if (y < h - 1) {
                    val j = p + w
                    if (!bodyMask[j] && fillRegion[j] && visit[j].toInt() == 0) { visit[j] = 1; queue[tail++] = j }
                }
            }
            // ── 洞内平均深度 ──
            var sumIn = 0f
            for (j in 0 until tail) sumIn += depth[queue[j]]
            val meanIn = sumIn / tail
            // ── 周围主体平均深度：bbox 外扩 6px 窗口内的所有 bodyMask 像素 ──
            var sumOut = 0f
            var cntOut = 0
            val y0 = max(0, minY - 6)
            val y1 = min(h - 1, maxY + 6)
            val x0 = max(0, minX - 6)
            val x1 = min(w - 1, maxX + 6)
            for (yy in y0..y1) {
                val row = yy * w
                for (xx in x0..x1) {
                    val q = row + xx
                    if (bodyMask[q]) { sumOut += depth[q]; cntOut++ }
                }
            }
            if (cntOut < 32) continue
            if (meanIn < sumOut / cntOut - gap) {
                for (j in 0 until tail) fillRegion[queue[j]] = false
                droppedPx += tail
            }
        }
        return droppedPx
    }

    /**
     * **两趟 8 邻域 chamfer 距离变换**：对每个像素求"最近的种子像素"。
     *
     * @param seed 种子掩码（true = 种子，距离 0）
     * @param dist 输出：到最近种子的近似欧氏距离（正交 1、对角 √2）
     * @param near 输出：最近种子的下标（没有种子可达时为 -1）
     */
    private fun chamferNearest(
        seed: BooleanArray,
        w: Int,
        h: Int,
        dist: FloatArray,
        near: IntArray,
    ) {
        val n = w * h
        for (i in 0 until n) {
            if (seed[i]) { dist[i] = 0f; near[i] = i } else { dist[i] = Float.MAX_VALUE; near[i] = -1 }
        }
        val d1 = 1f
        val d2 = 1.41421356f
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (dist[i] == 0f) continue
                var bd = dist[i]
                var bs = near[i]
                if (x > 0) {
                    val j = i - 1
                    if (dist[j] + d1 < bd) { bd = dist[j] + d1; bs = near[j] }
                }
                if (y > 0) {
                    val up = i - w
                    if (dist[up] + d1 < bd) { bd = dist[up] + d1; bs = near[up] }
                    if (x > 0) {
                        val j = up - 1
                        if (dist[j] + d2 < bd) { bd = dist[j] + d2; bs = near[j] }
                    }
                    if (x < w - 1) {
                        val j = up + 1
                        if (dist[j] + d2 < bd) { bd = dist[j] + d2; bs = near[j] }
                    }
                }
                dist[i] = bd
                near[i] = bs
            }
        }
        for (y in h - 1 downTo 0) {
            val row = y * w
            for (x in w - 1 downTo 0) {
                val i = row + x
                if (dist[i] == 0f) continue
                var bd = dist[i]
                var bs = near[i]
                if (x < w - 1) {
                    val j = i + 1
                    if (dist[j] + d1 < bd) { bd = dist[j] + d1; bs = near[j] }
                }
                if (y < h - 1) {
                    val dn = i + w
                    if (dist[dn] + d1 < bd) { bd = dist[dn] + d1; bs = near[dn] }
                    if (x < w - 1) {
                        val j = dn + 1
                        if (dist[j] + d2 < bd) { bd = dist[j] + d2; bs = near[j] }
                    }
                    if (x > 0) {
                        val j = dn - 1
                        if (dist[j] + d2 < bd) { bd = dist[j] + d2; bs = near[j] }
                    }
                }
                dist[i] = bd
                near[i] = bs
            }
        }
    }

    /**
     * 把标量场向掩码外"延拓"：轮廓外 [radius] 像素内，每个像素取最近主体像素的值。
     * （主体场的位移场要用它 —— 让轮廓两侧连续，见 FG_EXTEND_PX）
     */
    private fun extendField(
        k: FloatArray,
        mask: BooleanArray,
        w: Int,
        h: Int,
        radius: Int,
        dist: FloatArray,
        src: IntArray,
    ) {
        val n = w * h
        chamferNearest(mask, w, h, dist, src)
        val r = radius.toFloat()
        for (i in 0 until n) {
            if (!mask[i] && dist[i] <= r && src[i] >= 0) k[i] = k[src[i]]
        }
    }

    /**
     * **最近邻填充**：把 [hole] 内的值填成"最近的非洞像素"的值。
     *
     * 用途：深度的"背景化"（主体区要抹掉，否则背景场的位移场里留着主体轮廓）。
     *
     * 为什么不用 push-pull：它是加权平均，洞太大（主体占一半画面）时洞中心趋向整幅平均，
     * 而不是边界延伸 ⇒ 补全区留下一个渐变的主体形状。深度是分段平滑的，
     * "最近的那块背景的深度"比"所有背景的平均"更符合物理。
     *
     * 副作用：洞很大时，两侧不同背景的深度会在中线相遇，形成 Voronoi 接缝。
     * 接缝会被后续的 `limitSlope` 摊平（实测可接受）。
     */
    private fun nearestFill(
        src: FloatArray,
        hole: BooleanArray,
        w: Int,
        h: Int,
        out: FloatArray,
        dist: FloatArray,
        near: IntArray,
        seedTmp: BooleanArray,
    ): FloatArray {
        val n = w * h
        System.arraycopy(src, 0, out, 0, n)
        for (i in 0 until n) seedTmp[i] = !hole[i]
        chamferNearest(seedTmp, w, h, dist, near)
        for (i in 0 until n) {
            if (hole[i] && near[i] >= 0) out[i] = src[near[i]]
        }
        return out
    }

    /**
     * **对称 Gauss-Seidel 松弛**：在洞内解 `∇²d = 0`（每次迭代 = 取 4 邻域平均）。
     *
     * 前向 + 后向各扫一遍（不是纯前向），避免单向扫描在对角方向留下偏置。
     * 洞外的像素**不动**——它们就是 Dirichlet 边界。
     */
    private fun relax(d: FloatArray, m: BooleanArray, w: Int, h: Int, iters: Int) {
        repeat(iters) {
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    if (!m[i]) continue
                    var s = 0f
                    var c = 0
                    if (x > 0) { s += d[i - 1]; c++ }
                    if (x < w - 1) { s += d[i + 1]; c++ }
                    if (y > 0) { s += d[i - w]; c++ }
                    if (y < h - 1) { s += d[i + w]; c++ }
                    if (c > 0) d[i] = s / c
                }
            }
            for (y in h - 1 downTo 0) {
                val row = y * w
                for (x in w - 1 downTo 0) {
                    val i = row + x
                    if (!m[i]) continue
                    var s = 0f
                    var c = 0
                    if (x > 0) { s += d[i - 1]; c++ }
                    if (x < w - 1) { s += d[i + 1]; c++ }
                    if (y > 0) { s += d[i - w]; c++ }
                    if (y < h - 1) { s += d[i + w]; c++ }
                    if (c > 0) d[i] = s / c
                }
            }
        }
    }

    /**
     * **调和插值（Laplace）+ 多尺度（cascadic multigrid）** —— 背景化的最终方案。
     *
     * 洞内解 `∇²d = 0`、边界固定（Dirichlet），数学上就是「严格贴合边界、且最光滑」的延拓：
     * - 边界连续 ⇒ 背景场里**看不出主体轮廓**（push-pull 的毛病）；
     * - 洞内光滑、无平台 ⇒ **没有多边形碎块**（最近邻的毛病）。
     *
     * 为什么必须多尺度：纯 Jacobi/Gauss-Seidel 每次只把边界信息往里推 1 像素，
     * 洞宽 500px 要 250+ 次迭代（≈4.7 亿次操作，2~4 秒）。cascadic 的做法是
     * **从最粗层开始松弛**（粗层洞小、几次就收敛），再逐级上采样、细层只做少量精修
     * ⇒ 总代价 ~O(N)，实测规模下约 200~300ms。
     *
     * @param hint 初值（`nearestFill` 的结果：边界精确）。**会被原地改写**为最终结果。
     * @param hole true = 洞（要解），false = 边界（不动）
     */
    private fun harmonicFill(hint: FloatArray, hole: BooleanArray, w: Int, h: Int) {
        val lvW = ArrayList<Int>()
        val lvH = ArrayList<Int>()
        val lvD = ArrayList<FloatArray>()
        val lvM = ArrayList<BooleanArray>()
        lvW.add(w); lvH.add(h); lvD.add(hint); lvM.add(hole)

        // ── 建金字塔：粗层的洞 = 2×2 全洞；只要有一个非洞像素就用它们的平均 ──
        while (true) {
            val pw = lvW[lvW.size - 1]
            val ph = lvH[lvH.size - 1]
            if (pw <= 16 || ph <= 16) break
            val nw = (pw + 1) / 2
            val nh = (ph + 1) / 2
            val pd = lvD[lvD.size - 1]
            val pm = lvM[lvM.size - 1]
            val nd = FloatArray(nw * nh)
            val nm = BooleanArray(nw * nh)
            var anyFree = false
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    var sum = 0f
                    var cnt = 0
                    for (dy in 0..1) {
                        val sy = y * 2 + dy
                        if (sy >= ph) continue
                        for (dx in 0..1) {
                            val sx = x * 2 + dx
                            if (sx >= pw) continue
                            val j = sy * pw + sx
                            if (!pm[j]) { sum += pd[j]; cnt++ }
                        }
                    }
                    val i = y * nw + x
                    if (cnt > 0) {
                        nd[i] = sum / cnt
                        nm[i] = false
                        anyFree = true
                    } else {
                        nm[i] = true
                    }
                }
            }
            if (!anyFree) break       // 整层全洞 ⇒ 再粗也没有边界信息，停
            lvW.add(nw); lvH.add(nh); lvD.add(nd); lvM.add(nm)
        }

        // ── 从粗到细：上采样 → 精修 ──
        for (lv in lvD.size - 1 downTo 0) {
            val fw = lvW[lv]
            val fh = lvH[lv]
            val fd = lvD[lv]
            val fm = lvM[lv]
            if (lv < lvD.size - 1) {
                val cd = lvD[lv + 1]
                val cw = lvW[lv + 1]
                val ch = lvH[lv + 1]
                for (y in 0 until fh) {
                    val gy = ((y + 0.5f) * ch / fh - 0.5f).coerceIn(0f, (ch - 1).toFloat())
                    val y0 = gy.toInt().coerceIn(0, ch - 1)
                    val y1 = (y0 + 1).coerceAtMost(ch - 1)
                    val fy = gy - y0
                    val row = y * fw
                    for (x in 0 until fw) {
                        val i = row + x
                        if (!fm[i]) continue
                        val gx = ((x + 0.5f) * cw / fw - 0.5f).coerceIn(0f, (cw - 1).toFloat())
                        val x0 = gx.toInt().coerceIn(0, cw - 1)
                        val x1 = (x0 + 1).coerceAtMost(cw - 1)
                        val fx = gx - x0
                        val v00 = cd[y0 * cw + x0]
                        val v01 = cd[y0 * cw + x1]
                        val v10 = cd[y1 * cw + x0]
                        val v11 = cd[y1 * cw + x1]
                        fd[i] = (v00 * (1f - fx) + v01 * fx) * (1f - fy) +
                            (v10 * (1f - fx) + v11 * fx) * fy
                    }
                }
            }
            // 最细层多跑几趟（边界细节），其余层只做低频修正。
            // 次数不能再加：每次迭代是 2 趟全层扫描，最细层每加 1 次就多 3.7M 像素访问。
            relax(fd, fm, fw, fh, if (lv == 0) 12 else 8)
        }
    }

    /** **中值 3×3**：去掉深度图上的孤立噪点（否则会被 min-plus 限幅扩散成菱形暗斑）。 */
    private fun median3(src: FloatArray, dst: FloatArray, w: Int, h: Int) {
        val win = FloatArray(9)
        for (y in 0 until h) {
            val y0 = max(0, y - 1)
            val y1 = min(h - 1, y + 1)
            val row = y * w
            for (x in 0 until w) {
                val x0 = max(0, x - 1)
                val x1 = min(w - 1, x + 1)
                var c = 0
                for (yy in y0..y1) {
                    val r = yy * w
                    for (xx in x0..x1) win[c++] = src[r + xx]
                }
                var i = 1
                while (i < c) {
                    val v = win[i]
                    var j = i - 1
                    while (j >= 0 && win[j] > v) { win[j + 1] = win[j]; j-- }
                    win[j + 1] = v
                    i++
                }
                dst[row + x] = win[c / 2]
            }
        }
    }


    /**
     * **灰度闭运算** = 先膨胀后腐蚀，半径 [r]，原地写回 [a]。
     *
     * 用途：填掉孤立的暗坑（比周围暗的小区域），**亮峰完全不动** ⇒ k 的 range 不变。
     * 与已删除的 `despeckle` 的本质区别：那个是「检测 + 替换成局部中值」，判据在
     * 线性斜坡上恒为假（`(max+min)/2 ≈ k`）⇒ 实测对 L1 锥面修复率只有 6.2%；
     * 闭运算是纯形态学，没有判据，只受半径限制。
     *
     * @param out 膨胀结果的落脚处，必须与 [a]、[rowTmp] 互不相同
     * @param rowTmp 水平 pass 的行缓冲，可复用任意 FloatArray
     */
    private fun greyClosing(
        a: FloatArray,
        w: Int,
        h: Int,
        r: Int,
        out: FloatArray,
        rowTmp: FloatArray,
    ) {
        grayDilate(a, w, h, r, out, rowTmp)
        grayErode(out, w, h, r, a, rowTmp)
    }


    /**
     * **到 `solid` 的 L1 距离**（solid 内 > 0，外部 0）。前向 + 后向光栅，O(N)。
     *
     * 只用来定「边缘环带」的权重，不需要欧氏精度 —— 4 邻域曼哈顿距离足够，
     * 而且和 [minSweep] / [nearestFill] 同一套传播方式，行为可预期。
     */
    private fun distToSolid(solid: BooleanArray, dst: FloatArray, w: Int, h: Int) {
        val inf = 1e9f
        val n = w * h
        for (i in 0 until n) dst[i] = if (solid[i]) inf else 0f
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                var v = dst[i]
                if (x > 0) { val t = dst[i - 1] + 1f; if (t < v) v = t }
                if (y > 0) { val t = dst[i - w] + 1f; if (t < v) v = t }
                dst[i] = v
            }
        }
        for (y in h - 1 downTo 0) {
            val row = y * w
            for (x in w - 1 downTo 0) {
                val i = row + x
                var v = dst[i]
                if (x < w - 1) { val t = dst[i + 1] + 1f; if (t < v) v = t }
                if (y < h - 1) { val t = dst[i + w] + 1f; if (t < v) v = t }
                dst[i] = v
            }
        }
    }

    /**
     * **3 趟 box blur 近似高斯**（r=20 ⇒ σ≈10.0）。`src` 不被修改，结果落在 `dst`。
     *
     * @param tmp 水平中间缓冲，必须与 `src` / `dst` 都不同。
     */
    private fun boxBlur3(
        src: FloatArray,
        dst: FloatArray,
        w: Int,
        h: Int,
        r: Int,
        tmp: FloatArray,
    ) {
        boxBlurH(src, tmp, w, h, r)
        boxBlurV(tmp, dst, w, h, r)
        boxBlurH(dst, tmp, w, h, r)
        boxBlurV(tmp, dst, w, h, r)
        boxBlurH(dst, tmp, w, h, r)
        boxBlurV(tmp, dst, w, h, r)
    }

    /** 水平滑动窗口均值（边缘 clamp），O(N)，与半径无关。 */
    private fun boxBlurH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (j in -r..r) sum += src[row + j.coerceIn(0, w - 1)]
            dst[row] = sum * norm
            for (x in 1 until w) {
                sum += src[row + (x + r).coerceAtMost(w - 1)]
                sum -= src[row + (x - r - 1).coerceAtLeast(0)]
                dst[row + x] = sum * norm
            }
        }
    }

    /** 垂直滑动窗口均值（边缘 clamp），O(N)。 */
    private fun boxBlurV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        for (x in 0 until w) {
            var sum = 0f
            for (j in -r..r) sum += src[j.coerceIn(0, h - 1) * w + x]
            dst[x] = sum * norm
            for (y in 1 until h) {
                sum += src[(y + r).coerceAtMost(h - 1) * w + x]
                sum -= src[(y - r - 1).coerceAtLeast(0) * w + x]
                dst[y * w + x] = sum * norm
            }
        }
    }

    /**
     * **Lipschitz 约束投影**：强制任意 4 连通相邻像素的 |Δk| ≤ g（横纵各自的上限）。
     *
     * 为什么需要：位移场一旦有跳变，`uv = uv₀ − s(uv₀)` 的雅可比 `1 − ∂s/∂u` 就会变负
     * ⇒ 内容被跳过 = 沿深度边界"切开"。限制成 Lipschitz 连续后跳变变成斜坡，
     * 雅可比恒为正 ⇒ 只剩拉伸，不会翻面。
     *
     * **一趟 min-plus 腐蚀就够**：`f(p) = min_q (k(q) + D(p,q))` 是 ≤ k 的
     * **最大 g-Lipschitz 下界**，它自己就满足 |Δf| ≤ g，不需要再做「下界」那一半。
     * （踩过：交替 min/max 会不收敛 —— 竖直梯度实测超上限近 2 倍。）
     * 单趟是前向 + 后向光栅，即 4 邻域加权曼哈顿距离变换，精确且 O(N)。
     */
    private fun limitSlope(
        a: FloatArray,
        w: Int,
        h: Int,
        gx: Float,
        gy: Float,
        mask: BooleanArray? = null,
    ) {
        minSweep(a, w, h, gx, gy, mask)
    }

    /**
     * 原地：`a[p] = min(a[p], min over 邻域(a[q] + g))`（前向 + 后向光栅）。
     *
     * @param mask 非 null 时**只在掩码内传播**：掩码外的像素既不参与也不被写入。
     *   主体场要用它 —— 否则轮廓外那圈（背景深度 ⇒ k 大 3 倍）会把主体的 k 拽上去，
     *   主体边缘被拉变形。
     */
    private fun minSweep(
        a: FloatArray,
        w: Int,
        h: Int,
        gx: Float,
        gy: Float,
        mask: BooleanArray? = null,
    ) {
        for (y in 0 until h) {
            val row = y * w
            val up = if (y > 0) row - w else -1
            for (x in 0 until w) {
                val i = row + x
                if (mask != null && !mask[i]) continue
                var v = a[i]
                if (x > 0 && (mask == null || mask[i - 1])) {
                    val t = a[i - 1] + gx
                    if (t < v) v = t
                }
                if (up >= 0 && (mask == null || mask[up + x])) {
                    val t = a[up + x] + gy
                    if (t < v) v = t
                }
                a[i] = v
            }
        }
        for (y in h - 1 downTo 0) {
            val row = y * w
            val dn = if (y < h - 1) row + w else -1
            for (x in w - 1 downTo 0) {
                val i = row + x
                if (mask != null && !mask[i]) continue
                var v = a[i]
                if (x < w - 1 && (mask == null || mask[i + 1])) {
                    val t = a[i + 1] + gx
                    if (t < v) v = t
                }
                if (dn >= 0 && (mask == null || mask[dn + x])) {
                    val t = a[dn + x] + gy
                    if (t < v) v = t
                }
                a[i] = v
            }
        }
    }

    /**
     * Otsu 阈值 + 分离度。
     *
     * @param toBin 只统计 `0..toBin` 的直方图（2026-10-31 加）：用来对「低段」再求一次 Otsu，
     *   光看主阈值以下的分布里还有没有第二个前后景交界（见 `SPLIT_RATIO`）。
     *   默认 255 = 全图，行为与原来一致。
     * @return [threshold] ∈ 0..1；[separation] = 最大类间方差 / 总方差 ∈ 0..1
     */
    private fun otsu(values: FloatArray, toBin: Int = 255): Otsu {
        val hist = IntArray(256)
        for (v in values) hist[(v * 255f).toInt().coerceIn(0, 255)]++
        val top = toBin.coerceIn(1, 255)
        var total = 0
        for (i in 0..top) total += hist[i]
        if (total == 0) return Otsu(0.5f, 0f, 0f)
        var sumAll = 0.0
        var sumSq = 0.0
        for (i in 0..top) {
            sumAll += i.toDouble() * hist[i]
            sumSq += i.toDouble() * i * hist[i]
        }
        val mean = sumAll / total
        val varAll = (sumSq / total - mean * mean).coerceAtLeast(1e-9)
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = top / 2
        for (i in 0..top) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i.toDouble() * hist[i]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = i
            }
        }
        val separation = (best / (total.toDouble() * total) / varAll).toFloat().coerceIn(0f, 1f)
        // **未除方差的绝对分离度**（2026-10-31）：`separation` 对双峰分布总是接近 1，
        // 拿它比较「低段 vs 全图」会永远成立（实测三张图 1.13 / 0.73 / 0.67，全过阈值）。
        // `rawSep = 类间方差 / total²` 保留了「这一刀本身有多强 + 参与像素有多少」，
        // 低段像素少时天然偏小 —— 正是「低段里那个二分值不值得切」要的信号。
        val rawSep = (best / (total.toDouble() * total)).toFloat()
        return Otsu(threshold / 255f, separation, rawSep)
    }

    private data class Otsu(val threshold: Float, val separation: Float, val rawSep: Float)

    /**
     * **灰度膨胀（max filter）**，可分离两遍扫描，半径 [r] 像素。
     *
     * 用于主体 alpha 外扩（2026-10-24 用户定案）：二值膨胀会把软过渡削成硬边，
     * 灰度膨胀取邻域最大值，**保留原来的灰度层级**，只把"有内容"的区域往外推。
     */
    private fun grayDilate(
        src: FloatArray,
        w: Int,
        h: Int,
        r: Int,
        out: FloatArray,
        tmp: FloatArray,
    ): FloatArray {
        if (r <= 0) {
            System.arraycopy(src, 0, out, 0, w * h)
            return out
        }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var m = 0f
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                for (k in x0..x1) {
                    val v = src[row + k]
                    if (v > m) m = v
                }
                tmp[row + x] = m
            }
        }
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                var m = 0f
                for (k in y0..y1) {
                    val v = tmp[k * w + x]
                    if (v > m) m = v
                }
                out[y * w + x] = m
            }
        }
        return out
    }

    /** 灰度**腐蚀**（min filter），签名与 [grayDilate] 一致。 */
    private fun grayErode(
        src: FloatArray,
        w: Int,
        h: Int,
        r: Int,
        out: FloatArray,
        tmp: FloatArray,
    ): FloatArray {
        if (r <= 0) {
            System.arraycopy(src, 0, out, 0, w * h)
            return out
        }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var m = 1f
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                for (k in x0..x1) {
                    val v = src[row + k]
                    if (v < m) m = v
                }
                tmp[row + x] = m
            }
        }
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                var m = 1f
                for (k in y0..y1) {
                    val v = tmp[k * w + x]
                    if (v < m) m = v
                }
                out[y * w + x] = m
            }
        }
        return out
    }

    /** 由 sum / sqSum / count 反算标准差。 */
    private fun stdOf(sum: Double, sqSum: Double, count: Int): Float {
        if (count <= 0) return 0f
        val m = sum / count
        return kotlin.math.sqrt((sqSum / count - m * m).coerceAtLeast(0.0)).toFloat()
    }

    /** 布尔掩码的**方形核腐蚀**（原地，可分离两遍扫描），半径 [r] 像素。 */
    private fun erodeBool(mask: BooleanArray, w: Int, h: Int, r: Int) {
        if (r <= 0) return
        val tmp = BooleanArray(mask.size)
        for (y in 0 until h) {
            val row = y * w
            var last = -1000000
            for (x in 0 until w) {
                if (!mask[row + x]) last = x
                tmp[row + x] = x - last > r
            }
            last = 1000000
            for (x in w - 1 downTo 0) {
                if (!mask[row + x]) last = x
                if (last - x <= r) tmp[row + x] = false
            }
        }
        for (x in 0 until w) {
            var last = -1000000
            for (y in 0 until h) {
                val i = y * w + x
                if (!tmp[i]) last = y
                mask[i] = y - last > r
            }
            last = 1000000
            for (y in h - 1 downTo 0) {
                val i = y * w + x
                if (!tmp[i]) last = y
                if (last - y <= r) mask[i] = false
            }
        }
    }

    /** alpha 盒式羽化（半径 [r]，两遍可分离）。 */
    private fun featherBox(alpha: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val win = 2 * r + 1
        val tmp = IntArray(w * h)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (x in -r..r) sum += alpha[row + x.coerceIn(0, w - 1)].toInt() and 0xFF
            for (x in 0 until w) {
                tmp[row + x] = sum / win
                sum += (alpha[row + (x + r + 1).coerceIn(0, w - 1)].toInt() and 0xFF)
                sum -= (alpha[row + (x - r).coerceIn(0, w - 1)].toInt() and 0xFF)
            }
        }
        val col = IntArray(h)
        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = (sum / win).toByte()
                sum += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sum -= tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    /** 把原图解码到指定尺寸（先 `inSampleSize` 粗降，再精确缩放，避免整张大图进内存）。 */
    private fun decodeToSize(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        // `inSampleSize` **只用来控内存**（2026-11-01）：`BitmapFactory` 的跳采样不做低通，
        // 本身就是混叠源。以前它按「缩到接近目标」算，等于把两倍以上的缩放都交给它 ⇒
        // 细线被隔点采样成断续小方块。现在它只保证解码后 ≤ MAX_DECODE_PX，
        // 剩下的缩放交给 `SpatialResample`（支撑随比例扩展，有抗混叠）。
        val sample = SpatialResample.decodeSample(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("原图解码失败")
        return if (raw.width == w && raw.height == h) raw else {
            // 双立方 + 抗混叠（PIL 口径，2026-11-01 用户指定）。实测 RMSE 1.27，
            // 双线性 2.78、原来的 createScaledBitmap 3.49。层图 RGB 是直接显示的，
            // 这一路对锐度**敏感**（与 DA 的输入不同：那边被 14px patch 化抹平了 84% 差异）。
            SpatialResample.bitmap(raw, w, h, cubic = true)
        }
    }
}
