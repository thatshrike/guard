import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.Locale
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.acousticguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.acousticguard"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += "tflite"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // These dependency pins are intentionally coordinated with AGP 8.7.3 and compileSdk 35.
        disable += "GradleDependency"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.tensorflow:tensorflow-lite-task-audio:0.4.4")
    implementation("org.tensorflow:tensorflow-lite:2.13.0")
    implementation("com.google.auto.value:auto-value-annotations:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.register("validateModelManifests") {
    group = "verification"
    description = "Verifies packaged model and detection-policy integrity manifests."

    doLast {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        fun sha256(file: java.io.File): String = sha256(file.readBytes())

        val assetsDir = project.file("src/main/assets")
        val slurper = JsonSlurper()

        val yamnet = assetsDir.resolve("yamnet.tflite")
        val yamnetManifestFile = assetsDir.resolve("yamnet_model_manifest.json")
        check(yamnet.isFile) { "Missing bundled YAMNet asset: ${yamnet.path}" }
        check(yamnetManifestFile.isFile) { "Missing bundled YAMNet manifest: ${yamnetManifestFile.path}" }
        val yamnetManifest = slurper.parse(yamnetManifestFile) as Map<*, *>
        check(yamnetManifest["artifact"] == yamnet.name) {
            "YAMNet manifest points to ${yamnetManifest["artifact"]}, expected ${yamnet.name}."
        }
        check(sha256(yamnet).equals(yamnetManifest["sha256"]?.toString(), ignoreCase = true)) {
            "Bundled YAMNet SHA-256 does not match its manifest."
        }

        val policyFile = assetsDir.resolve("detection_policy_v1.json")
        check(policyFile.isFile) { "Missing detection policy asset: ${policyFile.path}" }
        val policy = slurper.parse(policyFile) as Map<*, *>
        val canonicalPolicy = listOf(
            "policy_version=${policy["policy_version"]}",
            "scream_threshold=${"%.6f".format(Locale.US, (policy["scream_threshold"] as Number).toFloat())}",
            "respiratory_threshold=${"%.6f".format(Locale.US, (policy["respiratory_threshold"] as Number).toFloat())}",
            "impact_threshold=${"%.6f".format(Locale.US, (policy["impact_threshold"] as Number).toFloat())}",
            "impact_jerk_threshold=${"%.6f".format(Locale.US, (policy["impact_jerk_threshold"] as Number).toFloat())}",
            "uncertainty_threshold=${"%.6f".format(Locale.US, (policy["uncertainty_threshold"] as Number).toFloat())}",
            "audio_quality_threshold=${"%.6f".format(Locale.US, (policy["audio_quality_threshold"] as Number).toFloat())}",
            "required_consecutive_windows=${policy["required_consecutive_windows"]}",
            "confirmation_window_ms=${policy["confirmation_window_ms"]}",
            "cooldown_ms=${policy["cooldown_ms"]}",
        ).joinToString("|")
        check(sha256(canonicalPolicy.toByteArray(Charsets.UTF_8)).equals(policy["sha256"]?.toString(), ignoreCase = true)) {
            "Detection policy SHA-256 does not match its canonical payload."
        }

        val studentModel = assetsDir.resolve("distress_student_int8.tflite")
        val studentManifestFile = assetsDir.resolve("distress_student_manifest.json")
        check(studentModel.exists() == studentManifestFile.exists()) {
            "The student model and manifest must be added together."
        }
        if (studentModel.isFile && studentManifestFile.isFile) {
            val studentManifest = slurper.parse(studentManifestFile) as Map<*, *>
            listOf(
                "artifact",
                "model_version",
                "sha256",
                "runtime",
                "quantization",
                "sample_rate_hz",
                "window_ms",
                "input_contract",
                "preprocessing_version",
                "dataset_revision",
                "calibration_revision",
                "license",
                "limitations",
                "threshold_policy_revision",
                "intended_use",
                "outputs",
            ).forEach { key ->
                check(studentManifest[key] != null) { "Student manifest is missing $key." }
            }
            check(studentManifest["artifact"] == studentModel.name) {
                "Student manifest points to ${studentManifest["artifact"]}, expected ${studentModel.name}."
            }
            check(sha256(studentModel).equals(studentManifest["sha256"]?.toString(), ignoreCase = true)) {
                "Student model SHA-256 does not match its manifest."
            }
            println("Student model manifest: verified")
        } else {
            println("Student model manifest: not packaged (expected until the reviewed model exists)")
        }

        println("Model and policy manifests: verified")
    }
}

tasks.named("check") {
    dependsOn("validateModelManifests")
}

tasks.named("preBuild") {
    dependsOn("validateModelManifests")
}
