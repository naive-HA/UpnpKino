plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "acab.naiveha.upnpkino"
    compileSdk = 37

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    defaultConfig {
        buildConfigField("String", "APPLICATION_URL", "\"https://github.com/naive-HA\"")
        buildConfigField("String", "APPLICATION_NAME", "\"UPnP Kino\"")
        buildConfigField("String", "APPLICATION_MANUFACTURER", "\"naive-HA\"")
        applicationId = "acab.naiveha.upnpkino"
        minSdk = 26
        //noinspection EditedTargetSdkVersion
        targetSdk = 37
        versionCode = 11
        val version = 4
        val versionMajor = 0
        val versionMinor = 0
        versionName = "${version}.${versionMajor}.${versionMinor}"

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }

        val apkVersionName = android.defaultConfig.versionName
        val releaseDir = layout.buildDirectory.dir("../release")

        val copyTask = tasks.register<Copy>("copyRenamed${variantName}Apk") {
            from(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            include("*.apk")
            into(releaseDir)
            rename { _ ->
                "UPnP.Kino.v${apkVersionName}.apk"
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
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":ponyfill"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.jetty.server)
    implementation(libs.androidx.documentfile)
    implementation(libs.okhttp)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    debugImplementation(libs.okhttp.logging)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
