plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "com.dudu"
version = "1.0.0"

dependencies {
    implementation("org.smali:dexlib2:2.5.2")
    implementation("org.smali:smali:2.5.2")
    implementation("com.android.tools:r8:8.2.42")
    implementation("com.android.tools.build:gradle:8.2.2")
    implementation("com.android.tools.build:apksig:8.2.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
    implementation(project(":mixin-lib"))
    implementation(project(":compiler"))
}

gradlePlugin {
    plugins {
        create("apkPatcher") {
            id = "com.dudu.apk-patcher"
            implementationClass = "com.dudu.mixin.plugin.ApkPatcherPlugin"
        }
    }
}
