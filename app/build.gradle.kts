// Built-in zip classes are not resolved implicitly in .kts; import them.
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.liuran001.mmliquidglass"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.liuran001.mmliquidglass"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.2.3"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // The module works off the host app's class names, so obfuscating
            // its own code buys nothing.
            isMinifyEnabled = false
            // Ship an installable APK signed with the AS debug key; configure a
            // real signing config under File > Project Structure > Signing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources {
            // Keep the xposed entry table at its original path.
            excludes += "META-INF/xposed/**"
        }
    }
}

dependencies {
    // libxposed API 102. compileOnly on purpose: these types are injected by the
    // LSPosed framework inside the host process. Baking them into the dex
    // duplicates every class and makes behaviour depend on ART resolution order.
    // The plain jar is used because javac does not look inside an aar's
    // libs/classes.jar.
    compileOnly(files("libs/api-102.0.0.jar"))
}

// ---------------------------------------------------------------------------
// Upstream keeps the module's entry table under META-INF/xposed/
// (java_init.list, module.prop, scope.list, module.scope). AGP treats those as
// ordinary resources and drops them, so they are appended to the APK after it
// has been built and signed.
// ---------------------------------------------------------------------------
val xposedMetaDir = file("META-INF/xposed")

listOf("debug", "release").forEach { variant ->
    val cap = variant.replaceFirstChar { it.uppercase() }
    val packTask = tasks.register("packXposedMeta${cap}") {
        group = "build"
        description = "Append META-INF/xposed/* to the $variant APK"
        dependsOn("assemble$cap")

        val apkDir = layout.buildDirectory.dir("outputs/apk/$variant")
        val meta = xposedMetaDir
        inputs.dir(meta)
        outputs.upToDateWhen { false }

        doLast {
            val apk = apkDir.get().asFile
                .listFiles { f -> f.isFile && f.extension == "apk" }
                ?.maxByOrNull { it.lastModified() }
                ?: throw GradleException("No APK found in ${apkDir.get().asFile}")

            // The APK is rewritten in place, so a second run has to cope with
            // the archive an earlier one already packed: every META-INF entry
            // (signature block and xposed table alike) is dropped first, which
            // leaves exactly what AGP would have produced unsigned.
            val stripped = File(apk.parentFile, apk.name + ".stripped")
            val rawZin = ZipInputStream(apk.inputStream().buffered())
            try {
                val rawZout = ZipOutputStream(stripped.outputStream().buffered())
                try {
                    var e = rawZin.nextEntry
                    while (e != null) {
                        if (!e.name.startsWith("META-INF/")) {
                            rawZout.putNextEntry(ZipEntry(e.name))
                            if (!e.isDirectory) rawZin.copyTo(rawZout)
                            rawZout.closeEntry()
                        }
                        rawZin.closeEntry()
                        e = rawZin.nextEntry
                    }
                } finally {
                    rawZout.close()
                }
            } finally {
                rawZin.close()
            }

            val tmp = File(apk.parentFile, apk.name + ".tmp")
            val zin = ZipInputStream(stripped.inputStream().buffered())
            try {
                val zout = ZipOutputStream(tmp.outputStream().buffered())
                try {
                    var e = zin.nextEntry
                    while (e != null) {
                        zout.putNextEntry(ZipEntry(e.name))
                        if (!e.isDirectory) zin.copyTo(zout)
                        zout.closeEntry()
                        zin.closeEntry()
                        e = zin.nextEntry
                    }
                    meta.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                        val rel = "META-INF/xposed/" + f.relativeTo(meta).invariantSeparatorsPath
                        zout.putNextEntry(ZipEntry(rel))
                        f.inputStream().use { it.copyTo(zout) }
                        zout.closeEntry()
                    }
                } finally {
                    zout.close()
                }
            } finally {
                zin.close()
            }
            stripped.delete()
            tmp.copyTo(apk, overwrite = true)
            tmp.delete()

            // Rewriting the archive invalidated the signature (JVMS signers only
            // cover pre-existing entries, so v1 would silently "verify" while v2
            // is missing). Re-sign with the very same keystore the variant used.
            signApk(apk, variant)
            println("Packed META-INF/xposed/* into ${apk.absolutePath}")
        }
    }
    tasks.matching { it.name == "assemble$cap" }.configureEach {
        finalizedBy(packTask)
    }
}

/**
 * Signs [apk] in place with the debug keystore AS/AGP uses, through the
 * apksigner and zipalign from the same build-tools that AGP resolved.
 */
fun signApk(apk: File, variant: String) {
    val sdk = File(project.rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.let { Properties().also { p -> it.inputStream().use(p::load) }
            .getProperty("sdk.dir") }
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: throw GradleException("No SDK: set sdk.dir in local.properties"))
    val buildTools = File(sdk, "build-tools").listFiles { f -> f.isDirectory }
        ?.maxByOrNull { f -> f.name.split('.').mapNotNull { it.toIntOrNull() }
            .fold(0) { acc, n -> acc * 1000 + n } }
        ?: throw GradleException("No build-tools under $sdk")
    val windows = System.getProperty("os.name").startsWith("Windows")
    val java = File(System.getProperty("java.home"), "bin/java" + if (windows) ".exe" else "")
    val keystore = File(System.getProperty("user.home"),
        ".android/debug.keystore")
    if (!keystore.exists()) {
        throw GradleException("Debug keystore not found at $keystore; " +
            "build once from Android Studio first.")
    }

    fun exec(vararg cmd: String) {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) throw GradleException("${cmd.joinToString(" ")} failed ($code):\n$out")
    }

    val aligned = File(apk.parentFile, apk.name + ".aligned")
    exec(File(buildTools, "zipalign" + if (windows) ".exe" else "").absolutePath,
        "-f", "-p", "4", apk.absolutePath, aligned.absolutePath)
    exec(java.absolutePath,
        "-cp", File(buildTools, "lib/apksigner.jar").absolutePath,
        "com.android.apksigner.ApkSignerTool", "sign",
        "--ks", keystore.absolutePath,
        "--ks-pass", "pass:android",
        "--key-pass", "pass:android",
        "--ks-key-alias", "androiddebugkey",
        "--out", apk.absolutePath,
        aligned.absolutePath)
    aligned.delete()
}
