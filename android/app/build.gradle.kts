plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Numéro de version fourni par la chaîne de construction (numéro du build GitHub).
val buildNumber = (System.getenv("COURRIER_BUILD") ?: "1").toInt()
val releaseKeystore: String? = System.getenv("COURRIER_KEYSTORE")

// Réglages Firebase (notifications instantanées), tirés du contenu de google-services.json que la
// chaîne de construction fournit dans GOOGLE_SERVICES_JSON. Sans lui, l'appli se construit quand même
// et relève simplement le courrier toutes les quinze minutes.
val firebase: Map<String, String> = run {
    val raw = System.getenv("GOOGLE_SERVICES_JSON")?.takeIf { it.isNotBlank() } ?: return@run emptyMap()
    try {
        val root = groovy.json.JsonSlurper().parseText(raw) as Map<*, *>
        val project = root["project_info"] as Map<*, *>
        val client = (root["client"] as List<*>).map { it as Map<*, *> }.firstOrNull {
            ((it["client_info"] as Map<*, *>)["android_client_info"] as? Map<*, *>)?.get("package_name") == "com.valentinlarge.courrier"
        } ?: error("aucune appli Android « com.valentinlarge.courrier » dans ce fichier")
        mapOf(
            "google_app_id" to (client["client_info"] as Map<*, *>)["mobilesdk_app_id"].toString(),
            "gcm_defaultSenderId" to project["project_number"].toString(),
            "project_id" to project["project_id"].toString(),
            "google_api_key" to ((client["api_key"] as List<*>).first() as Map<*, *>)["current_key"].toString(),
        )
    } catch (e: Exception) {
        throw GradleException("GOOGLE_SERVICES_JSON n'est pas un fichier google-services.json utilisable : ${e.message}")
    }
}

android {
    namespace = "com.valentinlarge.courrier"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.valentinlarge.courrier"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.5.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        firebase.forEach { (name, value) -> resValue("string", name, value) }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storeType = "pkcs12"
                storePassword = System.getenv("COURRIER_KEYSTORE_PASSWORD")
                keyAlias = "courrier"
                keyPassword = System.getenv("COURRIER_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // En test, l'appli parle au serveur lancé sur la machine qui héberge l'émulateur.
            buildConfigField("String", "DEFAULT_SERVER", "\"http://10.0.2.2:8787\"")
        }
        release {
            buildConfigField("String", "DEFAULT_SERVER", "\"https://valentin-mail.valentinlarge.workers.dev\"")
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/LICENSE",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Verrou par empreinte : la fenêtre de vérification du téléphone, qui demande une activité « à fragments ».
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Notifications instantanées : Firebase réveille l'appli quand le serveur signale un nouveau message.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    // Lecture des messages bruts (MIME) sur l'appareil.
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
