import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.services)
  alias(libs.plugins.firebase.crashlytics)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
  if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { load(it) }
  }
}
val hasReleaseKeystore =
  keystorePropsFile.exists() &&
    !keystoreProps.getProperty("storeFile").isNullOrBlank() &&
    rootProject.file(keystoreProps.getProperty("storeFile")).exists()

val googleServicesFile = file("google-services.json")
val allowCrashlyticsMappingUpload =
  System.getenv("CI") != "true" &&
    providers.gradleProperty("crashlyticsMappingUpload").orNull != "false" &&
    googleServicesFile.exists() &&
    !googleServicesFile.readText().contains("ci-stub-gameday-live")

android {
  namespace = "com.mflgameday.live"
  compileSdk = 36
  ndkVersion = "28.2.13676358"
  defaultConfig {
    minSdk = 31
    targetSdk = 36
    versionCode = 12
    versionName = "1.3.2"
  }

  flavorDimensions += "distribution"
  productFlavors {
    create("direct") {
      dimension = "distribution"
      applicationId = "com.mflgameday.live"
      resValue("string", "app_name", "MFL GameDay")
      buildConfigField("boolean", "IS_PLAY_STORE_BUILD", "false")
    }
    create("playStore") {
      dimension = "distribution"
      applicationId = "com.motiskoventures.gameday"
      resValue("string", "app_name", "GameDay Live")
      buildConfigField("boolean", "IS_PLAY_STORE_BUILD", "true")
    }
  }

  signingConfigs {
    if (hasReleaseKeystore) {
      create("release") {
        storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      ndk {
        debugSymbolLevel = "FULL"
      }
      (this as org.gradle.api.plugins.ExtensionAware).extensions.configure(
        com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension::class.java
      ) {
        mappingFileUploadEnabled = allowCrashlyticsMappingUpload
      }
      if (hasReleaseKeystore) {
        signingConfig = signingConfigs.getByName("release")
      }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    resValues = true
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.service)

  // Coroutines, Serialization & Networking
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)
  implementation(libs.coil.compose)
  implementation(libs.coil.gif)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material3.windowsizeclass)
  implementation(libs.androidx.compose.material.icons.extended)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, MockWebServer
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Native Google Firebase Crashlytics & Analytics (both direct & playStore flavors; AD_ID stripped in Manifest)
  val firebaseBom = platform(libs.firebase.bom)
  implementation(firebaseBom)
  implementation(libs.firebase.crashlytics)
  implementation(libs.firebase.analytics)
  implementation(libs.androidx.fragment)

  // Google Play In-App Updates (strictly scoped to playStore flavor; Direct APK stays Play-Core-free)
  add("playStoreImplementation", libs.play.app.update.ktx)
}
