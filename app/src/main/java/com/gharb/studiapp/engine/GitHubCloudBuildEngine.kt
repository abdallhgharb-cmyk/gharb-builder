package com.gharb.studiapp.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.gharb.studiapp.model.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class CloudBuildStartResult(
    val owner: String,
    val repo: String,
    val branch: String,
    val commitSha: String,
    val apkFileName: String
)

data class CloudBuildPollStatus(
    val status: String, // queued, in_progress, completed, failed
    val progressPercent: Int,
    val currentStepLabel: String,
    val runUrl: String?,
    val apkDownloadUrl: String?,
    val apkFileName: String?,
    val apkSizeBytes: Long,
    val artifactId: Long?
)

object GitHubCloudBuildEngine {

    private data class RepoFileBlob(
        val path: String,
        val bytes: ByteArray
    )

    fun parseRepoSlug(rawRepo: String): Pair<String, String>? {
        val cleaned = rawRepo.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removeSuffix(".git")
            .trim('/')
        val parts = cleaned.split("/").filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return parts[0] to parts[1]
    }

    suspend fun startCloudBuild(
        context: Context,
        githubRepo: String,
        githubToken: String,
        config: BuildConfig,
        htmlContent: String,
        extraAssets: Map<String, ByteArray>,
        previewUrl: String?
    ): Result<CloudBuildStartResult> = withContext(Dispatchers.IO) {
        try {
            val slug = parseRepoSlug(githubRepo)
                ?: return@withContext Result.failure(IllegalArgumentException("تنسيق مستودع GitHub غير صالح (استخدم owner/repo)."))

            val token = githubToken.trim()
            if (token.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("رمز GitHub Token مفقود في الإعدادات."))
            }

            val (owner, repo) = slug
            val safePkg = config.packageName.trim().ifBlank { "com.gharb.myapp" }
            val safeAppName = config.appName.trim().ifBlank { "تطبيقي الجديد" }
            val safeVersionName = config.versionName.trim().ifBlank { "1.0.0" }
            val safeVersionCode = config.versionCode.coerceAtLeast(1)
            val apkFileName = "${safePkg.replace(".", "_")}_v${safeVersionName}.apk"

            val repoFiles = buildGradleProjectFiles(
                context = context,
                pkg = safePkg,
                appName = safeAppName,
                versionName = safeVersionName,
                versionCode = safeVersionCode,
                keystoreAlias = config.keystoreAlias,
                keystorePassword = config.keystorePassword,
                iconUri = config.iconUri,
                htmlContent = htmlContent,
                extraAssets = extraAssets,
                previewUrl = previewUrl,
                apkFileName = apkFileName
            )

            // 1) Read default branch
            val repoResp = githubRequest("GET", "https://api.github.com/repos/$owner/$repo", token, null)
            val defaultBranch = JSONObject(repoResp).optString("default_branch", "main").ifBlank { "main" }

            // 2) Read latest commit SHA
            val refResp = githubRequest("GET", "https://api.github.com/repos/$owner/$repo/git/ref/heads/$defaultBranch", token, null)
            val latestCommitSha = JSONObject(refResp).getJSONObject("object").getString("sha")

            // 3) Upload blobs and build Git tree
            val treeArray = JSONArray()
            for (file in repoFiles) {
                val blobPayload = JSONObject().apply {
                    put("content", Base64.encodeToString(file.bytes, Base64.NO_WRAP))
                    put("encoding", "base64")
                }
                val blobResp = githubRequest(
                    "POST",
                    "https://api.github.com/repos/$owner/$repo/git/blobs",
                    token,
                    blobPayload.toString()
                )
                val blobSha = JSONObject(blobResp).getString("sha")
                treeArray.put(JSONObject().apply {
                    put("path", file.path)
                    put("mode", "100644")
                    put("type", "blob")
                    put("sha", blobSha)
                })
            }

            // 4) Create tree
            val treeResp = githubRequest(
                "POST",
                "https://api.github.com/repos/$owner/$repo/git/trees",
                token,
                JSONObject().put("tree", treeArray).toString()
            )
            val treeSha = JSONObject(treeResp).getString("sha")

            // 5) Create commit
            val commitPayload = JSONObject().apply {
                put("message", "gharb studi app build: $safeAppName ($safePkg v$safeVersionName)")
                put("tree", treeSha)
                put("parents", JSONArray().put(latestCommitSha))
            }
            val commitResp = githubRequest(
                "POST",
                "https://api.github.com/repos/$owner/$repo/git/commits",
                token,
                commitPayload.toString()
            )
            val commitSha = JSONObject(commitResp).getString("sha")

            // 6) Update branch ref
            val refPatch = JSONObject().apply {
                put("sha", commitSha)
                put("force", true)
            }
            githubRequest(
                "PATCH",
                "https://api.github.com/repos/$owner/$repo/git/refs/heads/$defaultBranch",
                token,
                refPatch.toString()
            )

            Result.success(
                CloudBuildStartResult(
                    owner = owner,
                    repo = repo,
                    branch = defaultBranch,
                    commitSha = commitSha,
                    apkFileName = apkFileName
                )
            )
        } catch (ex: Exception) {
            Result.failure(ex)
        }
    }

    suspend fun pollBuildStatus(
        owner: String,
        repo: String,
        commitSha: String,
        githubToken: String
    ): Result<CloudBuildPollStatus> = withContext(Dispatchers.IO) {
        try {
            val token = githubToken.trim()
            val runsRaw = githubRequest(
                "GET",
                "https://api.github.com/repos/$owner/$repo/actions/runs?per_page=10",
                token,
                null
            )
            val runsArr = JSONObject(runsRaw).optJSONArray("workflow_runs") ?: JSONArray()
            var matchedRun: JSONObject? = null
            for (i in 0 until runsArr.length()) {
                val item = runsArr.getJSONObject(i)
                if (item.optString("head_sha") == commitSha) {
                    matchedRun = item
                    break
                }
            }

            if (matchedRun == null) {
                return@withContext Result.success(
                    CloudBuildPollStatus(
                        status = "queued",
                        progressPercent = 12,
                        currentStepLabel = "جاري تشغيل مصنع البناء السحابي على GitHub Actions...",
                        runUrl = null,
                        apkDownloadUrl = null,
                        apkFileName = null,
                        apkSizeBytes = 0L,
                        artifactId = null
                    )
                )
            }

            val runId = matchedRun.getLong("id")
            val runNumber = matchedRun.optInt("run_number", 0)
            val runStatus = matchedRun.optString("status")
            val conclusion = matchedRun.optString("conclusion")
            val runUrl = matchedRun.optString("html_url")

            // Read job steps
            var stepsCompleted = 0
            var totalSteps = 6
            var activeStepName = "جاري تشغيل تجميع Gradle في السحابة..."
            try {
                val jobsRaw = githubRequest(
                    "GET",
                    "https://api.github.com/repos/$owner/$repo/actions/runs/$runId/jobs",
                    token,
                    null
                )
                val jobsArr = JSONObject(jobsRaw).optJSONArray("jobs") ?: JSONArray()
                if (jobsArr.length() > 0) {
                    val stepsArr = jobsArr.getJSONObject(0).optJSONArray("steps") ?: JSONArray()
                    if (stepsArr.length() > 0) {
                        totalSteps = stepsArr.length()
                        for (s in 0 until stepsArr.length()) {
                            val step = stepsArr.getJSONObject(s)
                            when (step.optString("status")) {
                                "completed" -> stepsCompleted++
                                "in_progress" -> activeStepName = step.optString("name", activeStepName)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }

            if (runStatus == "queued" || runStatus == "waiting") {
                return@withContext Result.success(
                    CloudBuildPollStatus(
                        status = "queued",
                        progressPercent = 16,
                        currentStepLabel = "في انتظار خادم GitHub Actions Runner...",
                        runUrl = runUrl,
                        apkDownloadUrl = null,
                        apkFileName = null,
                        apkSizeBytes = 0L,
                        artifactId = null
                    )
                )
            }

            if (runStatus == "completed") {
                if (conclusion == "success") {
                    var releaseUrl: String? = null
                    var releaseName: String? = null
                    var releaseSize = 0L
                    var artifactId: Long? = null

                    if (runNumber > 0) {
                        try {
                            val relRaw = githubRequest(
                                "GET",
                                "https://api.github.com/repos/$owner/$repo/releases/tags/apk-$runNumber",
                                token,
                                null
                            )
                            val assets = JSONObject(relRaw).optJSONArray("assets") ?: JSONArray()
                            for (a in 0 until assets.length()) {
                                val asset = assets.getJSONObject(a)
                                val name = asset.optString("name")
                                if (name.endsWith(".apk")) {
                                    releaseUrl = asset.optString("browser_download_url")
                                    releaseName = name
                                    releaseSize = asset.optLong("size", 0L)
                                    break
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }

                    try {
                        val artRaw = githubRequest(
                            "GET",
                            "https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts",
                            token,
                            null
                        )
                        val arts = JSONObject(artRaw).optJSONArray("artifacts") ?: JSONArray()
                        if (arts.length() > 0) {
                            val firstArt = arts.getJSONObject(0)
                            artifactId = firstArt.optLong("id")
                            if (releaseSize == 0L) {
                                releaseSize = firstArt.optLong("size_in_bytes", 0L)
                            }
                        }
                    } catch (_: Exception) {
                    }

                    return@withContext Result.success(
                        CloudBuildPollStatus(
                            status = "completed",
                            progressPercent = 100,
                            currentStepLabel = "تم تجميع وتوقيع ملف APK الحقيقي بنجاح!",
                            runUrl = runUrl,
                            apkDownloadUrl = releaseUrl,
                            apkFileName = releaseName,
                            apkSizeBytes = releaseSize,
                            artifactId = artifactId
                        )
                    )
                } else {
                    return@withContext Result.success(
                        CloudBuildPollStatus(
                            status = "failed",
                            progressPercent = 100,
                            currentStepLabel = "تعذر إتمام البناء السحابي ($conclusion)",
                            runUrl = runUrl,
                            apkDownloadUrl = null,
                            apkFileName = null,
                            apkSizeBytes = 0L,
                            artifactId = null
                        )
                    )
                }
            }

            val pct = ((stepsCompleted.toFloat() / totalSteps.toFloat()) * 82f + 14f).toInt().coerceIn(18, 95)
            Result.success(
                CloudBuildPollStatus(
                    status = "in_progress",
                    progressPercent = pct,
                    currentStepLabel = activeStepName,
                    runUrl = runUrl,
                    apkDownloadUrl = null,
                    apkFileName = null,
                    apkSizeBytes = 0L,
                    artifactId = null
                )
            )
        } catch (ex: Exception) {
            Result.failure(ex)
        }
    }

    suspend fun downloadRealApkToDevice(
        context: Context,
        owner: String,
        repo: String,
        githubToken: String,
        directDownloadUrl: String?,
        artifactId: Long?,
        preferredFileName: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val outDir = File(context.filesDir, "gharb_builds").apply { mkdirs() }
            val safeFileName = preferredFileName.ifBlank { "gharb_cloud_app.apk" }
            val outFile = File(outDir, safeFileName)

            if (!directDownloadUrl.isNullOrBlank()) {
                val conn = (URL(directDownloadUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20000
                    readTimeout = 45000
                    setRequestProperty("User-Agent", "gharb-studi-app")
                }
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                AppBuildEngine.saveToPhoneDownloads(context, outFile, safeFileName)
                return@withContext Result.success(outFile)
            }

            if (artifactId != null) {
                val zipBytes = downloadBinaryAuthorized(
                    "https://api.github.com/repos/$owner/$repo/actions/artifacts/$artifactId/zip",
                    githubToken
                )
                ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                            FileOutputStream(outFile).use { output ->
                                zis.copyTo(output)
                            }
                            AppBuildEngine.saveToPhoneDownloads(context, outFile, safeFileName)
                            return@withContext Result.success(outFile)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            Result.failure(IllegalStateException("لم يتم العثور على رابط APK صالح للتنزيل."))
        } catch (ex: Exception) {
            Result.failure(ex)
        }
    }

    private fun buildGradleProjectFiles(
        context: Context,
        pkg: String,
        appName: String,
        versionName: String,
        versionCode: Int,
        keystoreAlias: String,
        keystorePassword: String,
        iconUri: Uri?,
        htmlContent: String,
        extraAssets: Map<String, ByteArray>,
        previewUrl: String?,
        apkFileName: String
    ): List<RepoFileBlob> {
        val pkgPath = pkg.replace(".", "/")
        val escapedUrl = (previewUrl ?: "").replace("\"", "")
        val safeAlias = keystoreAlias.replace(Regex("[^a-zA-Z0-9_-]"), "").ifBlank { "gharb_release_key" }
        val safePass = keystorePassword.replace("'", "").ifBlank { "gharb@2026" }

        val files = mutableListOf<RepoFileBlob>()

        fun addText(path: String, content: String) {
            files.add(RepoFileBlob(path, content.toByteArray(Charsets.UTF_8)))
        }

        addText(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "GharbGeneratedApp"
            include(":app")
            """.trimIndent()
        )

        addText(
            "build.gradle.kts",
            """
            plugins {
                id("com.android.application") version "8.5.2" apply false
                id("org.jetbrains.kotlin.android") version "1.9.24" apply false
            }
            """.trimIndent()
        )

        addText(
            "gradle.properties",
            """
            org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
            android.useAndroidX=true
            android.nonTransitiveRClass=true
            """.trimIndent()
        )

        addText("app/proguard-rules.pro", "-keepattributes *Annotation*\n")

        addText(
            "app/build.gradle.kts",
            """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }
            android {
                namespace = "$pkg"
                compileSdk = 34
                defaultConfig {
                    applicationId = "$pkg"
                    minSdk = 24
                    targetSdk = 34
                    versionCode = $versionCode
                    versionName = "$versionName"
                }
                buildTypes {
                    release {
                        isMinifyEnabled = false
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro"
                        )
                    }
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                kotlinOptions {
                    jvmTarget = "17"
                }
            }
            dependencies {
                implementation("androidx.core:core-ktx:1.13.1")
                implementation("androidx.appcompat:appcompat:1.7.0")
            }
            """.trimIndent()
        )

        addText(
            "app/src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET" />
                <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
                <application
                    android:allowBackup="true"
                    android:icon="@mipmap/ic_launcher"
                    android:roundIcon="@mipmap/ic_launcher"
                    android:label="@string/app_name"
                    android:supportsRtl="true"
                    android:theme="@style/Theme.AppCompat.DayNight.NoActionBar"
                    android:usesCleartextTraffic="true">
                    <activity
                        android:name=".WebAppContainerActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent()
        )

        addText(
            "app/src/main/res/values/strings.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">${escapeXml(appName)}</string>
            </resources>
            """.trimIndent()
        )

        addText(
            "app/src/main/java/$pkgPath/WebAppContainerActivity.kt",
            """
            package $pkg

            import android.annotation.SuppressLint
            import android.os.Bundle
            import android.webkit.WebChromeClient
            import android.webkit.WebResourceRequest
            import android.webkit.WebSettings
            import android.webkit.WebView
            import android.webkit.WebViewClient
            import androidx.appcompat.app.AppCompatActivity

            class WebAppContainerActivity : AppCompatActivity() {
                private lateinit var webView: WebView

                @SuppressLint("SetJavaScriptEnabled")
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    webView = WebView(this)
                    setContentView(webView)
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    webView.webChromeClient = WebChromeClient()
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                    }
                    val initialUrl = "$escapedUrl"
                    if (initialUrl.isNotBlank()) {
                        webView.loadUrl(initialUrl)
                    } else {
                        webView.loadUrl("file:///android_asset/www/index.html")
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        @Suppress("DEPRECATION")
                        super.onBackPressed()
                    }
                }
            }
            """.trimIndent()
        )

        addText("app/src/main/assets/www/index.html", htmlContent)
        extraAssets.forEach { (name, bytes) ->
            val clean = name.trimStart('/')
            if (!clean.equals("index.html", ignoreCase = true)) {
                files.add(RepoFileBlob("app/src/main/assets/www/$clean", bytes))
            }
        }

        val customIconBytes = loadPngIcon(context, iconUri)
        if (customIconBytes != null) {
            files.add(RepoFileBlob("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png", customIconBytes))
        } else {
            addText(
                "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                    <background android:drawable="@drawable/ic_launcher_background" />
                    <foreground android:drawable="@drawable/ic_launcher_foreground" />
                </adaptive-icon>
                """.trimIndent()
            )
            addText(
                "app/src/main/res/drawable/ic_launcher_background.xml",
                """
                <vector xmlns:android="http://schemas.android.com/apk/res/android"
                    android:width="108dp"
                    android:height="108dp"
                    android:viewportWidth="108"
                    android:viewportHeight="108">
                    <path android:fillColor="#090D16" android:pathData="M0,0h108v108h-108z" />
                </vector>
                """.trimIndent()
            )
            addText(
                "app/src/main/res/drawable/ic_launcher_foreground.xml",
                """
                <vector xmlns:android="http://schemas.android.com/apk/res/android"
                    android:width="108dp"
                    android:height="108dp"
                    android:viewportWidth="108"
                    android:viewportHeight="108">
                    <path
                        android:fillColor="#10B981"
                        android:pathData="M38,38 L24,54 L38,70 L43,65 L33,54 L43,43 Z M70,38 L84,54 L70,70 L65,65 L75,54 L65,43 Z M58,34 L48,74 L53,75 L63,35 Z" />
                </vector>
                """.trimIndent()
            )
        }

        val workflowYml = """
            name: Build Gharb Android APK

            on:
              push:
                branches: [ main, master ]
              workflow_dispatch:

            permissions:
              contents: write

            jobs:
              build_apk:
                runs-on: ubuntu-latest
                steps:
                  - name: Checkout Project
                    uses: actions/checkout@v4

                  - name: Set up JDK 17
                    uses: actions/setup-java@v4
                    with:
                      java-version: '17'
                      distribution: 'temurin'

                  - name: Setup Android SDK
                    run: |
                      SDK_ROOT="${'$'}{ANDROID_HOME:-/usr/local/lib/android/sdk}"
                      echo "ANDROID_HOME=${'$'}SDK_ROOT" >> ${'$'}GITHUB_ENV
                      echo "ANDROID_SDK_ROOT=${'$'}SDK_ROOT" >> ${'$'}GITHUB_ENV
                      SDKMGR="${'$'}SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
                      yes | "${'$'}SDKMGR" --licenses >/dev/null 2>&1 || true
                      "${'$'}SDKMGR" "platforms;android-34" "build-tools;34.0.0" >/dev/null 2>&1 || true

                  - name: Setup Gradle
                    uses: gradle/actions/setup-gradle@v4
                    with:
                      gradle-version: '8.7'

                  - name: Generate Release Keystore
                    run: |
                      mkdir -p keystore
                      keytool -genkeypair -v -keystore keystore/gharb-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias "$safeAlias" -storepass "$safePass" -keypass "$safePass" -dname "CN=Gharb Studi, OU=Mobile, O=GharbStudi, L=Cairo, S=EG, C=EG"

                  - name: Build Signed Release APK
                    run: |
                      gradle assembleRelease --no-daemon -Pandroid.injected.signing.store.file="${'$'}PWD/keystore/gharb-release.jks" -Pandroid.injected.signing.store.password="$safePass" -Pandroid.injected.signing.key.alias="$safeAlias" -Pandroid.injected.signing.key.password="$safePass"
                      mkdir -p release_out
                      APK_PATH=${'$'}(find app/build/outputs/apk/release -name "*.apk" | head -n 1)
                      cp "${'$'}APK_PATH" "release_out/$apkFileName"

                  - name: Upload APK Artifact
                    uses: actions/upload-artifact@v4
                    with:
                      name: gharb-built-apk
                      path: release_out/$apkFileName

                  - name: Publish Direct APK Release
                    env:
                      GH_TOKEN: ${'$'}{{ secrets.GITHUB_TOKEN }}
                    run: |
                      TAG="apk-${'$'}{{ github.run_number }}"
                      gh release create "${'$'}TAG" "release_out/$apkFileName" --title "Build ${'$'}TAG ($apkFileName)" --notes "Built automatically by gharb studi app." || true
        """.trimIndent()
        addText(".github/workflows/build_android.yml", workflowYml)

        return files
    }

    private fun loadPngIcon(context: Context, iconUri: Uri?): ByteArray? {
        if (iconUri == null) return null
        return try {
            context.contentResolver.openInputStream(iconUri)?.use { input ->
                val bmp = BitmapFactory.decodeStream(input) ?: return null
                val scaled = Bitmap.createScaledBitmap(bmp, 192, 192, true)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
                baos.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun escapeXml(raw: String): String {
        return raw.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun githubRequest(method: String, urlStr: String, token: String, body: String?): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "gharb-studi-app-android")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
        }
        if (body != null) {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("GitHub API ($code): ${responseText.take(200)}")
        }
        return responseText
    }

    private fun downloadBinaryAuthorized(urlStr: String, token: String): ByteArray {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 45000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "gharb-studi-app-android")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("فشل تنزيل ملف البناء من GitHub ($code)")
        }
        return conn.inputStream.use { it.readBytes() }
    }
}
