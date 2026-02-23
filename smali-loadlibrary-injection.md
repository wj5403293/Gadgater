# smali/baksmali 注入 System.loadLibrary 详细报告

> 场景：在 Android App 内，对目标 APK 的 DEX 文件进行修改，插入 `System.loadLibrary("frida-gadget")` 调用，使 App 启动时自动加载 Frida Gadget。

---

## 一、背景：smali 是什么

Android App 的 Java/Kotlin 代码最终编译为 **DEX 字节码**（`.dex` 文件）。  
smali/baksmali 是 DEX 的汇编/反汇编工具：

```
Java/Kotlin 源码
    ↓ javac / kotlinc
  .class 字节码
    ↓ d8 / dx
  classes.dex（DEX 格式）
    ↓ baksmali（反汇编）
  .smali 文本文件（人类可读）
    ↓ smali（汇编）
  classes.dex（修改后）
```

我们的目标：**在 DEX 反汇编后的 smali 文本中插入一段代码，再重新汇编回 DEX**。

---

## 二、要插入的 smali 代码

`System.loadLibrary("frida-gadget")` 对应的 smali：

```smali
const-string v0, "frida-gadget"
invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
```

- `const-string v0, "frida-gadget"` — 把字符串常量放入寄存器 v0
- `invoke-static {v0}, ...` — 调用静态方法 System.loadLibrary，传入 v0

---

## 三、注入位置选择

### 最佳注入点：Application.attachBaseContext

```smali
# 文件：com/example/app/MyApplication.smali
.method protected attachBaseContext(Landroid/content/Context;)V
    .registers 3          # p0=this, p1=context, 需要 v0 → 改为 3

    # ← 在这里插入 loadLibrary
    const-string v0, "frida-gadget"
    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V

    invoke-super {p0, p1}, Landroid/app/Application;->attachBaseContext(Landroid/content/Context;)V
    return-void
.end method
```

**为什么选这里？**
- `attachBaseContext` 是 Application 生命周期最早的回调
- 在任何 Activity 启动前执行
- 比 `onCreate` 更早

### 备选：主 Activity 的静态初始化块

```smali
.method static constructor <clinit>()V
    .registers 1

    const-string v0, "frida-gadget"
    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V

    return-void
.end method
```

---

## 四、寄存器处理（关键细节）

smali 方法头部的 `.registers N` 声明了该方法使用的寄存器总数。  
插入 `const-string v0, ...` 需要至少 1 个本地寄存器（v0）。

**规则：**
- 参数寄存器从高位开始（p0, p1, ...）
- 本地寄存器从低位开始（v0, v1, ...）
- `.registers N` = 本地寄存器数 + 参数寄存器数

**处理方式：**

```
原始：.registers 2  （0 个本地寄存器，p0=this, p1=context）
修改：.registers 3  （1 个本地寄存器 v0，p0=this, p1=context）
```

如果原本已有 `.registers 3` 以上，v0 已存在，**无需修改寄存器数**（但要确认 v0 在插入点未被使用，或选择其他空闲寄存器）。

---

## 五、如何找到注入目标文件

### 步骤 1：解析 AndroidManifest.xml，找 Application 类名

APK 内的 `AndroidManifest.xml` 是二进制 XML（AXML 格式），需要解析：

```kotlin
// 使用 AXMLParser 或 apk-parser 库
val manifest = ApkFile(apkPath).manifestXml  // apk-parser 库
val appClass = extractApplicationClass(manifest)
// 例如：com.example.app.MyApplication → com/example/app/MyApplication.smali
```

如果没有自定义 Application 类，则找主 Activity（`intent-filter` 含 `MAIN` + `LAUNCHER`）。

### 步骤 2：定位 smali 文件路径

```kotlin
val smaliPath = appClass.replace('.', '/') + ".smali"
// → "com/example/app/MyApplication.smali"
```

---

## 六、完整实现流程（Kotlin，Android App 内）

### 6.1 依赖

```kotlin
// build.gradle.kts
implementation("com.android.tools.smali:smali:3.0.3")
implementation("com.android.tools.smali:baksmali:3.0.3")
implementation("net.dongliu:apk-parser:2.6.10")  // 解析 AXML manifest
```

### 6.2 反汇编 DEX

```kotlin
import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes

fun disassembleDex(dexFile: File, outputDir: File) {
    val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
    val options = BaksmaliOptions()
    Baksmali.disassembleDexFile(dex, outputDir, 4, options)
}
```

### 6.3 修改 smali 文件（插入 loadLibrary）

```kotlin
fun injectLoadLibrary(smaliFile: File) {
    val lines = smaliFile.readLines().toMutableList()

    // 找到 attachBaseContext 方法开始位置
    val methodIdx = lines.indexOfFirst {
        it.contains(".method") && it.contains("attachBaseContext")
    }
    if (methodIdx == -1) return  // 方法不存在，换备选方案

    // 找 .registers 行，确保有 v0 可用
    val regIdx = lines.indexOfFirst { it.trimStart().startsWith(".registers") }
    if (regIdx != -1) {
        val current = lines[regIdx].trim().removePrefix(".registers").trim().toIntOrNull() ?: 2
        // 确保至少有 1 个本地寄存器
        val needed = maxOf(current, getParamCount(lines, methodIdx) + 1)
        if (needed > current) {
            lines[regIdx] = lines[regIdx].replace(
                ".registers $current", ".registers $needed"
            )
        }
    }

    // 找方法体第一条真正的指令（跳过 .registers / .param / .prologue 等指令）
    val insertIdx = findFirstInstruction(lines, methodIdx)

    lines.add(insertIdx, "    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V")
    lines.add(insertIdx, "    const-string v0, \"frida-gadget\"")
    lines.add(insertIdx, "")

    smaliFile.writeText(lines.joinToString("\n"))
}

/** 找方法体内第一条非指令行（.registers/.param/.prologue）之后的位置 */
fun findFirstInstruction(lines: List<String>, methodStart: Int): Int {
    val skipPrefixes = listOf(".registers", ".param", ".prologue", ".line", ".local", "# ")
    for (i in (methodStart + 1) until lines.size) {
        val trimmed = lines[i].trim()
        if (trimmed.isEmpty()) continue
        if (skipPrefixes.none { trimmed.startsWith(it) }) return i
    }
    return methodStart + 1
}

fun getParamCount(lines: List<String>, methodStart: Int): Int {
    val sig = lines[methodStart]
    // 统计参数个数（简化：数括号内的参数）
    val params = sig.substringAfter("(").substringBefore(")")
    var count = if (!sig.contains("static")) 1 else 0  // p0=this（非静态）
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
```

### 6.4 重新汇编 DEX

```kotlin
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions

fun reassembleDex(smaliDir: File, outputDex: File) {
    val options = SmaliOptions()
    options.outputDexFile = outputDex.absolutePath
    Smali.assemble(options, listOf(smaliDir.absolutePath))
}
```

### 6.5 整合调用

```kotlin
suspend fun patchDex(apkWorkDir: File, targetSmaliClass: String) {
    val dexFile = File(apkWorkDir, "classes.dex")
    val smaliDir = File(apkWorkDir, "smali_out")

    // 1. 反汇编
    disassembleDex(dexFile, smaliDir)

    // 2. 找到目标 smali 文件并注入
    val targetFile = File(smaliDir, "$targetSmaliClass.smali")
    if (targetFile.exists()) {
        injectLoadLibrary(targetFile)
    } else {
        // 目标类不存在，创建一个新的 Application 子类（见附录）
        createApplicationStub(smaliDir, targetSmaliClass)
    }

    // 3. 重新汇编
    reassembleDex(smaliDir, dexFile)
}
```

---

## 七、没有自定义 Application 类时的处理

如果目标 APK 没有自定义 Application 类，需要：

1. **创建一个新的 smali 文件**（Application 子类）
2. **修改 AndroidManifest.xml**，在 `<application>` 标签加上 `android:name`

新建的 smali 文件模板：

```smali
.class public Lcom/injected/FridaApp;
.super Landroid/app/Application;

.method protected attachBaseContext(Landroid/content/Context;)V
    .registers 3

    const-string v0, "frida-gadget"
    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V

    invoke-super {p0, p1}, Landroid/app/Application;->attachBaseContext(Landroid/content/Context;)V
    return-void
.end method
```

AndroidManifest.xml 修改（二进制 XML，需要 AXML 编辑库或 apktool 处理）：

```xml
<application android:name="com.injected.FridaApp" ...>
```

---

## 八、多 DEX 情况（multidex）

大型 App 可能有 `classes2.dex`、`classes3.dex` 等。  
Application 类通常在 `classes.dex`（主 DEX），但需要验证：

```kotlin
fun findDexContainingClass(apkDir: File, className: String): File? {
    return apkDir.listFiles { f -> f.name.matches(Regex("classes\\d*\\.dex")) }
        ?.firstOrNull { dexFile ->
            val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
            dex.classes.any { it.type == "L${className.replace('.','/')};" }
        }
}
```

---

## 九、完整流程图

```
APK (ZIP)
  ├── classes.dex ──→ baksmali ──→ smali 文件目录
  │                                    ↓
  │                          找到 Application.smali
  │                                    ↓
  │                          插入 loadLibrary smali 代码
  │                          调整 .registers 数量
  │                                    ↓
  │                          smali 重新汇编 → classes.dex
  │
  ├── lib/arm64-v8a/libfrida-gadget.so  ← 注入的 .so
  ├── AndroidManifest.xml               ← 添加 INTERNET 权限
  │
  └── 重新打包 ZIP → zipalign → apksigner 签名 → patched.apk
```

---

## 十、参考资料

- smali/baksmali 源码：https://github.com/JesusFreke/smali
- com.android.tools.smali Maven：https://mvnrepository.com/artifact/com.android.tools.smali
- smali 语法参考：https://github.com/JesusFreke/smali/wiki
- DEX 格式规范：https://source.android.com/docs/core/runtime/dex-format
- Frida Gadget 无 root 教程：https://koz.io/using-frida-on-android-without-root/
