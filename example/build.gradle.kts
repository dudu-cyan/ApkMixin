import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

plugins {
    //kotlin("jvm")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
    id("com.dudu.apk-patcher") version "1.0.0"
    alias(libs.plugins.kotlin.android)
}

repositories {
    mavenLocal()
    google()
    mavenCentral()
}

android {
    compileSdk = 36
    namespace = "com.dudu.test"
    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":mixin-lib"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.wear.compose.material3)
    implementation(libs.core.ktx)
}


apkPatcher {
    targetApk.set(file("com.tencent.qqlite.apk"))
    enableResourceHook.set(false)

    // Stub 范围：只对 com.tencent.qqnt.watch.mainframe 包下的类生成编译期 stub
    stubIncludePrefixes.add("com.tencent.qqnt.watch.mainframe")

    // ───── 第三方库注入 ─────
    // 默认 autoInjectImplementation=true，上方 dependencies { implementation(...) } 里
    // 声明的 compose / wear-compose / androidx 等都会被自动 dex 化并合并进 patched APK。
    // 想关掉自动注入：autoInjectImplementation.set(false)
    // 想手动追加额外 jar：extraLibraries.from(file("libs/some-lib.jar"))
    // autoInjectExcludeGroups.add("org.jetbrains.kotlin")  // 例如想跳过 kotlin-stdlib

    // ───── 代码混淆 ─────
    obfuscation {
        enabled.set(true) // 默认关，开了之后会重命名 Mixin 类 + 加密字符串
        renameClasses.set(true)
        renameMembers.set(true)
        encryptStrings.set(true)
        seed.set(0xC0FFEEL)
        excludePrefixes.add("com.dudu.test") // 保留 example 命名空间，便于排查
    }

    // ───── APK 签名（v1+v2+v3，默认开 debug 自签名）─────
    signing {
        useDebugKey.set(true)
        // keystorePath.set(file("release.keystore"))
        // keystorePassword.set("android")
        // keyAlias.set("mixin-debug")
        // keyPassword.set("android")
        v1.set(true); v2.set(true); v3.set(true)
        zipAlign.set(true)
    }

    // ───── 资源 hook：AndroidManifest 二进制补丁 + 资产覆盖 ─────
    resourceHook {
        enabled.set(false)
        permissions.add("android.permission.INTERNET")
        permissions.add("android.permission.ACCESS_NETWORK_STATE")
        metaData.put("MIXIN_BUILD", "1.0.0")
        applicationAttributes.put("debuggable", "true")
        // activity("com.dudu.test.NewActivity") { exported.set(true) }
        // service("com.dudu.test.MyService")
        // receiver("com.dudu.test.MyReceiver")
        // provider("com.dudu.test.MyProvider", authorities = "com.dudu.test.provider")
    }
}


