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
        versionCode = 13
        val version = 4
        val versionMajor = 0
        val versionMinor = 2
        versionName = "${version}.${versionMajor}.${versionMinor}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        missingDimensionStrategy("distribution", "arm64")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("arm64") {
            dimension = "distribution"
            applicationId = "acab.naiveha.upnpkino"
            versionCode = 2013
            ndk {
                //noinspection ChromeOsAbiSupport
                abiFilters += "arm64-v8a"
            }
        }
        create("armv7") {
            dimension = "distribution"
            applicationId = "acab.naiveha.upnpkino"
            versionCode = 1013
            ndk {
                //noinspection ChromeOsAbiSupport
                abiFilters += "armeabi-v7a"
            }
        }
        create("x86") {
            dimension = "distribution"
            applicationId = "acab.naiveha.upnpkino"
            versionCode = 3013
            ndk {
                //noinspection ChromeOsAbiSupport
                abiFilters += "x86"
            }
        }
        create("x86_64") {
            dimension = "distribution"
            applicationId = "acab.naiveha.upnpkino"
            versionCode = 4013
            ndk {
                //noinspection ChromeOsAbiSupport
                abiFilters += "x86_64"
            }
        }
        create("aab") {
            dimension = "distribution"
            applicationId = "acab.naiveha.upnpkino.by.naiveha"
            versionCode = 13
            ndk {
                //noinspection ChromeOsAbiSupport
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }
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

    @get:Input
    abstract val apkVersionName: Property<String>

    @get:Input
    abstract val flavorName: Property<String>

    @get:OutputDirectory
    abstract val releaseDir: DirectoryProperty

    @TaskAction
    fun execute() {
        val inDir = inputDir.orNull?.asFile
        val version = apkVersionName.get()
        val flavor = flavorName.get()
        val relDir = releaseDir.orNull?.asFile
        if (inDir != null && inDir.exists() && relDir != null) {
            relDir.mkdirs()
            val apkFile = inDir.walkTopDown().firstOrNull { it.isFile && it.extension == "apk" }
            if (apkFile != null) {
                val names = when (flavor) {
                    "arm64" -> listOf("UPnP.Kino.v$version-arm64-v8a.apk")
                    "armv7" -> listOf("UPnP.Kino.v$version-armeabi-v7a.apk")
                    "x86" -> listOf("UPnP.Kino.v$version-x86.apk")
                    "x86_64" -> listOf("UPnP.Kino.v$version-x86_64.apk")
                    else -> listOf("UPnP.Kino.v$version-$flavor.apk")
                }
                for (targetName in names) {
                    val destFile = relDir.resolve(targetName)
                    if (apkFile.absolutePath != destFile.absolutePath) {
                        apkFile.copyTo(destFile, overwrite = true)
                    }
                    val buildDestFile = apkFile.parentFile.resolve(targetName)
                    if (apkFile.absolutePath != buildDestFile.absolutePath) {
                        apkFile.copyTo(buildDestFile, overwrite = true)
                    }
                }
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
        val flavorName = variant.productFlavors.firstOrNull()?.second ?: "arm64"
        val isAab = flavorName == "aab" || variant.name.contains("aab", ignoreCase = true)
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
            val buildTypeName = variant.buildType
            val listingTaskName = "create${variant.name.replaceFirstChar { it.uppercase() }}ApkListingFileRedirect"

            val targetVersionName = apkVersionName
            val targetReleaseDir = releaseDir
            val targetFlavor = flavorName
            val copyAndRenameTask = tasks.register<CopyAndRenameApkTask>("copyAndRename${variant.name}Apk") {
                inputDir.set(layout.buildDirectory.dir("outputs/apk/$flavorName/$buildTypeName"))
                this.apkVersionName.set(targetVersionName)
                this.flavorName.set(targetFlavor)
                this.releaseDir.set(targetReleaseDir)
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