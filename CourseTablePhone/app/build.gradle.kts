import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------- 正式包签名
// 把 storeFile / storePassword / keyAlias / keyPassword 写进 keystore.properties。
// 这个文件和 .keystore 文件都在 .gitignore 里，**千万别提交**：
// 泄漏了别人就能冒充你发版本，而且老用户升级会报签名不一致。
//
// 没有这个文件时（别人 clone 下来只想自己编译）自动退回 debug 签名，不会构建失败。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.liyan.coursetable.phone"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.liyan.coursetable.phone"
        minSdk = 26
        targetSdk = 36
        // versionCode 跟着 versionName 走：0.8.1 → 10。手表端也一样（都是 10）。
        // 开源发出去了就没法再改小，将来发版只能往上加。
        versionCode = 10
        versionName = "0.8.1"
    }

    signingConfigs {
        if (hasReleaseKey) {
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
            // 这两项一定要开。Compose 在 debug 包里不做 R8、不做内联、没有基线，
            // 上手就能感觉出「涩」——用户反馈的「卡」有一大半来自这里，
            // 而不是代码本身。开完 release 才是它真实的流畅度。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 「关于」面板要读 BuildConfig.VERSION_NAME。AGP 8 起这项默认关闭，得显式打开。
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
