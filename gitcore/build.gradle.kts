import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// gitcore：纯 JVM 模块。封装 JGit、同步引擎、冲突模型。
// 规则：禁止 import android.*；所有 JDK API 必须在 Android 10（API 29）可用（见 memory/WORKFLOW.md §6）。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// 运行时类路径上用 gitcore 自带的 InflaterCache 替换 JGit 的（D-033、L-036）
apply(from = rootProject.file("gradle/jgit-patch.gradle.kts"))

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    api(libs.jgit)
    implementation(libs.jgit.ssh.jsch) {
        // JGit 5.13 绑定的 com.jcraft:jsch 0.1.55 不支持 rsa-sha2 / ed25519（GitHub 已拒绝 ssh-rsa SHA-1）。
        // 换成 mwiede 维护的分支（同包名 com.jcraft.jsch，drop-in）。见 LESSONS L-003。
        exclude(group = "com.jcraft", module = "jsch")
    }
    implementation(libs.jsch)
    // JSch 的 ed25519 / X25519 在 Java 8 运行时（Android）回退到 BouncyCastle 实现；jdk15to18 是单版本 jar。
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    // SSH 联调用的本地 git-over-ssh 服务器（org.eclipse.jgit.junit.ssh → MINA sshd 2.7.0）；eddsa 让服务器端认得 ed25519 公钥。
    // 仅测试类路径，不进 APK（D-022）。
    testImplementation(libs.jgit.junit.ssh)
    testRuntimeOnly(libs.eddsa)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
}
