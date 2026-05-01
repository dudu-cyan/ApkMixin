package com.dudu.mixin.plugin.res

import java.nio.ByteBuffer
import java.nio.ByteOrder

/* ─────────────────────────  AXML chunk types  ───────────────────────── */

internal const val TYPE_STRING_POOL = 0x0001
internal const val TYPE_XML = 0x0003
internal const val TYPE_XML_START_NAMESPACE = 0x0100
internal const val TYPE_XML_END_NAMESPACE = 0x0101
internal const val TYPE_XML_START_ELEMENT = 0x0102
internal const val TYPE_XML_END_ELEMENT = 0x0103
internal const val TYPE_XML_CDATA = 0x0104
internal const val TYPE_XML_RESOURCE_MAP = 0x0180

private const val FLAG_SORTED = 0x1
private const val FLAG_UTF8 = 0x100

/** AXML attribute typed-value types。 */
object AxmlValueType {
    const val NULL = 0x00
    const val REFERENCE = 0x01
    const val ATTRIBUTE = 0x02
    const val STRING = 0x03
    const val FLOAT = 0x04
    const val DIMENSION = 0x05
    const val FRACTION = 0x06
    const val INT_DEC = 0x10
    const val INT_HEX = 0x11
    const val INT_BOOLEAN = 0x12
    const val INT_COLOR_ARGB8 = 0x1c
}

/** 完整 AXML 文档（解析后可读写的中间表示）。 */
class AxmlFile(
    val stringPool: AxmlStringPool,
    val resourceMap: MutableList<Int>,
    val chunks: MutableList<XmlChunk>,
)

/** AXML 字符串池（可变）。 */
class AxmlStringPool(
    val strings: MutableList<String> = mutableListOf(),
    var utf8: Boolean = true,
    var sorted: Boolean = false,
) {
    fun indexOf(s: String): Int = strings.indexOf(s)
    fun addOrGet(s: String): Int {
        val i = strings.indexOf(s)
        if (i >= 0) return i
        strings.add(s)
        return strings.size - 1
    }
}

sealed class XmlChunk(var lineNumber: Int = 1, var commentRef: Int = -1) {
    class StartNamespace(var prefix: Int, var uri: Int) : XmlChunk()
    class EndNamespace(var prefix: Int, var uri: Int) : XmlChunk()
    class StartElement(
        var ns: Int,
        var name: Int,
        val attributes: MutableList<Attribute> = mutableListOf(),
        var idIndex: Int = 0,
        var classIndex: Int = 0,
        var styleIndex: Int = 0,
    ) : XmlChunk()

    class EndElement(var ns: Int, var name: Int) : XmlChunk()
    class CData(var text: Int, var dataType: Int = AxmlValueType.STRING, var data: Int = 0) : XmlChunk()

    class Attribute(
        var ns: Int,
        var name: Int,
        var rawValue: Int,    // 字符串池下标，-1 表示无原始 string
        var dataType: Int,
        var data: Int,
    )
}

/* ─────────────────────────  Reader / Writer  ───────────────────────── */

object AxmlIo {

    fun read(bytes: ByteArray): AxmlFile {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val type = bb.short.toInt() and 0xFFFF
        val headerSize = bb.short.toInt() and 0xFFFF
        val totalSize = bb.int
        require(type == TYPE_XML) { "Not an AXML file (type=0x${type.toString(16)})" }
        // Skip remaining header bytes if any
        bb.position(headerSize)

        var stringPool: AxmlStringPool? = null
        val resourceMap = mutableListOf<Int>()
        val chunks = mutableListOf<XmlChunk>()

        while (bb.position() < totalSize && bb.hasRemaining()) {
            val chunkStart = bb.position()
            val cType = bb.short.toInt() and 0xFFFF
            val cHeader = bb.short.toInt() and 0xFFFF
            val cSize = bb.int
            when (cType) {
                TYPE_STRING_POOL -> {
                    stringPool = readStringPool(bb, chunkStart, cHeader, cSize)
                }
                TYPE_XML_RESOURCE_MAP -> {
                    val count = (cSize - cHeader) / 4
                    repeat(count) { resourceMap.add(bb.int) }
                }
                TYPE_XML_START_NAMESPACE,
                TYPE_XML_END_NAMESPACE,
                TYPE_XML_START_ELEMENT,
                TYPE_XML_END_ELEMENT,
                TYPE_XML_CDATA -> {
                    chunks.add(readXmlNode(bb, cType, chunkStart, cHeader, cSize))
                }
                else -> {
                    // 跳过未知 chunk
                    bb.position(chunkStart + cSize)
                }
            }
        }

        return AxmlFile(
            stringPool ?: AxmlStringPool(),
            resourceMap,
            chunks,
        )
    }

    fun write(axml: AxmlFile): ByteArray {
        // 1) 序列化 string pool
        val poolBytes = writeStringPool(axml.stringPool)
        // 2) 序列化 resource map
        val rmBytes = writeResourceMap(axml.resourceMap)
        // 3) 序列化所有 xml nodes
        val nodeBytes = ByteArrayOutput()
        for (c in axml.chunks) writeXmlNode(nodeBytes, c)
        val nodes = nodeBytes.toByteArray()

        // 4) 包裹整体头
        val total = 8 + poolBytes.size + rmBytes.size + nodes.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(TYPE_XML.toShort())
        out.putShort(8.toShort())
        out.putInt(total)
        out.put(poolBytes)
        out.put(rmBytes)
        out.put(nodes)
        return out.array()
    }

    /* ───── String Pool ───── */

    private fun readStringPool(bb: ByteBuffer, start: Int, headerSize: Int, totalSize: Int): AxmlStringPool {
        val stringCount = bb.int
        val styleCount = bb.int
        val flags = bb.int
        val stringsStart = bb.int
        val stylesStart = bb.int

        val isUtf8 = (flags and FLAG_UTF8) != 0
        val sorted = (flags and FLAG_SORTED) != 0

        val offsets = IntArray(stringCount) { bb.int }
        // skip styleCount style offsets
        repeat(styleCount) { bb.int }

        val pool = AxmlStringPool(utf8 = isUtf8, sorted = sorted)
        val stringsBase = start + stringsStart
        for (i in 0 until stringCount) {
            val off = stringsBase + offsets[i]
            pool.strings.add(if (isUtf8) readUtf8AxmlString(bb, off) else readUtf16AxmlString(bb, off))
        }
        bb.position(start + totalSize)
        return pool
    }

    private fun readUtf16AxmlString(bb: ByteBuffer, off: Int): String {
        val saved = bb.position()
        bb.position(off)
        var len = bb.short.toInt() and 0xFFFF
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or (bb.short.toInt() and 0xFFFF)
        }
        val chars = CharArray(len)
        for (i in 0 until len) chars[i] = bb.char
        bb.position(saved)
        return String(chars)
    }

    private fun readUtf8AxmlString(bb: ByteBuffer, off: Int): String {
        val saved = bb.position()
        bb.position(off)
        // 字符长度
        val first = bb.get().toInt() and 0xFF
        val charLen = if (first and 0x80 != 0) ((first and 0x7F) shl 8) or (bb.get().toInt() and 0xFF) else first
        // 字节长度
        val firstB = bb.get().toInt() and 0xFF
        val byteLen = if (firstB and 0x80 != 0) ((firstB and 0x7F) shl 8) or (bb.get().toInt() and 0xFF) else firstB
        val data = ByteArray(byteLen)
        bb.get(data)
        bb.position(saved)
        // charLen 暂未使用，相信 byteLen
        @Suppress("UNUSED_VARIABLE") val _cl = charLen
        return String(data, Charsets.UTF_8)
    }

    private fun writeStringPool(pool: AxmlStringPool): ByteArray {
        val n = pool.strings.size
        // 序列化字符串体
        val body = ByteArrayOutput()
        val offsets = IntArray(n)
        for ((i, s) in pool.strings.withIndex()) {
            offsets[i] = body.size()
            if (pool.utf8) writeUtf8AxmlString(body, s) else writeUtf16AxmlString(body, s)
        }
        // 4 字节对齐
        while (body.size() % 4 != 0) body.writeByte(0)

        val headerSize = 28
        val offsetsSize = n * 4
        val stringsStart = headerSize + offsetsSize
        val totalSize = stringsStart + body.size()

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(TYPE_STRING_POOL.toShort())
        buf.putShort(headerSize.toShort())
        buf.putInt(totalSize)
        buf.putInt(n)
        buf.putInt(0) // styleCount
        buf.putInt(if (pool.utf8) FLAG_UTF8 else 0)
        buf.putInt(stringsStart)
        buf.putInt(0) // stylesStart
        for (off in offsets) buf.putInt(off)
        buf.put(body.toByteArray())
        return buf.array()
    }

    private fun writeUtf16AxmlString(out: ByteArrayOutput, s: String) {
        val len = s.length
        if (len > 0x7FFF) {
            out.writeShortLE(((len ushr 16) or 0x8000) and 0xFFFF)
            out.writeShortLE(len and 0xFFFF)
        } else {
            out.writeShortLE(len)
        }
        for (c in s) out.writeShortLE(c.code)
        out.writeShortLE(0)
    }

    private fun writeUtf8AxmlString(out: ByteArrayOutput, s: String) {
        val charLen = s.length // codepoint count approximation
        val bytes = s.toByteArray(Charsets.UTF_8)
        // char length
        if (charLen > 0x7F) {
            out.writeByte(((charLen ushr 8) or 0x80) and 0xFF)
            out.writeByte(charLen and 0xFF)
        } else out.writeByte(charLen)
        // byte length
        if (bytes.size > 0x7F) {
            out.writeByte(((bytes.size ushr 8) or 0x80) and 0xFF)
            out.writeByte(bytes.size and 0xFF)
        } else out.writeByte(bytes.size)
        out.writeBytes(bytes)
        out.writeByte(0)
    }

    /* ───── Resource Map ───── */

    private fun writeResourceMap(map: List<Int>): ByteArray {
        val total = 8 + map.size * 4
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(TYPE_XML_RESOURCE_MAP.toShort())
        buf.putShort(8.toShort())
        buf.putInt(total)
        for (id in map) buf.putInt(id)
        return buf.array()
    }

    /* ───── XML node chunks ───── */

    private fun readXmlNode(bb: ByteBuffer, type: Int, start: Int, headerSize: Int, totalSize: Int): XmlChunk {
        val lineNumber = bb.int
        val commentRef = bb.int

        val node: XmlChunk = when (type) {
            TYPE_XML_START_NAMESPACE -> {
                val prefix = bb.int; val uri = bb.int
                XmlChunk.StartNamespace(prefix, uri)
            }
            TYPE_XML_END_NAMESPACE -> {
                val prefix = bb.int; val uri = bb.int
                XmlChunk.EndNamespace(prefix, uri)
            }
            TYPE_XML_START_ELEMENT -> {
                val ns = bb.int; val name = bb.int
                val attrStart = bb.short.toInt() and 0xFFFF
                val attrSize = bb.short.toInt() and 0xFFFF
                val attrCount = bb.short.toInt() and 0xFFFF
                val idIdx = bb.short.toInt() and 0xFFFF
                val clsIdx = bb.short.toInt() and 0xFFFF
                val styIdx = bb.short.toInt() and 0xFFFF
                val attrs = mutableListOf<XmlChunk.Attribute>()
                @Suppress("UNUSED_VARIABLE") val _as = attrStart
                for (i in 0 until attrCount) {
                    val ans = bb.int
                    val anm = bb.int
                    val arv = bb.int
                    val aSize = bb.short.toInt() and 0xFFFF
                    val aRes0 = bb.get().toInt() and 0xFF
                    val aType = bb.get().toInt() and 0xFF
                    val aData = bb.int
                    @Suppress("UNUSED_VARIABLE") val _u = aSize + aRes0
                    attrs.add(XmlChunk.Attribute(ans, anm, arv, aType, aData))
                }
                XmlChunk.StartElement(ns, name, attrs, idIdx, clsIdx, styIdx)
            }
            TYPE_XML_END_ELEMENT -> {
                val ns = bb.int; val name = bb.int
                XmlChunk.EndElement(ns, name)
            }
            TYPE_XML_CDATA -> {
                val text = bb.int
                val sz = bb.short.toInt() and 0xFFFF
                val res0 = bb.get().toInt() and 0xFF
                val ty = bb.get().toInt() and 0xFF
                val data = bb.int
                @Suppress("UNUSED_VARIABLE") val _u = sz + res0
                XmlChunk.CData(text, ty, data)
            }
            else -> error("Unsupported XML node type 0x${type.toString(16)}")
        }
        node.lineNumber = lineNumber
        node.commentRef = commentRef
        bb.position(start + totalSize)
        return node
    }

    private fun writeXmlNode(out: ByteArrayOutput, c: XmlChunk) {
        when (c) {
            is XmlChunk.StartNamespace, is XmlChunk.EndNamespace -> {
                val type = if (c is XmlChunk.StartNamespace) TYPE_XML_START_NAMESPACE else TYPE_XML_END_NAMESPACE
                val prefix = if (c is XmlChunk.StartNamespace) c.prefix else (c as XmlChunk.EndNamespace).prefix
                val uri = if (c is XmlChunk.StartNamespace) c.uri else (c as XmlChunk.EndNamespace).uri
                writeNodeHeader(out, type, 24, c.lineNumber, c.commentRef)
                out.writeIntLE(prefix); out.writeIntLE(uri)
            }
            is XmlChunk.StartElement -> {
                val attrCount = c.attributes.size
                val total = 24 + 16 /* attrExt */ + attrCount * 20
                writeNodeHeader(out, TYPE_XML_START_ELEMENT, total, c.lineNumber, c.commentRef)
                out.writeIntLE(c.ns); out.writeIntLE(c.name)
                out.writeShortLE(20)            // attributeStart
                out.writeShortLE(20)            // attributeSize
                out.writeShortLE(attrCount)     // attributeCount
                out.writeShortLE(c.idIndex); out.writeShortLE(c.classIndex); out.writeShortLE(c.styleIndex)
                for (a in c.attributes) {
                    out.writeIntLE(a.ns); out.writeIntLE(a.name); out.writeIntLE(a.rawValue)
                    out.writeShortLE(8)       // size
                    out.writeByte(0)          // res0
                    out.writeByte(a.dataType)
                    out.writeIntLE(a.data)
                }
            }
            is XmlChunk.EndElement -> {
                writeNodeHeader(out, TYPE_XML_END_ELEMENT, 24, c.lineNumber, c.commentRef)
                out.writeIntLE(c.ns); out.writeIntLE(c.name)
            }
            is XmlChunk.CData -> {
                writeNodeHeader(out, TYPE_XML_CDATA, 28, c.lineNumber, c.commentRef)
                out.writeIntLE(c.text)
                out.writeShortLE(8); out.writeByte(0); out.writeByte(c.dataType)
                out.writeIntLE(c.data)
            }
        }
    }

    private fun writeNodeHeader(out: ByteArrayOutput, type: Int, totalSize: Int, line: Int, comment: Int) {
        out.writeShortLE(type)
        out.writeShortLE(16)         // headerSize (ResXMLTree_node 是 16 字节)
        out.writeIntLE(totalSize)
        out.writeIntLE(line)
        out.writeIntLE(comment)
    }
}

/* ─────────────────────────  little-endian helper  ───────────────────────── */

internal class ByteArrayOutput {
    private val out = java.io.ByteArrayOutputStream()
    fun size() = out.size()
    fun writeByte(v: Int) = out.write(v and 0xFF)
    fun writeBytes(b: ByteArray) = out.write(b)
    fun writeShortLE(v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
    }
    fun writeIntLE(v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
    }
    fun toByteArray(): ByteArray = out.toByteArray()
}
