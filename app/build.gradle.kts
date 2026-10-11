import java.security.MessageDigest
import java.util.Properties
import java.util.zip.GZIPOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
}

// Подпись берём из local.properties (в git не попадает):
//   keystore.file=F:/bender.jks
//   keystore.password=...
//   keystore.alias=...
//   keystore.keyPassword=...
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    signingConfigs {
        getByName("debug") {
            // Флешки с ключом нет — собираем стандартным отладочным ключом.
            localProps.getProperty("keystore.file")?.takeIf { file(it).exists() }?.let { path ->
                storeFile = file(path)
                storePassword = localProps.getProperty("keystore.password")
                keyAlias = localProps.getProperty("keystore.alias")
                keyPassword = localProps.getProperty("keystore.keyPassword")
            }
        }
    }
    namespace = "com.crmsistems.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.crmsistems.app"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
    dependenciesInfo {
        includeInApk = true
        includeInBundle = true
    }
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.15729638 rc2"
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Списки
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.fragment)
    // Обновление списков свайпом сверху вниз
    implementation(libs.androidx.swiperefreshlayout)

    // Локальная база данных (вся база хранится на телефоне)
    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.androidx.room.compiler)

    // Фоновая проверка новых заказов, когда приложение закрыто
    implementation(libs.androidx.work.runtime)

    // ViewModel + LiveData: данные переживают поворот экрана
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)

    // SSH: приложение само ставит сервер магазина на VPS
    implementation(libs.jsch)

    testImplementation(libs.junit)
    // На телефоне org.json даёт сама система, а в JVM-тестах из android.jar приходит
    // заглушка — подкладываем настоящую реализацию, чтобы разбор заказов работал.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// ------------------------------------------------------------------
// Сервер магазина внутри APK.
//
// Перед сборкой приложения собираем Go-сервер из ../server под Linux
// (amd64 и arm64) и кладём в assets/server вместе с install.sh и backup.sh.
// Экран «Установка на сервер» заливает их по SSH на VPS. Нужен Go 1.21+
// в PATH. Версия — отпечаток исходников: по ней приложение понимает,
// что сервер на VPS устарел.
abstract class BuildShopServer : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val serverDir: DirectoryProperty

    @get:Input
    abstract val appVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile.resolve("server")
        out.deleteRecursively()
        out.mkdirs()

        val md = MessageDigest.getInstance("SHA-256")
        sources.files.sortedBy { it.invariantSeparatorsPath }.forEach { md.update(it.readBytes()) }
        val hash = md.digest().joinToString("") { "%02x".format(it) }.take(8)
        val version = "${appVersion.get()}+$hash"

        val src = serverDir.get().asFile
        for (arch in listOf("amd64", "arm64")) {
            val bin = out.resolve("crmshop-$arch")
            exec.exec {
                workingDir = src
                environment("GOOS", "linux")
                environment("GOARCH", arch)
                environment("CGO_ENABLED", "0")
                commandLine(
                    "go", "build", "-trimpath",
                    "-ldflags=-s -w -X main.version=$version",
                    "-o", bin.absolutePath, "."
                )
            }
            // В APK кладём сжатым: 13 МБ превращаются в ~5. Приложение
            // распаковывает его на лету, пока заливает на сервер.
            out.resolve("crmshop-$arch.size").writeText(bin.length().toString())
            bin.inputStream().use { input ->
                GZIPOutputStream(out.resolve("crmshop-$arch.pack").outputStream()).use { input.copyTo(it) }
            }
            bin.delete()
        }
        // Скрипты — с переводами строк Unix, иначе bash на сервере споткнётся о CR.
        for (name in listOf("install.sh", "backup.sh")) {
            out.resolve(name).writeText(src.resolve(name).readText().replace("\r\n", "\n"))
        }
        out.resolve("VERSION").writeText(version)
    }
}

val buildShopServer = tasks.register<BuildShopServer>("buildShopServer") {
    val dir = rootProject.layout.projectDirectory.dir("server")
    serverDir.set(dir)
    sources.from(fileTree(dir) {
        include("*.go", "go.mod", "go.sum", "*.sh", "templates/**", "static/**")
        exclude("*_test.go")
    })
    appVersion.set(android.defaultConfig.versionName ?: "1.0")
    outputDir.set(layout.buildDirectory.dir("generated/shopServer"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(buildShopServer, BuildShopServer::outputDir)
    }
}
