plugins {
    id("com.android.application")
}

android {
    // namespace 是这个模块里 R 类（资源索引）的包名
    namespace = "com.timeapp.shell"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.timeapp.shell"
        // minSdk 26 = 安卓 8.0。定这么高是为了能用**自适应图标**
        // （纯 XML 定义，不需要准备一堆 PNG）。安卓 8 是 2017 年的，
        // 现在基本没有更老的机器了。
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // 这次只出 debug 包。debug 包用系统自带的调试密钥签名，
    // 手机上直接能装——试验够用了。
    // 真要发布才需要 release + 自己的签名密钥，那是另一件事。

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
