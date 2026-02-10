package com.example.mixin.plugin

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
import org.jf.dexlib2.rewriter.Rewriter

import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import com.example.mixin.compiler.MixinMetaData
import com.example.mixin.compiler.InjectionData
import com.example.mixin.compiler.AtData

interface ApkPatcherExtension {
    val targetApk: RegularFileProperty
    val mixinClasses: ConfigurableFileCollection
    val enableResourceHook: Property<Boolean>
}

class ApkPatcherPlugin : Plugin<Project>, KotlinCompilerPluginSupportPlugin {
    override fun apply(project: Project) {
        val extension = project.extensions.create(ApkPatcherExtension::class.java, "apkPatcher", ApkPatcherExtension::class.java)
        extension.enableResourceHook.convention(false)

        val patchTask = project.tasks.register("patchApk", PatchApkTask::class.java) {
            val task = this
            task.targetApk.set(extension.targetApk)
            task.mixinClasses.from(extension.mixinClasses)
            
            val kotlinCompileTasks = project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java)
            task.mixinClasses.from(kotlinCompileTasks.map { t -> t.destinationDirectory })
            
            task.enableResourceHook.set(extension.enableResourceHook)
            task.group = "build"
            task.description = "Patches the target APK with Mixins"
            task.dependsOn(kotlinCompileTasks)
        }

        project.afterEvaluate {
            val runtimeConfig = project.configurations.findByName("runtimeClasspath")
            if (runtimeConfig != null) {
                patchTask.configure {
                    mixinClasses.from(runtimeConfig)
                }
            }
        }
    }

    override fun isApplicable(kotlinCompilation: org.jetbrains.kotlin.gradle.plugin.KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = "com.example.mixin-transformer"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "com.example",
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

    @get:Input
    abstract val enableResourceHook: Property<Boolean>

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
        
        // 5. 签名 APK
        signApk(unsignedApk, patchedApk)
        println("Patched APK created: ${patchedApk.absolutePath}")
    }

    private fun signApk(unsignedApk: File, signedApk: File) {
        println("Signing APK: ${unsignedApk.absolutePath}")
        // 实际开发中这里会调用 com.android.apksig.ApkSigner
        // 为了演示，我们先简单复制，并打印说明
        println("Signing process completed (mocked for environment)")
        unsignedApk.copyTo(signedApk, overwrite = true)
    }

    private fun handleResourceHook(workDir: File) {
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
        
        val arscFile = File(workDir, "resources.arsc")
        if (arscFile.exists()) {
            println("resources.arsc found, ready for advanced hooking (stub)")
        }
    }

    private fun injectMixins(workDir: File) {
        val metadataDir = project.layout.buildDirectory.dir("mixin-metadata").get().asFile
        if (!metadataDir.exists()) {
            println("No mixin metadata found in ${metadataDir.absolutePath}")
            return
        }
        
        val gson = com.google.gson.Gson()
        val metadataFiles = metadataDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        val allMetadata = metadataFiles.flatMap { file ->
            val content = file.readText()
            println("Metadata file content: $content")
            gson.fromJson(content, Array<com.example.mixin.compiler.MixinMetaData>::class.java).toList()
        }
        
        if (allMetadata.isEmpty()) {
            println("Metadata list is empty")
            return
        }

        // 1. 将所有的 Mixin 类编译成一个 DEX 文件并放入 APK
        val mixinDexFile = prepareMixinDex()
        if (mixinDexFile != null) {
            val dexFiles = workDir.listFiles { _, name -> name.endsWith(".dex") } ?: emptyArray()
            val targetName = if (dexFiles.isEmpty()) "classes.dex" else "classes${dexFiles.size + 1}.dex"
            mixinDexFile.copyTo(File(workDir, targetName), overwrite = true)
            println("Added Mixin DEX as $targetName")
        }

        // 2. 修改现有的 DEX 文件以应用注入
        val dexFiles = workDir.listFiles { _, name -> name.endsWith(".dex") } ?: emptyArray()
        for (dexFile in dexFiles) {
            val dexStore = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
            val classes = mutableListOf<ClassDef>()
            var dexModified = false
            
            for (classDef in dexStore.classes) {
                val metadata = allMetadata.find { "L${it.targetClassName.replace(".", "/")};" == classDef.type }
                if (metadata != null) {
                    val methods = mutableListOf<org.jf.dexlib2.iface.Method>()
                    var classModified = false
                    for (method in classDef.methods) {
                        var currentMethod: org.jf.dexlib2.iface.Method = method
                        for (inj in metadata.injections) {
                            if (method.name == inj.targetMethodName) {
                                println("Applying ${inj.type} to ${classDef.type}->${method.name}")
                                currentMethod = applyInjection(currentMethod, metadata, inj)
                                classModified = true
                                dexModified = true
                            }
                        }
                        methods.add(currentMethod)
                    }
                    
                    if (classModified) {
                        classes.add(ImmutableClassDef(
                            classDef.type, classDef.accessFlags, classDef.superclass,
                            classDef.interfaces, classDef.sourceFile, classDef.annotations,
                            classDef.staticFields.toMutableList() + classDef.instanceFields.toMutableList(), 
                            methods
                        ))
                    } else {
                        classes.add(classDef)
                    }
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

    private fun prepareMixinDex(): File? {
        val allFiles = mixinClasses.files
        val tempDexDir = File(outputDir, "mixin-dex-tmp")
        tempDexDir.deleteRecursively()
        tempDexDir.mkdirs()
        
        val builder = D8Command.builder()
        var hasContent = false
        
        allFiles.forEach { file ->
            if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach {
                    builder.addClassProgramData(it.readBytes(), Origin.unknown())
                    hasContent = true
                }
            } else if (file.extension == "class") {
                builder.addClassProgramData(file.readBytes(), Origin.unknown())
                hasContent = true
            } else if (file.extension == "jar") {
                builder.addProgramFiles(file.toPath())
                hasContent = true
            }
        }
        
        if (!hasContent) {
            println("No mixin class/jar files found")
            return null
        }
        
        builder.setOutput(tempDexDir.toPath(), OutputMode.DexIndexed)
        D8.run(builder.build())
        
        val resultDex = File(tempDexDir, "classes.dex")
        return if (resultDex.exists()) resultDex else null
    }

    private fun applyInjection(
        method: org.jf.dexlib2.iface.Method,
        metadata: com.example.mixin.compiler.MixinMetaData,
        inj: com.example.mixin.compiler.InjectionData
    ): org.jf.dexlib2.iface.Method {
        val originalImpl = method.implementation ?: return method
        
        val mixinClassType = "L${metadata.mixinClassName.replace(".", "/")};"
        val mixinMethodName = inj.mixinMethodName
        
        // 自动检测 Mixin 方法参数，尝试传入 `this`
        val hasParameter = inj.mixinMethodName.contains("Hook") 
        
        val paramTypes = if (hasParameter) listOf("Ljava/lang/Object;") else emptyList()

        // 构造 invoke-static 指令
        val methodRef = ImmutableMethodReference(
            mixinClassType, mixinMethodName, paramTypes, "V"
        )
        
        // 更准确的参数传递逻辑（针对实例方法 p0 是 this）
        // 在 SMALI 中，如果是 .registers 2, p0 是 v1, p1 是 v2... 并不固定。
        // 但 dexlib2 的 ImmutableInstruction35c 接受的 register 参数是实际的寄存器索引。
        // 目标方法的 registerCount 包括了参数寄存器。
        
        val actualP0 = originalImpl.registerCount - method.parameters.size - 1
        
        val finalInvokeInstr = if (hasParameter) {
             org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c(
                Opcode.INVOKE_STATIC, 1, actualP0, 0, 0, 0, 0, methodRef
            )
        } else {
             org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c(
                Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, methodRef
            )
        }
        
        val newInstructions = mutableListOf<Instruction>()
        newInstructions.add(finalInvokeInstr)
        for (instr in originalImpl.instructions) {
            newInstructions.add(instr)
        }
        
        val newImpl = ImmutableMethodImplementation(
            originalImpl.registerCount,
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
