plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
        versionCode = 2
        var versionMajor = 0
        var versionMinor = 2
        versionName = "${versionCode}.${versionMajor}.${versionMinor}"

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

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (variant.buildType.name == "release") {
                val appName = "UPnP Kino"
                val versionName = variant.versionName
                output.outputFileName = "${appName} v${versionName}.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("org.eclipse.jetty:jetty-server:12.1.5")
    implementation("org.eclipse.jetty.ee10:jetty-ee10-servlet:12.1.5")
    implementation("androidx.documentfile:documentfile:1.1.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
