// Gradle 的"去哪儿找依赖"清单。
//
// 为什么前面几行是 aliyun 而不是 google()/mavenCentral()？
// 因为国内直连这两个源很慢，阿里云有它们的完整镜像，内容一模一样。
// 顺序有意义：**从上往下找，找到就停**。所以镜像放前面，官方源留作兜底
// （万一镜像没同步到某个版本，还能从官方源拿到）。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // FAIL_ON_PROJECT_REPOS = 不许各个子模块自己声明仓库，
    // 一律用这里这一份。避免"某个模块偷偷从别处拉包"这种事。
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "time-shell"
include(":app")
