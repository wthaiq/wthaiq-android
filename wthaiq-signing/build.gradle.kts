import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    signing
}

android {
    namespace = "com.wthaiq.signing"
    compileSdk = 35
    resourcePrefix = "wthaiq_"

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        targetSdk = 35
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.systemProperty("wthaiq.version", property("VERSION_NAME") as String) }
        animationsDisabled = true
    }

    lint {
        targetSdk = 35
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    api(libs.androidx.activity)
    api(libs.androidx.fragment)
    implementation(libs.androidx.core)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.intents)
}
