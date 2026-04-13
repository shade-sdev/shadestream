import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    kotlin("plugin.serialization") version "2.3.20"
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.serialization)

            implementation(libs.libtorrent4j)
            implementation(libs.libtorrent4j.windows)

            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.vlcj)

            implementation(libs.slf4j.nop)

            implementation(libs.playwright)
            implementation(libs.jsoup)
        }
    }
}


compose.desktop {
    application {
        mainClass = "com.shade.dev.shadestream.MainKt"

        jvmArgs("-Djava.library.path=app/resources")

        nativeDistributions {
            includeAllModules = true
            targetFormats(TargetFormat.AppImage,)
            packageName = "ShadeStream"
            packageVersion = "1.0.0"

            appResourcesRootDir.set(project.layout.projectDirectory.dir("nativeLibs"))

            windows {
                menuGroup = "ShadeStream"
                perUserInstall = true
                upgradeUuid = "a8f3c2d1-4b7e-4f9a-b2c3-d1e5f6a7b8c9"
            }
        }
    }
}

tasks.withType<JavaExec> {
    systemProperty("java.library.path", project.layout.projectDirectory.dir("nativeLibs/windows").asFile.absolutePath)
}
