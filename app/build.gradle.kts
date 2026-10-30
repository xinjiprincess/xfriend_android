plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.kapt") }
android {
    namespace = "com.singu.xfriend"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.singu.xfriend.mobile"
        if (providers.gradleProperty("salesUiFixture").orNull == "true") applicationIdSuffix = ".qa"
        minSdk = 26
        targetSdk = 34
        buildConfigField("String", "PROVIDER_POOL_CLIENT_ID", "\"" + providers.environmentVariable("XFRIEND_PROVIDER_POOL_CLIENT_ID").orElse("").get().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"")
        buildConfigField("String", "PROVIDER_POOL_CLIENT_SECRET", "\"" + providers.environmentVariable("XFRIEND_PROVIDER_POOL_CLIENT_SECRET").orElse("").get().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"")
        versionCode = 19
        versionName = "0.19.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "product"
    productFlavors {
        create("xfriend") {
            dimension = "product"
            applicationId = "com.singu.xfriend.mobile"
            resValue("string", "app_name", "Xfriend")
            resValue("string", "app_name_debug", "XFriend 星助理 · 调试")
            buildConfigField("boolean", "SALES_APP", "false")
        }
        create("xsalers") {
            dimension = "product"
            applicationId = "com.singu.xsalers.mobile"
            resValue("string", "app_name", "Xsalers")
            resValue("string", "app_name_debug", "XSalers · 调试")
            buildConfigField("boolean", "SALES_APP", "true")
        }
    }
    buildTypes {
        getByName("debug") {
            // Personal debug builds must coexist with the installed app and its data.
            // The UI fixture already has its own .qa application id.
            if (providers.gradleProperty("salesUiFixture").orNull != "true") applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    if (providers.gradleProperty("salesUiFixture").orNull == "true") {
        sourceSets["debug"].java.srcDir("src/uiFixture/java")
        sourceSets["debug"].manifest.srcFile("src/uiFixture/AndroidManifest.xml")
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("dev.chrisbanes.haze:haze:0.7.3")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
