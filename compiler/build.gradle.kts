plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "com.example"
version = "1.0.0"

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.9.22")
    implementation("com.google.code.gson:gson:2.10.1")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
