plugins {
    id("com.android.library")
}

android {
    namespace = "acab.naiveha.ponyfill"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("--patch-module", "java.base=" + file("src/main/java").absolutePath))
    }
}
