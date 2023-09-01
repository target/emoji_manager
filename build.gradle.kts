buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.0")
        classpath(libs.ktlint)
    }
}

plugins {
    application
    kotlin("jvm") version "1.8.0"
    id("jacoco")
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set("0.48.2")
}
group = "com.target.slack"
version = "2.0.0"

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.target.slack.SlackApplicationKt")
    group = "com.target.slack"
    java.sourceCompatibility = JavaVersion.VERSION_17
}

configurations.all {
    exclude(group = "ch.qos.logback")
}

dependencies {
    implementation(libs.liteForJdbc)
    implementation(libs.postgresql)
    implementation(libs.flyway)
    implementation(libs.okhttp)
    implementation(libs.bundles.scrimage)
    implementation(libs.bundles.bolt)
    implementation(libs.http4kCore)
    implementation(libs.bundles.hoplite)
    implementation(libs.bundles.jackson)
    implementation(libs.websocket)
    implementation(libs.tyrusClient)
    implementation(libs.bundles.slf4j)

    testImplementation("com.h2database:h2:2.1.214")
    testImplementation(kotlin("test-junit"))
}

val jvmTargetVersion: String by project

tasks {
    withType<Jar> {
        isEnabled = true
        manifest {
            attributes["Implementation-Version"] = version
        }
    }
    withType<Tar> {
        archiveFileName.set("emoji_manager.tar")
    }
    withType<Test> {
        dependsOn(":db-migration:flywayMigrate")
        useJUnit()
    }
    java { toolchain { languageVersion.set(JavaLanguageVersion.of(jvmTargetVersion)) } }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport) // report is always generated after tests run
}
tasks.jacocoTestReport {
    dependsOn(tasks.test) // tests are required to run before generating the report
}
