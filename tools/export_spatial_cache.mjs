#!/usr/bin/env node
/**
 * 空间壁纸缓存包导出（PC 侧计算，绕开手机端 DA3-Base 推理的内存尖峰）。
 *
 * 用法：
 *   node tools/export_spatial_cache.mjs <photo.jpg|png> <model.onnx> <shortSide> [outZip]
 *
 * 例：
 *   node tools/export_spatial_cache.mjs D:/pic/a.jpg \
 *     ../app/src/main/assets/models/da3_base_int8.onnx 1024 out/a.zip
 *
 * 按模型族自动适配（文件名判别）：
 * * `da3_*` —— 输出是「距离」（越大越远）：**翻转 + 双峰整形**（与
 *   SpatialDepth.INVERT_DEPTH / shapeBimodal 同参数）。整形用 Otsu 找谷；
 *   注意：某些极端构图（如仰拍、猫头与天空值域纠缠）下 DA3 的距离输出
 *   **无阈值可分**，这类图建议直接用 dav2 导出。
 * * `dav2_*` —— 输出是「视差」（越大越近）：**不翻转、不整形**，与旧版设备行为
 *   完全一致（管线当年就是按它的双峰分布调的）。
 *
 * 产物（zip 内三个文件，平铺、无子目录）：
 *   source     —— 图片原始字节（与 App 选图时拷入的 source 完全一致）
 *   depth.png  —— 灰度深度图，**越亮 = 越近**
 *   meta.json  —— 与 App 相同 schema（model 字段以模型文件名开头，供缓存校验）
 *
 * 缓存键 = sha256(source).hex 前 16 位（与 App 的 sha16 一致）；zip 名即 <key>.zip。
 * 导入：把 zip 推到手机（adb push），App 空间壁纸页 →「导入缓存包」。
 * 层图（layers）不在包里 —— 那是 Kotlin/设备侧逻辑，导入后在手机上秒级补算。
 *
 * 预处理与 App SpatialDepth.generate 同构：短边 → 对齐 14 → ImageNet 归一化（gamma 空间）。
 * 缩放用双线性（App 是 Catmull-Rom；DA 对锐度不敏感，RMSE 1~3/255，见 SpatialResample 注释）。
 */
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, basename, resolve } from "node:path";
import { createHash } from "node:crypto";
import jpeg from "jpeg-js";
import { PNG } from "pngjs";
import AdmZip from "adm-zip";
import ort from "onnxruntime-node";

const MEAN = [0.485, 0.456, 0.406];
const STD = [0.229, 0.224, 0.225];
const MULT = 14;
// 翻转与整形按模型族在 main() 里决定（da3=距离制需翻转+整形；dav2=视差制原样）。

function decodeImage(path) {
  const buf = readFileSync(path);
  if (path.toLowerCase().endsWith(".png")) {
    const png = PNG.sync.read(buf);
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
      let r = 0;
      let g = 0;
      let b = 0;
      for (const [c, k] of [
        [c00, (1 - wx) * (1 - wy)],
        [c01, wx * (1 - wy)],
        [c10, (1 - wx) * wy],
        [c11, wx * wy],
      ]) {
        r += ((c >>> 24) & 0xff) * k;
        g += ((c >>> 16) & 0xff) * k;
        b += ((c >>> 8) & 0xff) * k;
      }
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

async function main() {
  const [photoArg, modelArg, shortSideArg, outZipArg] = process.argv.slice(2);
  if (!photoArg || !modelArg || !shortSideArg) {
    console.error(
      "用法: node tools/export_spatial_cache.mjs <photo.jpg|png> <model.onnx> <shortSide> [outZip]",
    );
    process.exit(2);
  }
  const photoPath = resolve(photoArg);
  const modelPath = resolve(modelArg);
  const modelName = basename(modelPath);
  const shortSide = Number(shortSideArg);
  if (![768, 896, 1024].includes(shortSide)) {
    console.error(`shortSide 必须是 App 合法档位之一（768/896/1024），收到: ${shortSide}`);
    process.exit(2);
  }
  // 模型族：dav2=视差制（近亮，原样输出）；da3=距离制（需翻转 + 双峰整形）
  const isDa2 = modelName.startsWith("dav2");
  const invert = !isDa2;
  console.log(`模型族: ${isDa2 ? "dav2（视差：不翻转、不整形）" : "da3（距离：翻转+整形）"}`);

  const sourceBytes = readFileSync(photoPath);

  // --- 预处理（与 SpatialDepth.generate 同构） ---
  const src = decodeImage(photoPath);
  const scale = shortSide / Math.min(src.width, src.height);
  const w = Math.max(MULT, Math.floor(Math.round(src.width * scale) / MULT) * MULT);
  const h = Math.max(MULT, Math.floor(Math.round(src.height * scale) / MULT) * MULT);
  const chw = toNchw(resizeBilinear(src, w, h));
  console.log(`图片 ${src.width}×${src.height} → 推理 ${w}×${h}（短边 ${shortSide}）`);
  console.log(`模型 ${modelName}`);

  // --- 推理 ---
  const session = await ort.InferenceSession.create(modelPath, {
    executionProviders: ["cpu"],
  });
  // dav2 是经典 NCHW [1,3,H,W]；da3 是多视图导出 [1,S,3,H,W]（单图 S=1）
  const input = new ort.Tensor("float32", chw, isDa2 ? [1, 3, h, w] : [1, 1, 3, h, w]);
  const t0 = performance.now();
  const results = await session.run({ [session.inputNames[0]]: input });
  const costMs = Math.round(performance.now() - t0);
  console.log(`推理耗时 ${costMs} ms`);

  const tensor = results["predicted_depth"] ?? results[session.outputNames[0]];
  if (!tensor) throw new Error("没有 predicted_depth 输出");
  const dims = tensor.dims;
  const oh = dims.length === 4 ? dims[2] : dims[1];
  const ow = dims.length === 4 ? dims[3] : dims[2];
  if (ow !== w || oh !== h) throw new Error(`输出尺寸 ${ow}×${oh} 与输入 ${w}×${h} 不符`);

  // --- min-max 归一化 + 方向翻转（越亮 = 越近）---
  const data = tensor.data;
  let lo = Infinity;
  let hi = -Infinity;
  for (let i = 0; i < data.length; i++) {
    const v = data[i];
    if (v < lo) lo = v;
    if (v > hi) hi = v;
  }
  const range = hi - lo > 1e-6 ? hi - lo : 1;
  const n = ow * oh;
  const gray = new Uint8Array(n);
  for (let i = 0; i < n; i++) {
    const norm = (data[i] - lo) / range;
    gray[i] = Math.round((invert ? 1 - norm : norm) * 255);
  }

  // --- 双峰整形（与 SpatialDepth.shapeBimodal 同参数；仅 DA3，dav2 跳过）---
  // DA3 输出「距离」，远景是拉开的渐变：直接分层会背景散（σ 0.098 vs DA2 0.044）、
  // 主体糊（轮廓梯度 1.5 vs 3.6）、主体内按米级差异乱动（面部抖动的根源）。
  // 分段单调曲线重塑成 DA2 风格双峰。**切点用 Otsu（类间方差最大化）找「亮主体 vs 其余」
  // 的谷** —— 固定百分位会在背景自身双峰（暗塔+中灰天空）内部下刀，把天空推进主体亮台
  // （2026-11-02 实测踩坑）。实测（猫/风景）：背 mean 0.05/σ 0.04、对比 0.68/0.72
  // （超 DA2 的 0.60）、天空带压暗 0.07、近景带 0.79、整体 σ ≈ DA2 定标点。
  // ⚠️ 例外：若某图的主体与天空**值域纠缠**（如仰拍猫头，均在灰阶 100~150 混作一团），
  // 任何阈值都分不开 —— 那类图用 dav2 模型导出（视差天然分离）。
  if (!isDa2) {
    const SHAPE_RAMP = 0.05; // 过渡带半宽（Otsu 谷两侧各 0.05）
    const SHAPE_BG = 0.10, SHAPE_MID = 0.60, SHAPE_GAMMA_BG = 3, SHAPE_GAMMA_SUBJ = 1.5;
    const hist = new Int32Array(256);
    for (let i = 0; i < n; i++) hist[gray[i]]++;
    // Otsu：最大化类间方差 ⇒ 亮主体 vs 其余的最优切点（0..255）
    let sum = 0;
    for (let v = 0; v < 256; v++) sum += v * hist[v];
    let sumB = 0, wB = 0, best = -1, tOtsu = 128;
    for (let v = 0; v < 256; v++) {
      wB += hist[v];
      if (!wB) continue;
      const wF = n - wB;
      if (!wF) break;
      sumB += v * hist[v];
      const mB = sumB / wB, mF = (sum - sumB) / wF;
      const score = wB * wF * (mB - mF) * (mB - mF);
      if (score > best) { best = score; tOtsu = v; }
    }
    const a = Math.max(8, tOtsu - Math.round(SHAPE_RAMP * 255));
    const b = Math.min(247, tOtsu + Math.round(SHAPE_RAMP * 255));
    if (b > a) {
      for (let i = 0; i < n; i++) {
        const v = gray[i];
        let s;
        if (v <= a) s = Math.pow(v / a, SHAPE_GAMMA_BG) * SHAPE_BG;
        else if (v <= b) s = SHAPE_BG + (SHAPE_MID - SHAPE_BG) * ((v - a) / (b - a));
        else s = SHAPE_MID + (1 - SHAPE_MID) * Math.pow((v - b) / (255 - b), SHAPE_GAMMA_SUBJ);
        gray[i] = Math.max(0, Math.min(255, Math.round(s * 255)));
      }
    }
    console.log(`双峰整形: Otsu T=${tOtsu} 过渡带 ${a}~${b}`);
  }

  const png = new PNG({ width: ow, height: oh });
  for (let i = 0; i < n; i++) {
    png.data[i * 4] = gray[i];
    png.data[i * 4 + 1] = gray[i];
    png.data[i * 4 + 2] = gray[i];
    png.data[i * 4 + 3] = 255;
  }

  // --- 缓存键：sha256(source) 前 16 hex（与 App sha16 一致） ---
  const key = createHash("sha256").update(sourceBytes).digest("hex").slice(0, 16);

  const meta = {
    srcW: src.width,
    srcH: src.height,
    shortSide,
    inferW: w,
    inferH: h,
    outW: ow,
    outH: oh,
    costMs,
    depthMin: lo,
    depthMax: hi,
    model: `${modelName} (int8, PC export, onnxruntime-node)`,
    createdAt: Date.now(),
  };

  const outZip = resolve(outZipArg ?? `out/${key}.zip`);
  mkdirSync(dirname(outZip), { recursive: true });
  const zip = new AdmZip();
  zip.addFile("source", sourceBytes);
  zip.addFile("depth.png", PNG.sync.write(png));
  zip.addFile("meta.json", Buffer.from(JSON.stringify(meta), "utf8"));
  zip.writeZip(outZip);

  await session.release?.();
  console.log(`缓存键 ${key}`);
  console.log(`导出 ${outZip}`);
  console.log(`下一步：`);
  console.log(`  adb push "${outZip}" /sdcard/Download/`);
  console.log(`  手机 App → 空间壁纸 →「导入缓存包」选这个 zip`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
