import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 서명 키: GitHub Secrets(KEYSTORE_BASE64 등) 또는 내 컴퓨터의 keystore.properties (둘 다 저장소에는 올리지 않음)
// 같은 키로 계속 서명해야 새 버전을 '업데이트'로 설치할 수 있습니다.
val ciKeystore = System.getenv("KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }?.let {
    val f = layout.buildDirectory.file("ci.keystore").get().asFile
    f.parentFile.mkdirs()
    f.writeBytes(Base64.getMimeDecoder().decode(it))
    f
}

val appVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
val appVersionName = System.getenv("VERSION_NAME") ?: "1.0.0"

android {
    namespace = "io.github.motodna.pcwake"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.motodna.pcwake"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            if (ciKeystore != null) {
                storeFile = ciKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                // 내 컴퓨터에서 빌드할 때: 저장소에 올리지 않는 keystore.properties 파일을 읽습니다.
                val props = Properties()
                val pf = rootProject.file("keystore.properties")
                if (pf.exists()) {
                    pf.inputStream().use { props.load(it) }
                    storeFile = rootProject.file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
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
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

android.testOptions.unitTests.isIncludeAndroidResources = true
dependencies {
    testImplementation("org.robolectric:robolectric:4.14.1")
}
