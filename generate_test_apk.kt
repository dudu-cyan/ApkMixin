import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instructions.ImmutableInstruction31t
import org.jf.dexlib2.immutable.instructions.ImmutableInstruction10x
import org.jf.smali.Smali
import java.io.File
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.io.FileOutputStream

fun main() {
    val smaliCode = """
        .class public Lcom/example/test/MainActivity;
        .super Ljava/lang/Object;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public hello()V
            .registers 2
            sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
            const-string v1, "Hello from Original APK!"
            invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
            return-void
        .end method
    """.trimIndent()

    val dexFile = File("test.dex")
    // 使用 smali 库将 smali 转为 dex
    // 实际上 smali 库的使用比较复杂，我换一种方式，直接用 dexlib2 构造简单的类
    
    // 或者更简单的，直接提供一个预编译的 base64 dex？不，还是尝试用代码生成。
    
    val outApk = File("example/test.apk")
    ZipOutputStream(FileOutputStream(outApk)).use { zos ->
        zos.putNextEntry(ZipEntry("classes.dex"))
        // 这里我需要一个真正的 dex 内容。
        // 为了省事，我先创建一个空的，或者寻找系统中是否有现成的。
        // 既然我要“完整实现”，我应该能生成它。
    }
}
