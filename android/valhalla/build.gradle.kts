import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost
import java.net.URI

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.dokka)
}

android {
    namespace = "com.valhalla.valhalla"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dokka {
    moduleName.set("Valhalla Mobile")

    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("docs"))
    }

    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl.set(URI("https://github.com/jakabtomas/valhalla-mobile"))
            remoteLineSuffix.set("#L")
        }

        includes.from(
            fileTree("docs") {
                include("**/*.md")
            }
        )
    }
}

dependencies {
    implementation(libs.core.ktx)

    implementation(libs.moshi.kotlin)
    implementation(libs.moshi.adapters)

    implementation(libs.valhalla.models.api)
    implementation(libs.valhalla.models.config)
    implementation(libs.osrm.api)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}

val archs = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

// Define a custom task to run the shell script
archs.forEach { arch ->
    tasks.register<Exec>("buildValhallaFor-${arch}") {
        description = "Build libValhalla for $arch architecture"
        group = "build"

        // Change the working door to the repository root.
        workingDir = file("${project.projectDir}/../../")
        environment("VCPKG_ROOT", "${workingDir.absolutePath}/vcpkg")

        commandLine("bash", "./build.sh", "--android", arch)

        onlyIf {
            !file("src/main/jniLibs/${arch}/libvalhalla-wrapper.so").exists()
        }
    }
}

tasks.register("buildValhallaAll") {
    description = "Build the native Valhalla library for every Android ABI"
    group = "build"
    dependsOn(archs.map { "buildValhallaFor-$it" })
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    if (project.version.toString() === "unspecified") {
        throw IllegalArgumentException("Version must be specified")
    }

    coordinates("io.github.jakabtomas", "valhalla-mobile", project.version.toString())

    configure(AndroidSingleVariantLibrary(sourcesJar = true, publishJavadocJar = true))

    pom {
        name.set("Valhalla Mobile")
        url.set("https://github.com/jakabtomas/valhalla-mobile")
        description.set("A configurable mobile wrapper for the Valhalla routing engine")
        inceptionYear.set("2024")
        licenses {
            license {
                name.set("MIT")
                url.set("https://github.com/jakabtomas/valhalla-mobile/blob/main/LICENSE.md")
            }
        }
        developers {
            developer {
                name.set("Jacob Fielding")
                organization.set("Rallista")
                organizationUrl.set("https://rallista.app")
            }
            developer {
                id.set("jakabtomas")
                name.set("Tomas Jakab")
                organizationUrl.set("https://github.com/jakabtomas")
            }
        }
        contributors {
            contributor {
                name.set("Valhalla")
                organizationUrl.set("https://github.com/valhalla/valhalla")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/jakabtomas/valhalla-mobile.git")
            developerConnection.set("scm:git:ssh://github.com/jakabtomas/valhalla-mobile.git")
            url.set("https://github.com/jakabtomas/valhalla-mobile")
        }
    }
}
