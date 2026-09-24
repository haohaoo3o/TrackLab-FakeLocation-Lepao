import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 高德 Key 注入链路：优先读取 local.properties 中的非空白值，否则回退环境变量 AMAP_API_KEY，
// 再经 manifestPlaceholders 写入 meta-data com.amap.api.v2.apikey。
// 两处均缺失或留空时使用空串，允许编译并由应用显示 NEEDS_KEY 配置提示；真实 Key 不写入仓库。
val amapApiKey: String = run {
    val fromLocal = rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }.getProperty("AMAP_API_KEY")
    }
    fromLocal?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("AMAP_API_KEY")?.trim().orEmpty()
}

// 发布签名配置在配置期保持可加载：仅在属性文件存在时创建 signingConfig。
// assembleRelease/packageRelease 执行前再检查属性文件并报告明确错误。
// 使用 tasks.matching(...).configureEach，以兼容 AGP 延迟注册任务。
val releaseKeystorePropsFile = rootProject.file(".secrets/keystore.properties")
val releaseKeystoreProps = Properties().apply {
    if (releaseKeystorePropsFile.exists()) {
        releaseKeystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.haohaoo3o.tracklab"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.haohaoo3o.tracklab"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["AMAP_API_KEY"] = amapApiKey
    }

    // keystore 存在时 debug/release 使用同一测试证书；不存在时跳过且不报错。
    if (releaseKeystorePropsFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(
                    releaseKeystoreProps.getProperty("storeFile") ?: ".secrets/tracklab-release.jks"
                )
                storePassword = releaseKeystoreProps.getProperty("storePassword")
                keyAlias = releaseKeystoreProps.getProperty("keyAlias")
                keyPassword = releaseKeystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            if (releaseKeystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        lintConfig = file("lint.xml")
    }
}

dependencies {
    // 依赖版本固定以保持可复现构建；org.json 仅用于测试，高德合包由主代码直接使用。
    // 不引入 Compose / Hilt / Guava / Play Services / test:rules / activity-ktx / fragment。
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7") // repeatOnLifecycle 生命周期收集
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.amap.api:3dmap-location-search:11.3.100_loc11.3.000_sea9.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// 发布任务执行前校验签名属性文件；配置其他任务时不触发该检查。
tasks.matching { it.name == "assembleRelease" || it.name == "packageRelease" }.configureEach {
    doFirst {
        if (!releaseKeystorePropsFile.exists()) {
            throw GradleException(
                "缺少 .secrets/keystore.properties——请先执行 bash tools/prepare_release_signing.sh"
            )
        }
    }
}
