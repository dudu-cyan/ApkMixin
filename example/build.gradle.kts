import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

plugins {
    kotlin("jvm")
    id("com.example.apk-patcher") version "1.0.0"
}

repositories {
    mavenLocal()
    google()
    mavenCentral()
}

// 定义一个配置来接收 Android 运行时依赖，并设置属性以匹配 androidJvm 变体
val androidRuntime by configurations.creating {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.ANDROID))
    }
}

// 定义一个配置来接收 Android 编译时依赖
val androidCompile by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_API))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.ANDROID))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
    }
}

dependencies {
    implementation(project(":mixin-lib"))
    compileOnly(project(":compose-stubs"))
    
    // Compose 依赖 (注入到 APK 中)
    val composeVersion = "1.5.4"
    val composeMaterial3Version = "1.1.2"
    
    val composeDeps = listOf(
        "androidx.compose.ui:ui:$composeVersion",
        "androidx.compose.material3:material3:$composeMaterial3Version",
        "androidx.compose.runtime:runtime:$composeVersion",
        "androidx.activity:activity-compose:1.8.2"
    )

    composeDeps.forEach { dep ->
        androidRuntime(dep) { exclude(group = "org.jetbrains.kotlin") }
    }

    // 还需要 Android SDK 的 Stub，因为 Compose UI 依赖 Android 类
    compileOnly("com.google.android:android:4.1.1.4")
}

// 应用 Compose Compiler Plugin (手动配置，因为 plugin id 可能不工作)
dependencies {
    // 禁用 Compose 编译器插件，因为它在 JVM 项目中处理 Android 类 stubs 时会崩溃
    // kotlinCompilerPluginClasspath("org.jetbrains.compose.compiler:compiler:1.5.4")
}

// 确保 patchApk 任务能拿到这些依赖
tasks.named<com.example.mixin.plugin.PatchApkTask>("patchApk") {
    mixinClasses.from(androidRuntime)
}

tasks.register("generateTestApk") {
    doLast {
        val dexFile = file("build/temp-dex/classes.dex")
        dexFile.parentFile.mkdirs()

        val opcodes = org.jf.dexlib2.Opcodes.getDefault()
        val classDef = org.jf.dexlib2.immutable.ImmutableClassDef(
            "Lcom/example/test/MainActivity;",
            org.jf.dexlib2.AccessFlags.PUBLIC.getValue(),
            "Landroidx/activity/ComponentActivity;",
            null, null, null, null,
            listOf(
                org.jf.dexlib2.immutable.ImmutableMethod(
                    "Lcom/example/test/MainActivity;",
                    "hello",
                    emptyList(),
                    "V",
                    org.jf.dexlib2.AccessFlags.PUBLIC.getValue(),
                    null, null,
                    org.jf.dexlib2.immutable.ImmutableMethodImplementation(
                        2,
                        listOf(
                            org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c(org.jf.dexlib2.Opcode.SGET_OBJECT, 0, org.jf.dexlib2.immutable.reference.ImmutableFieldReference("Ljava/lang/System;", "out", "Ljava/io/PrintStream;")),
                            org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c(org.jf.dexlib2.Opcode.CONST_STRING, 1, org.jf.dexlib2.immutable.reference.ImmutableStringReference("Hello from Original APK!")),
                            org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c(org.jf.dexlib2.Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, org.jf.dexlib2.immutable.reference.ImmutableMethodReference("Ljava/io/PrintStream;", "println", listOf("Ljava/lang/String;"), "V")),
                            org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x(org.jf.dexlib2.Opcode.RETURN_VOID)
                        ),
                        null, null
                    )
                )
            )
        )
        
        val dexFileObj = object : org.jf.dexlib2.iface.DexFile {
            override fun getClasses() = setOf(classDef)
            override fun getOpcodes() = opcodes
        }
        
        org.jf.dexlib2.DexFileFactory.writeDexFile(dexFile.absolutePath, dexFileObj)
        
        val apkFile = file("test.apk")
        ZipOutputStream(apkFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("classes.dex"))
            dexFile.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
        println("Generated test.apk at ${apkFile.absolutePath}")
    }
}

apkPatcher {
    targetApk.set(file("test.apk"))
    enableResourceHook.set(true)
}

tasks.named("patchApk") {
    dependsOn("generateTestApk")
}
