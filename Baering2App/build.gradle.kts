buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9의 내장 Kotlin 버전을 Compose 컴파일러 플러그인과 같은 버전으로 맞춘다
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
