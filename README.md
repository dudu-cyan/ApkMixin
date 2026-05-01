# ApkMixin

> SpongePowered Mixin 风格的 Android APK 补丁框架。
> 用纯 Kotlin 注解描述对目标 APK 中类与方法的修改，编译期收集元数据，构建期通过 Gradle 插件直接重写 DEX、修改资源、注入依赖，最后输出 zipalign + v1/v2/v3 签名的可安装 APK。

---

## 目录

- [项目动机](#项目动机)
- [快速开始](#快速开始)
- [模块结构](#模块结构)
- [Mixin 写法详解](#mixin-写法详解)
  - [@Mixin](#1-mixin--声明补丁类)
  - [@Inject](#2-inject--在目标方法中插入回调)
  - [@Overwrite](#3-overwrite--替换整个方法)
  - [@Redirect](#4-redirect--重定向调用)
  - [@ModifyArg / @ModifyVariable](#5-modifyarg--modifyvariable)
  - [@Shadow](#6-shadow--引用目标类成员)
  - [@Accessor](#7-accessor--访问私有成员)
  - [@At 注入点详解](#8-at-注入点详解)
- [Handler 参数传递约定](#handler-参数传递约定)
- [DSL 配置参考](#dsl-配置参考)
  - [基本配置](#基本配置)
  - [第三方库注入](#第三方库注入)
  - [代码混淆](#代码混淆)
  - [资源 Hook](#资源-hook)
  - [APK 签名](#apk-签名)
- [端到端示例](#端到端示例)
- [架构与实现](#架构与实现)
- [构建与运行](#构建与运行)
- [常见问题 / 故障排除](#常见问题--故障排除)
- [已知限制](#已知限制)
- [License](#license)

---

## 项目动机

修改一个已发布的 Android APK 通常只有几条路：

1. **反编译 → 改 smali → 重打包**：易碎、难维护、IDE 没有任何辅助。
2. **Xposed / Frida**：依赖 root 或注入器，分发受限。
3. **重编译源码**：不是总有源码。

**ApkMixin** 借鉴 Minecraft 模组生态里 [SpongePowered/Mixin](https://github.com/SpongePowered/Mixin) 的做法：
让你像写普通 Kotlin 类一样描述要打的补丁，所有的修改都在 **构建期** 落入 DEX，
产物就是一个普通可安装的 APK，无需运行时框架、无需 root。

---

## 快速开始

### 1. 引入插件

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts (app module)
plugins {
    id("com.android.application")
    kotlin("android")
    id("com.dudu.apk-patcher") version "1.0.0"
}

dependencies {
    implementation("com.dudu:mixin-lib:1.0.0")
}

apkPatcher {
    targetApk.set(file("app-release.apk"))
}
```

### 2. 写一个 Mixin

```kotlin
@Mixin(target = "com.example.MainActivity")
class MainActivityMixin {

    @JvmStatic
    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("HEAD"))
    fun onOnCreate(activity: MainActivity, savedInstanceState: Bundle?) {
        println("onCreate called on $activity")
    }

    @JvmStatic
    @Overwrite(method = "getTitle()Ljava/lang/String;")
    fun getNewTitle(): String = "Hooked Title!"
}
```

### 3. 执行补丁

```bash
./gradlew :app:patchApk
```

产物：`app/build/patched-apk/patched-app-release.apk`（已 zipalign + v1/v2/v3 签名）。

> 默认会把 `dependencies { implementation(...) }` 中的库自动 dex 化注入 APK，无需手动配置。

---

## 模块结构

| 模块 | 角色 |
| --- | --- |
| `mixin-lib`  | Mixin 注解定义 — `@Mixin`, `@Inject`, `@Redirect`, `@Overwrite`, `@ModifyArg`, `@ModifyVariable`, `@Shadow`, `@Accessor`, `@At` 以及 `CallbackInfo` / `CallbackInfoReturnable` |
| `compiler`   | Kotlin IR 编译器插件 — 扫描注解，提取 Mixin → 目标类映射，输出 `build/mixin-metadata/*.json` |
| `plugin`     | Gradle 插件 `com.dudu.apk-patcher` — 读取元数据，执行 DEX 重写、资源 hook、混淆、签名 |
| `example`    | 端到端示例 |

---

## Mixin 写法详解

### 1. @Mixin — 声明补丁类

```kotlin
@Mixin(target = "com.example.MainActivity")
class MainActivityMixin { ... }
```

**参数：**

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `target` | String | — | 目标类 JVM 全限定名（必填，与 targetClass 二选一） |
| `targetClass` | KClass | Unit | 目标类引用（与 target 二选一） |
| `priority` | Int | 1000 | 当多个 Mixin 注入同个目标时，数值越大优先级越高 |
| `remap` | Boolean | true | 是否参与重映射 |

Mixin 可以是 **class** 或 **object**。两者的区别在于：

- **class**：成员方法是非静态的，编译期在 JVM 字节码中会多一个 `this` 参数
- **object**：成员方法是静态的，字节码中无额外 `this` 参数

但对于 ApkMixin，无论 class 还是 object，**所有 Mixin 方法都需要加 `@JvmStatic`**（因为 DEX 中用 `invoke-static` 调用）。

### 2. @Inject — 在目标方法中插入回调

```kotlin
@Inject(
    method = "onCreate(Landroid/os/Bundle;)V",
    at = @At("HEAD")
)
```

**参数：**

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `method` | String | — | 目标方法签名，格式为 `方法名(参数描述符)返回描述符`（必填） |
| `at` | @At | — | 注入点位置（必填） |
| `cancellable` | Boolean | false | 如果设为 true，Mixin 框架会创建 `CallbackInfo` 对象传入 handler |
| `require` | Int | 0 | 要求至少找到 N 个匹配的注入点，否则编译失败；0 表示不强制 |

**目标方法签名写法：**

```kotlin
// void 方法
@Inject(method = "onCreate(Landroid/os/Bundle;)V", ...)
           方法名(                     )返回类型

// 有返回值的方法
@Inject(method = "getString(I)Ljava/lang/String;", ...)

// 无参方法
@Inject(method = "onResume()V", ...)
```

> `L` 开头的类型要写完整的 JVM 内部名：`Landroid/os/Bundle;`（注意分号结尾）。
> 基本类型：`I`=int, `J`=long, `Z`=boolean, `F`=float, `D`=double, `V`=void。

**`@At` 取值：**

| 值 | 语义 |
| --- | --- |
| `HEAD` | 在方法开头插入 |
| `RETURN` | 在每个 return 指令前插入 |
| `TAIL` | 在方法末尾（含 throw）前插入 |
| `INVOKE` | 在匹配的 invoke-* 调用前/后插入，需配合 `target` |
| `FIELD_GET` | 在匹配的字段读取前/后插入 |
| `FIELD_SET` | 在匹配的字段写入前/后插入 |
| `CONSTANT` | 在匹配的常量加载指令处插入 |

**Handler 参数会自动映射：**

```kotlin
class MyMixin {
    @JvmStatic
    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("HEAD"))
    fun onOnCreate(activity: MyActivity, savedInstanceState: Bundle?) {
        // activity        ← 目标类的 this 实例
        // savedInstanceState ← onCreate 的第一个参数 (Bundle)
    }

    // 也可只取需要的参数
    @JvmStatic
    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("HEAD"))
    fun onOnCreateSimple(savedInstanceState: Bundle?) {
        // 只接收 Bundle 参数，不接收 target 实例
    }

    // 不接收任何参数
    @JvmStatic
    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("HEAD"))
    fun onOnCreateSimple() {
        // 无参数
    }
}
```

详细参数映射规则见 [Handler 参数传递约定](#handler-参数传递约定)。

### 3. @Overwrite — 替换整个方法

```kotlin
@Overwrite(method = "getTitle()Ljava/lang/String;")
fun getNewTitle(): String = "Hooked Title!"
```

**参数：**

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `method` | String | — | 目标方法签名（必填） |
| `keepOriginal` | Boolean | false | 若为 true，保留原始方法重命名为 `<name>$original` |

**示例：**

```kotlin
@Overwrite(method = "onResume()V", keepOriginal = true)
fun onResumeHook() {
    // 调用原始方法（原始方法已被重命名为 onResume$original）
    onResume$original()
    // 额外逻辑
    println("after original onResume")
}
```

> **注意**：`onResume$original()` 需要声明为 `@JvmStatic` + 可见的。实际上你需要通过反射或 shadow 方法来调用。

Overwrite 的参数传递规则与 Inject 相同：如果 handler 第一个参数类型匹配目标类，会自动传入 `this`。

### 4. @Redirect — 重定向调用

将目标方法体中的特定 `invoke-*` 指令重定向到 Mixin 的静态方法。

```kotlin
@Redirect(
    method = "onCreate(Landroid/os/Bundle;)V",
    at = @At(
        value = "INVOKE",
        target = "Lcom/example/Logger;log(Ljava/lang/String;)V"
    )
)
fun onLogRedirect(msg: String) {
    // 原本会调用 Logger.log()，现在全部走这里
    println("[redirected] $msg")
}
```

**参数：**

| 参数 | 说明 |
| --- | --- |
| `method` | 目标方法（在哪个方法里做替换） |
| `at.value` | 固定为 `"INVOKE"` |
| `at.target` | 被替换的调用签名，格式：`Lowner类;方法名(参数描述符)返回描述符` |

**用法要点：**
- Handler 的参数列表必须与被替换的 `invoke-*` 指令完全一致（非静态调用需要把 receiver 作为首参）
- 返回值也要匹配

```kotlin
// 目标：bitmap.getWidth()  → 这是一个虚拟调用，receiver 是 Bitmap
@Redirect(
    method = "draw()V",
    at = @At(value = "INVOKE", target = "Landroid/graphics/Bitmap;getWidth()I")
)
fun onGetWidthRedirect(bitmap: Bitmap): Int {
    return bitmap.width * 2  // 返回两倍宽度
}
```

### 5. @ModifyArg / @ModifyVariable

**@ModifyArg** — 修改某次 invoke 调用的第 N 个参数：

```kotlin
@ModifyArg(
    method = "onCreate(Landroid/os/Bundle;)V",
    at = @At(value = "INVOKE", target = "Landroid/widget/TextView;setText(Ljava/lang/CharSequence;)V"),
    index = 0     // 修改第一个参数
)
fun onSetTextArg(text: String): String {
    return "[modified] $text"
}
```

**@ModifyVariable** — 修改方法体内的局部变量（按寄存器位置匹配）：

```kotlin
@ModifyVariable(
    method = "onCreate(Landroid/os/Bundle;)V",
    at = @At("HEAD"),
    ordinal = 0  // 第一个局部变量
)
fun onModifyVar(value: Int): Int {
    return value + 1
}
```

### 6. @Shadow — 引用目标类成员

如果 Mixin 中需要访问目标类已有的字段或方法，用 `@Shadow` 声明：

```kotlin
@Mixin(target = "com.example.MainActivity")
class MainActivityMixin {

    @Shadow
    private lateinit var mBase: Context  // shadow 字段

    @Shadow
    private fun getApplicationContext(): Context  // shadow 方法

    @JvmStatic
    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("RETURN"))
    fun onCreated(activity: MainActivity) {
        val ctx = activity.getApplicationContext()  // 调用 shadow 方法
        Toast.makeText(ctx, "Hooked!", Toast.LENGTH_LONG).show()
    }
}
```

**要点：**
- `@Shadow` 只是声明，不会往目标类添加新成员，仅仅是告诉编译器「这个成员存在」
- 在 DEX 中调用时，引用会自动指向目标类而非 Mixin 类
- 被 `@Shadow` 标记的成员会在 D8 编译前从 Mixin 的 .class 文件中移除，避免类重复

### 7. @Accessor — 访问私有成员

为目标类的私有字段生成 getter/setter：

```kotlin
@Mixin(target = "com.example.SomeClass")
class SomeClassMixin {

    // 为私有字段 private int count 生成访问器
    @Accessor
    fun getCount(): Int

    // 也可以指定目标字段名（默认按方法名推断）
    @Accessor(target = "mSecretKey")
    fun secretKey(): String

    @JvmStatic
    @Inject(method = "doSomething()V", at = @At("HEAD"))
    fun hook(instance: SomeClass) {
        val count = instance.getCount()
    }
}
```

> **注意**：目前 `@Accessor` 生成的代码在注入框架中尚未完全实现，建议优先使用 `@Shadow`。

### 8. @At 注入点详解

`@At` 注解定义在目标方法中的 **精确插入位置**。

```kotlin
@At("HEAD")                                    // 简写：方法开头
@At(value = "RETURN", ordinal = 0)             // 第一个 return 前
@At(value = "INVOKE", target = "L...", shift = Shift.AFTER)  // 某个调用之后
```

**参数说明：**

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `value` | — | 注入点类型 |
| `target` | "" | 对 INVOKE/FIELD_GET/FIELD_SET 精确匹配的目标 |
| `offset` | 0 | 在匹配位置的偏移量（正/负整数） |
| `ordinal` | -1 | 选择第 N 个匹配点，-1 表示全部匹配 |
| `shift` | "BEFORE" | `BEFORE` 在匹配点前插入，`AFTER` 在之后插入 |
| `args` | [] | 额外参数 |

#### HEAD

```kotlin
@At("HEAD")
```
在方法的第一条指令前插入。最常用。

#### RETURN

```kotlin
@At("RETURN")
```
在每个 `return` 指令前插入。常用于在方法返回前获取/修改返回值。

配合 `ordinal` 选择第几个 return（0-based）：

```kotlin
@At(value = "RETURN", ordinal = 0)
// 只在第一个 return 前插入
```

#### TAIL

```kotlin
@At("TAIL")
```
在所有正常出口（return）和异常出口（throw）前插入。与 RETURN 的区别是 TAIL 也会匹配 throw。

#### INVOKE

```kotlin
@At(value = "INVOKE", target = "Landroid/util/Log;d(Ljava/lang/String;Ljava/lang/String;)I")
```
在匹配的 invoke-* 指令前（BEFORE）或后（AFTER）插入。

**target 格式：** `Lowner类;方法名(参数描述符)返回描述符`

#### FIELD_GET / FIELD_SET

```kotlin
@At(value = "FIELD_GET", target = "Lcom/example/Data;value:Ljava/lang/String;")
@At(value = "FIELD_SET", target = "Lcom/example/Data;count:I")
```
在字段读取/写入指令前或后插入。

**target 格式：** `Lowner类;字段名:字段类型`

#### CONSTANT

```kotlin
@At(value = "CONSTANT", target = "some string value")
```
在加载指定常量的指令处插入。如果不指定 target，匹配所有常量加载指令。

---

## Handler 参数传递约定

这是 ApkMixin 最核心的约定之一：**Handler 方法的声明参数决定了注入器会传入什么寄存器**。

### 基本规则

对于目标方法 `void onCreate(Bundle savedInstanceState)`（非静态）：

| Handler 声明参数 | 注入器传入的寄存器 |
| --- | --- |
| `(activity: MainActivity?)` | 目标类的 `this` 实例 |
| `(savedInstanceState: Bundle?)` | `onCreate` 的第一个参数 |
| `(activity: MainActivity?, savedInstanceState: Bundle?)` | `this` + 第一个参数 |
| `()` | 不传任何参数 |

### 判断逻辑（混合检测）

判断是否将目标实例 `this` 作为 handler 首参传入，基于以下 **二选一** 规则：

1. **方法本身是非静态的**（class 中不加 `@JvmStatic` 的方法）—— 按 Sponge 惯例，handler 的第一个声明参数**就是**目标实例，无条件视为 receiver
2. **第一个参数类型与 `@Mixin(target = "...")` 声明的目标类完全一致** —— 适用于 object + `@JvmStatic` 静态方法

> 规则 1 保证 class mixin 即使参数声明为 `Any` 也能正确传入目标实例。
> 规则 2 保证 object/static mixin 在声明精确类型时也能使用 receiver。

### 示例集

```kotlin
// 目标类：com.example.MainActivity

// ─── class Mixin（不加 @JvmStatic）───
// 按惯例，首个声明参数必是目标实例，无论类型如何
@Mixin(target = "com.example.MainActivity")
class ClassMixin {
    // 场景 1：首参声明为 Any → 拿到 MainActivity 实例
    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("HEAD"))
    fun hook1(activity: Any?, savedInstanceState: Bundle?) {
        // activity = MainActivity 实例 ✓（惯例保证）
    }

    // 场景 2：首参声明为目标类型 → 也正确
    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("HEAD"))
    fun hook2(activity: MainActivity?, savedInstanceState: Bundle?) {
        // activity = MainActivity 实例 ✓
    }
}

// ─── object Mixin（必须加 @JvmStatic）───
// 必须通过精确类型匹配来判定
@Mixin(target = "com.example.MainActivity")
object ObjectMixin {
    @JvmStatic
    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("HEAD"))
    fun hook3(activity: MainActivity?, savedInstanceState: Bundle?) {
        // activity = MainActivity 实例 ✓（类型精确匹配）
    }

    // ❌ 以下写法在 object 中不会自动识别为 receiver
    @JvmStatic
    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("HEAD"))
    fun hook4(activity: Any?, savedInstanceState: Bundle?) {
        // activity = Bundle 实例 ✗（Any ≠ MainActivity，类型不匹配）
        // 运行时 ClassCastException！
    }

    // 如果拿不到目标类型，但只需要参数（不要 this）
    @JvmStatic
    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("HEAD"))
    fun hook5(savedInstanceState: Bundle?) {
        // 只接收 Bundle ✓
    }
}
```

> **总结**：**class Mixin 不加 `@JvmStatic` 最安全** —— 第一个参数永远自动接收目标实例。
> object Mixin 也可以用，但第一个参数必须声明为精确的目标类型才能拿到 `this`。
> 如果拿不到目标类型（目标 APK 类不在 classpath 上），建议用 class Mixin。

### Overwrite 中的参数约定

与 Inject 一致：

```kotlin
@Overwrite(method = "onCreate(Landroid/os/Bundle;)V")
fun onCreateHook(activity: MainActivity, savedInstanceState: Bundle?) {
    // activity 是 this, savedInstanceState 是参数
}
```

---

## DSL 配置参考

### 基本配置

```kotlin
apkPatcher {
    // ── 必填 ──
    targetApk.set(file("app-release.apk"))

    // ── Mixin 类来源（可选） ──
    // 默认自动从 KotlinJvmCompile 的 destinationDirectory 收集
    mixinClasses.from(fileTree("build/classes"))
}
```

> **提示**：设置 `targetApk` 后，插件会自动从其中的 DEX 提取应用类打包为 stub JAR
> （`build/target-stubs/classes.jar`）并加入 `compileOnly` classpath。
> 这意味着你可以在 Mixin 代码中直接 **import 目标 APK 中的任意类**，无需自己写 stub 或强制使用 `Any`。

### Stub 生成与作用域控制

从 `targetApk` 中读取所有 DEX，自动为应用类生成编译期 stub，让 Mixin 代码可以直接 import 目标 APK 中的类：

```kotlin
// Mixin 代码现在可以直接 import 目标 APK 中的类
import com.example.MainActivity
import android.os.Bundle

@Mixin(target = "com.example.MainActivity")
class MainActivityMixin {

    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("HEAD"))
    fun onCreate(activity: MainActivity, savedInstanceState: Bundle?) {
        // 可以直接使用精确类型，无需 Any
        val intent = activity.intent
        val title = activity.title
        println("onCreate: $title")
    }
}
```

**工作原理：**

- 构建期自动触发 `generateTargetStubs` 任务
- 解包 `targetApk` 中的所有 `classes*.dex`
- 使用 dexlib2 读取每个类的结构（类名、父类、接口、方法签名、字段签名）
- 用 ASM 生成空的 .class 文件（无方法体，仅保留签名）
- 自动跳过 `android.*`、`androidx.*`、`java.*`、`kotlin.*` 等系统包
- 打包为 JAR 加入 `compileOnly` classpath

**注意：**
- Stub 是**编译期纯引用**，不会出现在最终 APK 中（`compileOnly` 作用域）
- 只保留类结构签名，**不包含方法实现**
- 所有方法和字段在 stub 中被标记为 `public`，方便 Mixin 代码引用
- 建议优先用 `class` Mixin + 不加 `@JvmStatic` 以获得最佳 receiver 推断

**控制 Stub 生成范围：**

默认会为所有非系统类生成 stub（可能包含大量 AndroidX / Kotlin 类），通过 `stubIncludePrefixes` 可限制范围：

```kotlin
apkPatcher {
    targetApk.set(file("app-release.apk"))

    // 只对 com.tencent.qqnt 包下的类生成 stub
    stubIncludePrefixes.add("com.tencent.qqnt")
    stubIncludePrefixes.add("com.tencent.mobileqq")
}
```

- 指定后，只有匹配前缀的类会生成 stub，避免与 `dependencies { implementation(...) }` 中的 AndroidX / Kotlin stdlib 冲突
- 未指定时（默认），对所有非系统类生成 stub
- 注：即使 stub 范围受限，父类链仍然通过 DEX 原始信息正确解析，不影响类型层次

### 第三方库注入

```kotlin
apkPatcher {
    // 手动追加 jar/aar/class
    extraLibraries.from(file("libs/helper.aar"))
    extraLibraries.from(file("libs/sdk.jar"))

    // ── 自动注入（默认开启） ──
    // 自动把 dependencies { implementation(...) } 中的依赖全量 dex 化注入 APK
    autoInjectImplementation.set(true)

    // 选择从哪个 Android variant 的 runtimeClasspath 获取依赖
    autoInjectVariant.set("release")     // 默认

    // 跳过特定 group（防止 Kotlin stdlib 等与宿主冲突）
    autoInjectExcludeGroups.add("org.jetbrains.kotlin")
    autoInjectExcludeGroups.add("org.jetbrains.kotlinx")
}
```

自动注入的去重规则：
- 与宿主 APK 中已有类型重名的 → 跳过
- 多个库之间的同名类型 → 先到先得
- 默认跳过 `com.dudu:compiler`（构建期依赖）
- 超过 64K 方法 → 自动 multi-dex（`classes.dex`, `classes2.dex`, ...）

### 代码混淆

基于 ASM 的字节码混淆器：

```kotlin
apkPatcher {
    obfuscation {
        // 总开关
        enabled.set(true)

        // 类名重命名（如 com.example.Helper → a.a.a）
        renameClasses.set(true)

        // 成员重命名（较保守，默认关闭）
        renameMembers.set(false)

        // 字符串字面量 XOR 加密
        encryptStrings.set(true)

        // 随机种子，固定种子可保证增量构建时映射一致
        seed.set(0xC0FFEEL)

        // 排除前缀（不参与混淆的类名前缀）
        excludePrefixes.add("com.dudu.keep")
    }
}
```

混淆器自动保护以下系统类不被重命名：
- `java.*`, `javax.*`, `android.*`, `androidx.*`, `kotlin.*`, `kotlinx.*`
- Mixin 注解相关的类（`com.dudu.mixin.lib.*`）

### 资源 Hook

提供两个维度的资源修改：

**1. 资产覆盖**：将文件放在 `src/main/res-hook/` 下，目录结构对应 APK 内路径：

```
src/main/res-hook/
├── assets/
│   └── config.json         → APK 内 /assets/config.json
├── res/
│   └── drawable/
│       └── icon.png        → APK 内 /res/drawable/icon.png
└── lib/
    └── arm64-v8a/
        └── libnative.so    → APK 内 /lib/arm64-v8a/libnative.so
```

**2. AndroidManifest 二进制补丁**（不反编译 XML，直接在 AXML 字节层面追加）：

```kotlin
apkPatcher {
    resourceHook {
        // 总开关
        enabled.set(true)

        // 添加权限
        permissions.add("android.permission.INTERNET")
        permissions.add("android.permission.ACCESS_FINE_LOCATION")

        // 在 <application> 内添加 <meta-data>
        metaData.put("MIXIN_BUILD", "1.0.0")
        metaData.put("INJECT_TIMESTAMP", "${System.currentTimeMillis()}")

        // 修改 <application> 属性
        applicationAttributes.put("debuggable", "true")
        applicationAttributes.put("allowBackup", "true")

        // 添加四大组件
        activity("com.dudu.NewActivity") {
            exported.set(true)
        }
        service("com.dudu.BackgroundService")
        receiver("com.dudu.BootReceiver")
        provider("com.dudu.FileProvider", authorities = "com.dudu.fileprovider")
    }
}
```

> 所有组件都会插在 `<application>` 块末尾、原始组件之前。
> 只支持追加，不支持删除/修改已有组件。

### APK 签名

```kotlin
apkPatcher {
    signing {

        // ── 默认行为：自动生成 debug key ──
        useDebugKey.set(true)
        // 生成的 keystore 位于 build/patched-apk/mixin-debug.keystore
        // keystorePassword = "android", keyAlias = "mixin-debug", keyPassword = "android"

        // ── 也可使用自定义 keystore ──
        // keystorePath.set(file("release.keystore"))
        // keystorePassword.set("password")
        // keyAlias.set("release-key")
        // keyPassword.set("password")

        // 选择签名方案（v1/v2/v3 任意组合）
        v1.set(true)
        v2.set(true)
        v3.set(true)

        // 是否在签名前做 zipalign（强烈建议保留 true，否则 v2/v3 校验会失败）
        zipAlign.set(true)
    }
}
```

---

## 端到端示例

### 例：Hook Activity 生命周期

```kotlin
@Mixin(target = "com.example.MainActivity")
class LifecycleMixin {

    @JvmStatic
    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("HEAD"))
    fun beforeOnCreate(activity: MainActivity, savedInstanceState: Bundle?) {
        Log.d("Mixin", ">>> onCreate: activity=$activity bundle=$savedInstanceState")
    }

    @JvmStatic
    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("RETURN"))
    fun afterOnCreate(activity: MainActivity) {
        Log.d("Mixin", "<<< onCreate done")
    }

    @JvmStatic
    @Inject(method = "onResume()V", at = @At("HEAD"))
    fun onResume(activity: MainActivity) {
        Log.d("Mixin", ">>> onResume")
    }

    @JvmStatic
    @Inject(method = "onPause()V", at = @At("HEAD"))
    fun onPause() {
        Log.d("Mixin", ">>> onPause (no args)")
    }
}
```

### 例：替换方法返回值

```kotlin
@Mixin(target = "com.example.Utils")
object UtilsMixin {

    @JvmStatic
    @Overwrite(method = "getVersion()Ljava/lang/String;")
    fun getVersion(): String = "999.999.999"

    @JvmStatic
    @Overwrite(method = "isProUser()Z")
    fun isProUser(): Boolean = true
}
```

### 例：使用 @Shadow 访问目标类成员

```kotlin
@Mixin(target = "com.example.MainActivity")
class ShadowMixin {

    @Shadow
    private lateinit var mBase: Context

    @Shadow
    private fun getDataManager(): DataManager

    @JvmStatic
    @Inject(method = "onResume()V", at = @At("HEAD"))
    fun onResume(activity: MainActivity) {
        val ctx = activity.mBase  // 访问 shadow 字段
        val dm = activity.getDataManager()  // 调用 shadow 方法
        Toast.makeText(ctx, "Data: ${dm.getCount()}", Toast.LENGTH_SHORT).show()
    }
}
```

---

## 架构与实现

### 处理管线

```
Kotlin 源码
    │
    ▼
[Kotlin IR 编译器插件] ──→ build/mixin-metadata/*.json
    │                          │
    │  .class 文件             │ JSON 元数据
    ▼                          ▼
[Mixin 类 → D8 → DEX]    [DEX 重写引擎 (dexlib2)]
    │                          │
    ▼                          ▼
[字节码混淆（ASM）]    [Inject/Overwrite/Redirect 字节码变换]
    │                          │
    ▼                          ▼
[第三方库 → D8 → DEX (去重)]
    │
    ▼
[资源 Hook (AXML 二进制补丁 + 资产覆盖)]
    │
    ▼
[重打包 ZIP → zipalign → v1/v2/v3 签名]
    │
    ▼
patched-*.apk
```

### 各组件详解

#### 编译器插件 (`compiler`)

- 基于 Kotlin IR（中间表示）在编译期扫描所有类
- 找到 `@Mixin` 注解的类后，遍历其方法：
  - 解析 `@Inject` / `@Overwrite` 等注解提取目标方法签名
  - 检查参数列表识别 `CallbackInfo`
  - 检查第一个参数类型判断是否需要传入目标实例（`hasReceiver`）
  - 将所有元数据序列化为 JSON
- 强制让被注解的方法可见性提升为 `PUBLIC`，防止 Kotlin 编译器优化掉

#### DEX 重写引擎 (`plugin`)

基于 [dexlib2](https://github.com/JesusFreke/smali) 直接在 DEX 字节码层面操作：

- **@Inject @HEAD**：在方法第一条指令前插入 `invoke-static {v...}, MixinClass;->method(...)` 
- **@Inject @RETURN/TAIL**：遍历所有 return/throw 指令，在它们之前插入调用
- **@Inject @INVOKE**：匹配 invoke-* 指令的 owner/name/descriptor，在其前/后插入调用
- **@Overwrite**：完全替换方法体，生成从 `v0` 开始的连续寄存器布局
- **@Redirect**：将匹配的 invoke-* 指令的 method ref 替换为 Mixin 的静态方法
- **@Shadow 处理**：在 D8 编译前用 ASM 从 .class 文件中移除 @Shadow 方法，并将 Mixin 内的引用重定向到目标类

#### 第三方库注入

- 自动解析 `runtimeClasspath`（或 Android 的 `${variant}RuntimeClasspath`）
- 将 jar/aar 解压为 .class
- 用 ASM 读取每个类的 `this_class` 与宿主 APK 已有类型去重
- 批量用 D8 编译为 DEX，写入 `classesN.dex` 槽位
- 支持超过 64K 方法的 multi-dex

#### 资源 Hook (AXML)

- 纯 Kotlin 实现的 Android 二进制 XML (AXML) 解析/生成器
- 不依赖 Android SDK，能在构建期直接操作压缩的 XML
- 保持字符串池、resource map 的一致性
- 在 `<application>` 块末尾追加新元素

#### APK 签名器

- 封装 `com.android.apksig.ApkSigner`
- 支持 v1 (JAR)、v2 (APK Signature Scheme v2)、v3 (APK Signature Scheme v3) 任意组合
- 自动生成 25 年有效期的 debug keystore（首次运行通过 `keytool` 生成）
- 签名前自动做 4 字节对齐（zipalign），`.so` 文件 4096 字节对齐

---

## 构建与运行

### 首次构建

需要先将 `mixin-lib`、`compiler`、`plugin` 发布到本地 Maven：

```bash
./gradlew publishToMavenLocal
```

### 应用补丁

```bash
# 在 example 或你自己的 app 模块中
./gradlew :app:patchApk
```

### 查看产物

```bash
ls app/build/patched-apk/
# patched-app-release.apk    ← 最终可安装的 APK
# unsigned-app-release.apk   ← 未签名版本
# work/                      ← 展开的 APK 工作目录（调试用）
```

### 安装测试

```bash
adb install app/build/patched-apk/patched-app-release.apk
```

> 如果目标 APK 的签名与你的调试 key 不一致，需要先卸载原应用。

---

## 常见问题 / 故障排除

### Q: 编译期没有生成 mixin-metadata JSON

**确认：**
1. `plugin/build.gradle.kts` 使用了 `kotlin-dsl` 插件
2. 工程依赖了 `implementation(project(":compiler"))`
3. 编译器插件 SPI 注册文件存在：
   - `compiler/src/main/resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor`
   - `compiler/src/main/resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar`
4. 清理重试：`./gradlew clean :app:compileKotlin`

### Q: 运行时 VerifyError / ClassCastException

**原因：** Handler 的参数映射与目标方法实际参数不匹配。

**排查步骤：**
1. 检查 `@Inject(method = "...")` 中的签名是否正确（特别是参数描述符）
2. 检查 handler 的参数声明顺序是否符合传参约定
3. 如果要接收目标实例 `this`，第一个参数类型必须与 `@Mixin(target = "...")` 中的目标类**完全一致**

常见错误：
```kotlin
// ❌ 错误：签名写错了
@Inject(method = "onCreate(Landroid/os/Bundle;)V", ...)
fun hook(savedInstance: Bundle?)  // 参数名拼错不影响运行时，但类型不匹配会影响

// ❌ 错误：使用父类类型作为第一个参数（不会被识别为 receiver）
@Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("HEAD"))
fun hook(activity: AppCompatActivity, savedInstanceState: Bundle?)
// AppCompatActivity ≠ MainActivity → activity 实际拿到的是 Bundle 实例 → ClassCastException

// ✅ 正确：使用精确的目标类名
@Inject(method = "onCreate(Landroid/os/Bundle;)V", at = @At("HEAD"))
fun hook(activity: MainActivity, savedInstanceState: Bundle?)
```

### Q: @Overwrite 后 APK 安装失败

**原因：** 通常是因为方法参数寄存器数量不匹配导致的 VerifyError。

**解决方案：**
- 确保 Overwrite 方法声明了与原始方法完全一致的参数（类型要兼容）
- 如果 Overwrite 不需要所有参数，可以用精确的类型匹配规则（见上）
- 打开 `keepOriginal` 并用 `buildOverwriteMethod` 排查

### Q: 第三方库注入后出现 duplicate class

自动去重应该处理了这个问题。如果仍然出现：
1. 检查是否用了 `autoInjectExcludeGroups` 排除冲突的 group
2. 手动用 `extraLibraries.from(...)` + 关闭 `autoInjectImplementation` 精确控制

### Q: 签名失败

1. 检查 JDK 版本（需要 JDK 21+）
2. 如果 `keytool` 不可用，手动生成 debug keystore：
   ```bash
   keytool -genkey -v -keystore debug.keystore \
     -alias mixin-debug -keyalg RSA -keysize 2048 \
     -validity 9125 -storepass android -keypass android \
     -dname "CN=Mixin Debug, OU=Unknown, O=Unknown, L=Unknown, ST=Unknown, C=Unknown"
   ```
3. 将生成的文件复制到 `build/patched-apk/mixin-debug.keystore`

---

## 已知限制

- `@At("JUMP")` 暂未实现
- `@Accessor` 尚未完整实现，建议优先使用 `@Shadow`
- 资源 Hook 仅支持追加节点/修改属性，不支持删除已有节点
- `resources.arsc` 仅检测存在性，暂不重写字符串表
- 混淆的 `renameMembers` 较保守，默认关闭
- 类型匹配使用精确比较（`==`），不支持继承/接口检查
- 构建环境需要 **JDK 21+**
- 暂不支持 Kotlin Multiplatform / Compose Multiplatform

---

## License

Apache-2.0
