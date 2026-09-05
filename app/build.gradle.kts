plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.protobuf") version "0.10.0"
}

android {
    namespace = "com.jadenjsj.betterflow"
    compileSdk = 36

    val wisprBasetenApiKey = providers.environmentVariable("WISPR_BASETEN_API_KEY").orNull.orEmpty()

    defaultConfig {
        applicationId = "com.jadenjsj.betterflow"
        minSdk = 29
        targetSdk = 36
        versionCode = providers.environmentVariable("BETTERFLOW_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("BETTERFLOW_VERSION_NAME").orNull ?: "0.1.0-dev"
        buildConfigField("String", "WISPR_BASETEN_API_KEY", "\"$wisprBasetenApiKey\"")
    }

    val releaseStore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull?.takeIf { it.isNotBlank() }
    val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
    val releaseAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
    val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }

    signingConfigs {
        create("release") {
            if (releaseStore != null) {
                storeFile = file(releaseStore)
                storePassword = releaseStorePassword
                keyAlias = releaseAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseStore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    compileOnly(libs.libxposed.api)
    implementation("io.grpc:grpc-okhttp:1.84.0")
    implementation("io.grpc:grpc-protobuf-lite:1.84.0")
    implementation("io.grpc:grpc-stub:1.84.0")
    implementation("com.google.protobuf:protobuf-javalite:4.36.1")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.1"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.84.0"
        }
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") {
                    option("lite")
                }
            }
            plugins {
                create("grpc") {
                    option("lite")
                }
            }
        }
    }
}
