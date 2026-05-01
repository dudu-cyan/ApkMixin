package com.dudu.mixin.plugin.obf

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/* ─────────────────────── 选项 ─────────────────────── */

data class ObfuscatorOptions(
    val renameClasses: Boolean = true,
    val renameMembers: Boolean = false,
    val encryptStrings: Boolean = true,
    val seed: Long = 0xC0FFEEL,
    val excludePrefixes: List<String> = emptyList(),
)

data class ObfuscationReport(
    val classMapping: Map<String, String>,
    val encryptedStrings: Int,
    val touchedClasses: Int,
)

/* ─────────────────────── 主体 ─────────────────────── */

/**
 * 字节码层（.class）混淆器。
 *
 *  - 类重命名：对非保护类（不在 builtin/exclude 前缀里、且不是 @Mixin 标注类）打散到 `obf.cN_xxxx`。
 *  - 字符串加密：把所有字符串字面量改写成 `StringDecryptor.dec(<encStr>, <key>)` 调用，运行时还原。
 *  - Mixin 类（带 com.dudu.mixin.lib.Mixin 注解）以及匹配 `excludePrefixes` 的类型整体跳过，
 *    保证元数据 JSON 中记录的类/方法名仍可在 dex 注入阶段定位到。
 *
 * 使用方法：
 * ```
 * val opts = ObfuscatorOptions(...)
 * val report = MixinObfuscator(opts).process(stagingDir, outputDir)
 * ```
 */
class MixinObfuscator(private val options: ObfuscatorOptions) {

    private val mixinAnnotationDesc = "Lcom/example/mixin/lib/Mixin;"
    private val builtinProtected = listOf(
        "java/", "javax/", "android/", "androidx/",
        "kotlin/", "kotlinx/",
        "org/jetbrains/", "org/intellij/",
        "com/example/mixin/", // 同时保护我们自己的 runtime 包
    )

    private val classRenameCounter = AtomicInteger(0)

    fun process(inputDir: File, outputDir: File): ObfuscationReport {
        if (!inputDir.exists()) return ObfuscationReport(emptyMap(), 0, 0)
        outputDir.mkdirs()

        // 1) 扫描所有类：拿到 internal name + 是否为 Mixin 类
        data class ScanInfo(
            val file: File,
            val internalName: String,
            val isMixinClass: Boolean,
        )

        val scans: List<ScanInfo> = inputDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { f ->
                val bytes = f.readBytes()
                val cr = ClassReader(bytes)
                var isMixin = false
                cr.accept(object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(desc: String?, visible: Boolean): AnnotationVisitor? {
                        if (desc == mixinAnnotationDesc) isMixin = true
                        return null
                    }
                }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                ScanInfo(f, cr.className, isMixin)
            }
            .toList()

        if (scans.isEmpty()) return ObfuscationReport(emptyMap(), 0, 0)

        // 2) 构造 class 重命名映射
        val rng = Random(options.seed)
        val classMap = mutableMapOf<String, String>()
        if (options.renameClasses) {
            for (s in scans) {
                if (shouldProtect(s.internalName, s.isMixinClass)) continue
                classMap[s.internalName] = mangleClassName(rng)
            }
        }
        val remapper = object : Remapper() {
            override fun map(internalName: String): String =
                classMap[internalName] ?: internalName
        }

        // 3) 重写每一个类
        var encCount = 0
        var touched = 0
        for (s in scans) {
            val bytes = s.file.readBytes()
            val cr = ClassReader(bytes)
            val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)

            // 按 [Remapper] → [StringEncryptor] → [ClassWriter] 链
            var cv: ClassVisitor = cw
            if (options.encryptStrings && !s.isMixinClass) {
                cv = StringEncryptingClassVisitor(cv, options.seed.toInt()) { encCount++ }
            }
            if (options.renameClasses && classMap.isNotEmpty()) {
                cv = ClassRemapper(cv, remapper)
            }
            cr.accept(cv, 0)

            val outName = (classMap[s.internalName] ?: s.internalName) + ".class"
            val outFile = File(outputDir, outName).apply { parentFile.mkdirs() }
            outFile.writeBytes(cw.toByteArray())
            touched++
        }

        // 4) 如果做了字符串加密，注入 StringDecryptor 类
        if (options.encryptStrings && encCount > 0) {
            val dec = StringDecryptorEmitter.emit()
            val outFile = File(outputDir, "com/example/mixin/runtime/StringDecryptor.class")
                .apply { parentFile.mkdirs() }
            outFile.writeBytes(dec)
        }

        return ObfuscationReport(classMap.toMap(), encCount, touched)
    }

    private fun shouldProtect(internalName: String, isMixinClass: Boolean): Boolean {
        if (isMixinClass) return true
        for (p in builtinProtected) if (internalName.startsWith(p)) return true
        for (p in options.excludePrefixes) {
            val ip = p.replace('.', '/').trimEnd('/')
            if (internalName == ip || internalName.startsWith("$ip/")) return true
        }
        return false
    }

    private fun mangleClassName(rng: Random): String {
        val idx = classRenameCounter.getAndIncrement()
        val suffix = rng.nextInt(0, 0xFFFF).toString(16)
        return "obf/c_${idx}_${suffix}"
    }
}

/* ─────────────────────── 字符串加密访问器 ─────────────────────── */

private class StringEncryptingClassVisitor(
    next: ClassVisitor,
    private val key: Int,
    private val onEncrypted: () -> Unit,
) : ClassVisitor(Opcodes.ASM9, next) {

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        // <clinit> / <init> 不加密以避免引导问题
        if (name == "<clinit>" || name == "<init>") return mv
        return object : MethodVisitor(Opcodes.ASM9, mv) {
            override fun visitLdcInsn(value: Any?) {
                if (value is String && value.isNotEmpty()) {
                    val enc = encrypt(value, key)
                    super.visitLdcInsn(enc)
                    super.visitLdcInsn(key)
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "com/example/mixin/runtime/StringDecryptor",
                        "dec",
                        "(Ljava/lang/String;I)Ljava/lang/String;",
                        false,
                    )
                    onEncrypted()
                } else {
                    super.visitLdcInsn(value)
                }
            }
        }
    }

    companion object {
        fun encrypt(s: String, key: Int): String {
            val bytes = s.toByteArray(Charsets.UTF_8)
            val k = key.toByte().toInt() and 0xFF
            val out = ByteArray(bytes.size) { i ->
                ((bytes[i].toInt() and 0xFF) xor k).toByte()
            }
            // ISO-8859-1 是 1:1 字节↔字符的映射，便于 LDC 编码并在运行时通过同一编码取回
            return String(out, Charsets.ISO_8859_1)
        }
    }
}

/* ─────────────────────── StringDecryptor 类发射器 ─────────────────────── */

internal object StringDecryptorEmitter {
    /**
     * 生成 `com/example/mixin/runtime/StringDecryptor` 类（Java 1.8 字节码）：
     *
     * ```java
     * public final class StringDecryptor {
     *   public static String dec(String enc, int key) {
     *     byte[] in = enc.getBytes("ISO-8859-1");
     *     byte[] out = new byte[in.length];
     *     for (int i = 0; i < in.length; i++) out[i] = (byte)(in[i] ^ key);
     *     return new String(out, "UTF-8");
     *   }
     * }
     * ```
     */
    fun emit(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "com/example/mixin/runtime/StringDecryptor",
            null,
            "java/lang/Object",
            null,
        )

        // private <init>()
        cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        // public static String dec(String enc, int key)
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "dec",
            "(Ljava/lang/String;I)Ljava/lang/String;",
            null,
            arrayOf("java/io/UnsupportedEncodingException"),
        )
        mv.visitCode()
        // byte[] in = enc.getBytes("ISO-8859-1")  → local 2
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitLdcInsn("ISO-8859-1")
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "getBytes",
            "(Ljava/lang/String;)[B",
            false,
        )
        mv.visitVarInsn(Opcodes.ASTORE, 2)
        // int len = in.length → local 3
        mv.visitVarInsn(Opcodes.ALOAD, 2)
        mv.visitInsn(Opcodes.ARRAYLENGTH)
        mv.visitVarInsn(Opcodes.ISTORE, 3)
        // byte[] out = new byte[len] → local 4
        mv.visitVarInsn(Opcodes.ILOAD, 3)
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE)
        mv.visitVarInsn(Opcodes.ASTORE, 4)
        // int i = 0 → local 5
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitVarInsn(Opcodes.ISTORE, 5)

        val loop = Label()
        val end = Label()
        mv.visitLabel(loop)
        mv.visitVarInsn(Opcodes.ILOAD, 5)
        mv.visitVarInsn(Opcodes.ILOAD, 3)
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, end)
        // out[i] = (byte)(in[i] ^ key)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitVarInsn(Opcodes.ILOAD, 5)
        mv.visitVarInsn(Opcodes.ALOAD, 2)
        mv.visitVarInsn(Opcodes.ILOAD, 5)
        mv.visitInsn(Opcodes.BALOAD)
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        mv.visitInsn(Opcodes.IXOR)
        mv.visitInsn(Opcodes.I2B)
        mv.visitInsn(Opcodes.BASTORE)
        // i++
        mv.visitIincInsn(5, 1)
        mv.visitJumpInsn(Opcodes.GOTO, loop)
        mv.visitLabel(end)

        // return new String(out, "UTF-8")
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/String")
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitLdcInsn("UTF-8")
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/String",
            "<init>",
            "([BLjava/lang/String;)V",
            false,
        )
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }
}
