plugins {
    alias(libs.plugins.android.application)
}

dependencies {
    implementation(project(":ext"))
    compileOnly(libs.echo.common)
    compileOnly(libs.kotlin.stdlib)
}

val extType = project.property("extType") as String
val extId = project.property("extId") as String
val extClass = project.property("extClass") as String

val extIconUrl = project.findProperty("extIconUrl") as? String
val extName = project.property("extName") as String
val extDescription = project.findProperty("extDescription") as? String

val extAuthor = project.property("extAuthor") as String
val extAuthorUrl = project.findProperty("extAuthorUrl") as? String

val extRepoUrl = project.findProperty("extRepoUrl") as? String
val extUpdateUrl = project.findProperty("extUpdateUrl") as? String

val verCode = (project.findProperty("extVersionCode") as? String)?.toIntOrNull()
    ?: runCatching { execute("git", "rev-list", "--count", "HEAD").toInt() }.getOrDefault(1)
val verName = (project.findProperty("extVersionName") as? String)
    ?: runCatching { "v" + execute("git", "rev-parse", "HEAD").take(7) }.getOrDefault("v1.0.3")

val outputDir = file("${layout.buildDirectory.asFile.get()}/generated/proguard")
val generatedProguard = file("${outputDir}/generated-rules.pro")

tasks.register("generateProguardRules") {
    doLast {
        outputDir.mkdirs()
        generatedProguard.writeText(
            """
                -dontobfuscate
                -keep,allowoptimization class dev.brahmkshatriya.echo.extension.$extClass
                """.trimMargin()
        )
    }
}

tasks.named("preBuild") {
    dependsOn("generateProguardRules")
}

android {
    namespace = "dev.brahmkshatriya.echo.extension"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    defaultConfig {
        applicationId = "dev.brahmkshatriya.echo.extension.$extId"
        minSdk = 24
        targetSdk = 36
        versionCode = verCode
        versionName = verName

        manifestPlaceholders.apply {
            put("type", "dev.brahmkshatriya.echo.${extType}")
            put("id", extId)
            put("class_path", "dev.brahmkshatriya.echo.extension.${extClass}")
            put("version", verName)
            put("version_code", verCode.toString())
            put("icon_url", extIconUrl ?: "")
            put("app_name", "Echo : $extName Extension")
            put("name", extName)
            put("description", extDescription ?: "")
            put("author", extAuthor)
            put("author_url", extAuthorUrl ?: "")
            put("repo_url", extRepoUrl ?: "")
            put("update_url", extUpdateUrl ?: "")
        }
    }

    buildTypes {
        all {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                generatedProguard.absolutePath
            )
        }
    }
}

fun execute(vararg command: String): String = providers.exec {
    commandLine(*command)
}.standardOutput.asText.get().trim()
