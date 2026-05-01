package com.dudu.mixin.plugin.res

/**
 * AndroidManifest.xml 二进制补丁器。
 *
 * 支持的高层操作：
 *  - 添加 `<uses-permission>`（按 name 去重）
 *  - 在 `<application>` 中添加子节点：`<activity>` / `<service>` / `<receiver>` / `<provider>` / `<meta-data>`
 *  - 修改 `<application>` 上的属性（`android:name`、`android:debuggable`、`android:extractNativeLibs` 等）
 *
 * 所有插入都按 SpongePowered 风格的 "尾部追加" 策略：保持原有节点顺序、不改变行号语义。
 */
class ManifestPatcher(private val axml: AxmlFile) {

    private val ANDROID_NS_URI = "http://schemas.android.com/apk/res/android"

    /** Android 框架属性的 resource id（来自 frameworks/base/core/res/res/values/public.xml）。 */
    private object AttrId {
        const val NAME = 0x01010003
        const val LABEL = 0x01010001
        const val ICON = 0x01010002
        const val THEME = 0x01010000
        const val DEBUGGABLE = 0x0101000F
        const val EXPORTED = 0x01010010
        const val ENABLED = 0x0101000E
        const val PERMISSION = 0x01010006
        const val PROCESS = 0x01010011
        const val EXTRACT_NATIVE_LIBS = 0x010104ea
        const val VALUE = 0x01010024
        const val RESOURCE = 0x01010025
        const val AUTHORITIES = 0x01010018
    }

    /** 添加 uses-permission；若已存在则跳过。 */
    fun addPermission(permission: String): Boolean {
        if (hasPermission(permission)) return false
        val manifestEnd = findManifestEndIndex() ?: return false
        val pool = axml.stringPool

        val nsIdx = pool.addOrGet(ANDROID_NS_URI)
        val nameAttr = pool.addOrGet("name")
        val tagName = pool.addOrGet("uses-permission")
        val permIdx = pool.addOrGet(permission)
        ensureResourceMapForName(nameAttr, AttrId.NAME)

        val start = XmlChunk.StartElement(
            ns = -1,
            name = tagName,
            attributes = mutableListOf(
                XmlChunk.Attribute(
                    ns = nsIdx,
                    name = nameAttr,
                    rawValue = permIdx,
                    dataType = AxmlValueType.STRING,
                    data = permIdx,
                ),
            ),
        )
        val end = XmlChunk.EndElement(ns = -1, name = tagName)
        axml.chunks.add(manifestEnd, end)
        axml.chunks.add(manifestEnd, start)
        return true
    }

    /** 在 `<application>` 中追加 `<meta-data android:name="..." android:value="..." />`。 */
    fun addMetaData(name: String, value: String): Boolean {
        val applicationEnd = findApplicationEndIndex() ?: return false
        val pool = axml.stringPool

        val nsIdx = pool.addOrGet(ANDROID_NS_URI)
        val nameAttr = pool.addOrGet("name")
        val valueAttr = pool.addOrGet("value")
        val tagName = pool.addOrGet("meta-data")
        val nameRef = pool.addOrGet(name)
        val valueRef = pool.addOrGet(value)
        ensureResourceMapForName(nameAttr, AttrId.NAME)
        ensureResourceMapForName(valueAttr, AttrId.VALUE)

        val attrs = mutableListOf(
            XmlChunk.Attribute(nsIdx, nameAttr, nameRef, AxmlValueType.STRING, nameRef),
            XmlChunk.Attribute(nsIdx, valueAttr, valueRef, AxmlValueType.STRING, valueRef),
        )
        val s = XmlChunk.StartElement(ns = -1, name = tagName, attributes = attrs)
        val e = XmlChunk.EndElement(ns = -1, name = tagName)
        axml.chunks.add(applicationEnd, e)
        axml.chunks.add(applicationEnd, s)
        return true
    }

    /** 在 `<application>` 中追加 `<activity>` / `<service>` / `<receiver>` / `<provider>` 节点。 */
    fun addComponent(
        tag: String, // "activity" / "service" / "receiver" / "provider"
        className: String,
        exported: Boolean = false,
        authorities: String? = null,
        extraAttrs: Map<String, String> = emptyMap(),
    ): Boolean {
        val applicationEnd = findApplicationEndIndex() ?: return false
        val pool = axml.stringPool

        val nsIdx = pool.addOrGet(ANDROID_NS_URI)
        val nameAttr = pool.addOrGet("name")
        val exportedAttr = pool.addOrGet("exported")
        val tagName = pool.addOrGet(tag)
        val classRef = pool.addOrGet(className)
        ensureResourceMapForName(nameAttr, AttrId.NAME)
        ensureResourceMapForName(exportedAttr, AttrId.EXPORTED)

        val attrs = mutableListOf<XmlChunk.Attribute>()
        attrs.add(XmlChunk.Attribute(nsIdx, nameAttr, classRef, AxmlValueType.STRING, classRef))
        attrs.add(XmlChunk.Attribute(
            nsIdx, exportedAttr, -1, AxmlValueType.INT_BOOLEAN, if (exported) -1 else 0,
        ))

        if (authorities != null && tag == "provider") {
            val attrIdx = pool.addOrGet("authorities")
            val authIdx = pool.addOrGet(authorities)
            ensureResourceMapForName(attrIdx, AttrId.AUTHORITIES)
            attrs.add(XmlChunk.Attribute(nsIdx, attrIdx, authIdx, AxmlValueType.STRING, authIdx))
        }

        for ((k, v) in extraAttrs) {
            val attrIdx = pool.addOrGet(k)
            val valIdx = pool.addOrGet(v)
            attrs.add(XmlChunk.Attribute(nsIdx, attrIdx, valIdx, AxmlValueType.STRING, valIdx))
        }

        val s = XmlChunk.StartElement(ns = -1, name = tagName, attributes = attrs)
        val e = XmlChunk.EndElement(ns = -1, name = tagName)
        axml.chunks.add(applicationEnd, e)
        axml.chunks.add(applicationEnd, s)
        return true
    }

    /** 设置 application 的属性（已存在则覆盖，否则追加）。 */
    fun setApplicationAttribute(localName: String, value: String, dataType: Int = AxmlValueType.STRING): Boolean {
        val app = findApplicationStart() ?: return false
        val pool = axml.stringPool
        val nsIdx = pool.addOrGet(ANDROID_NS_URI)
        val nameIdx = pool.addOrGet(localName)
        AttrIdLookup.lookup(localName)?.let { ensureResourceMapForName(nameIdx, it) }

        val data: Int
        val rawValue: Int
        when (dataType) {
            AxmlValueType.INT_BOOLEAN -> {
                data = if (value == "true") -1 else 0
                rawValue = -1
            }
            AxmlValueType.INT_DEC, AxmlValueType.INT_HEX -> {
                data = value.toInt()
                rawValue = -1
            }
            else -> {
                val r = pool.addOrGet(value)
                data = r; rawValue = r
            }
        }

        val existing = app.attributes.find { it.ns == nsIdx && it.name == nameIdx }
        if (existing != null) {
            existing.rawValue = rawValue
            existing.dataType = dataType
            existing.data = data
        } else {
            app.attributes.add(XmlChunk.Attribute(nsIdx, nameIdx, rawValue, dataType, data))
        }
        return true
    }

    /* ───── helpers ───── */

    private fun hasPermission(permission: String): Boolean {
        val pool = axml.stringPool
        val nameIdx = pool.indexOf("uses-permission")
        if (nameIdx < 0) return false
        val permIdx = pool.indexOf(permission)
        if (permIdx < 0) return false
        for (c in axml.chunks) {
            if (c is XmlChunk.StartElement && c.name == nameIdx) {
                if (c.attributes.any { it.data == permIdx || it.rawValue == permIdx }) return true
            }
        }
        return false
    }

    private fun findManifestEndIndex(): Int? {
        val pool = axml.stringPool
        val mIdx = pool.indexOf("manifest")
        if (mIdx < 0) return null
        for ((i, c) in axml.chunks.withIndex()) {
            if (c is XmlChunk.EndElement && c.name == mIdx) return i
        }
        return null
    }

    private fun findApplicationStart(): XmlChunk.StartElement? {
        val pool = axml.stringPool
        val aIdx = pool.indexOf("application")
        if (aIdx < 0) return null
        return axml.chunks.firstOrNull { it is XmlChunk.StartElement && it.name == aIdx } as? XmlChunk.StartElement
    }

    private fun findApplicationEndIndex(): Int? {
        val pool = axml.stringPool
        val aIdx = pool.indexOf("application")
        if (aIdx < 0) return null
        for ((i, c) in axml.chunks.withIndex()) {
            if (c is XmlChunk.EndElement && c.name == aIdx) return i
        }
        return null
    }

    private fun ensureResourceMapForName(stringIdx: Int, resourceId: Int) {
        // 资源 map 的下标必须与字符串池下标一致，且长度 >= stringIdx+1
        while (axml.resourceMap.size <= stringIdx) axml.resourceMap.add(0)
        if (axml.resourceMap[stringIdx] == 0) axml.resourceMap[stringIdx] = resourceId
    }
}

/** 常用 Android 属性 → resourceId 映射；提升读到的属性命中 resourceMap。 */
private object AttrIdLookup {
    private val map = mapOf(
        "theme" to 0x01010000,
        "label" to 0x01010001,
        "icon" to 0x01010002,
        "name" to 0x01010003,
        "permission" to 0x01010006,
        "enabled" to 0x0101000E,
        "debuggable" to 0x0101000F,
        "exported" to 0x01010010,
        "process" to 0x01010011,
        "authorities" to 0x01010018,
        "value" to 0x01010024,
        "resource" to 0x01010025,
        "extractNativeLibs" to 0x010104ea,
        "usesCleartextTraffic" to 0x010103e9,
    )
    fun lookup(name: String): Int? = map[name]
}
