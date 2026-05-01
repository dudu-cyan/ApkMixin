plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "com.dudu"
version = "1.0.0"

dependencies {
    implementation(kotlin("stdlib"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
