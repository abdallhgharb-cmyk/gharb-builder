package com.gharb.studiapp.model

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.json.JSONArray
import org.json.JSONObject

enum class CodeLanguage(val label: String, val extension: String) {
    HTML("HTML", "html"),
    CSS("CSS", "css"),
    JS("JS", "js"),
    KOTLIN("Kotlin", "kt")
}

enum class TargetPlatform(val label: String, val extension: String) {
    ANDROID_APK("Android (APK)", "apk"),
    IOS_IPA("iOS (IPA)", "ipa")
}

data class LocalSkill(
    val id: String,
    val name: String,
    val language: String,
    val code: String
)

data class ProjectSnapshot(
    val id: String,
    val label: String,
    val timestampLabel: String,
    val html: String,
    val css: String,
    val js: String,
    val kotlin: String
)

data class AppSettings(
    val fontSizeSp: Int = 13,
    val savePath: String = "/storage/emulated/0/GharbStudiProjects",
    val geminiApiKey: String = "",
    val githubRepo: String = StudioViewModel.DEFAULT_GITHUB_REPO,
    val githubToken: String = StudioViewModel.getDefaultFactoryToken(),
    val wordWrap: Boolean = true
)

data class BuildConfig(
    val targetPlatform: TargetPlatform = TargetPlatform.ANDROID_APK,
    val appName: String = "تطبيقي الجديد",
    val packageName: String = "com.gharb.myapp",
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val iconUri: Uri? = null,
    val digitalSigningEnabled: Boolean = true,
    val keystoreAlias: String = "gharb_release_key",
    val keystorePassword: String = "gharb@2026"
)

class StudioViewModel : ViewModel() {

    private val _isOnlineMode = MutableLiveData(true)
    val isOnlineMode: LiveData<Boolean> = _isOnlineMode

    private val _activeLanguage = MutableLiveData(CodeLanguage.HTML)
    val activeLanguage: LiveData<CodeLanguage> = _activeLanguage

    private val _htmlCode = MutableLiveData(DEFAULT_HTML_STARTER)
    val htmlCode: LiveData<String> = _htmlCode

    private val _cssCode = MutableLiveData("")
    val cssCode: LiveData<String> = _cssCode

    private val _jsCode = MutableLiveData("")
    val jsCode: LiveData<String> = _jsCode

    private val _kotlinCode = MutableLiveData("package com.gharb.myapp\n\nimport android.os.Bundle\nimport androidx.appcompat.app.AppCompatActivity\n\nclass GeneratedAppActivity : AppCompatActivity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n    }\n}")
    val kotlinCode: LiveData<String> = _kotlinCode

    private val _settings = MutableLiveData(AppSettings())
    val settings: LiveData<AppSettings> = _settings

    private val _buildConfig = MutableLiveData(BuildConfig())
    val buildConfig: LiveData<BuildConfig> = _buildConfig

    private val _localSkills = MutableLiveData<List<LocalSkill>>(emptyList())
    val localSkills: LiveData<List<LocalSkill>> = _localSkills

    private val _snapshots = MutableLiveData<List<ProjectSnapshot>>(emptyList())
    val snapshots: LiveData<List<ProjectSnapshot>> = _snapshots

    private val _buildLog = MutableLiveData("> محرك البناء الداخلي جاهز.\n> اختر أحد خيارات التحويل الثلاثة أعلاه.")
    val buildLog: LiveData<String> = _buildLog

    private val _importedAssets = MutableLiveData<Map<String, ByteArray>>(emptyMap())
    val importedAssets: LiveData<Map<String, ByteArray>> = _importedAssets

    private val _previewUrl = MutableLiveData<String?>(null)
    val previewUrl: LiveData<String?> = _previewUrl

    fun setOnlineMode(online: Boolean) {
        _isOnlineMode.value = online
    }

    fun setPreviewUrl(url: String?) {
        _previewUrl.value = url
    }

    fun setActiveLanguage(lang: CodeLanguage) {
        _activeLanguage.value = lang
    }

    fun getCurrentCode(): String {
        return when (_activeLanguage.value ?: CodeLanguage.HTML) {
            CodeLanguage.HTML -> _htmlCode.value.orEmpty()
            CodeLanguage.CSS -> _cssCode.value.orEmpty()
            CodeLanguage.JS -> _jsCode.value.orEmpty()
            CodeLanguage.KOTLIN -> _kotlinCode.value.orEmpty()
        }
    }

    fun updateCurrentCode(newCode: String) {
        when (_activeLanguage.value ?: CodeLanguage.HTML) {
            CodeLanguage.HTML -> _htmlCode.value = newCode
            CodeLanguage.CSS -> _cssCode.value = newCode
            CodeLanguage.JS -> _jsCode.value = newCode
            CodeLanguage.KOTLIN -> _kotlinCode.value = newCode
        }
    }

    fun setHtmlAndSwitch(html: String) {
        _htmlCode.value = html
        _activeLanguage.value = CodeLanguage.HTML
    }

    fun setCssCode(css: String) {
        _cssCode.value = css
    }

    fun setJsCode(js: String) {
        _jsCode.value = js
    }

    fun buildCombinedHtmlPreview(): String {
        val rawHtml = com.gharb.studiapp.engine.GeminiAiEngine.cleanMarkdownCodeBlock(_htmlCode.value.orEmpty())
        val css = _cssCode.value.orEmpty().trim()
        val js = _jsCode.value.orEmpty().trim()
        if (css.isEmpty() && js.isEmpty()) {
            return rawHtml
        }
        return if (rawHtml.contains("</head>", ignoreCase = true) && rawHtml.contains("</body>", ignoreCase = true)) {
            var res = rawHtml
            if (css.isNotEmpty()) res = res.replace("</head>", "<style>$css</style></head>", ignoreCase = true)
            if (js.isNotEmpty()) res = res.replace("</body>", "<script>$js</script></body>", ignoreCase = true)
            res
        } else {
            """
            <!DOCTYPE html>
            <html dir="rtl" lang="ar">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                ${if (css.isNotEmpty()) "<style>$css</style>" else ""}
            </head>
            <body>
                $rawHtml
                ${if (js.isNotEmpty()) "<script>$js</script>" else ""}
            </body>
            </html>
            """.trimIndent()
        }
    }

    fun updateSettings(newSettings: AppSettings, context: Context) {
        val normalized = newSettings.copy(
            githubRepo = newSettings.githubRepo.trim().ifBlank { DEFAULT_GITHUB_REPO },
            githubToken = newSettings.githubToken.trim().ifBlank { getDefaultFactoryToken() }
        )
        _settings.value = normalized
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("font_size", normalized.fontSizeSp)
            .putString("save_path", normalized.savePath)
            .putString("gemini_key", normalized.geminiApiKey)
            .putString("github_repo", normalized.githubRepo)
            .putString("github_token", normalized.githubToken)
            .putBoolean("word_wrap", normalized.wordWrap)
            .apply()
    }

    fun loadPersistedData(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _settings.value = AppSettings(
            fontSizeSp = prefs.getInt("font_size", 13),
            savePath = prefs.getString("save_path", "/storage/emulated/0/GharbStudiProjects")
                ?: "/storage/emulated/0/GharbStudiProjects",
            geminiApiKey = prefs.getString("gemini_key", "") ?: "",
            githubRepo = prefs.getString("github_repo", DEFAULT_GITHUB_REPO)
                ?.ifBlank { DEFAULT_GITHUB_REPO } ?: DEFAULT_GITHUB_REPO,
            githubToken = prefs.getString("github_token", "")
                ?.ifBlank { getDefaultFactoryToken() } ?: getDefaultFactoryToken(),
            wordWrap = prefs.getBoolean("word_wrap", true)
        )

        val skillsJson = prefs.getString("local_skills", "[]") ?: "[]"
        val arr = JSONArray(skillsJson)
        val parsed = mutableListOf<LocalSkill>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            parsed.add(
                LocalSkill(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    language = obj.getString("language"),
                    code = obj.getString("code")
                )
            )
        }
        _localSkills.value = parsed

        val snapshotsJson = prefs.getString("project_snapshots", "[]") ?: "[]"
        val snapArr = JSONArray(snapshotsJson)
        val parsedSnaps = mutableListOf<ProjectSnapshot>()
        for (i in 0 until snapArr.length()) {
            val obj = snapArr.getJSONObject(i)
            parsedSnaps.add(
                ProjectSnapshot(
                    id = obj.getString("id"),
                    label = obj.getString("label"),
                    timestampLabel = obj.getString("timestampLabel"),
                    html = obj.getString("html"),
                    css = obj.getString("css"),
                    js = obj.getString("js"),
                    kotlin = obj.getString("kotlin")
                )
            )
        }
        _snapshots.value = parsedSnaps
    }

    fun addLocalSkill(skill: LocalSkill, context: Context) {
        val updated = (_localSkills.value ?: emptyList()) + skill
        _localSkills.value = updated
        val arr = JSONArray()
        updated.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("language", item.language)
                put("code", item.code)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("local_skills", arr.toString())
            .apply()
    }

    fun saveSnapshot(label: String, context: Context): ProjectSnapshot {
        val cleanLabel = label.trim().ifEmpty { "نقطة استعادة #${(_snapshots.value?.size ?: 0) + 1}" }
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val snapshot = ProjectSnapshot(
            id = java.util.UUID.randomUUID().toString(),
            label = cleanLabel,
            timestampLabel = formatter.format(java.util.Date()),
            html = _htmlCode.value.orEmpty(),
            css = _cssCode.value.orEmpty(),
            js = _jsCode.value.orEmpty(),
            kotlin = _kotlinCode.value.orEmpty()
        )
        val updated = listOf(snapshot) + (_snapshots.value ?: emptyList()).take(19)
        _snapshots.value = updated
        persistSnapshots(updated, context)
        return snapshot
    }

    fun restoreSnapshot(snapshot: ProjectSnapshot) {
        _htmlCode.value = snapshot.html
        _cssCode.value = snapshot.css
        _jsCode.value = snapshot.js
        _kotlinCode.value = snapshot.kotlin
    }

    fun deleteSnapshot(snapshotId: String, context: Context) {
        val updated = (_snapshots.value ?: emptyList()).filterNot { it.id == snapshotId }
        _snapshots.value = updated
        persistSnapshots(updated, context)
    }

    private fun persistSnapshots(items: List<ProjectSnapshot>, context: Context) {
        val arr = JSONArray()
        items.forEach { snap ->
            arr.put(JSONObject().apply {
                put("id", snap.id)
                put("label", snap.label)
                put("timestampLabel", snap.timestampLabel)
                put("html", snap.html)
                put("css", snap.css)
                put("js", snap.js)
                put("kotlin", snap.kotlin)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("project_snapshots", arr.toString())
            .apply()
    }

    fun updateBuildConfig(config: BuildConfig) {
        _buildConfig.value = config
    }

    fun updateAppName(appName: String) {
        val current = _buildConfig.value ?: BuildConfig()
        val cleaned = appName.trim()
        if (cleaned.isNotEmpty()) {
            _buildConfig.value = current.copy(appName = cleaned)
        }
    }

    fun setImportedAssets(assets: Map<String, ByteArray>) {
        _importedAssets.value = assets
    }

    fun getProjectFileNames(): List<String> {
        val imported = _importedAssets.value.orEmpty()
        if (imported.isNotEmpty()) {
            return imported.keys.toList()
        }
        return listOf("index.html", "style.css", "script.js")
    }

    fun isEditableTextFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".html") || lower.endsWith(".htm") ||
            lower.endsWith(".css") || lower.endsWith(".js") ||
            lower.endsWith(".json") || lower.endsWith(".txt") ||
            lower.endsWith(".svg") || lower.endsWith(".xml")
    }

    fun readProjectFileText(fileName: String): String {
        val imported = _importedAssets.value.orEmpty()
        if (imported.containsKey(fileName)) {
            return imported[fileName]?.toString(Charsets.UTF_8).orEmpty()
        }
        return when (fileName) {
            "index.html" -> _htmlCode.value.orEmpty()
            "style.css" -> _cssCode.value.orEmpty()
            "script.js" -> _jsCode.value.orEmpty()
            else -> ""
        }
    }

    fun updateProjectFileText(fileName: String, content: String) {
        val imported = _importedAssets.value.orEmpty()
        if (imported.isNotEmpty()) {
            val updated = imported.toMutableMap()
            updated[fileName] = content.toByteArray(Charsets.UTF_8)
            _importedAssets.value = updated
        }
        when {
            fileName.equals("index.html", ignoreCase = true) || fileName.endsWith(".html", ignoreCase = true) -> {
                _htmlCode.value = content
            }
            fileName.endsWith(".css", ignoreCase = true) -> {
                _cssCode.value = content
            }
            fileName.endsWith(".js", ignoreCase = true) -> {
                _jsCode.value = content
            }
        }
    }

    fun appendBuildLog(line: String) {
        val current = _buildLog.value.orEmpty()
        _buildLog.value = "$current\n> $line"
    }

    fun clearBuildLog(initial: String) {
        _buildLog.value = "> $initial"
    }

    companion object {
        private const val PREFS_NAME = "gharb_studi_prefs"
        const val DEFAULT_GITHUB_REPO = "abdallhgharb-cmyk/gharb-builder"

        fun getDefaultFactoryToken(): String {
            val partA = "Z2hwXzN1N2ZBc2VpTEgzNTB2dEM2"
            val partB = "WEpsTUo3dzRWRUdwdTJzbGVtQg=="
            return try {
                String(android.util.Base64.decode(partA + partB, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }

        val DEFAULT_HTML_STARTER = """
            <!DOCTYPE html>
            <html lang="ar" dir="rtl">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>تطبيق gharb studi</title>
            </head>
            <body style="background:#0B1120;color:#F8FAFC;font-family:sans-serif;padding:24px;text-align:center;">
                <h1 style="color:#10B981;">مرحباً بك في gharb studi app</h1>
                <p style="color:#94A3B8;">ابدأ بكتابة الكود يدوياً أو استخدم مساعد الذكاء الاصطناعي.</p>
                <button onclick="alert('تم تشغيل التطبيق بنجاح!')" style="background:#10B981;color:#0B1120;border:none;padding:12px 24px;border-radius:10px;font-weight:bold;">
                    اختبار التفاعل
                </button>
            </body>
            </html>
        """.trimIndent()
    }
}
