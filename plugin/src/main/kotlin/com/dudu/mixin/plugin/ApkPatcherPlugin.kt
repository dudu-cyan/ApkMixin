package com.dudu.mixin.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import com.android.tools.r8.origin.Origin
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.formats.Instruction35c
import org.jf.dexlib2.iface.instruction.formats.Instruction3rc
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction12x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction3rc
import org.jf.dexlib2.rewriter.Rewriter
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes as AsmOpcodes

import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import com.dudu.mixin.compiler.MixinMetaData
import com.dudu.mixin.compiler.InjectionData
import com.dudu.mixin.compiler.AtData
import com.dudu.mixin.plugin.obf.MixinObfuscator
import com.dudu.mixin.plugin.obf.ObfuscatorOptions
import com.dudu.mixin.plugin.res.AxmlIo
import com.dudu.mixin.plugin.res.ComponentSpec
import com.dudu.mixin.plugin.res.ManifestPatcher
import com.dudu.mixin.plugin.sign.ApkSignerHelper
import com.dudu.mixin.plugin.sign.ZipAligner
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import javax.inject.Inject

/**
 * 配置 DSL：
 *
 * ```kotlin
 * apkPatcher {
 *     targetApk.set(file("app.apk"))
 *     mixinClasses.from(...)            // 一般会自动从 Kotlin 编译产物补齐
 *     extraLibraries.from("libs/foo.jar")
 *
 *     enableResourceHook.set(true)
 *
 *     obfuscation {
 *         enabled.set(true)
 *         renameClasses.set(true)
 *         renameMembers.set(false)
 *         encryptStrings.set(true)
 *         seed.set(0xC0FFEEL)
 *         excludePrefixes.add("com.dudu.keep")
 *     }
 * }
 * ```
 */
/**
 * 资源 hook 子配置。
 *
 * ```kotlin
 * resourceHook {
 *     enabled.set(true)
 *     permissions.add("android.permission.INTERNET")
 *     metaData.put("MIXIN_BUILD", "v1")
 *     applicationAttributes.put("debuggable", "true")
 *     activity("com.dudu.NewActivity") { exported.set(true) }
 *     service("com.dudu.MyService")
 *     receiver("com.dudu.MyReceiver")
 *     provider("com.dudu.MyProvider", authorities = "com.dudu.provider")
 * }
 * ```
 */
abstract class ComponentDefinition @Inject constructor(objects: ObjectFactory) {
    abstract val tag: Property<String>
    abstract val className: Property<String>
    abstract val exported: Property<Boolean>
    abstract val authorities: Property<String>
    abstract val extraAttrs: MapProperty<String, String>

    init {
        exported.convention(false)
        extraAttrs.convention(emptyMap())
    }
}

abstract class ResourceHookConfig @Inject constructor(private val objects: ObjectFactory) {
    abstract val enabled: Property<Boolean>
    abstract val permissions: ListProperty<String>
    abstract val metaData: MapProperty<String, String>
    abstract val applicationAttributes: MapProperty<String, String>
    /** 内部使用：保存所有 activity/service/receiver/provider。 */
    val components: MutableList<ComponentDefinition> = mutableListOf()

    init {
        enabled.convention(false)
        permissions.convention(emptyList())
        metaData.convention(emptyMap())
        applicationAttributes.convention(emptyMap())
    }

    fun activity(className: String, action: Action<in ComponentDefinition>? = null) =
        addComponent("activity", className, action)
    fun service(className: String, action: Action<in ComponentDefinition>? = null) =
        addComponent("service", className, action)
    fun receiver(className: String, action: Action<in ComponentDefinition>? = null) =
        addComponent("receiver", className, action)
    fun provider(className: String, authorities: String, action: Action<in ComponentDefinition>? = null) {
        val wrap = object : Action<ComponentDefinition> {
            override fun execute(def: ComponentDefinition) {
                def.authorities.set(authorities)
                action?.execute(def)
            }
        }
        addComponent("provider", className, wrap)
    }

    private fun addComponent(tag: String, className: String, action: Action<in ComponentDefinition>?) {
        val def = objects.newInstance(ComponentDefinition::class.java)
        def.tag.set(tag); def.className.set(className)
        action?.execute(def)
        components.add(def)
    }
}

abstract class ObfuscationConfig @Inject constructor(objects: ObjectFactory) {
    abstract val enabled: Property<Boolean>
    abstract val renameClasses: Property<Boolean>
    abstract val renameMembers: Property<Boolean>
    abstract val encryptStrings: Property<Boolean>
    abstract val seed: Property<Long>
    abstract val excludePrefixes: ListProperty<String>

    init {
        enabled.convention(false)
        renameClasses.convention(true)
        renameMembers.convention(false)
        encryptStrings.convention(true)
        seed.convention(0xC0FFEEL)
        excludePrefixes.convention(emptyList())
    }
}

/**
 * APK 签名 + zipalign 配置。
 *
 * 默认行为（什么都不配）：
 *  - zipAlign = true
 *  - useDebugKey = true → 自动生成/复用 `<gradle build>/mixin-debug.keystore`
 *  - v1+v2+v3 签名都开
 */
abstract class SigningConfig @Inject constructor(objects: ObjectFactory) {
    /** 自定义 keystore 文件；为空则使用 [useDebugKey] 自动生成 */
    abstract val keystorePath: RegularFileProperty
    abstract val keystorePassword: Property<String>
    abstract val keyAlias: Property<String>
    abstract val keyPassword: Property<String>
    /** 不指定 keystorePath 时自动生成 debug 自签名证书并签名 */
    abstract val useDebugKey: Property<Boolean>

    abstract val v1: Property<Boolean>
    abstract val v2: Property<Boolean>
    abstract val v3: Property<Boolean>

    /** 是否在签名前做 zipalign（推荐保留 true，否则 v2/v3 校验会失败）。 */
    abstract val zipAlign: Property<Boolean>

    init {
        keystorePassword.convention("android")
        keyAlias.convention("mixin-debug")
        keyPassword.convention("android")
        useDebugKey.convention(true)
        v1.convention(true)
        v2.convention(true)
        v3.convention(true)
        zipAlign.convention(true)
    }
}

abstract class ApkPatcherExtension @Inject constructor(objects: ObjectFactory) {
    abstract val targetApk: RegularFileProperty
    abstract val mixinClasses: ConfigurableFileCollection
    abstract val enableResourceHook: Property<Boolean>
    /** 第三方库 jar/aar 文件集合 — 会被编译为 DEX 注入到 APK 中 */
    abstract val extraLibraries: ConfigurableFileCollection
    /**
     * 是否自动把本模块 `dependencies { implementation(...) }` 声明的依赖注入进目标 APK。
     *
     * 开启后（默认）会在 afterEvaluate 阶段把 runtime classpath 上的所有 jar/aar/project 解析产物
     * 追加到 [extraLibraries]：同名类型会在 DEX 合并时被去重，不会污染宿主 APK。
     *
     * 如果你只想手动通过 [extraLibraries] 控制注入的库，把它设为 false 即可。
     *
     * 支持的 configuration 按优先级：
     *  - Android variant: `releaseRuntimeClasspath` / `debugRuntimeClasspath`
     *  - JVM: `runtimeClasspath`
     *
     * 同时会兼容 `implementation(project(":xxx"))` 这种本地子项目依赖。
     */
    abstract val autoInjectImplementation: Property<Boolean>
    /** 自动注入时用于取依赖的 variant 名（默认 release，找不到时退化到 debug，再退化到 JVM runtimeClasspath） */
    abstract val autoInjectVariant: Property<String>
    /** 自动注入时额外跳过的 group（如 `"org.jetbrains.kotlin"`），防止 stdlib 冲突。 */
    abstract val autoInjectExcludeGroups: ListProperty<String>

    /** Stub 生成范围：指定前缀列表（如 "com.tencent.qqnt"）。空列表 = 生成所有非系统类的 stub。 */
    abstract val stubIncludePrefixes: ListProperty<String>
    /** 混淆器配置子块。 */
    val obfuscation: ObfuscationConfig = objects.newInstance(ObfuscationConfig::class.java)
    /** 签名子块。 */
    val signing: SigningConfig = objects.newInstance(SigningConfig::class.java)
    /** 资源 hook 子块（manifest 改动 + 资产覆盖）。 */
    val resourceHook: ResourceHookConfig = objects.newInstance(ResourceHookConfig::class.java)

    fun obfuscation(action: Action<in ObfuscationConfig>) {
        action.execute(obfuscation)
    }
    fun signing(action: Action<in SigningConfig>) {
        action.execute(signing)
    }
    fun resourceHook(action: Action<in ResourceHookConfig>) {
        action.execute(resourceHook)
    }
}

class ApkPatcherPlugin : Plugin<Project>, KotlinCompilerPluginSupportPlugin {
    override fun apply(project: Project) {
        val extension = project.extensions.create(ApkPatcherExtension::class.java, "apkPatcher", ApkPatcherExtension::class.java)
        extension.enableResourceHook.convention(false)
        extension.autoInjectImplementation.convention(true)
        extension.autoInjectVariant.convention("release")
        extension.autoInjectExcludeGroups.convention(emptyList())

        val patchTask = project.tasks.register("patchApk", PatchApkTask::class.java) {
            val task = this
            task.targetApk.set(extension.targetApk)
            task.mixinClasses.from(extension.mixinClasses)
            task.extraLibraries.from(extension.extraLibraries)

            val kotlinCompileTasks = project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile::class.java)
            task.mixinClasses.from(kotlinCompileTasks.map { t -> t.destinationDirectory })

            task.enableResourceHook.set(extension.enableResourceHook)
            task.resHookEnabled.set(extension.resourceHook.enabled)
            task.resHookPermissions.set(extension.resourceHook.permissions)
            task.resHookMetaData.set(extension.resourceHook.metaData)
            task.resHookAppAttrs.set(extension.resourceHook.applicationAttributes)
            task.resHookComponents.set(project.provider {
                extension.resourceHook.components.map { d ->
                    mapOf(
                        "tag" to d.tag.getOrElse(""),
                        "className" to d.className.getOrElse(""),
                        "exported" to d.exported.getOrElse(false).toString(),
                        "authorities" to (d.authorities.orNull ?: ""),
                    ) + d.extraAttrs.getOrElse(emptyMap()).mapKeys { "x:${it.key}" }
                }
            })
            task.obfuscationEnabled.set(extension.obfuscation.enabled)
            task.obfRenameClasses.set(extension.obfuscation.renameClasses)
            task.obfRenameMembers.set(extension.obfuscation.renameMembers)
            task.obfEncryptStrings.set(extension.obfuscation.encryptStrings)
            task.obfSeed.set(extension.obfuscation.seed)
            task.obfExcludePrefixes.set(extension.obfuscation.excludePrefixes)
            task.signKeystorePath.set(extension.signing.keystorePath)
            task.signKeystorePassword.set(extension.signing.keystorePassword)
            task.signKeyAlias.set(extension.signing.keyAlias)
            task.signKeyPassword.set(extension.signing.keyPassword)
            task.signUseDebugKey.set(extension.signing.useDebugKey)
            task.signV1.set(extension.signing.v1)
            task.signV2.set(extension.signing.v2)
            task.signV3.set(extension.signing.v3)
            task.signZipAlign.set(extension.signing.zipAlign)
            task.group = "build"
            task.description = "Patches the target APK with Mixins"
            task.dependsOn(kotlinCompileTasks)
        }

        // ── 从目标 APK 生成编译期 stub class，让 Mixin 代码可以直接 import 目标类 ──
        val stubTask = project.tasks.register("generateTargetStubs", GenerateTargetStubs::class.java) {
            val task = this
            task.targetApk.set(extension.targetApk)
            task.outputJar.set(project.layout.buildDirectory.file("target-stubs/classes.jar"))
            task.stubIncludePrefixes.set(extension.stubIncludePrefixes)
            group = "build"
            description = "Generates stub classes from target APK for compile-time reference"
        }

        // 让 Kotlin 编译依赖 stub 生成，并把 stub jar 加入 compileOnly classpath
        project.tasks.withType(KotlinJvmCompile::class.java).configureEach { dependsOn(stubTask) }
        val stubJar = stubTask.flatMap { it.outputJar }
        project.dependencies.add("compileOnly", project.files(stubJar).builtBy(stubTask))

        project.afterEvaluate {
            if (!extension.autoInjectImplementation.getOrElse(true)) {
                project.logger.lifecycle("[apkPatcher] autoInjectImplementation=false — dependencies will NOT be auto-injected")
                return@afterEvaluate
            }

            // 按优先级挑 runtime classpath。Android 通常是 ${variant}RuntimeClasspath，纯 JVM 则是 runtimeClasspath。
            val preferredVariant = extension.autoInjectVariant.getOrElse("release")
            val candidates = listOf(
                "${preferredVariant}RuntimeClasspath",
                "debugRuntimeClasspath",
                "releaseRuntimeClasspath",
                "runtimeClasspath",
            )
            val runtimeConfig = candidates
                .asSequence()
                .mapNotNull { project.configurations.findByName(it) }
                .firstOrNull { it.isCanBeResolved }

            if (runtimeConfig == null) {
                project.logger.lifecycle("[apkPatcher] No resolvable runtime classpath found; auto-inject skipped")
                return@afterEvaluate
            }

            val excludeGroups = extension.autoInjectExcludeGroups.getOrElse(emptyList()).toSet()
            // 永远跳过：compiler 是 Kotlin 编译器插件（Gson、kotlin-compiler-embeddable 等重型依赖），
            // 只在构建期用得到；mixin-lib 里的 CallbackInfo 可能会被 Mixin 方法体引用，所以不跳。
            val alwaysSkipModules = setOf(
                "com.dudu:compiler",
            )

            // 用 lazy provider 来避免在 configuration 阶段触发 resolution
            val autoInjectFiles = project.provider {
                val filter = org.gradle.api.specs.Spec<org.gradle.api.artifacts.component.ComponentIdentifier> { id ->
                    val coord = when (id) {
                        is org.gradle.api.artifacts.component.ModuleComponentIdentifier ->
                            "${id.group}:${id.module}"
                        is org.gradle.api.artifacts.component.ProjectComponentIdentifier ->
                            "project${id.projectPath}"
                        else -> id.displayName
                    }
                    val group = (id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group
                    when {
                        coord in alwaysSkipModules -> false
                        group != null && group in excludeGroups -> false
                        else -> true
                    }
                }
                val resolved = runtimeConfig.incoming.artifactView {
                    isLenient = true
                    componentFilter(filter)
                }.artifacts

                val files = mutableListOf<File>()
                resolved.forEach { art ->
                    val f = art.file
                    if (!f.exists()) return@forEach
                    // 只收 jar / aar / classes 目录：
                    //  - Android variant classpath 会把 android library project 解析成 exploded AAR 下的 classes.jar
                    //  - 本模块的 project(":x") 依赖会解析成 $buildDir/.../runtime (目录)
                    when {
                        f.isDirectory -> files.add(f)
                        f.extension == "jar" -> files.add(f)
                        f.extension == "aar" -> files.add(f)
                    }
                }
                project.logger.lifecycle(
                    "[apkPatcher] autoInject: picked ${files.size} artifacts from '${runtimeConfig.name}'"
                )
                files
            }

            patchTask.configure {
                extraLibraries.from(autoInjectFiles)
            }
        }
    }

    override fun isApplicable(kotlinCompilation: org.jetbrains.kotlin.gradle.plugin.KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = "com.dudu.mixin-transformer"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "com.dudu",
        artifactId = "compiler",
        version = "1.0.0"
    )

    override fun applyToCompilation(kotlinCompilation: org.jetbrains.kotlin.gradle.plugin.KotlinCompilation<*>): org.gradle.api.provider.Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        return project.provider {
            listOf(
                SubpluginOption(key = "outputDir", value = project.layout.buildDirectory.dir("mixin-metadata").get().asFile.absolutePath)
            )
        }
    }
}

abstract class PatchApkTask : DefaultTask() {
    @get:InputFile
    abstract val targetApk: RegularFileProperty

    @get:InputFiles
    abstract val mixinClasses: ConfigurableFileCollection

    /** 第三方库 jar/aar — 注入器会编译为 DEX 并合入 APK，自动跳过与目标 APK 重名的类。 */
    @get:InputFiles
    abstract val extraLibraries: ConfigurableFileCollection

    @get:Input
    abstract val enableResourceHook: Property<Boolean>

    @get:Input
    abstract val resHookEnabled: Property<Boolean>
    @get:Input
    abstract val resHookPermissions: ListProperty<String>
    @get:Input
    abstract val resHookMetaData: MapProperty<String, String>
    @get:Input
    abstract val resHookAppAttrs: MapProperty<String, String>
    /** 序列化后的 component 列表：每个 entry 的 key 见 apply() 包装。 */
    @get:Input
    abstract val resHookComponents: ListProperty<Map<String, String>>

    @get:Input
    abstract val obfuscationEnabled: Property<Boolean>
    @get:Input
    abstract val obfRenameClasses: Property<Boolean>
    @get:Input
    abstract val obfRenameMembers: Property<Boolean>
    @get:Input
    abstract val obfEncryptStrings: Property<Boolean>
    @get:Input
    abstract val obfSeed: Property<Long>
    @get:Input
    abstract val obfExcludePrefixes: ListProperty<String>

    @get:InputFile
    @get:Optional
    abstract val signKeystorePath: RegularFileProperty
    @get:Input
    abstract val signKeystorePassword: Property<String>
    @get:Input
    abstract val signKeyAlias: Property<String>
    @get:Input
    abstract val signKeyPassword: Property<String>
    @get:Input
    abstract val signUseDebugKey: Property<Boolean>
    @get:Input
    abstract val signV1: Property<Boolean>
    @get:Input
    abstract val signV2: Property<Boolean>
    @get:Input
    abstract val signV3: Property<Boolean>
    @get:Input
    abstract val signZipAlign: Property<Boolean>

    @get:OutputDirectory
    val outputDir: File = project.layout.buildDirectory.dir("patched-apk").get().asFile

    @TaskAction
    fun patch() {
        val apkFile = targetApk.get().asFile
        if (!apkFile.exists()) {
            println("Warning: Target APK not found at ${apkFile.absolutePath}. Skipping patch.")
            return
        }
        val patchedApk = File(outputDir, "patched-${apkFile.name}")
        println("Patching APK: ${apkFile.absolutePath}")

        val workDir = File(outputDir, "work")
        workDir.deleteRecursively()
        workDir.mkdirs()

        // 1. 解压 APK
        unzipApk(apkFile, workDir)

        // 2. 处理 Resource Hook (如果启用)
        if (enableResourceHook.get()) {
            handleResourceHook(workDir)
        }

        // 3. 处理 DEX 并注入 Mixin
        injectMixins(workDir)

        // 4. 重打包 APK
        val unsignedApk = File(outputDir, "unsigned-${apkFile.name}")
        zipApk(workDir, unsignedApk, apkFile)

        // 5. zipalign + 签名
        val toSign = if (signZipAlign.getOrElse(true)) {
            val aligned = File(outputDir, "aligned-${apkFile.name}")
            ZipAligner.align(unsignedApk, aligned)
            aligned
        } else unsignedApk

        signApk(toSign, patchedApk)
        println("Patched APK created: ${patchedApk.absolutePath}")
    }

    private fun signApk(unsignedApk: File, signedApk: File) {
        val opts = ApkSignerHelper.Options(
            keystorePath = signKeystorePath.orNull?.asFile,
            keystorePassword = signKeystorePassword.getOrElse("android"),
            keyAlias = signKeyAlias.getOrElse("mixin-debug"),
            keyPassword = signKeyPassword.getOrElse("android"),
            useDebugKey = signUseDebugKey.getOrElse(true),
            v1Signing = signV1.getOrElse(true),
            v2Signing = signV2.getOrElse(true),
            v3Signing = signV3.getOrElse(true),
            debugKeystoreFallback = File(outputDir, "mixin-debug.keystore"),
        )
        try {
            ApkSignerHelper.sign(unsignedApk, signedApk, opts)
        } catch (e: Throwable) {
            println("[apksig] signing failed: ${e.message}. Falling back to unsigned copy.")
            unsignedApk.copyTo(signedApk, overwrite = true)
        }
    }

    private fun handleResourceHook(workDir: File) {
        // 1) 资产覆盖（直接拷文件）
        val resHookDir = project.file("src/main/res-hook")
        if (resHookDir.exists()) {
            println("Applying resource hooks from ${resHookDir.absolutePath}")
            resHookDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(resHookDir).path.replace("\\", "/")
                    val destFile = File(workDir, relativePath)
                    destFile.parentFile.mkdirs()
                    file.copyTo(destFile, overwrite = true)
                    println("Hooked resource: $relativePath")
                }
            }
        }

        // 2) AndroidManifest 二进制补丁（按 DSL 配置）
        if (resHookEnabled.getOrElse(false)) {
            patchAndroidManifest(workDir)
        }

        val arscFile = File(workDir, "resources.arsc")
        if (arscFile.exists()) {
            println("resources.arsc found at ${arscFile.length()} bytes (string-table append not yet implemented)")
        }
    }

    private fun patchAndroidManifest(workDir: File) {
        val manifest = File(workDir, "AndroidManifest.xml")
        if (!manifest.exists()) {
            println("[res-hook] AndroidManifest.xml not found, skip manifest patch")
            return
        }

        val perms = resHookPermissions.getOrElse(emptyList())
        val meta = resHookMetaData.getOrElse(emptyMap())
        val appAttrs = resHookAppAttrs.getOrElse(emptyMap())
        val comps = resHookComponents.getOrElse(emptyList())
        if (perms.isEmpty() && meta.isEmpty() && appAttrs.isEmpty() && comps.isEmpty()) return

        try {
            val axml = AxmlIo.read(manifest.readBytes())
            val patcher = ManifestPatcher(axml)
            for (p in perms) {
                if (patcher.addPermission(p)) println("[res-hook] + uses-permission: $p")
            }
            for ((k, v) in meta) {
                if (patcher.addMetaData(k, v)) println("[res-hook] + meta-data: $k = $v")
            }
            for ((k, v) in appAttrs) {
                if (patcher.setApplicationAttribute(k, v)) println("[res-hook] application[$k] = $v")
            }
            for (m in comps) {
                val tag = m["tag"] ?: continue
                val cls = m["className"] ?: continue
                val exported = m["exported"] == "true"
                val auth = m["authorities"]?.takeIf { it.isNotEmpty() }
                val extra = m.filterKeys { it.startsWith("x:") }.mapKeys { it.key.removePrefix("x:") }
                if (patcher.addComponent(tag, cls, exported, auth, extra)) {
                    println("[res-hook] + <$tag android:name=\"$cls\"/>")
                }
            }
            manifest.writeBytes(AxmlIo.write(axml))
            println("[res-hook] AndroidManifest.xml patched (${manifest.length()} bytes)")
        } catch (e: Throwable) {
            println("[res-hook] manifest patch failed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun injectMixins(workDir: File) {
        val metadataDir = project.layout.buildDirectory.dir("mixin-metadata").get().asFile
        val gson = com.google.gson.Gson()

        val allMetadata: List<com.dudu.mixin.compiler.MixinMetaData> = if (metadataDir.exists()) {
            val metadataFiles = metadataDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
            metadataFiles.flatMap { file ->
                val content = file.readText()
                println("Metadata file content: $content")
                gson.fromJson(content, Array<com.dudu.mixin.compiler.MixinMetaData>::class.java).toList()
            }
        } else {
            println("No mixin metadata found in ${metadataDir.absolutePath}")
            emptyList()
        }

        // 0. 先收集 APK 中所有现有类型，供第三方库注入做去重判断
        val existingTypes = collectExistingTypes(workDir)

        // 1. 编译 Mixin 类成 DEX 并放入 APK（不会与已存在类型冲突，因为是 com.dudu.mixin.* 自有命名空间）
        val mixinDexFiles = prepareMixinDex()
        for (mixinDexFile in mixinDexFiles) {
            val nextSlot = nextDexSlotName(workDir)
            mixinDexFile.copyTo(File(workDir, nextSlot), overwrite = true)
            println("Added Mixin DEX as $nextSlot")
        }

        // 2. 第三方库 → DEX，并去重已存在的 type；同时提取 AAR 中的 res/ 资源
        val resMergeDir = File(workDir, "res")
        val extraLibDexFiles = prepareExtraLibrariesDex(existingTypes, workDir, resMergeDir)
        for (extraLibDex in extraLibDexFiles) {
            val nextSlot = nextDexSlotName(workDir)
            extraLibDex.copyTo(File(workDir, nextSlot), overwrite = true)
            println("Added Extra Library DEX as $nextSlot")
        }

        if (allMetadata.isEmpty()) {
            println("No mixin metadata; skipping bytecode rewriting")
            return
        }

        // 3. 修改现有的 DEX 文件以应用注入
        val dexFiles = workDir.listFiles { _, name -> name.endsWith(".dex") } ?: emptyArray()
        for (dexFile in dexFiles) {
            val dexStore = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
            val classes = mutableListOf<ClassDef>()
            var dexModified = false

            for (classDef in dexStore.classes) {
                val metadata = allMetadata.find { "L${it.targetClassName.replace(".", "/")};" == classDef.type }
                if (metadata != null) {
                    val (newClassDef, modified) = transformTargetClass(classDef, metadata)
                    classes.add(newClassDef)
                    if (modified) dexModified = true
                } else {
                    classes.add(classDef)
                }
            }

            if (dexModified) {
                val outputDex = File(dexFile.absolutePath + ".tmp")
                DexFileFactory.writeDexFile(outputDex.absolutePath, object : org.jf.dexlib2.iface.DexFile {
                    override fun getClasses() = classes.toMutableSet()
                    override fun getOpcodes() = Opcodes.getDefault()
                })
                dexFile.delete()
                outputDex.renameTo(dexFile)
            }
        }

        // 4. 重新编号 DEX 文件为连续数字，避免 ART 在部分版本上遇到空缺就停止扫描
        renumberDexFiles(workDir)
    }

    /** 将所有 classes*.dex 重编号为连续数字，消除空缺。 */
    private fun renumberDexFiles(workDir: File) {
        val allDex = (workDir.listFiles { _, n -> n.endsWith(".dex") } ?: emptyArray())
            .sortedBy { dexSlotNumber(it.name) }
        if (allDex.size <= 1) return

        // 先移到临时名避免冲突
        for ((i, f) in allDex.withIndex()) {
            if (!f.renameTo(File(workDir, ".dex-renumber-$i"))) {
                println("[dex] Warning: failed to rename ${f.name} for renumbering")
                return
            }
        }
        // 再按连续槽位命名
        for (i in allDex.indices) {
            val tmp = File(workDir, ".dex-renumber-$i")
            val slot = i + 1
            val targetName = if (slot == 1) "classes.dex" else "classes$slot.dex"
            if (!tmp.renameTo(File(workDir, targetName))) {
                println("[dex] Warning: failed to rename temp $i → $targetName")
            }
        }
        println("[dex] Renumbered ${allDex.size} DEX file(s) contiguously")
    }

    /** 计算下一个可用的 classes*.dex 槽位名。 */
    private fun nextDexSlotName(workDir: File): String {
        val existing = (workDir.listFiles { _, name -> name.endsWith(".dex") } ?: emptyArray())
            .map { it.name }
            .toSet()
        if ("classes.dex" !in existing) return "classes.dex"
        var i = 2
        while ("classes$i.dex" in existing) i++
        return "classes$i.dex"
    }

    /** Extract slot number from DEX filename: classes.dex → 1, classes2.dex → 2. */
    private fun dexSlotNumber(name: String): Int {
        if (name == "classes.dex") return 1
        val m = Regex("""classes(\d+)\.dex""").find(name)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    /** 收集 APK 中现存的所有 dex type（"Lcom/foo/Bar;" 形式）。 */
    private fun collectExistingTypes(workDir: File): Set<String> {
        val out = mutableSetOf<String>()
        val dexFiles = workDir.listFiles { _, name -> name.endsWith(".dex") } ?: return emptySet()
        for (dex in dexFiles) {
            val store = DexFileFactory.loadDexFile(dex, Opcodes.getDefault())
            store.classes.forEach { out.add(it.type) }
        }
        return out
    }

    /** 对一个匹配到的目标类应用所有 injection。返回 (新 ClassDef, 是否有修改)。 */
    private fun transformTargetClass(
        classDef: ClassDef,
        metadata: com.dudu.mixin.compiler.MixinMetaData,
    ): Pair<ClassDef, Boolean> {
        val methods = mutableListOf<org.jf.dexlib2.iface.Method>()
        var classModified = false

        // 把注入按目标方法名分组，便于后续遍历
        val injByName: Map<String, List<com.dudu.mixin.compiler.InjectionData>> =
            metadata.injections.groupBy { it.targetMethodName }

        for (method in classDef.methods) {
            val matched = injByName[method.name].orEmpty()
            if (matched.isEmpty()) {
                methods.add(method)
                continue
            }

            // 先处理 Overwrite —— 因为它会把整个方法换掉
            val overwrite = matched.firstOrNull { it.type == "Overwrite" }
            if (overwrite != null) {
                println("Applying Overwrite to ${classDef.type}->${method.name}")
                if (overwrite.keepOriginal) {
                    val originalRenamed = renameMethod(method, "${method.name}\$original")
                    methods.add(originalRenamed)
                }
                methods.add(buildOverwriteMethod(method, metadata, overwrite))
                classModified = true
                continue
            }

            // 否则按 Inject / Redirect / ModifyArg / ModifyVariable 顺序串行注入
            var current = method
            for (inj in matched) {
                println("Applying ${inj.type} to ${classDef.type}->${method.name} @${inj.at.value}")
                current = applyInjection(current, metadata, inj)
                classModified = true
            }
            methods.add(current)
        }

        val newDef = if (classModified) ImmutableClassDef(
            classDef.type, classDef.accessFlags, classDef.superclass,
            classDef.interfaces, classDef.sourceFile, classDef.annotations,
            classDef.staticFields.toMutableList() + classDef.instanceFields.toMutableList(),
            methods,
        ) else classDef

        return newDef to classModified
    }

    /** 将原始方法重命名（用于 @Overwrite(keepOriginal=true) 时保留 `<name>$original`）。 */
    private fun renameMethod(method: org.jf.dexlib2.iface.Method, newName: String): org.jf.dexlib2.iface.Method {
        return ImmutableMethod(
            method.definingClass,
            newName,
            method.parameters,
            method.returnType,
            method.accessFlags,
            method.annotations,
            method.hiddenApiRestrictions,
            method.implementation,
        )
    }

    /**
     * 构造 @Overwrite 方法体：
     *   非 void：return invoke-static Mixin.method(this?, args...)
     *   void   ：invoke-static; return-void
     */
    private fun buildOverwriteMethod(
        method: org.jf.dexlib2.iface.Method,
        metadata: com.dudu.mixin.compiler.MixinMetaData,
        inj: com.dudu.mixin.compiler.InjectionData,
    ): org.jf.dexlib2.iface.Method {
        val mixinClassType = "L${metadata.mixinClassName.replace(".", "/")};"
        val (mixinParamTypes, mixinReturnType) = parseDescriptor(inj.mixinMethodDescriptor)

        val methodRef = ImmutableMethodReference(
            mixinClassType, inj.mixinMethodName, mixinParamTypes, mixinReturnType,
        )

        val isStatic = (method.accessFlags and org.jf.dexlib2.AccessFlags.STATIC.value) != 0

        // 计算寄存器布局：参数寄存器从 v0 开始连续放置（符合 dex 调用约定）
        val targetParamRegCount = method.parameters.sumOf { wordSize(it.type) }
        val totalParamRegs = (if (isStatic) 0 else 1) + targetParamRegCount

        // 透传参数给 Mixin 静态方法，参数个数与 Mixin 方法声明匹配
        val argRegs = mutableListOf<Int>()
        var reg = 0
        if (!isStatic && inj.hasReceiver) {
            argRegs.add(reg)
            reg++
        } else if (!isStatic) {
            reg++ // skip this
        }

        // Only pass as many target params as the Mixin method actually expects
        val receiverSlot = if (inj.hasReceiver && !isStatic) 1 else 0
        val mixinRemaining = mixinParamTypes.size - receiverSlot
        val targetList = method.parameters.toList()
        val paramsToCopy = minOf(mixinRemaining, targetList.size)
        for (i in 0 until paramsToCopy) {
            argRegs.add(reg)
            reg += wordSize(targetList[i].type)
        }

        // 寄存器数至少要满足参数寄存器，再加上一个用来放返回值
        val resultRegCount = if (mixinReturnType == "V") 0
            else if (mixinReturnType == "J" || mixinReturnType == "D") 2 else 1
        val regCount = (totalParamRegs + resultRegCount).coerceAtLeast(totalParamRegs)

        val instructions = mutableListOf<Instruction>()
        instructions.add(buildInvokeStatic(argRegs, methodRef))
        when (mixinReturnType) {
            "V" -> instructions.add(ImmutableInstruction10x(Opcode.RETURN_VOID))
            "J", "D" -> {
                // move-result-wide v0; return-wide v0
                val rr = 0
                instructions.add(org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, rr))
                instructions.add(ImmutableInstruction11x(Opcode.RETURN_WIDE, rr))
            }
            "L", "[" -> { // ref/array
                val rr = 0
                instructions.add(ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, rr))
                instructions.add(ImmutableInstruction11x(Opcode.RETURN_OBJECT, rr))
            }
            else -> {
                if (mixinReturnType.startsWith("L") || mixinReturnType.startsWith("[")) {
                    val rr = 0
                    instructions.add(ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, rr))
                    instructions.add(ImmutableInstruction11x(Opcode.RETURN_OBJECT, rr))
                } else {
                    val rr = 0
                    instructions.add(ImmutableInstruction11x(Opcode.MOVE_RESULT, rr))
                    instructions.add(ImmutableInstruction11x(Opcode.RETURN, rr))
                }
            }
        }

        val newImpl = ImmutableMethodImplementation(
            regCount,
            instructions,
            emptyList(),
            null,
        )

        return ImmutableMethod(
            method.definingClass,
            method.name,
            method.parameters,
            method.returnType,
            method.accessFlags,
            method.annotations,
            method.hiddenApiRestrictions,
            newImpl,
        )
    }

    private fun prepareMixinDex(): List<File> {
        val allFiles = mixinClasses.files
        val tempDexDir = File(outputDir, "mixin-dex-tmp")
        tempDexDir.deleteRecursively()
        tempDexDir.mkdirs()

        // 阶段 1：把 .class 文件汇总到 staging 目录，供混淆器使用
        val stagingDir = File(outputDir, "mixin-staging").apply {
            deleteRecursively(); mkdirs()
        }
        var stagedCount = 0
        fun isDexable(name: String): Boolean {
            // 过滤 JPMS / 多版本 jar 中容易让 D8 报 "module-info missing super type" 的条目
            val lower = name.lowercase()
            if (lower.endsWith("/module-info.class") || lower == "module-info.class") return false
            if (lower.startsWith("meta-inf/")) return false
            return lower.endsWith(".class")
        }
        allFiles.forEach { file ->
            if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach {
                    val rel = it.relativeTo(file).path.replace('\\', '/')
                    if (!isDexable(rel)) return@forEach
                    val out = File(stagingDir, rel).apply { parentFile.mkdirs() }
                    it.copyTo(out, overwrite = true)
                    stagedCount++
                }
            } else if (file.extension == "class") {
                if (!isDexable(file.name)) return@forEach
                File(stagingDir, file.name).also { file.copyTo(it, overwrite = true); stagedCount++ }
            } else if (file.extension == "jar") {
                // jar 内 class 也解出来，便于混淆器统一处理
                ZipFile(file).use { zip ->
                    zip.entries().asSequence().forEach { e ->
                        if (!e.isDirectory && isDexable(e.name)) {
                            val out = File(stagingDir, e.name).apply { parentFile.mkdirs() }
                            zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                            stagedCount++
                        }
                    }
                }
            }
        }

        if (stagedCount == 0) {
            println("No mixin class/jar files found")
            return emptyList()
        }

        // 阶段 2：可选混淆
        val toDexDir = if (obfuscationEnabled.getOrElse(false)) {
            val obfDir = File(outputDir, "mixin-obf").apply {
                deleteRecursively(); mkdirs()
            }
            val opts = ObfuscatorOptions(
                renameClasses = obfRenameClasses.getOrElse(true),
                renameMembers = obfRenameMembers.getOrElse(false),
                encryptStrings = obfEncryptStrings.getOrElse(true),
                seed = obfSeed.getOrElse(0xC0FFEEL),
                excludePrefixes = obfExcludePrefixes.getOrElse(emptyList()),
            )
            val report = MixinObfuscator(opts).process(stagingDir, obfDir)
            println("[mixin] obfuscator: classes=${report.touchedClasses}, " +
                "renamed=${report.classMapping.size}, encryptedStrings=${report.encryptedStrings}")
            obfDir
        } else stagingDir

        // 阶段 3：D8 → dex
        val builder = D8Command.builder()
        builder.minApiLevel = 21
        toDexDir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach {
            builder.addClassProgramData(it.readBytes(), Origin.unknown())
        }
        builder.setOutput(tempDexDir.toPath(), OutputMode.DexIndexed)
        D8.run(builder.build())

        // D8 可能输出 classes.dex / classes2.dex / ...（方法数 > 64K 时）
        val produced = tempDexDir.listFiles { _, name -> name.endsWith(".dex") }?.sortedBy { it.name }
            ?: emptyList()
        return produced
    }

    /**
     * 第三方库管理：把 [extraLibraries] 里的所有 jar/aar/class 编译成 DEX，
     * 并自动剔除已经存在于宿主 APK 中的同名类型，避免 D8 报 duplicate type / 安装失败。
     *
     * 支持超过 64K 方法的 multi-dex 产物（classes.dex / classes2.dex / ...）。
     */
    private fun prepareExtraLibrariesDex(existingTypes: Set<String>, workDir: File, resDir: File? = null): List<File> {
        val files = extraLibraries.files
        if (files.isEmpty()) return emptyList()

        val tempInputDir = File(outputDir, "extra-lib-input").apply {
            deleteRecursively(); mkdirs()
        }
        val tempDexDir = File(outputDir, "extra-lib-dex").apply {
            deleteRecursively(); mkdirs()
        }

        // 把所有 .class（含 jar/aar 解压出来的）规范化收到一个目录，按需做去重
        var idx = 0
        val collectedClasses = mutableListOf<File>()
        for (f in files) {
            when {
                f.isDirectory -> {
                    f.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach {
                        val rel = it.relativeTo(f).path.replace('\\', '/')
                        if (!isDexableEntry(rel)) return@forEach
                        val out = File(tempInputDir, "dir-${idx}/$rel").apply { parentFile.mkdirs() }
                        it.copyTo(out, overwrite = true)
                        collectedClasses.add(out)
                    }
                    idx++
                }
                f.extension == "class" -> {
                    if (isDexableEntry(f.name)) {
                        val out = File(tempInputDir, "loose-${idx++}.class")
                        f.copyTo(out, overwrite = true)
                        collectedClasses.add(out)
                    }
                }
                f.extension == "jar" || f.extension == "aar" -> {
                    val target = File(tempInputDir, "lib-${idx++}").apply { mkdirs() }
                    extractZipClasses(f, target)
                    target.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { collectedClasses.add(it) }
                    // AAR 额外提取 res/ 资源文件
                    if (f.extension == "aar" && resDir != null) {
                        extractAarResources(f, resDir)
                    }
                    // 从 R.txt 生成 R$* class 并注入 D8 编译（支持 .aar / .jar 中有 R.txt 的情况）
                    if (f.extension == "aar" || f.extension == "jar") {
                        val rClasses = generateRClassesFromAAR(f, existingTypes, target)
                        collectedClasses.addAll(rClasses)
                        if (rClasses.isNotEmpty()) {
                            println("Generated ${rClasses.size} R class file(s) from ${f.name}")
                        }
                    }
                }
            }
        }

        if (collectedClasses.isEmpty()) {
            println("No classes found in extraLibraries")
            return emptyList()
        }

        // 去重 vs 现有 types（宿主 APK）+ 跨 jar 之间重复的 type（多库同名冲突时先到先得）
        val builder = D8Command.builder()
        builder.minApiLevel = 21
        var added = 0
        var skippedHost = 0
        var skippedDup = 0
        val nonDupClasses = mutableListOf<File>()
        val dupClasses = mutableListOf<File>()
        val seenAcrossLibs = mutableSetOf<String>()
        for (cls in collectedClasses) {
            val typeDesc = classFileToTypeDescriptor(cls)
            if (typeDesc != null) {
                if (typeDesc in existingTypes) { dupClasses.add(cls); skippedHost++; continue }
                if (!seenAcrossLibs.add(typeDesc)) { skippedDup++; continue }
            }
            nonDupClasses.add(cls)
            added++
        }

        for (cls in nonDupClasses) {
            builder.addClassProgramData(cls.readBytes(), Origin.unknown())
        }
        val nonDupAdded = added
        println("Extra lib: added $nonDupAdded classes, skipped $skippedHost dup-vs-host, $skippedDup dup-across-libs")
        if (nonDupAdded > 0) {
            builder.setOutput(tempDexDir.toPath(), OutputMode.DexIndexed)
            D8.run(builder.build())
        }

        // Supplement host DEX with missing methods/fields from duplicate library classes
        if (dupClasses.isNotEmpty()) {
            println("Extra lib: supplementing host duplicate class(es) with library methods/fields")
            supplementHostDex(dupClasses, workDir)
        }

        val produced = if (nonDupAdded > 0) {
            tempDexDir.listFiles { _, name -> name.endsWith(".dex") }?.sortedBy { it.name } ?: emptyList()
        } else emptyList()
        return produced
    }

    /**
     * Compile duplicate (host-existing) library class files to DEX and merge
     * missing methods/fields into the host DEX. Handles the case where R8
     * optimized away methods that the library at runtime expects to exist.
     */
    private fun supplementHostDex(dupClassFiles: List<File>, workDir: File) {
        val tempDir = File(outputDir, "supplement-dex-tmp").apply {
            deleteRecursively(); mkdirs()
        }
        try {
            val builder = D8Command.builder()
            builder.minApiLevel = 21
            for (cls in dupClassFiles) {
                builder.addClassProgramData(cls.readBytes(), Origin.unknown())
            }
            builder.setOutput(tempDir.toPath(), OutputMode.DexIndexed)
            D8.run(builder.build())

            val allSupplement = mutableMapOf<String, ClassDef>()
            for (dexFile in tempDir.listFiles { _, n -> n.endsWith(".dex") }.orEmpty()) {
                val store = DexFileFactory.loadDexFile(dexFile.absolutePath, Opcodes.getDefault())
                for (cls in store.classes) {
                    allSupplement[cls.type] = cls
                }
            }
            if (allSupplement.isEmpty()) return

            // Pre-filter: load all host classes and only keep supplement classes
            // that actually have methods/fields not present in the host
            val hostAll = mutableMapOf<String, ClassDef>()
            for (hf in workDir.listFiles { _, n -> n.endsWith(".dex") }.orEmpty()) {
                val s = DexFileFactory.loadDexFile(hf.absolutePath, Opcodes.getDefault())
                for (c in s.classes) hostAll[c.type] = c
            }
            val supplementMap = mutableMapOf<String, ClassDef>()
            var skippedNoDiff = 0
            for ((type, supp) in allSupplement) {
                val host = hostAll[type]
                if (host == null) { continue /* already handled by non-dup path */ }

                // 跳过类层次被 R8 改变的类：补充方法引用的父类可能不存在于宿主
                if (host.superclass != supp.superclass || host.interfaces.toSet() != supp.interfaces.toSet()) {
                    println("[supplement] Skipping $type (hierarchy changed by R8)")
                    skippedNoDiff++
                    continue
                }

                val needsMethods = supp.methods.any { methodKey(it) !in host.methods.associateBy { methodKey(it) } }
                val needsFields = supp.fields.any { fieldKey(it) !in host.fields.associateBy { fieldKey(it) } }
                if (needsMethods || needsFields) {
                    supplementMap[type] = supp
                } else {
                    skippedNoDiff++
                }
            }
            if (skippedNoDiff > 0) println("[supplement] Filtered $skippedNoDiff class(es) with no missing methods/fields")
            if (supplementMap.isEmpty()) return

            // Pass 2: 检查补充方法引用的字段在宿主+其他被补充类中是否存在。
            // 如果字段所有者也在 supplementMap 中（如 R$id），则字段在补充 DEX 中存在，视为可解析。
            val toRemove = mutableListOf<String>()
            for ((type, supp) in supplementMap) {
                if (hasUnresolvableFieldRefs(supp, hostAll, supplementMap)) {
                    println("[supplement] Removing $type from supplement (methods reference fields not in host or other supplement classes)")
                    toRemove.add(type)
                }
            }
            toRemove.forEach { supplementMap.remove(it) }
            if (supplementMap.isEmpty()) return

            // Build merged supplement classes (host + missing methods/fields from library)
            val mergedTypes = mutableMapOf<String, ClassDef>()
            for ((type, supp) in supplementMap) {
                mergedTypes[type] = mergeClassDefs(hostAll[type]!!, supp)
            }

            // Determine which host DEX files contain supplemented types
            data class DexSlot(val file: File, val slot: Int)
            val affectedDexes = mutableListOf<DexSlot>()
            for (hf in workDir.listFiles { _, n -> n.endsWith(".dex") }.orEmpty()) {
                val store = DexFileFactory.loadDexFile(hf.absolutePath, Opcodes.getDefault())
                if (store.classes.any { it.type in mergedTypes }) {
                    val slot = dexSlotNumber(hf.name)
                    affectedDexes.add(DexSlot(hf, slot))
                }
            }

            if (affectedDexes.isEmpty()) {
                println("[supplement] Warning: no host DEX files contain supplemented types")
                return
            }

            // Write all merged supplement classes to a temp DEX file
            val suppTemp = File(workDir, ".supplement-tmp")
            try {
                DexFileFactory.writeDexFile(
                    suppTemp.absolutePath,
                    object : org.jf.dexlib2.iface.DexFile {
                        override fun getClasses() = mergedTypes.values.toMutableSet()
                        override fun getOpcodes() = Opcodes.getDefault()
                    }
                )
            } catch (e: Exception) {
                println("[supplement] Warning: can't write supplement DEX: ${e.message}")
                suppTemp.delete()
                return
            }

            // Find highest existing DEX slot
            val allSlots = (workDir.listFiles { _, n -> n.endsWith(".dex") }.orEmpty())
                .mapNotNull { dexSlotNumber(it.name) }
                .toSet()
            val maxSlot = allSlots.maxOrNull() ?: 1

            // Rename affected host DEX files to HIGHER slots (past max),
            // freeing their original slots for the supplement DEX.
            var nextSlot = maxSlot + 1
            val lowestOrigSlot = affectedDexes.minOf { it.slot }
            for (dexInfo in affectedDexes) {
                val newName = if (nextSlot == 1) "classes.dex" else "classes$nextSlot.dex"
                val renamed = File(workDir, newName)
                dexInfo.file.renameTo(renamed)
                println("[supplement] Relocated ${dexInfo.file.name} → $newName")
                nextSlot++
            }

            // Place supplement DEX in the lowest freed slot so it loads FIRST
            val suppName = if (lowestOrigSlot == 1) "classes.dex" else "classes$lowestOrigSlot.dex"
            val suppFile = File(workDir, suppName)
            suppTemp.renameTo(suppFile)
            println("[supplement] Wrote ${mergedTypes.size} supplemented class(es) as $suppName (loaded before original)")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** Merge missing methods and fields from [supplement] into [host]. */
    private fun mergeClassDefs(host: ClassDef, supplement: ClassDef): ClassDef {
        val hostMethodsByKey = host.methods.associateBy { methodKey(it) }
        val methodsToAdd = supplement.methods.filter { methodKey(it) !in hostMethodsByKey }

        val hostFieldsByKey = host.fields.associateBy { fieldKey(it) }
        val fieldsToAdd = supplement.fields.filter { fieldKey(it) !in hostFieldsByKey }

        if (methodsToAdd.isEmpty() && fieldsToAdd.isEmpty()) return host

        println("[supplement] ${host.type}: +${methodsToAdd.size} method(s), +${fieldsToAdd.size} field(s)")

        return ImmutableClassDef(
            host.type,
            host.accessFlags,
            host.superclass,
            host.interfaces,
            host.sourceFile,
            host.annotations,
            host.staticFields.toList() + host.instanceFields.toList() + fieldsToAdd,
            host.methods.toList() + methodsToAdd,
        )
    }

    private fun methodKey(m: org.jf.dexlib2.iface.Method): String =
        "${m.name}(${m.parameterTypes.joinToString("")})${m.returnType}"

    private fun fieldKey(f: org.jf.dexlib2.iface.Field): String =
        "${f.name}:${f.type}"

    /** Convert a ClassDef (backed or immutable) to an immutable copy. */
    private fun toImmutable(cd: ClassDef): ImmutableClassDef = ImmutableClassDef(
        cd.type, cd.accessFlags, cd.superclass, cd.interfaces,
        cd.sourceFile, cd.annotations,
        cd.staticFields.toList() + cd.instanceFields.toList(),
        cd.methods.toList(),
    )

    /** Check if any of [supp]'s methods reference fields that don't exist in the host.
     *  R8 often removes resource ID fields (R$id, etc.) that supplemented methods may reference,
     *  causing NoSuchFieldError at runtime. Also checks [supplementMap] — if the field owner is
     *  being supplemented, the field is resolvable at runtime. */
    private fun hasUnresolvableFieldRefs(supp: ClassDef, hostAll: Map<String, ClassDef>, supplementMap: Map<String, ClassDef>): Boolean {
        for (method in supp.methods) {
            val impl = method.implementation ?: continue
            for (insn in impl.instructions) {
                if (insn !is ReferenceInstruction) continue
                val ref = insn.reference
                if (ref !is org.jf.dexlib2.iface.reference.FieldReference) continue
                val hostOwner = hostAll[ref.definingClass] ?: continue
                if (hostOwner.fields.none { it.name == ref.name && it.type == ref.type }) {
                    // Field not in host — check if the field owner is being supplemented
                    val suppOwner = supplementMap[ref.definingClass]
                    if (suppOwner == null || suppOwner.fields.none { it.name == ref.name && it.type == ref.type }) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /** 过滤不可 dex 化 / 不需要注入的 class entry。 */
    private fun isDexableEntry(name: String): Boolean {
        val lower = name.lowercase().replace('\\', '/')
        if (lower.endsWith("/module-info.class") || lower == "module-info.class") return false
        if (lower.startsWith("meta-inf/")) return false
        return lower.endsWith(".class")
    }

    private fun extractZipClasses(zip: File, dest: File) {
        ZipFile(zip).use { z ->
            z.entries().asSequence().forEach { e ->
                if (e.isDirectory) return@forEach
                val name = e.name
                val lower = name.lowercase()
                if (!name.endsWith(".class")) {
                    // aar 内部还有 classes.jar，需要进一步解压
                    if (name == "classes.jar") {
                        val tmpJar = File(dest, "_inner-${System.nanoTime()}.jar").apply { parentFile.mkdirs() }
                        z.getInputStream(e).use { input -> tmpJar.outputStream().use { input.copyTo(it) } }
                        extractZipClasses(tmpJar, dest)
                        tmpJar.delete()
                    }
                    return@forEach
                }
                // 过滤 module-info / META-INF 目录下的版本化 class
                if (lower.endsWith("/module-info.class") || lower == "module-info.class") return@forEach
                if (lower.startsWith("meta-inf/")) return@forEach
                val out = File(dest, name).apply { parentFile.mkdirs() }
                z.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    /**
     * 从 AAR 中提取 res/ 目录的资源文件到目标目录。
     * 这些资源会合并到 APK 的 res/ 目录中，使库的布局/图片等资源在运行时可用。
     */
    private fun extractAarResources(aar: File, resDest: File) {
        ZipFile(aar).use { z ->
            z.entries().asSequence().forEach { e ->
                if (e.isDirectory) return@forEach
                val name = e.name
                // 只提取 res/ 开头的资源文件，跳过 res/ 目录本身
                if (!name.startsWith("res/")) return@forEach
                val out = File(resDest, name.removePrefix("res/")).apply { parentFile.mkdirs() }
                z.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    /**
     * 从 AAR 的 AndroidManifest.xml（纯文本 XML）中提取 package 名称。
     * AAR 与 APK 不同，Manifest 是文本格式，不是二进制 AXML。
     */
    private fun extractPackageFromManifest(manifestData: ByteArray): String? {
        return try {
            val text = manifestData.toString(Charsets.UTF_8)
            // package="xxx" 或 package = "xxx"
            Regex("""package\s*=\s*"([^"]+)""").find(text)?.groupValues?.getOrNull(1)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从 AAR 中提取类文件的公共包前缀作为 R class 的包名回退方案。
     * 选取出现次数最多的非 R/BuildConfig 类的包名。
     */
    private fun derivePackageFromAarClasses(file: File): String? {
        return try {
            ZipFile(file).use { z ->
                z.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .mapNotNull { entry ->
                        val name = entry.name.removeSuffix(".class").replace('/', '.')
                        if (name.contains("R\$") || name.contains("BuildConfig")) null
                        else {
                            val dot = name.lastIndexOf('.')
                            if (dot > 0) name.substring(0, dot) else null
                        }
                    }
                    .groupBy { it }
                    .maxByOrNull { it.value.size }
                    ?.key
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从 AAR/JAR 的 R.txt 生成 R$* 类（如 R$id、R$drawable、R$styleable 等），
     * 并写入 [outputDir] 对应包路径下。
     *
     * 很多 Android AAR 在 classes.jar 中不包含 R class（因为 R.java 在消费方
     * 构建时由 aapt2 生成），但 R.txt 保留资源 ID 信息。运行时如果库代码引用
     * R$id 等类会导致 NoClassDefFoundError，这里提前生成好对应的 .class。
     *
     * 对于 R.txt 中值为 0x0 的 id 类型资源（即未分配固定值的 ID），
     * 会分配一个从 0x7F010000 开始的自增 ID。
     */
    private fun generateRClassesFromAAR(file: File, existingTypes: Set<String>, outputDir: File): List<File> {
        val result = mutableListOf<File>()
        val zipFile: ZipFile = try { ZipFile(file) } catch (_: Exception) { return result }

        zipFile.use { zf ->
            val rTxtEntry = zf.getEntry("R.txt") ?: return@use result
            val rTxtLines = try {
                zf.getInputStream(rTxtEntry).readBytes().toString(Charsets.UTF_8).lines()
            } catch (_: Exception) { return@use result }
            if (rTxtLines.isEmpty()) return@use result

            // 提取 package 名：优先从 AndroidManifest.xml，回退到类文件路径
            val filePackage = run {
                val manifestEntry = zf.getEntry("AndroidManifest.xml")
                if (manifestEntry != null) {
                    try {
                        val manifestData = zf.getInputStream(manifestEntry).readBytes()
                        extractPackageFromManifest(manifestData)
                    } catch (_: Exception) { null }
                } else null
            } ?: derivePackageFromAarClasses(file)

            if (filePackage == null) {
                println("[R-gen] Warning: can't determine package for ${file.name} — skip R class generation")
                return@use result
            }
            // 验证包名看起来是合法的 Java 包名（拒绝 XML 命名空间域名）
            if (!isValidJavaPackage(filePackage)) {
                println("[R-gen] Warning: invalid package '$filePackage' from ${file.name} — skip")
                return@use result
            }

            val packagePath = filePackage.replace('.', '/')

            // 解析 R.txt
            val intFields = mutableMapOf<String, MutableMap<String, String>>()
            val arrayFields = mutableMapOf<String, MutableMap<String, List<String>>>()

            for (line in rTxtLines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                when {
                    trimmed.startsWith("int[] ") -> {
                        val rest = trimmed.removePrefix("int[] ").trim()
                        val firstSpace = rest.indexOf(' ')
                        if (firstSpace < 0) continue
                        val innerClass = rest.substring(0, firstSpace)
                        val fieldDef = rest.substring(firstSpace + 1).trim()
                        val fieldName = Regex("""\S+""").find(fieldDef)?.value ?: continue
                        val arrayContent = fieldDef.substringAfter('{').substringBefore('}').trim()
                        val values = arrayContent.split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        if (values.isEmpty()) continue
                        arrayFields.getOrPut(innerClass) { mutableMapOf() }[fieldName] = values
                    }
                    trimmed.startsWith("int ") -> {
                        val rest = trimmed.removePrefix("int ").trim()
                        val firstSpace = rest.indexOf(' ')
                        if (firstSpace < 0) continue
                        val innerClass = rest.substring(0, firstSpace)
                        val fieldDef = rest.substring(firstSpace + 1).trim()
                        val thirdSpace = fieldDef.indexOf(' ')
                        val fieldName = if (thirdSpace < 0) fieldDef else fieldDef.substring(0, thirdSpace)
                        val fieldValue = if (thirdSpace < 0) "" else fieldDef.substring(thirdSpace + 1).trim()
                        intFields.getOrPut(innerClass) { mutableMapOf() }[fieldName] = fieldValue
                    }
                }
            }

            if (intFields.isEmpty() && arrayFields.isEmpty()) return@use result

            // 为 0x0 值分配自增 ID
            var nextId = 0x7F010000

            val allInnerNames = (intFields.keys + arrayFields.keys).toSet()
            var genCount = 0

            for (innerName in allInnerNames) {
                val internalName = "$packagePath/R\$$innerName"
                val typeDesc = "L$internalName;"
                val existsInHost = typeDesc in existingTypes

                val fields = intFields[innerName] ?: emptyMap()
                val arrays = arrayFields[innerName] ?: emptyMap()

                val cw = ClassWriter(0)
                cw.visit(AsmOpcodes.V1_8, AsmOpcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)

                // private constructor
                val init = cw.visitMethod(AsmOpcodes.ACC_PRIVATE, "<init>", "()V", null, null)
                init.visitCode()
                init.visitVarInsn(AsmOpcodes.ALOAD, 0)
                init.visitMethodInsn(AsmOpcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                init.visitInsn(AsmOpcodes.RETURN)
                init.visitMaxs(1, 1)
                init.visitEnd()

                // int fields
                for ((fieldName, rawValue) in fields) {
                    val value = when {
                        rawValue.isEmpty() || rawValue == "0" || rawValue == "0x0" -> nextId++
                        rawValue.startsWith("0x") ->
                            rawValue.removePrefix("0x").toIntOrNull(16) ?: nextId++
                        else -> rawValue.toIntOrNull() ?: nextId++
                    }
                    val fv = cw.visitField(
                        AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_STATIC or AsmOpcodes.ACC_FINAL,
                        fieldName, "I", null, value
                    )
                    fv.visitEnd()
                }

                // int[] fields with <clinit>
                if (arrays.isNotEmpty()) {
                    for ((fieldName) in arrays) {
                        val fv = cw.visitField(
                            AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_STATIC or AsmOpcodes.ACC_FINAL,
                            fieldName, "[I", null, null
                        )
                        fv.visitEnd()
                    }

                    val clinit = cw.visitMethod(AsmOpcodes.ACC_STATIC, "<clinit>", "()V", null, null)
                    clinit.visitCode()
                    for ((fieldName, values) in arrays) {
                        val intValues = values.map {
                            it.removePrefix("0x").toIntOrNull(16) ?: 0
                        }.toIntArray()

                        pushInt(clinit, intValues.size)
                        clinit.visitIntInsn(AsmOpcodes.NEWARRAY, AsmOpcodes.T_INT)
                        for ((idx, v) in intValues.withIndex()) {
                            clinit.visitInsn(AsmOpcodes.DUP)
                            pushInt(clinit, idx)
                            pushInt(clinit, v)
                            clinit.visitInsn(AsmOpcodes.IASTORE)
                        }
                        clinit.visitFieldInsn(AsmOpcodes.PUTSTATIC, internalName, fieldName, "[I")
                    }
                    clinit.visitInsn(AsmOpcodes.RETURN)
                    clinit.visitMaxs(4, 0)
                    clinit.visitEnd()
                }

                cw.visitEnd()

                val classFile = File(outputDir, "$internalName.class")
                classFile.parentFile.mkdirs()
                classFile.writeBytes(cw.toByteArray())
                result.add(classFile)
                genCount++
                if (existsInHost) {
                    println("[R-gen] Generated $typeDesc (exists in host but may have additional fields for supplement)")
                }
            }

            if (genCount > 0) {
                println("[R-gen] Generated $genCount R class(es) for $filePackage from ${file.name}")
            }
            result
        }

        return result
    }

    /** 验证字符串是否是合法的 Java 包名（拒绝 XML 命名空间域名等）。 */
    private fun isValidJavaPackage(pkg: String): Boolean {
        if (pkg.contains("://")) return false
        if (pkg.count { it == '.' } < 1) return false
        val segments = pkg.split('.')
        // 拒绝域名样式的包名（XML 命名空间工件）
        if (segments.first() in setOf("www", "ww2", "schemas")) return false
        // 拒绝包含 HTML 标签、空格等
        if (!segments.all { it.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")) }) return false
        // 拒绝单段过长
        if (segments.any { it.length > 64 }) return false
        return true
    }

    /** ASM 助手：push int 常量到栈，适配各种范围。 */
    private fun pushInt(mv: org.objectweb.asm.MethodVisitor, value: Int) {
        when (value) {
            0 -> mv.visitInsn(AsmOpcodes.ICONST_0)
            1 -> mv.visitInsn(AsmOpcodes.ICONST_1)
            2 -> mv.visitInsn(AsmOpcodes.ICONST_2)
            3 -> mv.visitInsn(AsmOpcodes.ICONST_3)
            4 -> mv.visitInsn(AsmOpcodes.ICONST_4)
            5 -> mv.visitInsn(AsmOpcodes.ICONST_5)
            -1 -> mv.visitInsn(AsmOpcodes.ICONST_M1)
            in -128..127 -> mv.visitIntInsn(AsmOpcodes.BIPUSH, value)
            in -32768..32767 -> mv.visitIntInsn(AsmOpcodes.SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }

    /** 读取 class 文件的 this_class 常量 → "Lcom/foo/Bar;"。 */
    private fun classFileToTypeDescriptor(classFile: File): String? {
        return try {
            val cn = org.objectweb.asm.ClassReader(classFile.readBytes()).className
            "L$cn;"
        } catch (_: Throwable) {
            // ASM 不可用时退化：用文件相对路径推断（不准确，但聊胜于无）
            null
        }
    }

    private fun applyInjection(
        method: org.jf.dexlib2.iface.Method,
        metadata: com.dudu.mixin.compiler.MixinMetaData,
        inj: com.dudu.mixin.compiler.InjectionData
    ): org.jf.dexlib2.iface.Method {
        val originalImpl = method.implementation ?: return method

        val mixinClassType = "L${metadata.mixinClassName.replace(".", "/")};"

        // 解析 Mixin 方法描述符：(params)ret
        val (mixinParamTypes, mixinReturnType) = parseDescriptor(inj.mixinMethodDescriptor)

        val methodRef = ImmutableMethodReference(
            mixinClassType, inj.mixinMethodName, mixinParamTypes, mixinReturnType
        )

        // ── REDIRECT：把目标方法体内对 at.target 描述的方法/字段调用换成对 Mixin 静态方法的调用 ──
        if (inj.type == "Redirect") {
            return applyRedirect(method, originalImpl, inj, methodRef)
        }

        val isStaticTarget = (method.accessFlags and org.jf.dexlib2.AccessFlags.STATIC.value) != 0
        val targetParamRegCount = method.parameters.sumOf { wordSize(it.type) }
        val totalParamSlots = targetParamRegCount + (if (isStaticTarget) 0 else 1)  // this + explicit params

        // ── 寄存器污染防护 ──
        // 非 HEAD 注入时，参数寄存器（p0, p1, ...）在方法体执行过程中可能被后续代码覆写
        // （如 Kotlin inline 函数复用寄存器槽位）。
        //
        // 修复方案：
        //   1. 提升 registerCount（+totalParamSlots），使新参数寄存器落到原始寄存器范围之外，
        //      原始代码的写操作（寄存器 < originalRegCount）不会触及新参数槽位。
        //   2. 在方法入口从新参数寄存器复制到旧参数槽位，确保原始字节码（引用旧参数槽位）
        //      能通过 ART 验证器。
        //   3. invoke-static 使用新参数寄存器（始终持有原始参数值）。
        val needsPollutionFix = inj.at.value != "HEAD"
        val effectiveRegCount = if (needsPollutionFix) {
            originalImpl.registerCount + totalParamSlots
        } else {
            originalImpl.registerCount
        }

        val firstParamReg = effectiveRegCount - targetParamRegCount - (if (isStaticTarget) 0 else 1)

        // 构造 invoke-static：
        //   1) hasReceiver=true → 将 target 实例 this 作为 handler 首参传入
        //   2) 剩余 handler 形参与 target 方法实参按位置匹配（跳过 CallbackInfo）
        val invokeArgRegs = mutableListOf<Int>()
        if (!isStaticTarget && inj.hasReceiver) {
            invokeArgRegs.add(firstParamReg)  // target's this
        }

        // 跳过 this 寄存器槽位，从 target 第一个参数开始映射
        val targetParamStart = firstParamReg + (if (isStaticTarget) 0 else 1)
        val handlerParamCount = mixinParamTypes.size - (if (inj.hasCallbackInfo) 1 else 0)
        val paramsToTake = if (inj.hasReceiver) handlerParamCount - 1 else handlerParamCount
        val targetParamList = method.parameters.toList()

        var reg = targetParamStart
        for (i in 0 until minOf(paramsToTake, targetParamList.size)) {
            invokeArgRegs.add(reg)
            reg += wordSize(targetParamList[i].type)
        }

        // ── 构建参数初始化指令（仅非 HEAD 注入需要）──
        // 在方法入口将新参数寄存器值复制到旧参数槽位，使原始字节码（引用旧槽位）
        // 能正确通过验证器，同时保证 invoke-static 使用的新参数寄存器不被污染。
        val initInstructions = mutableListOf<Instruction>()
        if (needsPollutionFix) {
            val oldBase = originalImpl.registerCount - totalParamSlots
            val newBase = effectiveRegCount - totalParamSlots
            val paramTypes = mutableListOf<String>()
            if (!isStaticTarget) paramTypes.add("L")  // this
            method.parameters.forEach { paramTypes.add(it.type) }

            var oldReg = oldBase
            var newReg = newBase
            for (ptype in paramTypes) {
                when {
                    ptype == "J" || ptype == "D" -> {
                        initInstructions.add(buildMove(Opcode.MOVE_WIDE, oldReg, newReg))
                        oldReg += 2; newReg += 2
                    }
                    else -> {
                        initInstructions.add(buildMove(Opcode.MOVE_OBJECT, oldReg, newReg))
                        oldReg += 1; newReg += 1
                    }
                }
            }
        }

        val invokeInstr = buildInvokeStatic(invokeArgRegs, methodRef)

        // 计算插入位置
        val originalList = originalImpl.instructions.toList()
        val newInstructions = mutableListOf<Instruction>()

        when (inj.at.value) {
            "HEAD" -> {
                newInstructions.add(invokeInstr)
                newInstructions.addAll(originalList)
            }
            "RETURN", "TAIL" -> {
                newInstructions.addAll(initInstructions)
                val returnIdx = originalList.mapIndexedNotNull { i, ins ->
                    if (isReturnOpcode(ins.opcode) || (inj.at.value == "TAIL" && ins.opcode == Opcode.THROW)) i else null
                }
                val ord = inj.at.ordinal
                val targets = if (ord < 0) returnIdx.toSet()
                    else if (ord < returnIdx.size) setOf(returnIdx[ord]) else emptySet()
                val shiftAfter = inj.at.shift == "AFTER"
                originalList.forEachIndexed { i, ins ->
                    val hit = i in targets
                    if (hit && !shiftAfter) newInstructions.add(invokeInstr)
                    newInstructions.add(ins)
                    if (hit && shiftAfter) newInstructions.add(invokeInstr)
                }
            }
            "INVOKE", "FIELD_GET", "FIELD_SET", "CONSTANT" -> {
                val matchIdx = findMatchingIndices(originalList, inj.at)
                val ord = inj.at.ordinal
                val targets = if (ord < 0) matchIdx.toSet()
                    else if (ord >= 0 && ord < matchIdx.size) setOf(matchIdx[ord]) else emptySet()
                if (targets.isEmpty()) {
                    println("[mixin] At ${inj.at.value} target='${inj.at.target}' not found in ${method.definingClass}->${method.name}, fallback to HEAD")
                    newInstructions.add(invokeInstr)
                    newInstructions.addAll(originalList)
                } else {
                    newInstructions.addAll(initInstructions)
                    val shiftAfter = inj.at.shift == "AFTER"
                    originalList.forEachIndexed { i, ins ->
                        val hit = i in targets
                        if (hit && !shiftAfter) newInstructions.add(invokeInstr)
                        newInstructions.add(ins)
                        if (hit && shiftAfter) newInstructions.add(invokeInstr)
                    }
                    println("[mixin] At ${inj.at.value} matched ${targets.size} site(s) in ${method.definingClass}->${method.name}")
                }
            }
            else -> {
                // 其他注入点 (JUMP 等) 暂未支持，回退为 HEAD
                println("[mixin] At ${inj.at.value} not yet supported, fallback to HEAD")
                newInstructions.add(invokeInstr)
                newInstructions.addAll(originalList)
            }
        }

        val newRegCount = effectiveRegCount

        val newImpl = ImmutableMethodImplementation(
            newRegCount,
            newInstructions,
            originalImpl.tryBlocks,
            originalImpl.debugItems
        )

        return ImmutableMethod(
            method.definingClass,
            method.name,
            method.parameters,
            method.returnType,
            method.accessFlags,
            method.annotations,
            method.hiddenApiRestrictions,
            newImpl
        )
    }

    /**
     * @Redirect: 在目标方法字节码里寻找 invoke-* 指令，其引用方法签名匹配 [com.dudu.mixin.compiler.AtData.target]
     * 时改写为 invoke-static Mixin.method(...)。要求 Mixin 方法的参数列表与原 invoke 完全一致
     * （非静态调用还要保留 receiver 作为首参）。
     */
    private fun applyRedirect(
        method: org.jf.dexlib2.iface.Method,
        originalImpl: org.jf.dexlib2.iface.MethodImplementation,
        inj: com.dudu.mixin.compiler.InjectionData,
        methodRef: ImmutableMethodReference,
    ): org.jf.dexlib2.iface.Method {
        val targetSig = inj.at.target
        val targetMatch = parseInvokeTarget(targetSig)
        if (targetMatch == null) {
            println("[mixin] Redirect target signature unparseable: $targetSig — skipping")
            return method
        }

        var hits = 0
        val ord = inj.at.ordinal
        val newInstructions = mutableListOf<Instruction>()
        for (ins in originalImpl.instructions) {
            val redirected = tryRedirectInstruction(ins, targetMatch, methodRef, hits, ord)
            if (redirected != null) {
                newInstructions.add(redirected)
                hits++
            } else {
                newInstructions.add(ins)
            }
        }
        if (hits == 0) {
            println("[mixin] Redirect: no INVOKE matching $targetSig found in ${method.definingClass}->${method.name}")
        } else {
            println("[mixin] Redirect: rewrote $hits INVOKE site(s) in ${method.definingClass}->${method.name}")
        }

        val newImpl = ImmutableMethodImplementation(
            originalImpl.registerCount,
            newInstructions,
            originalImpl.tryBlocks,
            originalImpl.debugItems,
        )
        return ImmutableMethod(
            method.definingClass,
            method.name,
            method.parameters,
            method.returnType,
            method.accessFlags,
            method.annotations,
            method.hiddenApiRestrictions,
            newImpl,
        )
    }

    private data class InvokeTargetSig(val owner: String, val name: String, val desc: String)

    /** 解析 "Lowner;name(args)ret" 形式。 */
    private fun parseInvokeTarget(raw: String): InvokeTargetSig? {
        if (!raw.startsWith("L")) return null
        val semi = raw.indexOf(';')
        if (semi < 0) return null
        val owner = raw.substring(0, semi + 1)
        val rest = raw.substring(semi + 1)
        val paren = rest.indexOf('(')
        if (paren <= 0) return null
        return InvokeTargetSig(owner, rest.substring(0, paren), rest.substring(paren))
    }

    /** 若指令是匹配的 invoke-*，则返回改写后的 invoke-static；否则返回 null。 */
    private fun tryRedirectInstruction(
        ins: Instruction,
        match: InvokeTargetSig,
        newRef: ImmutableMethodReference,
        hitsSoFar: Int,
        ordinal: Int,
    ): Instruction? {
        if (ins !is ReferenceInstruction) return null
        val ref = ins.reference as? MethodReference ?: return null
        if (ref.definingClass != match.owner) return null
        if (ref.name != match.name) return null
        val refDesc = "(${ref.parameterTypes.joinToString("")})${ref.returnType}"
        if (refDesc != match.desc) return null
        if (ordinal >= 0 && hitsSoFar != ordinal) return null

        return when (ins) {
            is Instruction35c -> ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                ins.registerCount,
                ins.registerC, ins.registerD, ins.registerE, ins.registerF, ins.registerG,
                newRef,
            )
            is Instruction3rc -> ImmutableInstruction3rc(
                Opcode.INVOKE_STATIC_RANGE,
                ins.startRegister,
                ins.registerCount,
                newRef,
            )
            else -> null
        }
    }

    private fun buildInvokeStatic(
        argRegs: List<Int>,
        methodRef: ImmutableMethodReference,
    ): Instruction {
        // 35c 支持 0..5 个寄存器；超过则使用 3rc
        return if (argRegs.size <= 5 && (argRegs.maxOrNull() ?: 0) < 16) {
            val regs = IntArray(5)
            argRegs.forEachIndexed { i, r -> regs[i] = r }
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                argRegs.size,
                regs[0], regs[1], regs[2], regs[3], regs[4],
                methodRef
            )
        } else {
            val start = argRegs.firstOrNull() ?: 0
            org.jf.dexlib2.immutable.instruction.ImmutableInstruction3rc(
                Opcode.INVOKE_STATIC_RANGE,
                start,
                argRegs.size,
                methodRef
            )
        }
    }

    /**
     * 根据目标/源寄存器范围选择最优 move 指令格式：
     *  - 两者均 < 16：12x（move / move-wide / move-object）
     *  - 否则：22x（move/from16 / move-wide/from16 / move-object/from16）
     */
    private fun buildMove(opcode: Opcode, dest: Int, src: Int): Instruction {
        return if (dest < 16 && src < 16) {
            ImmutableInstruction12x(opcode, dest, src)
        } else {
            val from16Op = when (opcode) {
                Opcode.MOVE -> Opcode.MOVE_FROM16
                Opcode.MOVE_WIDE -> Opcode.MOVE_WIDE_FROM16
                Opcode.MOVE_OBJECT -> Opcode.MOVE_OBJECT_FROM16
                else -> Opcode.MOVE_OBJECT_FROM16
            }
            ImmutableInstruction22x(from16Op, dest, src)
        }
    }

    private fun isReturnOpcode(op: Opcode): Boolean = when (op) {
        Opcode.RETURN_VOID,
        Opcode.RETURN,
        Opcode.RETURN_WIDE,
        Opcode.RETURN_OBJECT -> true
        else -> false
    }

    /**
     * 根据 [com.dudu.mixin.compiler.AtData] 在指令列表中查找所有匹配位置。
     *  - INVOKE     : 匹配 invoke-* 指令；at.target 形如 "Lowner;name(args)ret"
     *  - FIELD_GET  : 匹配 iget-* / sget-* 指令；at.target 形如 "Lowner;name:type"
     *  - FIELD_SET  : 匹配 iput-* / sput-* 指令；at.target 形如 "Lowner;name:type"
     *  - CONSTANT   : 匹配 const-string / const / const-wide 等加载常量指令；at.target 用于精确字符串匹配（可省略）
     */
    private fun findMatchingIndices(
        instructions: List<Instruction>,
        at: AtData,
    ): List<Int> {
        val result = mutableListOf<Int>()
        when (at.value) {
            "INVOKE" -> {
                val sig = parseInvokeTarget(at.target) ?: return emptyList()
                for ((i, ins) in instructions.withIndex()) {
                    if (!isInvokeOpcode(ins.opcode)) continue
                    val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    if (ref.definingClass != sig.owner) continue
                    if (ref.name != sig.name) continue
                    val refDesc = "(${ref.parameterTypes.joinToString("")})${ref.returnType}"
                    if (refDesc != sig.desc) continue
                    result.add(i)
                }
            }
            "FIELD_GET" -> {
                val (owner, name, type) = parseFieldTarget(at.target) ?: return emptyList()
                for ((i, ins) in instructions.withIndex()) {
                    if (!isFieldGetOpcode(ins.opcode)) continue
                    val ref = (ins as? ReferenceInstruction)?.reference as? org.jf.dexlib2.iface.reference.FieldReference ?: continue
                    if (ref.definingClass == owner && ref.name == name && ref.type == type) result.add(i)
                }
            }
            "FIELD_SET" -> {
                val (owner, name, type) = parseFieldTarget(at.target) ?: return emptyList()
                for ((i, ins) in instructions.withIndex()) {
                    if (!isFieldSetOpcode(ins.opcode)) continue
                    val ref = (ins as? ReferenceInstruction)?.reference as? org.jf.dexlib2.iface.reference.FieldReference ?: continue
                    if (ref.definingClass == owner && ref.name == name && ref.type == type) result.add(i)
                }
            }
            "CONSTANT" -> {
                val needle = at.target
                for ((i, ins) in instructions.withIndex()) {
                    if (!isConstOpcode(ins.opcode)) continue
                    if (needle.isEmpty()) { result.add(i); continue }
                    val ref = (ins as? ReferenceInstruction)?.reference as? org.jf.dexlib2.iface.reference.StringReference
                    if (ref != null && ref.string == needle) result.add(i)
                }
            }
        }
        return result
    }

    private fun parseFieldTarget(raw: String): Triple<String, String, String>? {
        // 形如 "Lowner;name:type"
        if (!raw.startsWith("L")) return null
        val semi = raw.indexOf(';')
        if (semi < 0) return null
        val owner = raw.substring(0, semi + 1)
        val rest = raw.substring(semi + 1)
        val colon = rest.indexOf(':')
        if (colon <= 0) return null
        return Triple(owner, rest.substring(0, colon), rest.substring(colon + 1))
    }

    private fun isInvokeOpcode(op: Opcode): Boolean = when (op) {
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_SUPER, Opcode.INVOKE_DIRECT,
        Opcode.INVOKE_STATIC, Opcode.INVOKE_INTERFACE,
        Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_SUPER_RANGE,
        Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_STATIC_RANGE,
        Opcode.INVOKE_INTERFACE_RANGE -> true
        else -> false
    }

    private fun isFieldGetOpcode(op: Opcode): Boolean = when (op) {
        Opcode.IGET, Opcode.IGET_WIDE, Opcode.IGET_OBJECT,
        Opcode.IGET_BOOLEAN, Opcode.IGET_BYTE, Opcode.IGET_CHAR, Opcode.IGET_SHORT,
        Opcode.SGET, Opcode.SGET_WIDE, Opcode.SGET_OBJECT,
        Opcode.SGET_BOOLEAN, Opcode.SGET_BYTE, Opcode.SGET_CHAR, Opcode.SGET_SHORT -> true
        else -> false
    }

    private fun isFieldSetOpcode(op: Opcode): Boolean = when (op) {
        Opcode.IPUT, Opcode.IPUT_WIDE, Opcode.IPUT_OBJECT,
        Opcode.IPUT_BOOLEAN, Opcode.IPUT_BYTE, Opcode.IPUT_CHAR, Opcode.IPUT_SHORT,
        Opcode.SPUT, Opcode.SPUT_WIDE, Opcode.SPUT_OBJECT,
        Opcode.SPUT_BOOLEAN, Opcode.SPUT_BYTE, Opcode.SPUT_CHAR, Opcode.SPUT_SHORT -> true
        else -> false
    }

    private fun isConstOpcode(op: Opcode): Boolean = when (op) {
        Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO,
        Opcode.CONST, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_HIGH16,
        Opcode.CONST_WIDE, Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE_HIGH16,
        Opcode.CONST_CLASS -> true
        else -> false
    }

    private fun wordSize(type: String): Int = when (type) {
        "J", "D" -> 2
        else -> 1
    }

    /** Parse `(params)ret` JVM descriptor into (paramTypes, ret). */
    private fun parseDescriptor(desc: String): Pair<List<String>, String> {
        if (!desc.startsWith("(")) return emptyList<String>() to "V"
        val end = desc.indexOf(')')
        if (end < 0) return emptyList<String>() to "V"
        val params = mutableListOf<String>()
        var i = 1
        while (i < end) {
            val start = i
            while (desc[i] == '[') i++
            when (desc[i]) {
                'L' -> { i = desc.indexOf(';', i) + 1 }
                else -> i++
            }
            params.add(desc.substring(start, i))
        }
        return params to desc.substring(end + 1)
    }

    private fun unzipApk(apkFile: File, destDir: File) {
        ZipFile(apkFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    /**
     * 以原始 APK 为基准重建 ZIP：只替换被插件修改过的条目（DEX、AndroidManifest.xml），
     * 其余条目（含大小写敏感的文件名）直接从原始 APK 拷贝，避免 Windows 不区分文件名大小写导致资源丢失。
     */
    private fun zipApk(srcDir: File, destZipFile: File, originalApk: File) {
        val crc32 = java.util.zip.CRC32()
        ZipOutputStream(FileOutputStream(destZipFile)).use { zos ->
            // 从原始 APK 拷贝所有条目，只替换被修改的
            ZipFile(originalApk).use { orig ->
                orig.entries().asSequence().forEach { entry ->
                    val name = entry.name
                    val isReplaced = name.endsWith(".dex") || name == "AndroidManifest.xml"
                    val newEntry = ZipEntry(name)
                    newEntry.method = entry.method

                    if (isReplaced) {
                        val diskFile = File(srcDir, name)
                        if (!diskFile.exists() && name.endsWith(".dex")) {
                            // DEX 文件已被 supplement 方案重命名到更高槽位，跳过原始 APK 条目。
                            // 重命名后的文件会在下一轮作为新文件添加。
                            return@forEach
                        }
                        val data = if (diskFile.exists() && diskFile.isFile) {
                            diskFile.readBytes()
                        } else {
                            orig.getInputStream(entry).readAllBytes()
                        }
                        if (entry.method == ZipEntry.STORED) {
                            newEntry.size = data.size.toLong()
                            newEntry.compressedSize = data.size.toLong()
                            crc32.reset(); crc32.update(data)
                            newEntry.crc = crc32.value
                        }
                        zos.putNextEntry(newEntry)
                        zos.write(data)
                    } else {
                        // 直接从原始 APK 拷贝（保留原始文件名大小写和压缩方式）
                        if (entry.method == ZipEntry.STORED) {
                            newEntry.size = entry.size
                            newEntry.compressedSize = entry.compressedSize
                            newEntry.crc = entry.crc
                        }
                        zos.putNextEntry(newEntry)
                        orig.getInputStream(entry).use { it.copyTo(zos) }
                    }
                    zos.closeEntry()
                }
            }

            // 补充原始 APK 中没有的新文件（如 injectMixins 新增的 classesN.dex）
            val originalNames = mutableSetOf<String>().apply {
                ZipFile(originalApk).use { orig ->
                    orig.entries().asSequence().forEach { add(it.name) }
                }
            }
            srcDir.walkTopDown().forEach { file ->
                if (!file.isFile) return@forEach
                val entryName = file.relativeTo(srcDir).path.replace("\\", "/")
                if (entryName in originalNames) return@forEach
                val newEntry = ZipEntry(entryName)
                newEntry.method = if (entryName.endsWith(".dex") || entryName.startsWith("res/") || entryName == "resources.arsc") {
                    ZipEntry.STORED
                } else {
                    ZipEntry.DEFLATED
                }
                val data = file.readBytes()
                if (newEntry.method == ZipEntry.STORED) {
                    crc32.reset(); crc32.update(data)
                    newEntry.crc = crc32.value
                    newEntry.size = data.size.toLong()
                    newEntry.compressedSize = data.size.toLong()
                }
                zos.putNextEntry(newEntry)
                zos.write(data)
                zos.closeEntry()
            }
        }
    }
}

/**
 * 从目标 APK 的 DEX 中读取所有应用类，生成空的 .class stub，
 * 打包为 JAR 供 Mixin 模块在编译期引用。
 *
 * 生成的 stub 只保留类层次结构 + 方法/字段签名（无方法体），
 * 不会与任何运行时依赖冲突（compileOnly 作用域）。
 */
abstract class GenerateTargetStubs : DefaultTask() {
    @get:InputFile
    abstract val targetApk: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    /** 范围过滤：只对匹配这些前缀的类生成 stub（空 = 生成所有非系统类的 stub）。 */
    @get:Input
    abstract val stubIncludePrefixes: ListProperty<String>

    /** 系统包前缀 — 这些类已存在于 Android SDK / JDK 中，无需生成 stub */
    /** 系统包前缀 — 这些类已存在于 Android SDK / JDK 中，无需生成 stub */
    private val systemPrefixes = listOf(
        "android/", "androidx/", "java/", "javax/", "kotlin/", "kotlinx/",
        "dalvik/", "com/android/", "org/apache/", "org/json/",
    )

    /** Bootclasspath 前缀 — 这些类在 android.jar / JDK 上，编译期必定可用。 */
    private val bootclasspathPrefixes = listOf(
        "android/", "java/", "javax/", "dalvik/",
    )

    @TaskAction
    fun generate() {
        val apk = targetApk.orNull?.asFile
        if (apk == null || !apk.exists()) {
            println("[mixin-stubs] targetApk not set or file not found — skipping stub generation")
            return
        }

        val jar = outputJar.get().asFile
        jar.parentFile.mkdirs()

        val includePrefixes = stubIncludePrefixes.getOrElse(emptyList()).map { it.replace('.', '/') }
        // empty = all non-system

        // 第一遍：读取所有 DEX 中的全部类定义（不过滤），同时维护一个应用类子集用于 stub 生成
        val rawClassDefs = linkedMapOf<String, ClassDef>()  // 全部类（含系统），用于 super 链解析
        val appClassDefs = linkedMapOf<String, ClassDef>()  // 非系统 + 匹配范围前缀 → 实际生成 stub

        ZipFile(apk).use { zip ->
            val dexEntries = zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) && !it.isDirectory }
                .toList()

            if (dexEntries.isEmpty()) {
                println("[mixin-stubs] No DEX files found in ${apk.absolutePath}")
                return
            }

            for (entry in dexEntries) {
                val dexData = zip.getInputStream(entry).readBytes()
                val tempDex = File.createTempFile("dex-stub-", ".dex").apply { deleteOnExit() }
                try {
                    tempDex.writeBytes(dexData)
                    val dexFile = DexFileFactory.loadDexFile(tempDex, org.jf.dexlib2.Opcodes.getDefault())
                    for (classDef in dexFile.classes) {
                        val internalName = classDef.type.removePrefix("L").removeSuffix(";")
                        if (internalName in rawClassDefs) continue
                        rawClassDefs[internalName] = classDef

                        // 只有非系统类且匹配范围时才生成 stub
                        if (systemPrefixes.any { internalName.startsWith(it) }) continue
                        if (includePrefixes.isNotEmpty() && includePrefixes.none { internalName.startsWith(it) }) continue
                        appClassDefs[internalName] = classDef
                    }
                } finally {
                    tempDex.delete()
                }
            }
        }

        if (appClassDefs.isEmpty()) {
            println("[mixin-stubs] No stubs generated (all classes match system or scope filter)")
            JarOutputStream(FileOutputStream(jar)).use { /* empty jar */ }
            return
        }

        // 第二遍：为每个类计算有效父类。
        // 使用 rawClassDefs（全部类）沿继承链向上走，直到遇到 bootclasspath 上的类为止。
        val visited = hashMapOf<String, String?>()  // internalName → resolvedSuper (null = Object)

        fun effectiveSuper(internal: String): String? {
            visited[internal]?.let { return it }
            if (internal == "java/lang/Object") return null
            if (bootclasspathPrefixes.any { internal.startsWith(it) }) return internal

            val def = rawClassDefs[internal] ?: return null
            val superName = def.superclass?.removePrefix("L")?.removeSuffix(";") ?: return null
            val resolved = effectiveSuper(superName)
            visited[internal] = resolved
            return resolved
        }

        val stubs = linkedMapOf<String, ByteArray>()
        for ((internalName, classDef) in appClassDefs) {
            val superName = if (classDef.superclass != null) {
                val rawSuper = classDef.superclass!!.removePrefix("L").removeSuffix(";")
                effectiveSuper(rawSuper) ?: "java/lang/Object"
            } else "java/lang/Object"
            stubs[internalName] = generateStub(classDef, superName)
        }

        JarOutputStream(FileOutputStream(jar)).use { jos ->
            for ((name, bytes) in stubs) {
                jos.putNextEntry(JarEntry("$name.class"))
                jos.write(bytes)
                jos.closeEntry()
            }
        }

        println("[mixin-stubs] Generated ${stubs.size} stubs -> ${jar.absolutePath}")
    }

    private fun generateStub(classDef: ClassDef, effectiveSuperName: String): ByteArray {
        val internalName = classDef.type.removePrefix("L").removeSuffix(";")
        val interfaces = classDef.interfaces.map { it.removePrefix("L").removeSuffix(";") }.toTypedArray()

        // 构造空类骨架：保留类层次，方法/字段签名
        val cw = ClassWriter(0)
        cw.visit(
            AsmOpcodes.V1_8,
            (classDef.accessFlags and 0xFFFF) or AsmOpcodes.ACC_PUBLIC,
            internalName, null,
            effectiveSuperName,
            interfaces,
        )

        // 字段：保留签名
        for (field in classDef.fields) {
            cw.visitField(
                (field.accessFlags and 0xFFFF) or AsmOpcodes.ACC_PUBLIC,
                field.name, field.type, null, null,
            )?.visitEnd()
        }

        // 方法：生成空方法体（返回类型对应的默认值）
        for (method in classDef.methods) {
            val access = (method.accessFlags and 0xFFFF) or AsmOpcodes.ACC_PUBLIC
            val desc = "(${method.parameterTypes.joinToString("")})${method.returnType}"
            val mw = cw.visitMethod(access, method.name, desc, null, null)
            mw.visitCode()
            val retType = method.returnType.toString()
            when {
                retType == "V" -> mw.visitInsn(AsmOpcodes.RETURN)
                retType in setOf("I", "Z", "B", "C", "S") -> {
                    mw.visitInsn(AsmOpcodes.ICONST_0); mw.visitInsn(AsmOpcodes.IRETURN)
                }
                retType == "J" -> { mw.visitInsn(AsmOpcodes.LCONST_0); mw.visitInsn(AsmOpcodes.LRETURN) }
                retType == "F" -> { mw.visitInsn(AsmOpcodes.FCONST_0); mw.visitInsn(AsmOpcodes.FRETURN) }
                retType == "D" -> { mw.visitInsn(AsmOpcodes.DCONST_0); mw.visitInsn(AsmOpcodes.DRETURN) }
                else -> { mw.visitInsn(AsmOpcodes.ACONST_NULL); mw.visitInsn(AsmOpcodes.ARETURN) }
            }
            mw.visitMaxs(2, 1)
            mw.visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }
}
