buildscript {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20")
    }
}

allprojects {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
    }
}
