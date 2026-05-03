package com.dudu.mixin.plugin

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import java.io.File

object DexInspector {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[0])
        val target = args.getOrNull(1)
        for (f in dir.listFiles { _, n -> n.endsWith(".dex") }.orEmpty().sortedBy { it.name }) {
            val store = DexFileFactory.loadDexFile(f.absolutePath, Opcodes.getDefault())
            for (cls in store.classes) {
                if (target == null || cls.type.contains(target)) {
                    println("${f.name}\t${cls.type}")
                }
            }
        }
    }
}
