// `java` 在这个脚本里被 Gradle 的 java 插件扩展占用，用别名 import 绕开，
// 否则 `java.util.Properties` 会被解析成 `JavaPluginExtension.util`。
import java.util.Properties as JavaProperties
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// 发布签名：机密值不进版本库，改从仓库外的 keystore.properties 读取。
// 没有该文件时 release 退回 debug 签名 —— 保证 clone 下来就能构建（见 README）。
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    JavaProperties().apply { f.inputStream().use(::load) }
}

android {
    namespace = "com.frederic22.hyperextreme"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "28.2.13676358"

    // 桌面桥（home_bridge）：native 子工程（libxposed API 102 native 入口），仅 arm64-v8a；
    // libc++ 静态链接，避免运行期依赖 libc++_shared。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.frederic22.hyperextreme"
        minSdk = 33
        targetSdk = 37
        versionCode = 5
        versionName = "0.5"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments.add("-DANDROID_STL=c++_static")
            }
        }
    }

    signingConfigs {
        // 发布签名（本地模块项目，没有对外服务）：keystore/release.jks
        // 生成方式（口令只写进 keystore.properties，不要进版本库）：
        //   keytool -genkeypair -keystore keystore/release.jks -alias <别名> \
        //     -keyalg RSA -keysize 2048 -validity 10000
        // 没有 keystore.properties 时不创建这个配置，release 退回 debug 签名。
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 全量裁剪 + 资源收缩。
            //
            // 【为什么以前是 false】原判断是"Xposed 入口按类名找，混淆会弄丢" —— 这是误判：
            // 按类名加载的入口只有两个（HookEntry / XposedProvider），都在
            // app/proguard-rules.pro 里显式 keep 住了。R8 剪掉的是**没被引用到的库代码**：
            // 本次打包前 dex 里有 17,803 个类，工程自己的不到 300 个，其余全是
            // androidx.compose.*（text / lazy / gestures / animation…）和 miuix 的 316 个
            // basic 组件 —— 一行都没执行过。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (keystoreProps != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("未找到 keystore.properties —— release 将使用 debug 签名")
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // dex 压缩进 APK（minSdk >= 28 时 AGP 默认是不压缩、安装时直接 mmap，
        // 体积会大 20MB 左右）。这里要的是包小，所以打开 legacy 打包。
        dex {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // libxposed API 102（由 io.github.libxposed:api:102.0.0 的 AAR 解出的 classes.jar）。
    // 只参与编译，运行时由框架提供，绝不打包进 APK。
    compileOnly(files("libs/libxposed-api-102.jar"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.jetbrains.compose.foundation)
    implementation(libs.miuix.core)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    // 底栏玻璃：改用 miuix 官方的 miuix-blur（AGSL 手写可分离高斯 + 自适应降采样），
    // 不再用 kyant backdrop 的 RenderEffect.createBlurEffect 那条链。
    implementation(libs.miuix.blur)
    // 悬浮底栏（从 HyperChanger 移植：Apache-2.0）用到的液态玻璃与形状库
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)
    // 空间壁纸的深度推理（Depth Anything 3，Small + Base 双模型，2026-11-02 换代）。
    // **完整版**：mobile（minimal build）缺 ConvInteger / MatMulNBits 等算子，实测跑不动
    // 动态量化模型（2026-10-22），DA3-Base 的 MatMulNBits 同样只有完整版带全 kernel
    // （so 17.8MB），模型直接用 `.onnx`。
    implementation(libs.onnxruntime.android)
}

// ---------------------------------------------------------------------------
// DA3-Base 模型（123MB）：超过 GitHub 单文件 100MB 上限，不入库。
// 构建时从 HuggingFace 下载 + SHA-256 校验，落在 `src/main/assets/models/`
// （默认 main asset 源，merge*Assets 自动打包进 APK ⇒ APK 里照样"内置"）。
// 校验失败会响亮报错；网络不通可手动下载放到同一路径后重试。
// ---------------------------------------------------------------------------
val da3BaseModelFile = file("src/main/assets/models/da3_base_int8.onnx")
val da3BaseUrls = listOf(
    "https://huggingface.co/jlampel/depth-anything-3-base-onnx-int8/resolve/main/da3_base_int8.onnx",
    "https://hf-mirror.com/jlampel/depth-anything-3-base-onnx-int8/resolve/main/da3_base_int8.onnx",
)
val da3BaseSha256 = "86dd3200ed2506fe4d394b6526691f5c4591d88f51d3f42520aaee0b85387aaf"

fun sha256Hex(f: File): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    f.inputStream().use { ins ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val r = ins.read(buf)
            if (r <= 0) break
            md.update(buf, 0, r)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val fetchDa3BaseModel = tasks.register("fetchDa3BaseModel") {
    inputs.property("urls", da3BaseUrls.joinToString("|"))
    inputs.property("sha256", da3BaseSha256)
    outputs.file(da3BaseModelFile)
    doLast {
        if (da3BaseModelFile.isFile && sha256Hex(da3BaseModelFile) == da3BaseSha256) {
            logger.lifecycle("fetchDa3BaseModel：已存在且校验通过，跳过下载")
            return@doLast
        }
        var lastErr: String = "未知错误"
        for (url in da3BaseUrls) {
            try {
                logger.lifecycle("fetchDa3BaseModel：下载 $url")
                val tmp = File(da3BaseModelFile.parentFile, "${da3BaseModelFile.name}.tmp")
                if (tmp.exists()) tmp.delete()
                val md = java.security.MessageDigest.getInstance("SHA-256")
                java.net.URI(url).toURL().openStream().use { ins ->
                    java.io.FileOutputStream(tmp).use { outs ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val r = ins.read(buf)
                            if (r <= 0) break
                            outs.write(buf, 0, r)
                            md.update(buf, 0, r)
                        }
                    }
                }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (hex == da3BaseSha256) {
                    if (da3BaseModelFile.exists()) da3BaseModelFile.delete()
                    check(tmp.renameTo(da3BaseModelFile)) {
                        "模型落盘失败：${da3BaseModelFile.absolutePath}"
                    }
                    logger.lifecycle(
                        "fetchDa3BaseModel：完成并校验通过（${da3BaseModelFile.length()} 字节）",
                    )
                    return@doLast
                }
                tmp.delete()
                lastErr = "SHA-256 不匹配（$url）：$hex"
                logger.warn("fetchDa3BaseModel：$lastErr")
            } catch (e: Exception) {
                lastErr = "${e.javaClass.simpleName}: ${e.message}（$url）"
                logger.warn("fetchDa3BaseModel：下载失败，$lastErr")
            }
        }
        throw GradleException(
            "DA3-Base 模型获取失败：$lastErr\n" +
                "可手动下载 ${da3BaseUrls.first()} 放到 " +
                "${da3BaseModelFile.absolutePath} 后重试（SHA-256 必须为 $da3BaseSha256）。\n" +
                "若网络需要代理：在 gradle.properties 配置 systemProp.http(s).proxyHost/Port。",
        )
    }
}

// preBuild 与资产合并都依赖它：fresh clone 第一次 assembleRelease 会先下载模型。
tasks.named("preBuild") { dependsOn(fetchDa3BaseModel) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(fetchDa3BaseModel) }
