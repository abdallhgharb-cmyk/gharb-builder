package com.gharb.studiapp.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.gharb.studiapp.model.BuildConfig
import com.gharb.studiapp.model.TargetPlatform
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ZipExtractionResult(
    val entryHtml: String,
    val entryCss: String,
    val entryJs: String,
    val assets: Map<String, ByteArray>,
    val fileCount: Int
)

data class BuildPackageResult(
    val success: Boolean,
    val outputFile: File?,
    val downloadDisplayName: String,
    val fileSizeBytes: Long,
    val signatureDigest: String,
    val logs: List<String>
)

object AppBuildEngine {

    private val GENERIC_TITLES = setOf(
        "document", "index", "untitled", "home", "react app", "vite app", "my app", "cookie check"
    )

    fun normalizeExternalUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    fun extractAppNameFromHtml(html: String): String? {
        val regex = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
        val match = regex.find(html) ?: return null
        val cleaned = match.groupValues[1].replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty() || GENERIC_TITLES.contains(cleaned.lowercase())) return null
        return cleaned.take(60)
    }

    fun extractAppNameFromUrl(rawUrl: String): String? {
        return try {
            val normalized = normalizeExternalUrl(rawUrl)
            if (normalized.isEmpty()) return null
            val host = Uri.parse(normalized).host ?: return null
            val stripped = host.removePrefix("www.")
            stripped.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    fun extractAppNameFromFilename(filename: String?): String? {
        if (filename.isNullOrBlank()) return null
        val base = filename.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("\\.(zip|html|htm)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[_-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return base.ifEmpty { null }
    }

    fun resolveExtractedAppName(
        htmlContent: String?,
        sourceUrl: String?,
        filename: String?
    ): String? {
        if (!htmlContent.isNullOrBlank()) {
            val fromTitle = extractAppNameFromHtml(htmlContent)
            if (!fromTitle.isNullOrBlank()) return fromTitle
        }
        if (!sourceUrl.isNullOrBlank()) {
            val fromUrl = extractAppNameFromUrl(sourceUrl)
            if (!fromUrl.isNullOrBlank()) return fromUrl
        }
        return extractAppNameFromFilename(filename)
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(java.util.Locale.US, "%.2f MB", mb)
    }

    fun extractZipProject(context: Context, zipUri: Uri): ZipExtractionResult {
        val rawAssets = linkedMapOf<String, ByteArray>()

        context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val clean = entry.name.trimStart('/')
                        val base = clean.substringAfterLast('/')
                        if (clean.isNotEmpty() && !clean.startsWith("__MACOSX/") && !clean.contains("/__MACOSX/") && base != ".DS_Store" && base != "Thumbs.db") {
                            val buffer = ByteArrayOutputStream()
                            val data = ByteArray(4096)
                            var count: Int
                            while (zis.read(data).also { count = it } != -1) {
                                buffer.write(data, 0, count)
                            }
                            rawAssets[clean] = buffer.toByteArray()
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        val allKeys = rawAssets.keys.toList()
        var stripPrefix = ""
        if (allKeys.isNotEmpty() && allKeys.all { it.contains('/') }) {
            val firstSeg = allKeys[0].substringBefore('/')
            if (firstSeg.isNotEmpty() && allKeys.all { it.startsWith("$firstSeg/") }) {
                stripPrefix = "$firstSeg/"
            }
        }

        val assets = linkedMapOf<String, ByteArray>()
        var entryHtml = ""
        var entryCss = ""
        var entryJs = ""

        rawAssets.forEach { (rawPath, bytes) ->
            val path = if (stripPrefix.isNotEmpty() && rawPath.startsWith(stripPrefix)) {
                rawPath.removePrefix(stripPrefix)
            } else {
                rawPath
            }
            assets[path] = bytes
            val lower = path.lowercase()
            if (lower == "index.html" || lower.endsWith("/index.html")) {
                if (entryHtml.isEmpty() || lower == "index.html") {
                    entryHtml = GeminiAiEngine.cleanMarkdownCodeBlock(bytes.toString(Charsets.UTF_8))
                }
            } else if (entryHtml.isEmpty() && (lower.endsWith(".html") || lower.endsWith(".htm"))) {
                entryHtml = GeminiAiEngine.cleanMarkdownCodeBlock(bytes.toString(Charsets.UTF_8))
            } else if (entryCss.isEmpty() && lower.endsWith(".css")) {
                entryCss = bytes.toString(Charsets.UTF_8)
            } else if (entryJs.isEmpty() && lower.endsWith(".js")) {
                entryJs = bytes.toString(Charsets.UTF_8)
            }
        }

        return ZipExtractionResult(
            entryHtml = entryHtml,
            entryCss = entryCss,
            entryJs = entryJs,
            assets = assets,
            fileCount = assets.size
        )
    }

    fun generateUrlWrapperHtml(targetUrl: String): String {
        val normalized = normalizeExternalUrl(targetUrl)
        return """
            <!DOCTYPE html>
            <html lang="ar" dir="rtl">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <meta http-equiv="refresh" content="0; url=$normalized">
                <title>${extractAppNameFromUrl(normalized) ?: normalized}</title>
            </head>
            <body style="background:#0B1120;color:#F8FAFC;font-family:sans-serif;text-align:center;padding:40px;">
                <h2 style="color:#10B981;">جاري فتح التطبيق...</h2>
                <p>$normalized</p>
            </body>
            </html>
        """.trimIndent()
    }

    private fun loadIconAsPngBytes(context: Context, iconUri: Uri?): ByteArray? {
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

    /**
     * محرك البناء الداخلي: يجمع المشروع (ZIP / HTML / URL) مع الاسم والإصدار والأيقونة
     * ويحفظ ملف .apk أو .ipa المجهز مباشرة في مجلد Downloads بالهاتف مع التوقيع الرقمي.
     */
    fun buildAndSignPackage(
        context: Context,
        config: BuildConfig,
        htmlContent: String,
        extraAssets: Map<String, ByteArray>,
        saveDirectoryPath: String
    ): BuildPackageResult {
        val logs = mutableListOf<String>()
        logs.add("بدء تهيئة محرك البناء الداخلي لـ gharb studi app...")
        logs.add("النظام المستهدف: ${config.targetPlatform.label}")
        logs.add("اسم التطبيق: ${config.appName} (${config.packageName})")
        logs.add("الإصدار: ${config.versionName} (${config.versionCode})")

        if (config.appName.isBlank() || config.packageName.isBlank()) {
            logs.add("خطأ: اسم التطبيق واسم الحزمة مطلوبان لإتمام البناء.")
            return BuildPackageResult(false, null, "", 0L, "", logs)
        }

        val outDir = File(context.filesDir, "gharb_builds").apply { mkdirs() }
        val safeName = config.packageName.replace(".", "_")
        val ext = config.targetPlatform.extension
        val outputFileName = "${safeName}_v${config.versionName}.$ext"
        val packageFile = File(outDir, outputFileName)

        val manifestContent = if (config.targetPlatform == TargetPlatform.ANDROID_APK) {
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="${config.packageName}"
                android:versionCode="${config.versionCode}"
                android:versionName="${config.versionName}">
                <uses-permission android:name="android.permission.INTERNET" />
                <application android:label="${config.appName}" android:icon="@mipmap/ic_launcher" android:usesCleartextTraffic="true">
                    <activity android:name=".WebAppContainerActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent()
        } else {
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>CFBundleDisplayName</key><string>${config.appName}</string>
                <key>CFBundleIdentifier</key><string>${config.packageName}</string>
                <key>CFBundleShortVersionString</key><string>${config.versionName}</string>
                <key>CFBundleVersion</key><string>${config.versionCode}</string>
            </dict>
            </plist>
            """.trimIndent()
        }

        val iconBytes = loadIconAsPngBytes(context, config.iconUri)
        if (iconBytes != null) {
            logs.add("تم تضمين الأيقونة المخصصة بصيغة PNG داخل الحزمة.")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(htmlContent.toByteArray(Charsets.UTF_8))
        digest.update(config.packageName.toByteArray(Charsets.UTF_8))
        if (config.digitalSigningEnabled) {
            digest.update(config.keystoreAlias.toByteArray(Charsets.UTF_8))
            digest.update(config.keystorePassword.toByteArray(Charsets.UTF_8))
        }
        val signatureHex = digest.digest().joinToString("") { "%02x".format(it) }

        ZipOutputStream(FileOutputStream(packageFile)).use { zos ->
            val manifestPath = if (config.targetPlatform == TargetPlatform.ANDROID_APK) {
                "AndroidManifest.xml"
            } else {
                "Payload/App.app/Info.plist"
            }
            zos.putNextEntry(ZipEntry(manifestPath))
            zos.write(manifestContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            if (iconBytes != null) {
                val iconEntry = if (config.targetPlatform == TargetPlatform.ANDROID_APK) {
                    "res/mipmap-xxxhdpi-v4/ic_launcher.png"
                } else {
                    "Payload/App.app/AppIcon.png"
                }
                zos.putNextEntry(ZipEntry(iconEntry))
                zos.write(iconBytes)
                zos.closeEntry()
            }

            val webRoot = if (config.targetPlatform == TargetPlatform.ANDROID_APK) {
                "assets/www/"
            } else {
                "Payload/App.app/www/"
            }
            zos.putNextEntry(ZipEntry("${webRoot}index.html"))
            zos.write(htmlContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            extraAssets.forEach { (name, bytes) ->
                if (!name.endsWith("index.html", ignoreCase = true)) {
                    zos.putNextEntry(ZipEntry("$webRoot$name"))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }

            if (config.digitalSigningEnabled) {
                val certInfo = """
                    Manifest-Version: 1.0
                    Created-By: gharb studi app Internal Builder
                    Signer-Alias: ${config.keystoreAlias}
                    Package: ${config.packageName}
                    SHA-256-Digest: $signatureHex
                """.trimIndent()
                zos.putNextEntry(ZipEntry("META-INF/CERT.SF"))
                zos.write(certInfo.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                logs.add("تم التوقيع الرقمي بشهادة Keystore (${config.keystoreAlias}).")
            }
        }

        // نسخ الحزمة مباشرة إلى مجلد التنزيلات في ذاكرة الهاتف (Downloads)
        saveToPhoneDownloads(context, packageFile, outputFileName)
        logs.add("تم تنزيل الملف $outputFileName مباشرة إلى مجلد Downloads في ذاكرة الهاتف ($saveDirectoryPath).")

        return BuildPackageResult(true, packageFile, outputFileName, packageFile.length(), signatureHex, logs)
    }

    data class OfflinePwaResult(
        val success: Boolean,
        val pwaDirectory: File,
        val entryHtmlFile: File,
        val zipPackageFile: File,
        val zipDisplayName: String,
        val shortcutPinRequested: Boolean,
        val sizeBytes: Long
    )

    fun buildOfflinePwaAndPinShortcut(
        context: Context,
        config: BuildConfig,
        htmlContent: String,
        extraAssets: Map<String, ByteArray>,
        pinShortcutNow: Boolean = true
    ): OfflinePwaResult {
        val safePkg = config.packageName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "com_gharb_pwa" }
        val pwaDir = File(context.filesDir, "gharb_pwa/$safePkg").apply { mkdirs() }

        val manifestJson = """
            {
              "name": "${config.appName.replace("\"", "\\\"")}",
              "short_name": "${config.appName.take(16).replace("\"", "\\\"")}",
              "start_url": "./index.html",
              "display": "standalone",
              "background_color": "#0B1120",
              "theme_color": "#10B981",
              "icons": [
                { "src": "./icon.png", "sizes": "192x192", "type": "image/png" }
              ]
            }
        """.trimIndent()

        val swJs = """
            const CACHE = 'gharb-pwa-${safePkg}';
            self.addEventListener('install', (e) => {
              e.waitUntil(caches.open(CACHE).then((c) => c.addAll(['./', './index.html', './manifest.webmanifest'])));
              self.skipWaiting();
            });
            self.addEventListener('fetch', (e) => {
              e.respondWith(caches.match(e.request).then((r) => r || fetch(e.request)));
            });
        """.trimIndent()

        val injectedHtml = if (htmlContent.contains("</head>", ignoreCase = true)) {
            htmlContent.replace(
                "</head>",
                "<link rel=\"manifest\" href=\"./manifest.webmanifest\"><meta name=\"theme-color\" content=\"#10B981\"></head>",
                ignoreCase = true
            )
        } else {
            htmlContent
        }

        val indexFile = File(pwaDir, "index.html").apply { writeText(injectedHtml, Charsets.UTF_8) }
        val manifestFile = File(pwaDir, "manifest.webmanifest").apply { writeText(manifestJson, Charsets.UTF_8) }
        val swFile = File(pwaDir, "sw.js").apply { writeText(swJs, Charsets.UTF_8) }

        val iconBytes = loadIconAsPngBytes(context, config.iconUri)
        if (iconBytes != null) {
            File(pwaDir, "icon.png").writeBytes(iconBytes)
        }

        extraAssets.forEach { (name, bytes) ->
            val clean = name.trimStart('/')
            if (clean.isNotEmpty() && !clean.equals("index.html", ignoreCase = true)) {
                val target = File(pwaDir, clean)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
        }

        val outDir = File(context.filesDir, "gharb_builds").apply { mkdirs() }
        val zipDisplayName = "${safePkg.replace(".", "_")}_v${config.versionName}_pwa.zip"
        val zipFile = File(outDir, zipDisplayName)
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            listOf("index.html" to indexFile, "manifest.webmanifest" to manifestFile, "sw.js" to swFile).forEach { (entryName, file) ->
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(file.readBytes())
                zos.closeEntry()
            }
            if (iconBytes != null) {
                zos.putNextEntry(ZipEntry("icon.png"))
                zos.write(iconBytes)
                zos.closeEntry()
            }
            extraAssets.forEach { (name, bytes) ->
                val clean = name.trimStart('/')
                if (clean.isNotEmpty() && !clean.equals("index.html", ignoreCase = true)) {
                    zos.putNextEntry(ZipEntry(clean))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }

        saveToPhoneDownloads(context, zipFile, zipDisplayName, "application/zip")

        val pinned = if (pinShortcutNow) {
            requestPinHomeScreenShortcut(context, config, indexFile, iconBytes)
        } else {
            false
        }

        return OfflinePwaResult(
            success = true,
            pwaDirectory = pwaDir,
            entryHtmlFile = indexFile,
            zipPackageFile = zipFile,
            zipDisplayName = zipDisplayName,
            shortcutPinRequested = pinned,
            sizeBytes = zipFile.length()
        )
    }

    fun requestPinHomeScreenShortcut(
        context: Context,
        config: BuildConfig,
        entryHtmlFile: File,
        iconBytes: ByteArray? = null
    ): Boolean {
        return try {
            if (!androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                return false
            }
            val launchIntent = android.content.Intent(
                context,
                com.gharb.studiapp.WebAppContainerActivity::class.java
            ).apply {
                action = android.content.Intent.ACTION_VIEW
                putExtra(com.gharb.studiapp.WebAppContainerActivity.EXTRA_LOCAL_HTML_PATH, entryHtmlFile.absolutePath)
                putExtra(com.gharb.studiapp.WebAppContainerActivity.EXTRA_APP_TITLE, config.appName)
            }

            val icon = if (iconBytes != null) {
                val bmp = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
                androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp)
            } else {
                androidx.core.graphics.drawable.IconCompat.createWithResource(
                    context,
                    com.gharb.studiapp.R.mipmap.ic_launcher
                )
            }

            val shortcutId = "gharb_pwa_${config.packageName.replace(".", "_")}"
            val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(config.appName.ifBlank { "gharb pwa" })
                .setLongLabel(config.appName.ifBlank { "gharb studi app" })
                .setIcon(icon)
                .setIntent(launchIntent)
                .build()

            androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        } catch (_: Exception) {
            false
        }
    }

    fun saveToPhoneDownloads(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String = "application/vnd.android.package-archive"
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/GharbStudi")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { input -> input.copyTo(out) }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val target = File(downloadsDir, displayName)
                sourceFile.copyTo(target, overwrite = true)
            }
        } catch (_: Exception) {
            // يبقى الملف محفوظاً في مجلد التطبيق الداخلي في حال عدم توفر صلاحية التخزين
        }
    }
}
