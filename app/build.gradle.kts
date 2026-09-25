import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 发布签名：口令与密钥文件放在项目根（`keystore.properties` + `keystore/`），
 * 两者都在 `.gitignore` 里，**不进版本库**。缺失时（例如他人 clone、CI）自动回退 debug 签名，
 * 保证 `assembleRelease` 不会因为缺密钥而失败。
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = !keystoreProps.getProperty("storeFile").isNullOrBlank()

/**
 * 云同步仓库的访问令牌（**不进版本库**：本仓库是公开仓库，写进去会被 GitHub 立刻判定泄露并吊销）。
 *
 * 来源：`local.properties` 的 `sync.token`，或环境变量 `LR_SYNC_TOKEN`；构建时注入 `BuildConfig.SYNC_TOKEN`。
 * 缺失时为空串，设置页会退化成「自定义仓库」对话框让用户手填 token。
 * 建议用只授权 `genoling/ling-reader-sync`、权限仅 Contents 读写的 fine-grained token。
 */
val syncToken: String = run {
    val props = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    (props.getProperty("sync.token") ?: System.getenv("LR_SYNC_TOKEN")).orEmpty().trim()
}

android {
    namespace = "com.lreader"
    compileSdk = 33
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.lreader"
        minSdk = 24
        targetSdk = 33
        versionCode = 20
        versionName = "1.6.2"

        // 一键生成同步码用的内置仓库令牌（见文件顶部的 syncToken；空串 = 未注入，走手填对话框）。
        // 用 resValue 而非 buildConfigField：本机 JBR 没有 jlink，一旦开启 buildConfig 就会触发
        // javac + JdkImageTransform 直接构建失败（详见 docs/development.md 的「构建环境」）。
        resValue("string", "sync_token", syncToken)
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.4.3"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        noCompress += listOf("db", "min.db")
    }
}

dependencies {
    // 兼容 compileSdk 33 的最后一组稳定版本
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.7.2")

    implementation("androidx.compose.ui:ui:1.4.3")
    implementation("androidx.compose.ui:ui-graphics:1.4.3")
    implementation("androidx.compose.ui:ui-tooling-preview:1.4.3")
    implementation("androidx.compose.material3:material3:1.1.2")
    implementation("androidx.compose.material:material-icons-extended:1.4.3")
    debugImplementation("androidx.compose.ui:ui-tooling:1.4.3")

    implementation("androidx.navigation:navigation-compose:2.6.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")
}
