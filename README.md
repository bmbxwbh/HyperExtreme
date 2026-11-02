<p align="center">
  <img src="app/src/main/res/drawable-nodpi/logo.png" alt="LOGO" width="128">
</p>

<h1 align="center"><strong>HyperExtreme</strong></h1>

<p align="center">Extreme for HyperOS</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-blue?style=flat-square&logo=android" alt="platform">
  <img src="https://img.shields.io/badge/minSdk-33-red?style=flat-square" alt="minSdk">
  <img src="https://img.shields.io/badge/targetSdk-37-green?style=flat-square" alt="targetSdk">
  <img src="https://img.shields.io/badge/License-AGPL--3.0-yellow?style=flat-square" alt="license">
</p>

## 兼容性

对于空间壁纸：LSPosed模式只在澎湃OS4上测试，也只会对澎湃OS4适配；系统壁纸服务（免Root）模式理论支持到安卓13，但对安卓17之外的安卓版本未测试。

对于其他功能：都需要Root与LSPosed，只会对澎湃OS4适配。

## 使用说明

对于需要LSPosed的功能，请先提前勾选并重启所有推荐作用域（初次安装及模块更新时），再使用对应功能。

本模块可能对修改较多的HyperOS ROM或修改过的推荐作用域应用不生效甚至导致其他问题。

本模块可能与其他修改相同作用域的LSPosed模块产生冲突，在使用模块出现问题时，请先排查模块冲突。

## 空间壁纸深度模型（Depth Anything 3）

空间壁纸的深度推理内置两档 **Depth Anything 3**（Apache-2.0），在页面「推理模型」下拉中切换：

| 模型 | 大小 | 速度（真机参考） | 适用 |
|---|---|---|---|
| DA3-Base（默认） | 123MB | 快档 @1024 实测峰值内存 5~7GB，**直算有闪退风险** | 配合下方「缓存包导入」使用 |
| DA3-Small | 29MB | 约 5 秒 | 手机直算，内存与旧版相当、稳定 |

单张生成预算 120 秒；推理分辨率档位 768 / 896 / 1024（数值越大越清晰越慢）。

### 缓存包导入（推荐，PC 计算深度）

DA3-Base 在手机上推理时原生内存峰值可达 5~7GB，会被 MIUI 低内存杀手直接杀掉（表现为生成到一半闪退）。**推荐在电脑上算好深度，打成缓存包导入手机**，彻底绕开内存尖峰；导入后手机只做秒级的层图分层。

1. 电脑上准备（Node.js ≥18，首次需装依赖）：

   ```bash
   cd tools && npm install
   ```

2. 导出缓存包（`shortSide` 取 768 / 896 / 1024）：

   ```bash
   node tools/export_spatial_cache.mjs 图片.jpg ../app/src/main/assets/models/da3_base_int8.onnx 1024 out/图片.zip
   ```

   包内含三件套：`source`（原图原始字节）、`depth.png`（越亮=越近的深度图）、`meta.json`（缓存元数据）；文件名即缓存键。

3. 推到手机：`adb push out/图片.zip /sdcard/Download/`

4. 手机端：空间壁纸页 →「导入缓存包」选这个 zip →「生成深度缓存」（深度已命中，秒级补算层图）→「立即替换」。

其他相关工具：`tools/check_da3_model.mjs` 可离线验证模型的输入输出形状、耗时与深度图方向。

## 开源代码许可

本项目采用AGPL-3.0许可证，有关开源代码许可请在模块的关于页查看。
