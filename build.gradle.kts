plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.10.4"
}

group = "com.jasontorres"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.jasontorres.flagsmith"
        name = "Flagsmith Feature Flags"
        version = "0.1.0"
        ideaVersion {
            sinceBuild.set("263")
            // No until-build: stay compatible with future IDE releases.
            untilBuild.set(provider { null })
        }
    }

    signing {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }

    buildSearchableOptions = false

    pluginVerification {
        ides {
            recommended()
        }
    }
}

dependencies {
    intellijPlatform {
        local("/Users/jasontorres/Applications/WebStorm.app")
        // Compile against the bundled JS/TS plugin. Runtime stays optional via plugin.xml.
        bundledPlugin("JavaScript")
    }
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }

    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    runIde {
        maxHeapSize = "1024m"
    }
}


// The React + Vite showcase in demo/ is a standalone app, not plugin source,
// and is outside src/main so Gradle does not pack it into the zip.
