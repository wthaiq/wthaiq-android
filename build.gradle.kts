import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.bcv)
}

apiValidation {
    ignoredProjects += listOf("sample")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    source.setFrom(
        files(
            "wthaiq-signing/src/main/kotlin",
            "wthaiq-signing/src/test/kotlin",
            "wthaiq-signing/src/androidTest/kotlin",
            "wthaiq-signing-compose/src/main/kotlin",
            "sample/src/main/kotlin",
        ),
    )
    parallel = true
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

// Maven Central publishing for the library modules. Artifacts are written to a local staging repository
// (build/staging-deploy); uploading the bundle to the Central Portal is a manual release step (see RELEASING in
// CONTRIBUTING.md). Signing runs only when signing keys are provided through Gradle properties.
subprojects {
    plugins.withId("maven-publish") {
        val artifactIdValue = findProperty("POM_ARTIFACT_ID") as String
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications {
                    create("release", MavenPublication::class.java) {
                        from(components.getByName("release"))
                        groupId = property("GROUP") as String
                        artifactId = artifactIdValue
                        version = property("VERSION_NAME") as String
                        pom {
                            name.set(property("POM_NAME") as String)
                            description.set(property("POM_DESCRIPTION") as String)
                            url.set(property("POM_URL") as String)
                            licenses {
                                license {
                                    name.set(property("POM_LICENSE_NAME") as String)
                                    url.set(property("POM_LICENSE_URL") as String)
                                }
                            }
                            developers {
                                developer {
                                    id.set(property("POM_DEVELOPER_ID") as String)
                                    name.set(property("POM_DEVELOPER_NAME") as String)
                                    email.set(property("POM_DEVELOPER_EMAIL") as String)
                                }
                            }
                            scm {
                                url.set(property("POM_URL") as String)
                                connection.set(property("POM_SCM_CONNECTION") as String)
                                developerConnection.set(property("POM_SCM_DEV_CONNECTION") as String)
                            }
                        }
                    }
                }
                repositories {
                    maven {
                        name = "staging"
                        url = rootProject.layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
                    }
                }
            }
            val signingKey = findProperty("signingInMemoryKey") as String?
            if (!signingKey.isNullOrBlank()) {
                extensions.configure<SigningExtension> {
                    useInMemoryPgpKeys(
                        findProperty("signingInMemoryKeyId") as String?,
                        signingKey,
                        findProperty("signingInMemoryKeyPassword") as String?,
                    )
                    sign(extensions.getByType(PublishingExtension::class.java).publications)
                }
            }
        }
    }
}
