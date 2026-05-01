package com.dudu.mixin.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import java.io.File
import java.util.zip.ZipFile
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
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
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction3rc
import org.jf.dexlib2.rewriter.Rewriter

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

            val kotlinCompileTasks = project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java)
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
        zipApk(workDir, unsignedApk)

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

        // 2. 第三方库 → DEX，并去重已存在的 type
        val extraLibDexFiles = prepareExtraLibrariesDex(existingTypes)
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

        // 透传所有参数（含 receiver）给 Mixin 静态方法
        val argRegs = mutableListOf<Int>()
        var reg = 0
        if (!isStatic && inj.hasReceiver) {
            argRegs.add(reg)
            reg++
        } else if (!isStatic) {
            reg++ // skip this
        }
        for (p in method.parameters) {
            argRegs.add(reg)
            reg += wordSize(p.type)
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
    private fun prepareExtraLibrariesDex(existingTypes: Set<String>): List<File> {
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
        val seenAcrossLibs = mutableSetOf<String>()
        for (cls in collectedClasses) {
            val typeDesc = classFileToTypeDescriptor(cls)
            if (typeDesc != null) {
                if (typeDesc in existingTypes) { skippedHost++; continue }
                if (!seenAcrossLibs.add(typeDesc)) { skippedDup++; continue }
            }
            builder.addClassProgramData(cls.readBytes(), Origin.unknown())
            added++
        }
        println("Extra lib: added $added classes, skipped $skippedHost dup-vs-host, $skippedDup dup-across-libs")
        if (added == 0) return emptyList()

        builder.setOutput(tempDexDir.toPath(), OutputMode.DexIndexed)
        D8.run(builder.build())

        val produced = tempDexDir.listFiles { _, name -> name.endsWith(".dex") }?.sortedBy { it.name }
            ?: emptyList()
        return produced
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
        val firstParamReg = originalImpl.registerCount - targetParamRegCount - (if (isStaticTarget) 0 else 1)

        // 构造 invoke-static：如果 hasReceiver，则把 this(p0) 作为参数传入
        val invokeArgRegs = mutableListOf<Int>()
        if (inj.hasReceiver && !isStaticTarget) {
            invokeArgRegs.add(firstParamReg)  // this
        }
        // 注：CallbackInfo / 其它额外参数的真正构造留给后续任务，这里只匹配最基础形态

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
                    // 默认行为：BEFORE 指令前插入；AFTER：指令后插入
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

        // 确保寄存器数量足以容纳调用所需寄存器（35c 最多 5 个低位寄存器 v0..v15）
        val maxArgReg = invokeArgRegs.maxOrNull() ?: -1
        val newRegCount = if (maxArgReg >= 16) {
            // 需要 3rc（range）；目前我们没有用 range 形式，简单提升 registerCount 不能解决
            // 但保险起见保持原值
            originalImpl.registerCount
        } else originalImpl.registerCount

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

    private fun zipApk(srcDir: File, destZipFile: File) {
        ZipOutputStream(FileOutputStream(destZipFile)).use { zos ->
            srcDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val entryName = file.relativeTo(srcDir).path.replace("\\", "/")
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }
}
