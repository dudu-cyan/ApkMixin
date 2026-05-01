package com.dudu.mixin.plugin.res

/**
 * 资源 hook 的高层操作描述。Patch 任务会把它们应用到目标 APK 内的 AndroidManifest.xml /
 * 资源/资产目录。
 *
 * 这个数据类是 Plugin DSL ↔ Task 的桥梁；为了被 Gradle 实现并写入 task @Input，
 * 字段必须是简单可序列化类型（String / Int / Boolean / List）。
 */
data class ResourceHookSpec(
    val permissions: List<String> = emptyList(),
    val metaData: Map<String, String> = emptyMap(),
    val components: List<ComponentSpec> = emptyList(),
    val applicationAttributes: Map<String, String> = emptyMap(),
)

data class ComponentSpec(
    val tag: String,
    val className: String,
    val exported: Boolean = false,
    val authorities: String? = null,
    val extraAttrs: Map<String, String> = emptyMap(),
)
