buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(libs.postgresql)
        classpath(libs.bundles.flyway)
    }
}

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.flyway)
}

flyway {
    val dbhost = when {
        System.getenv().containsKey("DATABASE_HOST") -> System.getenv("DATABASE_HOST")
        System.getenv().containsKey("CI") -> "postgres"
        else -> "localhost"
    }
    baselineOnMigrate = true
    baselineVersion = "0"
    url = "jdbc:postgresql://$dbhost:5432/emojimanagerdocker"
    user = System.getenv("DATABASE_USERNAME") ?: "emojimanagerdocker"
    password = System.getenv("DATABASE_PASSWORD") ?: "emojimanagerdocker"
}
