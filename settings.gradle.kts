pluginManagement {
    repositories {
        // 优先使用阿里云镜像，避免 plugins.gradle.org 不可达时整个构建失败
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "XAiWebHook"
