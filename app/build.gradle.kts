import java.util.Properties

/**
 * Release signing, read from `keystore.properties` at the repository root.
 *
 * That file is gitignored and is NOT in this repository — it holds the passwords to the
 * upload key, and a signing key that has been committed is a signing key that has to be
 * replaced. Create it yourself; see `docs/release.md` for the two commands.
 *
 * When it is absent — which is the normal state for a fresh clone, for CI, and for anyone
 * who only wants a debug build — `signingConfigs.release` is simply not created and the
 * release build stays unsigned. That is deliberate: failing the whole build because a
 * developer has no upload key would make `assembleDebug` depend on a secret.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasSigningKey = keystoreProperties.getProperty("storeFile") != null

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and resources to stand up a Context.
            isIncludeAndroidResources = true
        }
    }
    namespace = "com.jawon.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jawon.app"
        minSdk = 28
        targetSdk = 37
        versionCode = 15
        versionName = "0.6.2 beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        /**
         * A music server this build ships already pointed at, as a `ServerInvite` string.
         *
         * **Empty by default, and that is the shipping default.** With nothing here the app
         * behaves exactly as it always has: no server, nothing sent anywhere, the user's own
         * library and nothing else. Set it only to cut a build for a specific shared library,
         * where the point is that whoever installs it has music without configuring anything.
         *
         *     ./gradlew assembleRelease -Pjawon.serverInvite=jawon://eyJ1Ijoi...
         *
         * or a line in `gradle.properties` (which is committed — so put an invite there only if
         * the library behind it is genuinely public).
         *
         * **What goes in it is a shared secret handed to strangers.** Anyone with the APK can
         * read it back out. Use a read-only account that exists for this and nothing else, and
         * rotate it by changing the server password and cutting a new build. See ServerInvite.
         */
        buildConfigField(
            "String",
            "DEFAULT_SERVER_INVITE",
            "\"${project.findProperty("jawon.serverInvite") ?: ""}\""
        )
    }

    // Room writes each schema version here as JSON, and the directory is committed. It is
    // what a hand-written migration gets checked against — the database is built with no
    // destructive fallback, so a migration that disagrees with the entities throws on first
    // open rather than silently dropping someone's library. See data/local/Migrations.kt.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    signingConfigs {
        if (hasSigningKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 on, with rules in proguard-rules.pro. Most of that file is Filament: its
            // JNI resolves Java fields by NAME, so obfuscating them does not fail to build,
            // it fails to render — read the file before trimming anything from it.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // With an upload key present, sign for real. Without one, fall back to the debug
            // key — NOT because that is publishable (Play rejects debug-signed uploads), but
            // because an unsigned APK cannot be installed at all, and an R8 build that
            // cannot be installed cannot be tested.
            //
            // That matters more here than in most apps: the risk of minification is Filament
            // losing a field name to obfuscation, and the symptom is a native abort or a
            // silently-ignored setting at runtime. A green build says nothing about it.
            signingConfig = if (hasSigningKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            // Version name only — deliberately NOT applicationIdSuffix.
            //
            // Suffixing the id lets debug and release sit side by side, which is genuinely
            // useful, but it also makes the debug build a different app: different data
            // directory, so the library you already imported is not there and the app opens
            // on an empty wall. Until the library lives somewhere that survives that, the
            // continuity is worth more than the side-by-side install.
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // For DEFAULT_SERVER_INVITE above.
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// Hilt's annotation processor bundles a kotlin-metadata-jvm that only reads metadata up to
// 2.3.0, so it rejects every class this project now compiles with Kotlin 2.4.x. The reader
// is not shaded (the failure names kotlin.metadata.jvm.internal.JvmReadUtils directly), and
// Hilt aggregates over javac rather than KSP, so the matching version has to be both forced
// and actually present on every processor classpath.
configurations.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.sceneview)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    annotationProcessor("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
    ksp("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)

    // Robolectric, so the data layer can be tested on the JVM in seconds rather than needing
    // a device. The backup writer is the reason: it is what stands between someone and losing
    // their collection, and a silent regression in it would only be found by a person who had
    // already lost everything.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
