package com.dudu.mixin.lib

import kotlin.reflect.KClass

/**
 * 标记一个 Mixin 类。
 *  - target / targetClass 至少提供一个，target 取 JVM 二进制名（com.foo.Bar）
 *  - priority 决定多个 mixin 注入同一个方法时的执行顺序（数值越小越先执行）
 *  - remap 指示注入器是否对 Mixin 内的引用进行重命名匹配
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Mixin(
    val target: String = "",
    val targetClass: KClass<*> = Unit::class,
    val priority: Int = 1000,
    val remap: Boolean = true,
)

/**
 * 在目标方法的指定位置注入回调。
 *  - method: 目标方法名（可同时携带签名以消歧义，如 "onCreate(Landroid/os/Bundle;)V"）
 *  - at: 注入点
 *  - cancellable: 允许通过 CallbackInfo 提前从原方法返回
 *  - require: 至少需要命中的注入点数量（< 1 视为关闭检查）
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Inject(
    val method: String,
    val at: At,
    val cancellable: Boolean = false,
    val require: Int = 0,
)

/**
 * 重定向目标方法体内对其它方法 / 字段的访问到本 Mixin 静态方法。
 * 必须配合 At(value = INVOKE / FIELD_GET / FIELD_PUT, target = "Lowner;name(args)ret")
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Redirect(
    val method: String,
    val at: At,
)

/** 修改方法调用前的指定参数。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyArg(
    val method: String,
    val at: At,
    val index: Int = 0,
)

/** 修改局部变量（dex 寄存器层面）。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyVariable(
    val method: String,
    val at: At,
    val index: Int = -1,
    val ordinal: Int = 0,
)

/**
 * 整方法替换。
 *  - keepOriginal 为 true 时，原方法会被重命名为 `<name>$$original`，
 *    Mixin 方法可通过 Shadow 机制调用以保留原始逻辑。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Overwrite(
    val method: String,
    val keepOriginal: Boolean = false,
)

/**
 * 影子成员：声明该字段/方法在目标类中已存在，可在 Mixin 中编写代码时正常引用，
 * 注入时不会真的添加到目标。
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Shadow(
    val prefix: String = "",
    val remap: Boolean = true,
)

/** 显式访问器（用于读写 private 字段或调用 private 方法）。 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Accessor(val value: String = "")

/**
 * 注入点描述。
 *  - value: 见 [AtValues]
 *  - target: INVOKE/FIELD_* 时的目标签名（"Lowner;name(args)ret" 或 "Lowner;name:type"）
 *  - offset: 命中后再向前/向后偏移指令数
 *  - ordinal: 第几次命中（-1 表示全部）
 *  - shift: BEFORE/AFTER 决定在命中指令前还是后插入
 *  - args: 额外参数（如 CONSTANT 时 "stringValue=foo"）
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class At(
    val value: String,
    val target: String = "",
    val offset: Int = 0,
    val ordinal: Int = -1,
    val shift: String = "BEFORE",
    val args: Array<String> = [],
)

object AtValues {
    const val HEAD = "HEAD"
    const val RETURN = "RETURN"
    const val TAIL = "TAIL"
    const val INVOKE = "INVOKE"
    const val FIELD_GET = "FIELD_GET"
    const val FIELD_PUT = "FIELD_PUT"
    const val CONSTANT = "CONSTANT"
    const val JUMP = "JUMP"
}

object Shift {
    const val BEFORE = "BEFORE"
    const val AFTER = "AFTER"
    const val BY = "BY"
}

/**
 * 注入回调信息：Mixin 方法可声明 [CallbackInfo] 参数；
 * 注入器会构造一个实例传入，调用 [cancel] 后会在注入点直接从原方法返回。
 */
open class CallbackInfo(val name: String, val cancellable: Boolean) {
    var isCancelled: Boolean = false
        private set

    fun cancel() {
        check(cancellable) { "Callback $name is not cancellable" }
        isCancelled = true
    }
}

/** 带返回值的回调信息（用于注入到有返回值的方法）。 */
class CallbackInfoReturnable<T>(name: String, cancellable: Boolean) : CallbackInfo(name, cancellable) {
    @PublishedApi internal var _returnValue: T? = null
    fun setReturnValue(value: T) {
        _returnValue = value
        cancel()
    }
    fun getReturnValue(): T? = _returnValue
}
