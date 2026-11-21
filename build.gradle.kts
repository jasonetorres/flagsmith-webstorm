plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.0.0"
}

group = "com.jasontorres"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Flagsmith"
        version = "1.0.0"
        changeNotes = """
            Project scaffold.
        """.trimIndent()
        ideaVersion {
            sinceBuild.set("263.0")
        }
    }
}

dependencies {
    intellijPlatform {
        local("/Users/jasontorres/Applications/WebStorm.app")
        instrumentationTools()
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
