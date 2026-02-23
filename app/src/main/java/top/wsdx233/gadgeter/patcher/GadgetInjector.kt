package top.wsdx233.gadgeter.patcher

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.File

object GadgetInjector {

    fun disassembleDex(dexFile: File, outputDir: File) {
        val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
        val options = BaksmaliOptions()
        Baksmali.disassembleDexFile(dex, outputDir, 4, options)
    }

    fun reassembleDex(smaliDir: File, outputDex: File) {
        val options = SmaliOptions()
        options.outputDexFile = outputDex.absolutePath
        Smali.assemble(options, listOf(smaliDir.absolutePath))
    }

    fun injectLoadLibrary(smaliFile: File, fallbackLevel: Int = 1, libName: String = "frida-gadget"): Boolean {
        if (!smaliFile.exists()) return false
        val lines = smaliFile.readLines().toMutableList()

        val targetMethod = when (fallbackLevel) {
            1 -> "attachBaseContext"
            2 -> "<clinit>"
            3 -> "onCreate"
            else -> return false
        }
        
        var methodIdx = lines.indexOfFirst {
            it.contains(".method") && it.contains(targetMethod)
        }
        
        if (methodIdx == -1 && fallbackLevel == 2) {
            val endClassIdx = lines.indexOfFirst { it.startsWith(".source") || it.startsWith(".implements") || it.startsWith(".super") }.let { if (it == -1) 1 else it + 1 }
            lines.add(endClassIdx, """
                
.method static constructor <clinit>()V
    .registers 1
    const-string v0, "$libName"
    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
    return-void
.end method
                
            """.trimIndent())
            smaliFile.writeText(lines.joinToString("\n"))
            return true
        } else if (methodIdx == -1) {
            return false
        }

        val regIdx = lines.subList(methodIdx, lines.size).indexOfFirst { it.trimStart().startsWith(".registers") } + methodIdx
        if (regIdx >= methodIdx) {
            val current = lines[regIdx].trim().removePrefix(".registers").trim().toIntOrNull() ?: 2
            val needed = maxOf(current, getParamCount(lines, methodIdx) + 1)
            if (needed > current) {
                lines[regIdx] = lines[regIdx].replace(".registers $current", ".registers $needed")
            }
        } else {
            // no .registers found? Wait, Dalvik uses .locals sometimes maybe? Smali default uses .registers
            val localsIdx = lines.subList(methodIdx, lines.size).indexOfFirst { it.trimStart().startsWith(".locals") } + methodIdx
            if (localsIdx >= methodIdx) {
                val current = lines[localsIdx].trim().removePrefix(".locals").trim().toIntOrNull() ?: 0
                val needed = maxOf(current, 1)
                if (needed > current) {
                    lines[localsIdx] = lines[localsIdx].replace(".locals $current", ".locals $needed")
                }
            }
        }

        val insertIdx = findFirstInstruction(lines, methodIdx)
        lines.add(insertIdx, "    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V")
        lines.add(insertIdx, "    const-string v0, \"$libName\"")
        lines.add(insertIdx, "")

        smaliFile.writeText(lines.joinToString("\n"))
        return true
    }

    private fun findFirstInstruction(lines: List<String>, methodStart: Int): Int {
        val skipPrefixes = listOf(".registers", ".locals", ".param", ".prologue", ".line", ".local", "# ")
        for (i in (methodStart + 1) until lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty()) continue
            if (skipPrefixes.none { trimmed.startsWith(it) }) return i
        }
        return methodStart + 1
    }

    private fun getParamCount(lines: List<String>, methodStart: Int): Int {
        val sig = lines[methodStart]
        val params = sig.substringAfter("(").substringBefore(")")
        var count = if (!sig.contains("static")) 1 else 0
        var i = 0
        while (i < params.length) {
            when {
                params[i] == 'L' -> { count++; i = params.indexOf(';', i) + 1 }
                params[i] == '[' -> i++
                else -> { count++; i++ }
            }
        }
        return count
    }
}
