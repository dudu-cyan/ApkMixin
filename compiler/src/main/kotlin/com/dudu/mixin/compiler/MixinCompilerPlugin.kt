@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.dudu.mixin.compiler

import com.google.gson.GsonBuilder
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitor
import org.jetbrains.kotlin.name.FqName
import java.io.File

/* ─────────────────────────  数据模型  ───────────────────────── */

/**
 * 一个 Mixin 类的全部元数据。
 *  - mixinClassName / targetClassName 为 JVM 二进制名（com.foo.Bar）
 *  - priority: 注入排序权重
 *  - remap: 是否参与重映射
 *  - injections: 所有需要注入到目标的方法描述
 *  - shadows: Mixin 中声明为 @Shadow 的成员（注入器会剥离它们）
 *  - accessors: @Accessor 成员
 *  - addMembers: 既不是 Shadow / Accessor / Inject 的方法（视为新增成员的候选）
 */
data class MixinMetaData(
    val mixinClassName: String,
    val targetClassName: String,
    val priority: Int = 1000,
    val remap: Boolean = true,
    val injections: List<InjectionData> = emptyList(),
    val shadows: List<MemberRef> = emptyList(),
    val accessors: List<AccessorData> = emptyList(),
    val addMembers: List<MemberRef> = emptyList(),
)

enum class InjectionKind { INJECT, REDIRECT, OVERWRITE, MODIFY_ARG, MODIFY_VARIABLE }

/**
 * 注入指令描述。
 *
 *  - mixinMethodName / mixinMethodDescriptor: Mixin 内部对应方法（JVM 描述符）
 *  - targetMethodName / targetMethodDescriptor: 目标方法。allow descriptor 为空表示按名匹配
 *  - hasCallbackInfo / callbackInfoIsReturnable: 末位参数是否是 CallbackInfo[Returnable]
 *  - hasReceiver: 首参是否是 receiver（非静态目标方法的 this）
 */
data class InjectionData(
    val mixinMethodName: String,
    val mixinMethodDescriptor: String,
    val targetMethodName: String,
    val targetMethodDescriptor: String,
    val type: String,
    val at: AtData,
    val cancellable: Boolean = false,
    val require: Int = 0,
    val injectIndex: Int = 0,
    val keepOriginal: Boolean = false,
    val hasCallbackInfo: Boolean = false,
    val callbackInfoIsReturnable: Boolean = false,
    val hasReceiver: Boolean = false,
)

/** 注入点的具体描述。 */
data class AtData(
    val value: String,
    val target: String = "",
    val offset: Int = 0,
    val ordinal: Int = -1,
    val shift: String = "BEFORE",
    val args: List<String> = emptyList(),
)

/** Shadow / 新增成员引用。 */
data class MemberRef(
    val name: String,
    val descriptor: String,
    val isField: Boolean = false,
)

/** @Accessor 描述。 */
data class AccessorData(
    val name: String,
    val descriptor: String,
    val target: String,
    val isField: Boolean,
)

/* ─────────────────────────  注解 FQ  ───────────────────────── */

private object Fq {
    const val PKG = "com.dudu.mixin.lib"
    val MIXIN = FqName("$PKG.Mixin")
    val INJECT = FqName("$PKG.Inject")
    val REDIRECT = FqName("$PKG.Redirect")
    val OVERWRITE = FqName("$PKG.Overwrite")
    val MODIFY_ARG = FqName("$PKG.ModifyArg")
    val MODIFY_VAR = FqName("$PKG.ModifyVariable")
    val SHADOW = FqName("$PKG.Shadow")
    val ACCESSOR = FqName("$PKG.Accessor")
    const val CALLBACK_INFO = "com.dudu.mixin.lib.CallbackInfo"
    const val CALLBACK_INFO_RETURNABLE = "com.dudu.mixin.lib.CallbackInfoReturnable"
}

/* ─────────────────────────  CompilerPlugin entry  ───────────────────────── */

@OptIn(ExperimentalCompilerApi::class)
class MixinCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val outputDir = configuration.get(MixinCommandLineProcessor.ARG_OUTPUT_DIR)
            ?: "build/mixin-metadata"
        IrGenerationExtension.registerExtension(MixinIrGenerationExtension(outputDir))
    }
}

@OptIn(ExperimentalCompilerApi::class)
class MixinCommandLineProcessor : CommandLineProcessor {
    companion object {
        val ARG_OUTPUT_DIR = CompilerConfigurationKey<String>("outputDir")
        val OPTION_OUTPUT_DIR = CliOption(
            "outputDir", "<path>", "Output directory for Mixin metadata", required = false,
        )
    }

    override val pluginId: String = "com.dudu.mixin-transformer"
    override val pluginOptions: Collection<AbstractCliOption> = listOf(OPTION_OUTPUT_DIR)

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option) {
            OPTION_OUTPUT_DIR -> configuration.put(ARG_OUTPUT_DIR, value)
        }
    }
}

class MixinIrGenerationExtension(private val outputDir: String) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        try {
            val metadataList = mutableListOf<MixinMetaData>()
            moduleFragment.accept(MixinIrVisitor(metadataList), null)
            if (metadataList.isEmpty()) return

            val dir = File(outputDir).apply { mkdirs() }
            val safeName = moduleFragment.name.asString().replace(Regex("[<>]"), "_")
            val out = File(dir, "$safeName.json")
            out.writeText(GsonBuilder().setPrettyPrinting().create().toJson(metadataList))
            println("[mixin] metadata -> ${out.absolutePath} (${metadataList.size} class(es))")
        } catch (e: Throwable) {
            System.err.println("[mixin] compiler plugin error: ${e.message}")
            e.printStackTrace()
        }
    }
}

/* ─────────────────────────  Visitor  ───────────────────────── */

private class MixinIrVisitor(
    private val out: MutableList<MixinMetaData>,
) : IrVisitor<Unit, Nothing?>() {

    override fun visitElement(element: IrElement, data: Nothing?) {
        element.acceptChildren(this, null)
    }

    override fun visitClass(declaration: IrClass, data: Nothing?) {
        val mixinAnn = declaration.getAnnotation(Fq.MIXIN)
        if (mixinAnn != null) processMixinClass(declaration, mixinAnn)
        declaration.acceptChildren(this, null)
    }

    private fun processMixinClass(declaration: IrClass, mixinAnn: IrConstructorCall) {
        // target 字符串优先，否则 targetClass: KClass
        val targetByString =
            (mixinAnn.arguments[0] as? IrConst)?.value as? String
        val targetByClass = (mixinAnn.arguments[1] as? IrClassReference)
            ?.classFqName
            ?.takeIf { it != "kotlin.Unit" }
        val targetClassName = (targetByString?.takeIf { it.isNotBlank() } ?: targetByClass)
            ?: return

        val priority = (mixinAnn.arguments[2] as? IrConst)?.value as? Int ?: 1000
        val remap = (mixinAnn.arguments[3] as? IrConst)?.value as? Boolean ?: true

        // 提升所有带 mixin 注解的方法可见性，保证 KotlinJVM 字节码与 dex 链路上方法仍保留
        declaration.declarations.filterIsInstance<IrSimpleFunction>().forEach { f ->
            if (f.annotations.any { it.isFromMixinLib() }) {
                f.visibility = DescriptorVisibilities.PUBLIC
            }
        }

        val injections = mutableListOf<InjectionData>()
        val shadows = mutableListOf<MemberRef>()
        val accessors = mutableListOf<AccessorData>()
        val addMembers = mutableListOf<MemberRef>()

        for (func in declaration.declarations.filterIsInstance<IrSimpleFunction>()) {
            if (func.name.asString().contains("<")) continue

            val descriptor = func.toJvmMethodDescriptor()

            when {
                func.hasAnnotation(Fq.SHADOW) -> {
                    shadows += MemberRef(func.name.asString(), descriptor, isField = false)
                }
                func.hasAnnotation(Fq.ACCESSOR) -> {
                    val ann = func.getAnnotation(Fq.ACCESSOR)
                    val target = (ann?.arguments[0] as? IrConst)?.value as? String ?: ""
                    accessors += AccessorData(
                        func.name.asString(), descriptor, target, isField = false,
                    )
                }
                func.hasAnnotation(Fq.INJECT) -> injections.addParse(
                    func, func.getAnnotation(Fq.INJECT)!!, InjectionKind.INJECT,
                )
                func.hasAnnotation(Fq.REDIRECT) -> injections.addParse(
                    func, func.getAnnotation(Fq.REDIRECT)!!, InjectionKind.REDIRECT,
                )
                func.hasAnnotation(Fq.OVERWRITE) -> injections.addParse(
                    func, func.getAnnotation(Fq.OVERWRITE)!!, InjectionKind.OVERWRITE,
                )
                func.hasAnnotation(Fq.MODIFY_ARG) -> injections.addParse(
                    func, func.getAnnotation(Fq.MODIFY_ARG)!!, InjectionKind.MODIFY_ARG,
                )
                func.hasAnnotation(Fq.MODIFY_VAR) -> injections.addParse(
                    func, func.getAnnotation(Fq.MODIFY_VAR)!!, InjectionKind.MODIFY_VARIABLE,
                )
                else -> {
                    addMembers += MemberRef(func.name.asString(), descriptor)
                }
            }
        }

        out += MixinMetaData(
            mixinClassName = declaration.kotlinFqName.asString(),
            targetClassName = targetClassName,
            priority = priority,
            remap = remap,
            injections = injections,
            shadows = shadows,
            accessors = accessors,
            addMembers = addMembers,
        )
    }

    private fun MutableList<InjectionData>.addParse(
        func: IrSimpleFunction,
        ann: IrConstructorCall,
        kind: InjectionKind,
    ) {
        val mixinName = func.name.asString()
        val mixinDesc = func.toJvmMethodDescriptor()

        val (targetName, targetDesc) = parseTargetMethodSignature(
            (ann.arguments[0] as? IrConst)?.value as? String ?: return
        )

        val at: AtData
        val cancellable: Boolean
        val require: Int
        val injectIndex: Int
        val keepOriginal: Boolean

        when (kind) {
            InjectionKind.INJECT -> {
                at = (ann.arguments[1] as? IrConstructorCall)?.toAtData()
                    ?: AtData("HEAD")
                cancellable = (ann.arguments[2] as? IrConst)?.value as? Boolean ?: false
                require = (ann.arguments[3] as? IrConst)?.value as? Int ?: 0
                injectIndex = 0
                keepOriginal = false
            }
            InjectionKind.REDIRECT -> {
                at = (ann.arguments[1] as? IrConstructorCall)?.toAtData()
                    ?: AtData("INVOKE")
                cancellable = false
                require = 0
                injectIndex = 0
                keepOriginal = false
            }
            InjectionKind.OVERWRITE -> {
                at = AtData("HEAD")
                cancellable = false
                require = 0
                injectIndex = 0
                keepOriginal = (ann.arguments[1] as? IrConst)?.value as? Boolean ?: false
            }
            InjectionKind.MODIFY_ARG -> {
                at = (ann.arguments[1] as? IrConstructorCall)?.toAtData()
                    ?: AtData("INVOKE")
                cancellable = false
                require = 0
                injectIndex = (ann.arguments[2] as? IrConst)?.value as? Int ?: 0
                keepOriginal = false
            }
            InjectionKind.MODIFY_VARIABLE -> {
                at = (ann.arguments[1] as? IrConstructorCall)?.toAtData()
                    ?: AtData("HEAD")
                cancellable = false
                require = 0
                injectIndex = (ann.arguments[2] as? IrConst)?.value as? Int ?: -1
                keepOriginal = false
            }
        }

        val (hasReceiver, hasCi, ciReturnable) = detectMixinSignature(func)

        add(
            InjectionData(
                mixinMethodName = mixinName,
                mixinMethodDescriptor = mixinDesc,
                targetMethodName = targetName,
                targetMethodDescriptor = targetDesc,
                type = kind.toMetaString(),
                at = at,
                cancellable = cancellable,
                require = require,
                injectIndex = injectIndex,
                keepOriginal = keepOriginal,
                hasCallbackInfo = hasCi,
                callbackInfoIsReturnable = ciReturnable,
                hasReceiver = hasReceiver,
            )
        )
    }
}

/* ─────────────────────────  Helpers  ───────────────────────── */

private fun InjectionKind.toMetaString(): String = when (this) {
    InjectionKind.INJECT -> "Inject"
    InjectionKind.REDIRECT -> "Redirect"
    InjectionKind.OVERWRITE -> "Overwrite"
    InjectionKind.MODIFY_ARG -> "ModifyArg"
    InjectionKind.MODIFY_VARIABLE -> "ModifyVariable"
}

/** 解析 `name(desc)ret` 风格的目标方法签名。 */
private fun parseTargetMethodSignature(raw: String): Pair<String, String> {
    val parenIdx = raw.indexOf('(')
    if (parenIdx <= 0) return raw to ""
    return raw.substring(0, parenIdx) to raw.substring(parenIdx)
}

private fun IrConstructorCall.isFromMixinLib(): Boolean {
    val parent = this.symbol.owner.parent as? IrClass ?: return false
    return parent.kotlinFqName.asString().startsWith(Fq.PKG)
}

private fun IrConstructorCall.toAtData(): AtData {
    fun str(idx: Int) = (arguments[idx] as? IrConst)?.value as? String
    fun int(idx: Int) = (arguments[idx] as? IrConst)?.value as? Int
    val args = (arguments[5] as? IrVararg)?.elements
        ?.mapNotNull { (it as? IrConst)?.value as? String }
        ?: emptyList()
    return AtData(
        value = str(0) ?: "HEAD",
        target = str(1) ?: "",
        offset = int(2) ?: 0,
        ordinal = int(3) ?: -1,
        shift = str(4) ?: "BEFORE",
        args = args,
    )
}

private val IrClassReference.classFqName: String?
    get() = (symbol.owner as? IrClass)?.kotlinFqName?.asString()

/**
 * Probe Mixin method signature characteristics:
 *  - Triple(hasReceiver, hasCallbackInfo, ciReturnable)
 *
 * 第一个参数若不是 CallbackInfo，则视为 receiver；
 * 末位参数若是 CallbackInfo / CallbackInfoReturnable，则识别为回调对象。
 */
private fun detectMixinSignature(
    func: IrSimpleFunction,
): Triple<Boolean, Boolean, Boolean> {
    val params = func.parameters.filter {
        it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.Context
    }
    if (params.isEmpty()) return Triple(false, false, false)

    val firstFqn = params.first().type.fqnOrNull()
    val lastFqn = params.last().type.fqnOrNull()

    val hasCi = lastFqn == Fq.CALLBACK_INFO || lastFqn == Fq.CALLBACK_INFO_RETURNABLE
    val ciRet = lastFqn == Fq.CALLBACK_INFO_RETURNABLE

    val hasReceiver = func.dispatchReceiverParameter != null
    return Triple(hasReceiver, hasCi, ciRet)
}

private fun IrType.fqnOrNull(): String? {
    val cls = (this as? IrSimpleType)?.classifierOrNull?.owner as? IrClass ?: return null
    return cls.kotlinFqName.asString()
}

private fun IrType.toJvmDescriptor(): String {
    val cls = (this as? IrSimpleType)?.classifierOrNull?.owner as? IrClass
        ?: return "Ljava/lang/Object;"
    val fqn = cls.kotlinFqName.asString()
    return when (fqn) {
        "kotlin.Unit" -> "V"
        "kotlin.Int", "java.lang.Integer" -> "I"
        "kotlin.Long", "java.lang.Long" -> "J"
        "kotlin.Short", "java.lang.Short" -> "S"
        "kotlin.Byte", "java.lang.Byte" -> "B"
        "kotlin.Boolean", "java.lang.Boolean" -> "Z"
        "kotlin.Char", "java.lang.Character" -> "C"
        "kotlin.Float", "java.lang.Float" -> "F"
        "kotlin.Double", "java.lang.Double" -> "D"
        "kotlin.Any" -> "Ljava/lang/Object;"
        "kotlin.String" -> "Ljava/lang/String;"
        "kotlin.collections.List", "kotlin.collections.MutableList" -> "Ljava/util/List;"
        "kotlin.collections.Map", "kotlin.collections.MutableMap" -> "Ljava/util/Map;"
        "kotlin.collections.Set", "kotlin.collections.MutableSet" -> "Ljava/util/Set;"
        "kotlin.IntArray" -> "[I"
        "kotlin.LongArray" -> "[J"
        "kotlin.ShortArray" -> "[S"
        "kotlin.ByteArray" -> "[B"
        "kotlin.BooleanArray" -> "[Z"
        "kotlin.CharArray" -> "[C"
        "kotlin.FloatArray" -> "[F"
        "kotlin.DoubleArray" -> "[D"
        "kotlin.Array" -> {
            val arg = (this as IrSimpleType).arguments.firstOrNull()
            val elem = (arg as? IrTypeProjection)?.type?.toJvmDescriptor() ?: "Ljava/lang/Object;"
            "[$elem"
        }
        else -> "L${fqn.replace('.', '/')};"
    }
}

private fun IrSimpleFunction.toJvmMethodDescriptor(): String {
    val params = parameters
        .filter { it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.Context }
        .joinToString(separator = "") { it.type.toJvmDescriptor() }
    val ret = returnType.toJvmDescriptor()
    return "($params)$ret"
}
