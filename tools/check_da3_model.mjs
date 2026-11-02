#!/usr/bin/env node
/**
 * DA3 深度模型离线验证（不依赖 Android 设备）。
 *
 * 用法：
 *   node tools/check_da3_model.mjs <model.onnx> <photo.jpg|png> [outDir] [shortSide]
 *
 * 做什么：
 *   1. 打印会话输入/输出的名字与形状（验证 `pixel_values` 单输入、
 *      `predicted_depth` [1,1,H,W]、`confidence` 是否存在）；
 *   2. 按应用同款预处理（短边 → 14 倍数、ImageNet 归一化）跑推理，打印耗时；
 *   3. min-max 归一化后输出两张灰度 PNG：`raw.png`（原向）与 `flipped.png`（翻转后），
 *      并打印上 30% / 下 30% 行的平均亮度。
 *
 * 方向判定（应用约定「越亮 = 越近」）：
 *   天空在上、主体在下的风景照 → 正确的一版应当**底部明显比顶部亮**；
 *   人像/近景照直接看图：脸和近处物体应当亮、背景应当暗。
 *   结论写回 `SpatialDepth.INVERT_DEPTH`。
 *
 * 依赖：`cd tools && npm install`（onnxruntime-node / jpeg-js / pngjs）。
 */
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, basename, join } from "node:path";
import { fileURLToPath } from "node:url";
import jpeg from "jpeg-js";
import { PNG } from "pngjs";
import ort from "onnxruntime-node";

const MEAN = [0.485, 0.456, 0.406];
const STD = [0.229, 0.224, 0.225];
const MULT = 14;

function decodeImage(path) {
  const buf = readFileSync(path);
  if (path.toLowerCase().endsWith(".png")) {
    const png = PNG.sync.read(buf);
    // pngjs: data 是 RGBA；转 ARGB 风格的 int 数组（与应用 Bitmap.getPixels 同构）
    const px = new Uint32Array(png.width * png.height);
    for (let i = 0; i < px.length; i++) {
      const o = i * 4;
      px[i] =
        ((png.data[o + 3] & 0xff) << 24) |
        ((png.data[o] & 0xff) << 16) |
        ((png.data[o + 1] & 0xff) << 8) |
        (png.data[o + 2] & 0xff);
    }
    return { width: png.width, height: png.height, px };
  }
  const raw = jpeg.decode(buf, { useTArray: true, formatAsRGBA: true });
  const px = new Uint32Array(raw.width * raw.height);
  for (let i = 0; i < px.length; i++) {
    const o = i * 4;
    px[i] =
      (0xff << 24) |
      ((raw.data[o] & 0xff) << 16) |
      ((raw.data[o + 1] & 0xff) << 8) |
      (raw.data[o + 2] & 0xff);
  }
  return { width: raw.width, height: raw.height, px };
}

/** 双线性 resize（方向验证够用；应用侧是 Catmull-Rom，DA 对锐度不敏感）。 */
function resizeBilinear(src, dw, dh) {
  const out = new Uint32Array(dw * dh);
  const sx = src.width / dw;
  const sy = src.height / dh;
  for (let y = 0; y < dh; y++) {
    const fy = (y + 0.5) * sy - 0.5;
    const y0 = Math.max(0, Math.min(src.height - 1, Math.floor(fy)));
    const y1 = Math.min(src.height - 1, y0 + 1);
    const wy = Math.max(0, Math.min(1, fy - y0));
    for (let x = 0; x < dw; x++) {
      const fx = (x + 0.5) * sx - 0.5;
      const x0 = Math.max(0, Math.min(src.width - 1, Math.floor(fx)));
      const x1 = Math.min(src.width - 1, x0 + 1);
      const wx = Math.max(0, Math.min(1, fx - x0));
      const c00 = src.px[y0 * src.width + x0];
      const c01 = src.px[y0 * src.width + x1];
      const c10 = src.px[y1 * src.width + x0];
      const c11 = src.px[y1 * src.width + x1];
      const r =
        ((c00 >>> 24) & 0xff) * (1 - wx) * (1 - wy) +
        ((c01 >>> 24) & 0xff) * wx * (1 - wy) +
        ((c10 >>> 24) & 0xff) * (1 - wx) * wy +
        ((c11 >>> 24) & 0xff) * wx * wy;
      const g =
        ((c00 >>> 16) & 0xff) * (1 - wx) * (1 - wy) +
        ((c01 >>> 16) & 0xff) * wx * (1 - wy) +
        ((c10 >>> 16) & 0xff) * (1 - wx) * wy +
        ((c11 >>> 16) & 0xff) * wx * wy;
      const b =
        ((c00 >>> 8) & 0xff) * (1 - wx) * (1 - wy) +
        ((c01 >>> 8) & 0xff) * wx * (1 - wy) +
        ((c10 >>> 8) & 0xff) * (1 - wx) * wy +
        ((c11 >>> 8) & 0xff) * wx * wy;
      out[y * dw + x] =
        (0xff << 24) | (Math.round(r) << 16) | (Math.round(g) << 8) | Math.round(b);
    }
  }
  return { width: dw, height: dh, px: out };
}

function toNchw(img) {
  const n = img.width * img.height;
  const chw = new Float32Array(3 * n);
  for (let i = 0; i < n; i++) {
    const c = img.px[i];
    chw[i] = (((c >>> 16) & 0xff) / 255 - MEAN[0]) / STD[0];
    chw[n + i] = (((c >>> 8) & 0xff) / 255 - MEAN[1]) / STD[1];
    chw[2 * n + i] = ((c & 0xff) / 255 - MEAN[2]) / STD[2];
  }
  return chw;
}

function writeGrayPng(path, w, h, values /* FloatArray 0..255 */) {
  const png = new PNG({ width: w, height: h });
  for (let i = 0; i < w * h; i++) {
    const g = Math.max(0, Math.min(255, Math.round(values[i])));
    png.data[i * 4] = g;
    png.data[i * 4 + 1] = g;
    png.data[i * 4 + 2] = g;
    png.data[i * 4 + 3] = 255;
  }
  writeFileSync(path, PNG.sync.write(png));
}

/** 上 30% / 下 30% 行的平均亮度。 */
function bandLuminance(w, h, values) {
  const topRows = Math.max(1, Math.floor(h * 0.3));
  const botRows = Math.max(1, Math.floor(h * 0.3));
  let top = 0;
  let bot = 0;
  for (let y = 0; y < topRows; y++)
    for (let x = 0; x < w; x++) top += values[y * w + x];
  for (let y = h - botRows; y < h; y++)
    for (let x = 0; x < w; x++) bot += values[y * w + x];
  return {
    top: top / (topRows * w),
    bottom: bot / (botRows * w),
  };
}

async function main() {
  const [modelPath, photoPath, outDirArg, shortSideArg] = process.argv.slice(2);
  if (!modelPath || !photoPath) {
    console.error(
      "用法: node tools/check_da3_model.mjs <model.onnx> <photo.jpg|png> [outDir] [shortSide]",
    );
    process.exit(2);
  }
  const outDir = outDirArg ?? "tools/out";
  const shortSide = Number(shortSideArg ?? 768);
  mkdirSync(outDir, { recursive: true });

  console.log(`模型: ${modelPath}`);
  console.log(`照片: ${photoPath}`);

  // --- 预处理（与 SpatialDepth.generate 同构） ---
  const src = decodeImage(photoPath);
  // 与应用一致：短边缩放后 roundToInt 再向下对齐 14（`roundToInt() / MULT * MULT`）
  const scale = shortSide / Math.min(src.width, src.height);
  const w = Math.max(MULT, Math.floor(Math.round(src.width * scale) / MULT) * MULT);
  const h = Math.max(MULT, Math.floor(Math.round(src.height * scale) / MULT) * MULT);
  const resized = resizeBilinear(src, w, h);
  const chw = toNchw(resized);
  console.log(`输入尺寸: ${src.width}×${src.height} → ${w}×${h}（短边 ${shortSide}，14 倍数）`);

  // --- 会话 ---
  const t0 = performance.now();
  const session = await ort.InferenceSession.create(modelPath, {
    executionProviders: ["cpu"],
  });
  const tInit = performance.now() - t0;
  console.log(`会话创建耗时: ${tInit.toFixed(0)} ms`);
  console.log(`输入名: ${JSON.stringify(session.inputNames)}`);
  console.log(`输出名: ${JSON.stringify(session.outputNames)}`);
  if (session.inputNames.length !== 1) {
    console.warn(`⚠️ 期望单输入，实际 ${session.inputNames.length} 个`);
  }

  // DA3 是多视图导出：`pixel_values` = [N, S, 3, H, W]，单图 S=1
  const input = new ort.Tensor("float32", chw, [1, 1, 3, h, w]);
  const feeds = { [session.inputNames[0]]: input };
  const t1 = performance.now();
  const results = await session.run(feeds);
  const tInfer = performance.now() - t1;
  console.log(`推理耗时: ${tInfer.toFixed(0)} ms`);

  for (const name of session.outputNames) {
    const t = results[name];
    console.log(`输出 ${name}: shape=[${t.dims.join(",")}] type=${t.type}`);
  }
  const depthTensor =
    results["predicted_depth"] ?? results[session.outputNames[0]];
  if (!depthTensor) throw new Error("没有可用的深度输出");
  const dims = depthTensor.dims;
  // rank-4 [1,1,H,W] 或 rank-3 [1,H,W]
  const oh = dims.length === 4 ? dims[2] : dims[1];
  const ow = dims.length === 4 ? dims[3] : dims[2];
  if (oh !== h || ow !== w) {
    console.warn(`⚠️ 输出尺寸 ${ow}×${oh} 与输入 ${w}×${h} 不一致`);
  }
  const data = depthTensor.data;
  const n = ow * oh;

  // --- min-max 归一化（原向） ---
  let lo = Infinity;
  let hi = -Infinity;
  for (let i = 0; i < n; i++) {
    const v = data[i];
    if (v < lo) lo = v;
    if (v > hi) hi = v;
  }
  const range = hi - lo > 1e-6 ? hi - lo : 1;
  const raw = new Float32Array(n);
  const flipped = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const norm = ((data[i] - lo) / range) * 255;
    raw[i] = norm;
    flipped[i] = 255 - norm;
  }

  const base = basename(photoPath, ".jpg").replace(/\.jpeg$/i, "");
  const rawPath = join(outDir, `${base}_${basename(modelPath, ".onnx")}_raw.png`);
  const flipPath = join(outDir, `${base}_${basename(modelPath, ".onnx")}_flipped.png`);
  writeGrayPng(rawPath, ow, oh, raw);
  writeGrayPng(flipPath, ow, oh, flipped);

  const rawBands = bandLuminance(ow, oh, raw);
  const flipBands = bandLuminance(ow, oh, flipped);
  console.log(`原向   上30%平均亮度=${rawBands.top.toFixed(1)}  下30%=${rawBands.bottom.toFixed(1)}`);
  console.log(`翻转后 上30%平均亮度=${flipBands.top.toFixed(1)}  下30%=${flipBands.bottom.toFixed(1)}`);
  console.log(`深度范围: [${lo.toFixed(4)}, ${hi.toFixed(4)}]`);
  console.log(`PNG: ${rawPath}`);
  console.log(`PNG: ${flipPath}`);
  console.log(
    "判定：风景照（天在上）看哪张底部更亮 = 符合「越亮=越近」；" +
      "再目检 PNG（脸/近处亮、天空暗）。结论写回 SpatialDepth.INVERT_DEPTH。",
  );

  await session.release?.();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
