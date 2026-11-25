plugins {
    id("java-library")
    id("maven-publish")
    alias(libs.plugins.gradle.shadow)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
}

dependencies {
    compileOnly(libs.echo.common)
    compileOnly(libs.kotlin.stdlib)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.echo.common)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

// Extension properties goto `gradle.properties` to set values

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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "dev.brahmkshatriya.echo.extension"
            artifactId = extId
            version = verName

            from(components["java"])
        }
    }
}

tasks {
    shadowJar {
        archiveBaseName.set(extId)
        archiveVersion.set(verName)
        manifest {
            attributes(
                mapOf(
                    "Extension-Id" to extId,
                    "Extension-Type" to extType,
                    "Extension-Class" to extClass,

                    "Extension-Version-Code" to verCode,
                    "Extension-Version-Name" to verName,

                    "Extension-Icon-Url" to extIconUrl,
                    "Extension-Name" to extName,
                    "Extension-Description" to extDescription,

                    "Extension-Author" to extAuthor,
                    "Extension-Author-Url" to extAuthorUrl,

                    "Extension-Repo-Url" to extRepoUrl,
                    "Extension-Update-Url" to extUpdateUrl
                )
            )
        }
    }
}

fun execute(vararg command: String): String = providers.exec {
    commandLine(*command)
}.standardOutput.asText.get().trim()
