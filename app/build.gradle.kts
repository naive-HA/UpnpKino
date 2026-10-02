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
        minSdk = 29
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
        missingDimensionStrategy("distribution", "apk")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("apk") {
            dimension = "distribution"
            applicationId = "acab.naiveha.upnpkino"
        }
        create("aab") {
            dimension = "distribution"
            applicationId = "acab.naiveha.upnpkino.by.naiveha"
        }
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

abstract class CopyAndRenameBundleTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    abstract val inputBundle: RegularFileProperty

    @get:OutputFile
    abstract val outputReleaseFile: RegularFileProperty

    @get:OutputFile
    abstract val outputBuildFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val inFile = inputBundle.orNull?.asFile
        if (inFile != null && inFile.exists()) {
            outputReleaseFile.get().asFile.parentFile.mkdirs()
            outputBuildFile.get().asFile.parentFile.mkdirs()
            inFile.copyTo(outputReleaseFile.get().asFile, overwrite = true)
            inFile.copyTo(outputBuildFile.get().asFile, overwrite = true)
        }
    }
}

abstract class CopyAndRenameApkTask : DefaultTask() {
    @get:Internal
    abstract val inputDir: DirectoryProperty

    @get:OutputFile
    abstract val outputReleaseFile: RegularFileProperty

    @get:OutputFile
    abstract val outputBuildFile: RegularFileProperty

    @get:Input
    abstract val targetFileName: Property<String>

    @TaskAction
    fun execute() {
        val inDir = inputDir.orNull?.asFile
        if (inDir != null && inDir.exists()) {
            val apkFile = inDir.walkTopDown().firstOrNull { it.isFile && it.extension == "apk" && it.name != targetFileName.get() }
            if (apkFile != null) {
                outputReleaseFile.get().asFile.parentFile.mkdirs()
                outputBuildFile.get().asFile.parentFile.mkdirs()
                apkFile.copyTo(outputReleaseFile.get().asFile, overwrite = true)
                apkFile.copyTo(outputBuildFile.get().asFile, overwrite = true)
            }
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("debug").withFlavor("distribution" to "aab")) { variant ->
        variant.enable = false
    }

    onVariants { variant ->
        val apkVersionName = android.defaultConfig.versionName ?: "1.0"
        val isAab = variant.productFlavors.any { it.second == "aab" } || variant.name.contains("aab", ignoreCase = true)
        val ext = if (isAab) "aab" else "apk"
        val targetName = "UPnP.Kino.v$apkVersionName.$ext"
        val releaseDir = layout.projectDirectory.dir("../release")

        if (isAab) {
            val listingTaskName = "produce${variant.name.replaceFirstChar { it.uppercase() }}BundleIdeListingFile"
            val copyAndRenameTask = tasks.register<CopyAndRenameBundleTask>("copyAndRename${variant.name}Bundle") {
                inputBundle.set(layout.buildDirectory.file("outputs/bundle/${variant.name}/app-aab-release.aab"))
                outputReleaseFile.set(releaseDir.file(targetName))
                outputBuildFile.set(layout.buildDirectory.file("outputs/bundle/${variant.name}/$targetName"))
            }
            tasks.matching { it.name == listingTaskName }.configureEach {
                finalizedBy(copyAndRenameTask)
            }
        } else {
            val assembleTaskName = "assemble${variant.name.replaceFirstChar { it.uppercase() }}"
            val flavorName = variant.productFlavors.firstOrNull()?.second ?: "apk"
            val buildTypeName = variant.buildType
            val listingTaskName = "create${variant.name.replaceFirstChar { it.uppercase() }}ApkListingFileRedirect"

            val copyAndRenameTask = tasks.register<CopyAndRenameApkTask>("copyAndRename${variant.name}Apk") {
                inputDir.set(layout.buildDirectory.dir("outputs/apk/$flavorName/$buildTypeName"))
                outputReleaseFile.set(releaseDir.file(targetName))
                outputBuildFile.set(layout.buildDirectory.file("outputs/apk/$flavorName/$buildTypeName/$targetName"))
                targetFileName.set(targetName)
            }
            tasks.matching { it.name == assembleTaskName }.configureEach {
                finalizedBy(copyAndRenameTask)
            }
            tasks.matching { it.name == listingTaskName }.configureEach {
                mustRunAfter(copyAndRenameTask)
            }
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
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.effect)
    debugImplementation(libs.okhttp.logging)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
