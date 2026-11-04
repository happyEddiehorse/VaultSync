# R8 规则（release）。理由见 docs/ANDROID_PLATFORM.md §10。

# JGit：NLS 资源包反射加载、ServiceLoader、内部反射
-keep class org.eclipse.jgit.** { *; }
-keepnames class org.eclipse.jgit.** { *; }

# JSch：算法实现类由配置字符串 Class.forName 加载
-keep class com.jcraft.jsch.** { *; }

# BouncyCastle
-keep class org.bouncycastle.** { *; }

# SLF4J
-keep class org.slf4j.** { *; }

-keepclassmembers class * implements java.io.Serializable { *; }

# Android 上不存在、但只在错误路径/桌面路径引用的 JDK 包
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn java.rmi.**
-dontwarn javax.security.auth.login.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.**
-dontwarn java.lang.foreign.**
-dontwarn org.slf4j.impl.**
# mwiede jsch 的 multi-release 目录已从打包中排除，基线类里对它们的引用无需警告
-dontwarn com.jcraft.jsch.**
# JGit 可选依赖
-dontwarn org.apache.sshd.**
-dontwarn net.i2p.crypto.**
-dontwarn com.googlecode.javaewah.**
-dontwarn org.eclipse.jgit.**
-dontwarn javax.servlet.**
