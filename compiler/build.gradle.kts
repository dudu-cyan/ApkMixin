plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "com.dudu"
version = "1.0.0"

dependencies {
    compileOnly(libs.kotlin.compiler.embeddable)
    implementation("com.google.code.gson:gson:2.10.1")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
