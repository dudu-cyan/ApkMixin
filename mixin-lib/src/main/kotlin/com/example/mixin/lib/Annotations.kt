package com.example.mixin.lib

@Target(AnnotationTarget.CLASS)
annotation class Mixin(val target: String)

@Target(AnnotationTarget.FUNCTION)
annotation class Inject(val method: String, val at: At)

@Target(AnnotationTarget.FUNCTION)
annotation class Redirect(val method: String, val at: At)

annotation class At(val value: String, val offset: Int = 0)

object AtValues {
    const val HEAD = "HEAD"
    const val RETURN = "RETURN"
    const val TAIL = "TAIL"
    const val INVOKE = "INVOKE"
}
