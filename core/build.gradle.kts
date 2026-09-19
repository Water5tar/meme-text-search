plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.google.re2j:re2j:1.8")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
