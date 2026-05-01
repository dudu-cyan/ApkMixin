package com.dudu.mixin.plugin.sign

import com.android.apksig.ApkSigner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * 基于 [com.android.apksig.ApkSigner] 的真实 APK 签名器，支持 v1+v2+v3 三套方案任意组合。
 *
 * 当 `keystorePath` 为空时会在 build dir 下自动生成一个 debug keystore（PKCS12 格式，
 * 自签名 X.509 证书，25 年有效期），后续 patch 任务可重复使用以减少噪音。
 * 生成方式使用 `keytool` 命令行（JDK 附带），不依赖任何 sun.* 内部 API，兼容 JDK 17/21+。
 */
object ApkSignerHelper {

    data class Options(
        val keystorePath: File?,
        val keystorePassword: String,
        val keyAlias: String,
        val keyPassword: String,
        val useDebugKey: Boolean,
        val v1Signing: Boolean,
        val v2Signing: Boolean,
        val v3Signing: Boolean,
        /** 当 useDebugKey=true 且 keystorePath 为空时，debug keystore 落盘位置。 */
        val debugKeystoreFallback: File,
    )

    fun sign(input: File, output: File, opts: Options) {
        val (privateKey, certs) = loadOrCreateSigningMaterial(opts)

        val signerConfig = ApkSigner.SignerConfig.Builder(
            "MIXIN", privateKey, certs,
        ).build()

        val builder = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(opts.v1Signing)
            .setV2SigningEnabled(opts.v2Signing)
            .setV3SigningEnabled(opts.v3Signing)
            // 显式提供 minSdkVersion 防止 apksig 无法从 AndroidManifest 推断
            .setMinSdkVersion(21)

        val signer = builder.build()
        signer.sign()
        println("[apksig] signed: v1=${opts.v1Signing} v2=${opts.v2Signing} v3=${opts.v3Signing} → ${output.name}")
    }

    private fun loadOrCreateSigningMaterial(opts: Options): Pair<PrivateKey, List<X509Certificate>> {
        val keystoreFile: File = when {
            opts.keystorePath != null && opts.keystorePath.exists() -> opts.keystorePath
            opts.useDebugKey -> ensureDebugKeystore(
                opts.debugKeystoreFallback, opts.keyAlias, opts.keystorePassword,
            )
            else -> error(
                "No keystore configured. Either provide signing.keystorePath or set signing.useDebugKey=true",
            )
        }

        val ksType = if (keystoreFile.name.endsWith(".p12") || keystoreFile.name.endsWith(".pfx"))
            "PKCS12" else "JKS"
        val ks = try {
            KeyStore.getInstance(ksType)
        } catch (_: Throwable) {
            KeyStore.getInstance("PKCS12")
        }
        FileInputStream(keystoreFile).use { ks.load(it, opts.keystorePassword.toCharArray()) }
        val key = ks.getKey(opts.keyAlias, opts.keyPassword.toCharArray()) as? PrivateKey
            ?: error("Key alias '${opts.keyAlias}' not found / not a private key in $keystoreFile")
        val chain = ks.getCertificateChain(opts.keyAlias)
            ?: error("Certificate chain for alias '${opts.keyAlias}' not found")
        val x509 = chain.map { it as X509Certificate }
        return key to x509
    }

    /**
     * 确保 debug keystore 已生成；若已存在直接复用。
     * 优先使用 `keytool` 命令行工具（完全公开 API），若找不到则回退到纯 JCA 方式。
     */
    private fun ensureDebugKeystore(target: File, alias: String, password: String): File {
        if (target.exists()) return target
        target.parentFile?.mkdirs()
        try {
            generateDebugKeystoreViaKeytool(target, alias, password)
        } catch (keytoolEx: Throwable) {
            // keytool 不可用时，尝试使用反射（旧版 JDK）
            try {
                generateDebugKeystoreReflective(target, alias, password)
            } catch (reflectEx: Throwable) {
                throw RuntimeException(
                    "Failed to auto-generate debug keystore at ${target.absolutePath}. " +
                        "Please provide signing.keystorePath manually. Cause: ${reflectEx.message}",
                    reflectEx,
                )
            }
        }
        println("[apksig] generated debug keystore: ${target.absolutePath}")
        return target
    }

    /**
     * 通过 `keytool` 命令行生成 debug keystore。
     * `keytool` 是 JDK 的标准组成部分（$JAVA_HOME/bin/keytool），适用于 JDK 8/11/17/21+。
     */
    private fun generateDebugKeystoreViaKeytool(target: File, alias: String, password: String) {
        // 找到 keytool 可执行文件
        val javaHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME")
        val keytoolName = if (System.getProperty("os.name").lowercase().contains("win")) "keytool.exe" else "keytool"

        val keytoolCandidates = listOfNotNull(
            if (javaHome != null) File(javaHome, "bin/$keytoolName") else null,
            if (javaHome != null) File(javaHome, "../bin/$keytoolName") else null,  // JDK 8 jre/ 目录
            File(keytoolName), // PATH 搜索
        )
        val keytool = keytoolCandidates.firstOrNull { it.canExecute() }?.canonicalPath ?: keytoolName

        val cmd = listOf(
            keytool,
            "-genkeypair",
            "-keystore", target.absolutePath,
            "-storetype", "PKCS12",
            "-storepass", password,
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-keypass", password,
            "-validity", "9125",   // 25 年
            "-dname", "CN=Mixin Debug, OU=Mixin, O=Example, L=Beijing, ST=Beijing, C=CN",
        )
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        if (exitCode != 0 || !target.exists()) {
            throw RuntimeException("keytool exited $exitCode:\n$output")
        }
    }

    /**
     * 旧版 JDK 反射回退（JDK 8/11 下 sun.* 可访问时使用）。
     */
    private fun generateDebugKeystoreReflective(target: File, alias: String, password: String) {
        val genClass = Class.forName("sun.security.tools.keytool.CertAndKeyGen")
        val gen = genClass.getConstructor(String::class.java, String::class.java)
            .newInstance("RSA", "SHA256WithRSA")
        genClass.getMethod("generate", Int::class.javaPrimitiveType).invoke(gen, 2048)
        val privateKey = genClass.getMethod("getPrivateKey").invoke(gen) as PrivateKey

        val x500Class = Class.forName("sun.security.x509.X500Name")
        val dn = x500Class.getConstructor(String::class.java).newInstance(
            "CN=Mixin Debug, OU=Mixin, O=Example, L=Beijing, ST=Beijing, C=CN",
        )
        val seconds = 25L * 365L * 24L * 60L * 60L
        val cert = genClass
            .getMethod("getSelfCertificate", x500Class, Long::class.javaPrimitiveType)
            .invoke(gen, dn, seconds) as X509Certificate

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(alias, privateKey, password.toCharArray(), arrayOf(cert))
        FileOutputStream(target).use { ks.store(it, password.toCharArray()) }
    }
}
