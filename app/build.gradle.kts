import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ---- Versioning -----------------------------------------------------------------------------
// Semantic version derived from git tags of the form vMAJOR.MINOR.PATCH.
//   * exactly on a tag:      1.2.3
//   * N commits after a tag: 1.2.3-dev.N+<sha>   (dirty tree adds ".dirty")
//   * no tag at all:         0.0.0-dev.N+<sha>
// CI passes -PversionName=... / -PversionCode=... explicitly, which take precedence.
// versionCode = MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + min(N, 99), monotonic per tag.

val gitDescribe: Provider<String> = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "v[0-9]*", "--long", "--always", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }

val gitCommitCount: Provider<String> = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }

data class SemVer(val major: Int, val minor: Int, val patch: Int, val distance: Int, val sha: String, val dirty: Boolean) {
    val name: String
        get() {
            val base = "$major.$minor.$patch"
            if (distance == 0 && !dirty) return base
            val meta = buildString { append(sha); if (dirty) append(".dirty") }
            return "$base-dev.$distance+$meta"
        }
    val code: Int get() = major * 1_000_000 + minor * 10_000 + patch * 100 + distance.coerceAtMost(99)
}

fun semVerFromGit(): SemVer {
    val described = gitDescribe.getOrElse("")
    val tagged = Regex("""^v(\d+)\.(\d+)\.(\d+)-(\d+)-g([0-9a-f]+)(-dirty)?$""").matchEntire(described)
    if (tagged != null) {
        val (ma, mi, pa, dist, sha) = tagged.destructured
        return SemVer(ma.toInt(), mi.toInt(), pa.toInt(), dist.toInt(), sha, tagged.groupValues[6].isNotEmpty())
    }
    // No tag reachable: "--always" yields just "<sha>[-dirty]".
    val sha = described.removeSuffix("-dirty").ifEmpty { "unknown" }
    val count = gitCommitCount.getOrElse("0").toIntOrNull() ?: 0
    return SemVer(0, 0, 0, count, sha, described.endsWith("-dirty"))
}

fun semVerFromName(name: String): SemVer? {
    val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-dev\.(\d+))?.*$""").matchEntire(name) ?: return null
    val (ma, mi, pa, dist) = m.destructured
    return SemVer(ma.toInt(), mi.toInt(), pa.toInt(), dist.toIntOrNull() ?: 0, "", false)
}

val overrideName = providers.gradleProperty("versionName").orNull
val overrideCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull()
val semVer = overrideName?.let(::semVerFromName) ?: semVerFromGit()
val appVersionName = overrideName ?: semVer.name
val appVersionCode = overrideCode ?: semVer.code

tasks.register("printVersionName") {
    description = "Prints the computed versionName (used by CI to name artifacts)."
    val v = appVersionName
    doLast { println(v) }
}

tasks.register("printVersionCode") {
    description = "Prints the computed versionCode."
    val v = appVersionCode
    doLast { println(v) }
}

android {
    namespace = "pl.vovcia.softtempest"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.vovcia.softtempest"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // ---- Signing ----------------------------------------------------------------------------
    // Release builds are signed with the keystore given through environment variables (CI
    // secrets). Without one they fall back to the debug key so the APK is still installable,
    // but then every CI run signs with a different throw-away key and updates over a previous
    // install fail with a signature mismatch. See README, "Versioning & releases".
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    val hasReleaseKeystore = !keystorePath.isNullOrBlank() && file(keystorePath).exists()
    if (hasReleaseKeystore) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath!!)
            storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    // APK file name: softtempest-<versionName>-<buildType>.apk
    applicationVariants.all {
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName =
                "softtempest-${versionName}-${buildType.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        // Target JVM 17 bytecode; the build itself runs on any JDK >= 17.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
