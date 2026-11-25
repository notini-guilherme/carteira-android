plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.app.carteira"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.app.carteira"
        minSdk = 29
        targetSdk = 35
        // o GitHub numera cada compilacao; assim um APK novo sempre instala por cima do anterior
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "4.3"
    }

    signingConfigs {
        // Chave de TESTE fixa (app/debug.keystore): mantem a mesma assinatura em todas as compilacoes,
        // para o APK novo atualizar o antigo sem perder os dados. Para a Play Store, usar uma chave propria.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // 4.4.1 (176): o APK publicado agora e a versao final (nao depuravel: ninguem le os dados pelo cabo USB),
            // assinada com a MESMA chave de sempre para continuar atualizando por cima sem perder os dados
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.work:work-runtime:2.9.1")
}
