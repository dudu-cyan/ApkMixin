package com.example.mixin.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import com.google.gson.GsonBuilder
import java.io.File

@OptIn(ExperimentalCompilerApi::class)
class MixinCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val outputDir = configuration.get(MixinCommandLineProcessor.ARG_OUTPUT_DIR) ?: "build/mixin-metadata"
        IrGenerationExtension.registerExtension(MixinIrGenerationExtension(outputDir))
    }
}

class MixinIrGenerationExtension(private val outputDir: String) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        try {
            val metadataList = mutableListOf<MixinMetaData>()
            moduleFragment.accept(MixinIrVisitor(pluginContext, metadataList), null)
            
            if (metadataList.isNotEmpty()) {
                val dir = File(outputDir)
                dir.mkdirs()
                val safeName = moduleFragment.name.asString().replace(Regex("[<>]"), "_")
                val file = File(dir, "$safeName.json")
                file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(metadataList))
                println("Mixin metadata written to ${file.absolutePath}")
            }
        } catch (e: Exception) {
            println("MixinCompilerPlugin Error: ${e.message}")
            e.printStackTrace()
        }
    }
}

data class MixinMetaData(
    val mixinClassName: String,
    val targetClassName: String,
    val injections: List<InjectionData>
)

data class InjectionData(
    val mixinMethodName: String,
    val targetMethodName: String,
    val type: String,
    val at: AtData
)

data class AtData(val value: String, val offset: Int)

class MixinIrVisitor(
    private val context: IrPluginContext,
    private val metadataList: MutableList<MixinMetaData>
) : org.jetbrains.kotlin.ir.visitors.IrElementVisitor<Unit, Nothing?> {
    override fun visitElement(element: org.jetbrains.kotlin.ir.IrElement, data: Nothing?) {
        element.acceptChildren(this, null)
    }

    override fun visitClass(declaration: IrClass, data: Nothing?) {
        val mixinAnnotation = declaration.getAnnotation(FqName("com.example.mixin.lib.Mixin"))
        if (mixinAnnotation != null) {
            declaration.declarations.filterIsInstance<IrSimpleFunction>().forEach { func ->
                if (func.hasAnnotation(FqName("com.example.mixin.lib.Inject")) || 
                    func.hasAnnotation(FqName("com.example.mixin.lib.Redirect"))) {
                    func.visibility = org.jetbrains.kotlin.descriptors.DescriptorVisibilities.PUBLIC
                }
            }
            
            val targetClassName = (mixinAnnotation.getValueArgument(0) as? IrConst<*>)?.value as? String
            if (targetClassName != null) {
                val injections = mutableListOf<InjectionData>()
                declaration.declarations.filterIsInstance<IrSimpleFunction>().forEach { func ->
                    val injectAnn = func.getAnnotation(FqName("com.example.mixin.lib.Inject"))
                    if (injectAnn != null) {
                        val methodName = (injectAnn.getValueArgument(0) as? IrConst<*>)?.value as? String
                        val atCall = injectAnn.getValueArgument(1) as? IrConstructorCall
                        if (methodName != null && atCall != null) {
                            val atValue = (atCall.getValueArgument(0) as? IrConst<*>)?.value as? String
                            val atOffset = (atCall.getValueArgument(1) as? IrConst<*>)?.value as? Int ?: 0
                            injections.add(InjectionData(func.name.asString(), methodName, "Inject", AtData(atValue ?: "HEAD", atOffset)))
                        }
                    }
                    
                    val redirectAnn = func.getAnnotation(FqName("com.example.mixin.lib.Redirect"))
                    if (redirectAnn != null) {
                         val methodName = (redirectAnn.getValueArgument(0) as? IrConst<*>)?.value as? String
                         val atCall = redirectAnn.getValueArgument(1) as? IrConstructorCall
                         if (methodName != null && atCall != null) {
                             val atValue = (atCall.getValueArgument(0) as? IrConst<*>)?.value as? String
                             val atOffset = (atCall.getValueArgument(1) as? IrConst<*>)?.value as? Int ?: 0
                             injections.add(InjectionData(func.name.asString(), methodName, "Redirect", AtData(atValue ?: "INVOKE", atOffset)))
                         }
                    }
                }
                metadataList.add(MixinMetaData(declaration.kotlinFqName.asString(), targetClassName, injections))
            }
        }
        declaration.acceptChildren(this, null)
    }
}

@OptIn(ExperimentalCompilerApi::class)
class MixinCommandLineProcessor : CommandLineProcessor {
    companion object {
        val ARG_OUTPUT_DIR = CompilerConfigurationKey<String>("outputDir")
        val OPTION_OUTPUT_DIR = CliOption("outputDir", "<path>", "Output directory for Mixin metadata", required = false)
    }

    override val pluginId: String = "com.example.mixin-transformer"
    override val pluginOptions: Collection<AbstractCliOption> = listOf(OPTION_OUTPUT_DIR)

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option) {
            OPTION_OUTPUT_DIR -> configuration.put(ARG_OUTPUT_DIR, value)
        }
    }
}
