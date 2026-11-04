import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// app：Android 应用模块。只做 UI、权限、前台服务、平台适配；Git 逻辑全部在 :gitcore。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 运行时类路径（进 APK 的）上用 gitcore 自带的 InflaterCache 替换 JGit 的（D-033、L-036）
apply(from = rootProject.file("gradle/jgit-patch.gradle.kts"))

val appVersionName = "0.4.3"
val appVersionCode = 7

// APK 文件名带版本号（D-041）：vaultsync-0.4.0-debug.apk、vaultsync-0.4.0-release-unsigned.apk
base {
    archivesName.set("vaultsync-$appVersionName")
}

android {
    namespace = "com.mountriv.vaultsync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mountriv.vaultsync"
        minSdk = 29          // 用户设备 Android 10；任何 API 调用都要在 29 可用
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables.useSupportLibrary = true
        // Android 上读不到 jar manifest 的 Implementation-Version（返回 "0.0"），用编译期常量展示 JGit 版本
        buildConfigField("String", "JGIT_VERSION", "\"${libs.versions.jgit.get()}\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 不设 signingConfig：这里只出未签名包，由 tools/release/build_release.py 弹框要密码、用 apksigner 签名。
            // 签名密码不能交给 Gradle：AGP 会把密码的 SHA-256 作为任务输入写进 .gradle/ 的执行历史（D-037、L-041）
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/versions/**",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.SF",
                "META-INF/*.RSA",
                "META-INF/*.DSA",
                "META-INF/eclipse.inf",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "about.html",
                "plugin.properties",
                "OSGI-INF/**",
            )
            // META-INF/services/** 默认合并，必须保留：JGit 通过 ServiceLoader 发现 JschConfigSessionFactory
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // NewApi 默认为 error：防止用到 Android 10 没有的 API
    }

    // 应用内可以切换语言（D-030）：打 AAB 时不按语言拆分，否则只装了系统语言的资源，切换后缺文案
    bundle {
        language {
            enableSplit = false
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":gitcore"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // 只用 core 图标集（设置、添加、返回、同步等），不引入 extended（约 10 MB）
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // SLF4J：绑定为自定义 org.slf4j.impl.StaticLoggerBinder（app 源码内，D-015），只需 api
    implementation(libs.slf4j.api)
}
