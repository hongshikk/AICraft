plugins {
    id("java-library")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.2 API（新版 ItemStack 数据组件 API）。
    // 仅编译期使用，运行期由 Paper 服务端提供；Gson 由 Paper 自带，无需打包。
    //
    // 版本必须与 plugins/plugin.yml 里的 api-version 对齐，且不得高于服务端：
    // Paper 在 CraftMagicNumbers.checkSupported 会拒绝加载 api-version 高于服务端的插件
    // （InvalidPluginException: Unsupported API version）。
    // 当前服务端：Paper 26.2-129（API 26.2.build.129-stable）。
    // 服务端升级到 26.3 之后，再把这里和 plugin.yml 的 api-version 一起改成 26.3。
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")

    // 单元测试：目前只覆盖 ai 包里不依赖 Bukkit 的纯逻辑（BodyReader 的超时看门狗、
    // AiClient 的 SSE 正文提取），所以测试运行时不需要 Paper 服务端。
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.processResources {
    val props = mapOf("version" to version)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName.set("AICraft-${project.version}.jar")
}
