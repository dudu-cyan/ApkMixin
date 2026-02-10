plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "com.example"
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
