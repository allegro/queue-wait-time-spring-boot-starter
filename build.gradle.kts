import java.lang.System.getenv
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import pl.allegro.tech.build.axion.release.domain.PredefinedVersionCreator.VERSION_WITH_BRANCH

plugins {
    `java-library`
    `maven-publish`
    kotlin("jvm") version libs.versions.kotlin.get()
    kotlin("plugin.spring") version libs.versions.kotlin.get()
    signing
    alias(libs.plugins.axion.release)
    alias(libs.plugins.nexus.publish)
}

scmVersion {
    versionCreator = VERSION_WITH_BRANCH.versionCreator
}

group = "pl.allegro.tech.queuewaittime"
version = scmVersion.version


dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.autoconfigure)

    compileOnly(libs.micrometer)
    compileOnly(libs.spring.boot.tomcat)
    compileOnly(libs.spring.boot.reactor.netty)
    compileOnly(libs.spring.boot.webclient)
    compileOnly(libs.spring.boot.mongodb)
    compileOnly(libs.mongodb.driver.reactivestreams)
    compileOnly(libs.netty.transport)

    testImplementation(libs.junit.jupiter)
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.micrometer.metrics)
    testImplementation(libs.spring.boot.starter.webmvc)
    testImplementation(libs.spring.boot.starter.webflux)
    testImplementation(libs.spring.boot.starter.webclient)
    testImplementation(libs.spring.boot.starter.mongodb.reactive)
    testImplementation(libs.awaitility)
    testImplementation(libs.micrometer)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}

publishing {
    publications {
        create<MavenPublication>("sonatype") {
            from(components["java"])
            pom {
                name = project.name
                description = "Performance metrics related to task queue wait time"
                url = "https://github.com/allegro/queue-wait-time-spring-boot-starter"
                inceptionYear = "2026"
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "KamilPatora"
                        name = "Kamil Patora"
                    }
                    developer {
                        id = "mateuszNalepaAllegro"
                        name = "Mateusz Nalepa"
                    }
                }
                scm {
                    url = "https://github.com/allegro/queue-wait-time-spring-boot-starter"
                    connection = "git@github.com:allegro/queue-wait-time-spring-boot-starter.git"
                    developerConnection = "git@github.com:allegro/queue-wait-time-spring-boot-starter.git"
                }
            }
        }
    }
}

signing {
    val gpgKeyId = getenv("GPG_KEY_ID") ?: return@signing
    val gpgPrivateKey = getenv("GPG_PRIVATE_KEY") ?: return@signing
    val gpgPrivateKeyPassword = getenv("GPG_PRIVATE_KEY_PASSWORD") ?: return@signing

    useInMemoryPgpKeys(gpgKeyId, gpgPrivateKey, gpgPrivateKeyPassword)
    sign(publishing.publications["sonatype"])
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
            snapshotRepositoryUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
            username = getenv("SONATYPE_USERNAME")
            password = getenv("SONATYPE_PASSWORD")
        }
    }
}
