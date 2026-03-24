plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "acab.naiveha.upnpkino"
    compileSdk = 36

    defaultConfig {
        buildConfigField("String", "APPLICATION_URL", "\"https://github.com/naive-HA\"")
        buildConfigField("String", "APPLICATION_NAME", "\"UPnP Kino\"")
        buildConfigField("String", "APPLICATION_MANUFACTURER", "\"naive-HA\"")
        applicationId = "acab.naiveha.upnpkino"
        minSdk = 35
        targetSdk = 36
        versionCode = 10
        val version = 3
        val versionMajor = 0
        val versionMinor = 0
        versionName = "${version}.${versionMajor}.${versionMinor}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }

        val copyTask = tasks.register<Copy>("copyRenamed${variantName}Apk") {
            from(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            include("*.apk")
            into(layout.buildDirectory.dir("../release"))
            rename { _ ->
                "UPnP.Kino.v${android.defaultConfig.versionName}.apk"
            }
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }

        tasks.matching { it.name == "assemble$variantName" }.configureEach {
            finalizedBy(copyTask)
        }

        tasks.matching { it.name == "create${variantName}ApkListingFileRedirect" }.configureEach {
            dependsOn(copyTask)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.jetty.server)
    implementation(libs.jetty.ee10.servlet)
    implementation(libs.androidx.documentfile)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
