# ApkMixin

> SpongePowered Mixin 风格的 Android APK 补丁框架。
> 用 Kotlin 注解描述对目标 APK 中类与方法的修改，编译期收集元数据，构建期通过 Gradle 插件直接重写 DEX、资源、AndroidManifest，最后做 zipalign + v1/v2/v3 签名。

---

## 项目动机

修改一个已发布的 Android APK 通常只有两条路：

1. 反编译 → 改 smali → 重打包：易碎、难维护、IDE 没有任何辅助。
2. 用 Xposed/Frida 这类运行时 hook 框架：依赖 root 或注入器，分发受限。

**ApkMixin** 借鉴 Minecraft 模组生态里 [SpongePowered/Mixin](https://github.com/SpongePowered/Mixin) 的做法：
让你像写普通 Kotlin 类一样描述要打的补丁，所有修改都在 **构建期** 落入 DEX，
产物就是一个普通可安装的 APK，无需运行时。

---

## 模块结构

| 模块 | 角色 |
| --- | --- |
| `mixin-lib`         | Mixin 注解定义 (`@Mixin`, `@Inject`, `@Redirect`, `@Overwrite`, `@ModifyArg`, `@ModifyVariable`, `@Shadow`, `@Accessor`, `@At`) |
| `compiler`          | Kotlin IR 编译器插件，扫描注解并把 Mixin → 目标类的映射输出到 `build/mixin-metadata/*.json` |
| `plugin`            | Gradle 插件 `com.dudu.apk-patcher`，读取元数据，执行 DEX 重写 / 资源 hook / 混淆 / 签名 |
| `compose-stubs`     | 编译期 stub，让示例可以直接 `import androidx.compose.*` 而不引入完整 Android 运行时 |
| `example`           | 端到端示例：生成测试 APK → 应用 Mixin → 输出 patched APK |

---

## 快速开始

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { mavenLocal(); google(); mavenCentral(); gradlePluginPortal() }
}

// build.gradle.kts
plugins {
    kotlin("jvm")
    id("com.dudu.apk-patcher") version "1.0.0"
}

dependencies {
    implementation(project(":mixin-lib"))
}

apkPatcher {
    targetApk.set(file("app-release.apk"))
}
```

### 写一个 Mixin

```kotlin
@Mixin(target = "com.dudu.test.MainActivity")
object MainActivityMixin {
    @JvmStatic
    @Inject(method = "hello", at = At(AtValues.HEAD))
    fun onHelloHook(activity: Any) {
        println("MainActivity.hello() HEAD hooked!")
    }
}
```

### 应用补丁

```bash
./gradlew :example:patchApk
```

产物：`example/build/patched-apk/patched-test.apk`（已 zipalign + 三套签名）。

> ✨ 自 v1.0.0 起，`dependencies { implementation(...) }` 中声明的库（jar / aar / `project(":xxx")`）
> 会在 `patchApk` 阶段被自动 dex 化并合入 APK，不再需要手动 `extraLibraries.from(...)`。
> 关闭：`apkPatcher { autoInjectImplementation.set(false) }`。

---

## DSL 参考

```kotlin
apkPatcher {
    // ── 必填 ──
    targetApk.set(file("app.apk"))

    // ── 可选：Mixin 类来源（默认会自动从 KotlinCompile 产物收集）──
    mixinClasses.from(...)

    // ── 第三方库注入（jar / aar），与宿主同名类型自动剔除 ──
    extraLibraries.from(file("libs/some-lib.jar"))

    // ── 自动注入 implementation 依赖（默认开启）──
    // 开启后，本模块 dependencies { implementation(...) } 声明的所有 jar/aar/project
    // 会在 afterEvaluate 阶段被自动追加到 extraLibraries，无需手写 from(...)。
    // 同名类型在 D8 合并阶段会被去重（宿主已有 → 跳过；多库重复 → 先到先得）。
    autoInjectImplementation.set(true)
    autoInjectVariant.set("release")            // 取哪个 Android variant 的 runtimeClasspath
    autoInjectExcludeGroups.add("org.jetbrains.kotlin")  // 想跳过 stdlib 时可加

    enableResourceHook.set(true)

    // ── 代码混淆（ASM）：类名重命名 + 字符串加密 ──
    obfuscation {
        enabled.set(true)
        renameClasses.set(true)
        renameMembers.set(false)
        encryptStrings.set(true)
        seed.set(0xC0FFEEL)
        excludePrefixes.add("com.dudu.keep")
    }

    // ── APK 签名（apksig）：v1+v2+v3，默认 debug 自签名 ──
    signing {
        useDebugKey.set(true)
        // keystorePath.set(file("release.keystore"))
        // keystorePassword.set("..."); keyAlias.set("..."); keyPassword.set("...")
        v1.set(true); v2.set(true); v3.set(true)
        zipAlign.set(true)
    }

    // ── 资源 hook：AndroidManifest 二进制补丁 + assets/res 覆盖 ──
    resourceHook {
        enabled.set(true)
        permissions.add("android.permission.INTERNET")
        metaData.put("MIXIN_BUILD", "1.0.0")
        applicationAttributes.put("debuggable", "true")
        activity("com.dudu.NewActivity") { exported.set(true) }
        service("com.dudu.MyService")
        receiver("com.dudu.MyReceiver")
        provider("com.dudu.MyProvider", authorities = "com.dudu.provider")
    }
}
```

资产/资源覆盖：把文件放在 `src/main/res-hook/` 下，目录结构对应 APK 内位置（如 `src/main/res-hook/assets/foo.json`）。

---

## Mixin 注解参考

| 注解 | 语义 |
| --- | --- |
| `@Mixin(target = "...")`            | 标注一个类是某个目标类的补丁 |
| `@Inject(method, at = @At(...))`    | 在目标方法的指定位置插入回调 |
| `@Redirect(method, at = @At(target=...))` | 把目标方法体里的 invoke-* 重定向到 Mixin 静态方法 |
| `@Overwrite(method, keepOriginal)`  | 整个方法体替换；`keepOriginal=true` 时保留 `<name>$original` |
| `@ModifyArg(method, index)`         | 修改某次 invoke 的第 N 个参数 |
| `@ModifyVariable(method, ordinal)`  | 修改方法体内的局部变量 |
| `@Shadow`                           | 声明一个目标类里的字段/方法供 Mixin 访问 |
| `@Accessor` / `@Invoker`            | 生成对私有字段/方法的 getter/setter |
| `@At(value, target, ordinal, shift)`| 注入点：HEAD / RETURN / TAIL / INVOKE / FIELD_GET / FIELD_SET / CONSTANT / JUMP |

---

## 实现要点

- **DEX 重写**：基于 [`smali/dexlib2`](https://github.com/JesusFreke/smali) 操作 DEX；Mixin 类先被 [D8](https://r8.googlesource.com/r8) 编译为 dex 后整体并入 APK。
- **第三方库注入**：jar/aar 解压 → D8 → 与宿主类型查重 → 作为新的 `classesN.dex` 落盘。
- **自动注入 implementation**：`afterEvaluate` 阶段按优先级读取
  `${variant}RuntimeClasspath` → `debugRuntimeClasspath` → `releaseRuntimeClasspath` → `runtimeClasspath`，
  通过 `incoming.artifactView { componentFilter }` 懒解析，把所有 jar/aar/project 追加到 `extraLibraries`；
  `com.dudu:compiler` 等仅构建期依赖会被硬性跳过。Multi-dex 产物（`classes2.dex` …）会按顺序编号占槽。
- **混淆**：ASM 9.x 的 `ClassRemapper` 重命名 + 字符串字面量 XOR 加密 + 解密入口注入。
- **资源 hook**：自实现的二进制 AXML parser/writer (`res/AxmlIo.kt`)，保留字符串池 / resource map，对 `<manifest>` / `<application>` 做尾部追加。
- **签名**：`com.android.apksig.ApkSigner` v1+v2+v3 任意组合；首次运行会通过反射调用 `sun.security.tools.keytool.CertAndKeyGen` 自动生成 25 年有效期的 debug PKCS12 keystore。
- **zipalign**：在签名前对 STORED 条目做 4 字节对齐，`.so` 做 4096 字节对齐。

---

## 构建 & 运行示例

```bash
# 1. 把所有模块发布到本地 mavenLocal（让 example 能解析到 plugin/compiler）
./gradlew publishToMavenLocal

# 2. 跑端到端补丁
./gradlew :example:patchApk

# 3. 看产物
ls example/build/patched-apk/
```

---

## 已知限制

- `@At` 完整支持 `HEAD` / `RETURN` / `TAIL` / `INVOKE` / `FIELD_GET` / `FIELD_SET` / `CONSTANT`（支持 `BEFORE`/`AFTER` shift 和 `ordinal` 选择第 N 个匹配点）；`JUMP` 暂未实现。
- 资源 hook 目前仅追加节点 / 修改 application 属性，不支持移除节点；`resources.arsc` 仅检测、不重写。
- 混淆的 `renameMembers` 还比较保守，默认关闭。
- 构建期需要 **JDK 21+**（受 `com.android.tools.build:gradle 8.2.2` 影响）。
- Debug keystore 自动生成走 `keytool` 命令行（无须 `sun.*` 内部 API），兼容 JDK 17/21+。

欢迎 PR 🛠️

---

## License

Apache-2.0
