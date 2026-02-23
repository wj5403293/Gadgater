# Frida Gadget 注入器 Android App — 技术调研报告

> 目标：构建一个 Android 应用，能选择已安装 App 或本地 APK，自动注入 Frida Gadget 并重新签名，输出可安装的 patched APK。

---

## 一、核心流程

```
选择 APK
  → apktool decode（反编译）
  → 注入 libfrida-gadget.so 到 /lib/<abi>/
  → 在入口 smali 插入 System.loadLibrary("frida-gadget")
  → 添加 INTERNET 权限（AndroidManifest.xml）
  → apktool build（重打包）
  → zipalign 对齐
  → apksigner 重签名
  → 输出 patched APK
```

参考：
- https://koz.io/using-frida-on-android-without-root/
- https://docs.caido.io/app/tutorials/modifying_apk

---

## 二、现有参考工具

| 工具 | 语言 | 平台 | 链接 |
|------|------|------|------|
| frida-apk-patcher (SPERIXLABS) | Python | 桌面 | https://github.com/SPERIXLABS/frida-apk-patcher |
| apkpatcher (badadaf) | Python | 桌面 | https://github.com/badadaf/apkpatcher |
| ksg97031/frida-gadget | Python | 桌面 | https://github.com/ksg97031/frida-gadget |
| LIEF 注入法 | Python/C++ | 桌面 | https://lief.re/doc/stable/tutorials/09_frida_lief.html |

> ⚠️ 以上均为桌面工具。**在 Android 上做同样的事需要特殊处理**（见下）。

---

## 三、Android 端实现的技术挑战

| 挑战 | 说明 |
|------|------|
| apktool 依赖 JVM | apktool 是 Java CLI 工具，Android 上无法直接运行 |
| 文件权限 | Android 10+ 限制外部存储访问，需用 SAF（Storage Access Framework） |
| 签名 | 需在 App 内生成 keystore 或使用 BouncyCastle 签名 |
| 多架构 Gadget | 需要为 arm64-v8a / armeabi-v7a / x86_64 分别打包 |
| zipalign | 需要内嵌或调用 zipalign 逻辑 |

---

## 四、推荐技术栈

### 语言 & UI
- **Kotlin** + **Jetpack Compose**（现代 Android 开发首选）

### APK 解包/重打包
两种方案二选一：

**方案 A（推荐）：直接 ZIP 操作 + smali 库**
- `java.util.zip` / `ZipFile` — 解包/重打包 APK（APK 本质是 ZIP）
- [`smali/baksmali`](https://github.com/JesusFreke/smali) — DEX 级别注入 loadLibrary 调用
- 优点：无需 apktool，轻量，适合 Android 内嵌

**方案 B：内嵌 apktool JAR**
- 将 `apktool.jar` 作为 assets 打包，通过 `ProcessBuilder` 或反射调用
- 缺点：包体大（~10MB），启动慢，兼容性风险

### APK 签名
- [`BouncyCastle`](https://www.bouncycastle.org/) — 纯 Java，可在 Android 内生成 keystore 并签名
- 或使用 Android `KeyStore` API 生成密钥对后手动构造签名块

### Frida Gadget 获取
- 方案 1：App 内置多架构 `.so`（包体增大 ~30MB）
- 方案 2：运行时从 [GitHub Releases](https://github.com/frida/frida/releases) 下载对应架构版本（推荐，按需下载）

### 文件选择
- `ActivityResultContracts.GetContent` + SAF — 选择本地 APK 文件
- `PackageManager.getInstalledApplications` — 列出已安装 App，提取 APK 路径（`sourceDir`）

---

## 五、推荐项目结构

```
app/
├── ui/                  # Compose UI（选择 APK、进度、输出）
├── patcher/
│   ├── ApkUnpacker.kt   # ZIP 解包
│   ├── GadgetInjector.kt # 注入 .so + smali patch
│   ├── ManifestPatcher.kt # 添加 INTERNET 权限
│   ├── ApkRepacker.kt   # ZIP 重打包 + zipalign
│   └── ApkSigner.kt     # BouncyCastle 签名
├── gadget/
│   └── GadgetDownloader.kt # 按架构下载 frida-gadget.so
└── util/
    └── AppExtractor.kt  # 从已安装 App 提取 APK
```

---

## 六、关键依赖（build.gradle）

```kotlin
// BouncyCastle 签名
implementation("org.bouncycastle:bcpkix-jdk15on:1.70")

// smali/baksmali（DEX 操作）
implementation("com.android.tools.smali:smali:3.0.3")
implementation("com.android.tools.smali:baksmali:3.0.3")

// Jetpack Compose
implementation(platform("androidx.compose:compose-bom:2024.02.00"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")

// 协程（后台处理）
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
```

---

## 七、注意事项

1. **Play Integrity / 签名校验**：重签名后 App 签名变更，有签名校验的 App 会拒绝运行（无法绕过，这是预期行为）。
2. **Android 版本兼容**：Frida 14.2+ 支持 Android 11，15.0+ 支持 Android 12，建议下载最新版 Gadget。
3. **目标用途**：此工具适用于安全研究、逆向分析、自有 App 调试，不应用于未授权的第三方 App。

---

## 八、参考链接汇总

- Frida 官方文档：https://frida.re/docs/gadget/
- Frida Releases（Gadget 下载）：https://github.com/frida/frida/releases
- apktool：https://apktool.org/
- smali/baksmali：https://github.com/JesusFreke/smali
- LIEF（ELF 注入替代方案）：https://lief.re/doc/stable/tutorials/09_frida_lief.html
- koz.io 无 root 使用 Frida 教程：https://koz.io/using-frida-on-android-without-root/
- SPERIXLABS frida-apk-patcher：https://github.com/SPERIXLABS/frida-apk-patcher
- badadaf apkpatcher：https://github.com/badadaf/apkpatcher
- ksg97031/frida-gadget 工具：https://github.com/ksg97031/frida-gadget
- BouncyCastle：https://www.bouncycastle.org/
