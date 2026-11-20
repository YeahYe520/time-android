// 根工程的构建脚本。这里只声明"要用哪个版本的安卓构建插件"，
// 具体怎么构建在 app/build.gradle.kts 里。
//
// apply false = 只把插件下载下来、先别应用。根工程自己不需要它，
// 真正用它的是 :app 那个模块。
plugins {
    id("com.android.application") version "8.5.2" apply false
}
