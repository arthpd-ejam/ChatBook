import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  // AGP 9 has built-in Kotlin support, so org.jetbrains.kotlin.android is NOT applied here.
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.chatbook"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    applicationId = "com.chatbook"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Sign release builds with the debug key so the CI APK installs without a keystore.
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures { compose = true }
  // LiteRT-LM ships the same JNI library in more than one AAR.
  packaging { jniLibs { pickFirsts.add("**/liblitertlm_jni.so") } }
  lint { abortOnError = false }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
    // The litertlm AAR is built with a newer Kotlin metadata version.
    freeCompilerArgs.addAll("-Xskip-metadata-version-check")
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.material.icons.core)
  implementation(libs.androidx.work.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.litertlm)
  implementation(libs.pdfbox.android)
  implementation(libs.jsoup)
  testImplementation(libs.junit)
  debugImplementation(libs.androidx.ui.tooling)
}
