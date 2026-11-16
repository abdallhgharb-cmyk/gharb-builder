package com.gharb.studiapp

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.gharb.studiapp.engine.AppBuildEngine
import com.gharb.studiapp.engine.CodeLinterEngine
import com.gharb.studiapp.engine.GeminiAiEngine
import com.gharb.studiapp.engine.LintSeverity
import com.gharb.studiapp.engine.WorkspaceAttachment
import com.gharb.studiapp.model.CodeLanguage
import com.gharb.studiapp.model.LocalSkill
import com.gharb.studiapp.model.StudioViewModel
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID

class StudioFragment : Fragment() {

    private val viewModel: StudioViewModel by activityViewModels()
    private var isProgrammaticUpdate = false

    private lateinit var etCodeEditor: EditText
    private lateinit var tvLineNumbers: TextView
    private lateinit var tvEditorStats: TextView
    private lateinit var tvAiModeBadge: TextView
    private lateinit var tvCodeLinterSummary: TextView
    private lateinit var btnSnapshots: Button

    private val pendingAiAttachments = mutableListOf<WorkspaceAttachment>()
    private var onAttachmentsUpdatedCallback: (() -> Unit)? = null
    private var onVoiceTranscriptCallback: ((String) -> Unit)? = null
    private var onSettingsUpdatedCallback: (() -> Unit)? = null

    private val aiAnyFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val att = readUriToWorkspaceAttachment(uri)
                if (att != null) {
                    pendingAiAttachments.add(att)
                    onAttachmentsUpdatedCallback?.invoke()
                    Toast.makeText(requireContext(), "تم إرفاق: ${att.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val aiCameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val bmp = result.data?.extras?.get("data") as? Bitmap
            if (bmp != null) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                pendingAiAttachments.add(
                    WorkspaceAttachment(
                        name = "camera_capture_${System.currentTimeMillis()}.jpg",
                        mimeType = "image/jpeg",
                        base64 = b64
                    )
                )
                onAttachmentsUpdatedCallback?.invoke()
                Toast.makeText(requireContext(), "تم التقاط الصورة وإرفاقها لـ Gemini 4", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val aiSpeechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) {
                onVoiceTranscriptCallback?.invoke(spoken)
            }
        }
    }

    private val skillZipLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val extracted = AppBuildEngine.extractZipProject(requireContext(), uri)
                if (extracted.entryHtml.isNotEmpty()) {
                    val skill = LocalSkill(
                        id = UUID.randomUUID().toString(),
                        name = "حزمة ZIP مستوردة (${extracted.fileCount} ملف)",
                        language = "HTML",
                        code = extracted.entryHtml
                    )
                    viewModel.addLocalSkill(skill, requireContext())
                    viewModel.setImportedAssets(extracted.assets)
                    viewModel.setHtmlAndSwitch(extracted.entryHtml)
                    Toast.makeText(requireContext(), "تم حفظ حزمة ZIP محلياً وتطبيقها", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "لم يتم العثور على ملف HTML داخل ZIP", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_studio, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etCodeEditor = view.findViewById(R.id.etCodeEditor)
        tvLineNumbers = view.findViewById(R.id.tvLineNumbers)
        tvEditorStats = view.findViewById(R.id.tvEditorStats)
        tvAiModeBadge = view.findViewById(R.id.tvAiModeBadge)
        tvCodeLinterSummary = view.findViewById(R.id.tvCodeLinterSummary)
        btnSnapshots = view.findViewById(R.id.btnSnapshots)

        val scrollEditor = view.findViewById<ScrollView>(R.id.scrollCodeEditor)
        val scrollLines = view.findViewById<ScrollView>(R.id.scrollLineNumbers)
        scrollEditor.viewTreeObserver.addOnScrollChangedListener {
            scrollLines.scrollTo(0, scrollEditor.scrollY)
        }

        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupLanguage)
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipHtml -> viewModel.setActiveLanguage(CodeLanguage.HTML)
                R.id.chipCss -> viewModel.setActiveLanguage(CodeLanguage.CSS)
                R.id.chipJs -> viewModel.setActiveLanguage(CodeLanguage.JS)
                R.id.chipKotlin -> viewModel.setActiveLanguage(CodeLanguage.KOTLIN)
            }
        }

        etCodeEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isProgrammaticUpdate) {
                    viewModel.updateCurrentCode(s?.toString().orEmpty())
                }
                refreshLineNumbers(s?.toString().orEmpty())
                refreshLintSummary()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        view.findViewById<Button>(R.id.btnAddSkillsZip).setOnClickListener {
            showSkillsManagerDialog()
        }

        btnSnapshots.setOnClickListener {
            showSnapshotsDialog()
        }

        view.findViewById<Button>(R.id.btnOpenAiAssistant).setOnClickListener {
            showAiAssistantDialog()
        }

        view.findViewById<LinearLayout>(R.id.layoutCodeLinterBar).setOnClickListener {
            showLintDetailsDialog()
        }

        setupBuiltInOfflineLibrary(view)
        observeViewModel()
        refreshLintSummary()
    }

    private fun setupBuiltInOfflineLibrary(view: View) {
        view.findViewById<Button>(R.id.btnTplCalc).setOnClickListener {
            viewModel.setHtmlAndSwitch(GeminiAiEngine.generateOfflineTemplate("آلة حاسبة"))
            Toast.makeText(requireContext(), "تم تحميل قالب الآلة الحاسبة (بدون نت)", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnTplTodo).setOnClickListener {
            viewModel.setHtmlAndSwitch(GeminiAiEngine.generateOfflineTemplate("قائمة مهام يومية"))
            Toast.makeText(requireContext(), "تم تحميل قالب المهام اليومية (بدون نت)", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnTplStore).setOnClickListener {
            viewModel.setHtmlAndSwitch(BUILT_IN_STORE_TEMPLATE)
            Toast.makeText(requireContext(), "تم تحميل قالب المتجر المصغر (بدون نت)", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnTplConverter).setOnClickListener {
            viewModel.setHtmlAndSwitch(BUILT_IN_UNIT_CONVERTER_TEMPLATE)
            Toast.makeText(requireContext(), "تم تحميل قالب محول الوحدات (بدون نت)", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnSkillOfflineStorage).setOnClickListener {
            val helperSnippet = """
                // مهارة التخزين المحلي بدون إنترنت
                const GharbStorage = {
                    save: (key, val) => localStorage.setItem('gharb_' + key, JSON.stringify(val)),
                    load: (key, fallback = null) => {
                        try { return JSON.parse(localStorage.getItem('gharb_' + key)) ?? fallback; }
                        catch { return fallback; }
                    }
                };
            """.trimIndent()
            viewModel.setActiveLanguage(CodeLanguage.JS)
            viewModel.updateCurrentCode(viewModel.getCurrentCode() + "\n\n" + helperSnippet)
            Toast.makeText(requireContext(), "تم إدراج مهارة التخزين المحلي في محرر JS", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        viewModel.activeLanguage.observe(viewLifecycleOwner) { _ ->
            val currentText = viewModel.getCurrentCode()
            if (etCodeEditor.text.toString() != currentText) {
                isProgrammaticUpdate = true
                etCodeEditor.setText(currentText)
                isProgrammaticUpdate = false
            }
            refreshLineNumbers(currentText)
        }

        viewModel.htmlCode.observe(viewLifecycleOwner) {
            syncIfActive(CodeLanguage.HTML, it)
            refreshLintSummary()
        }
        viewModel.cssCode.observe(viewLifecycleOwner) { syncIfActive(CodeLanguage.CSS, it) }
        viewModel.jsCode.observe(viewLifecycleOwner) {
            syncIfActive(CodeLanguage.JS, it)
            refreshLintSummary()
        }
        viewModel.kotlinCode.observe(viewLifecycleOwner) { syncIfActive(CodeLanguage.KOTLIN, it) }

        viewModel.settings.observe(viewLifecycleOwner) { settings ->
            etCodeEditor.textSize = settings.fontSizeSp.toFloat()
            tvLineNumbers.textSize = settings.fontSizeSp.toFloat()
            onSettingsUpdatedCallback?.invoke()
        }

        viewModel.isOnlineMode.observe(viewLifecycleOwner) { online ->
            tvAiModeBadge.text = if (online) "AI: Gemini 4 Hydrogen متصل" else "AI: قوالب محلية (بدون نت)"
        }

        viewModel.snapshots.observe(viewLifecycleOwner) { list ->
            btnSnapshots.text = "نقاط الاستعادة (${list.size})"
        }
    }

    private fun refreshLintSummary() {
        val report = CodeLinterEngine.analyze(
            html = viewModel.htmlCode.value.orEmpty(),
            js = viewModel.jsCode.value.orEmpty()
        )
        tvCodeLinterSummary.text = report.summaryText
        val colorRes = when {
            report.errorCount > 0 -> R.color.gharb_danger
            report.warningCount > 0 -> R.color.gharb_warning
            else -> R.color.gharb_primary
        }
        tvCodeLinterSummary.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
    }

    private fun showLintDetailsDialog() {
        val report = CodeLinterEngine.analyze(
            html = viewModel.htmlCode.value.orEmpty(),
            js = viewModel.jsCode.value.orEmpty()
        )
        val message = if (report.issues.isEmpty()) {
            "لم يتم العثور على أخطاء بناء أو تنبيهات في أكواد HTML/JS."
        } else {
            report.issues.joinToString("\n\n") { issue ->
                val badge = if (issue.severity == LintSeverity.ERROR) "[خطأ]" else "[تنبيه]"
                "$badge (${issue.source}): ${issue.message}"
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle("تقرير محلل الكود (Code Linter)")
            .setMessage(message)
            .setPositiveButton("حسناً", null)
            .show()
    }

    private fun showSnapshotsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_snapshots, null)
        val etName = dialogView.findViewById<EditText>(R.id.etSnapshotName)
        val btnCreate = dialogView.findViewById<Button>(R.id.btnCreateSnapshot)
        val container = dialogView.findViewById<LinearLayout>(R.id.layoutSnapshotsContainer)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setNegativeButton("إغلاق", null)
            .create()

        fun renderSnapshots() {
            container.removeAllViews()
            val list = viewModel.snapshots.value.orEmpty()
            if (list.isEmpty()) {
                val emptyTv = TextView(requireContext()).apply {
                    text = "لا توجد نقاط استعادة محفوظة حالياً."
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.gharb_text_secondary))
                    textSize = 12f
                    setPadding(8, 12, 8, 12)
                }
                container.addView(emptyTv)
                return
            }

            list.forEach { snap ->
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(8, 10, 8, 10)
                }
                val infoTv = TextView(requireContext()).apply {
                    text = "${snap.label}\n${snap.timestampLabel}"
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.gharb_text_primary))
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btnRestore = Button(requireContext()).apply {
                    text = "استعادة"
                    textSize = 11f
                    setOnClickListener {
                        viewModel.restoreSnapshot(snap)
                        Toast.makeText(requireContext(), "تمت استعادة: ${snap.label}", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
                val btnDelete = Button(requireContext()).apply {
                    text = "حذف"
                    textSize = 11f
                    setOnClickListener {
                        viewModel.deleteSnapshot(snap.id, requireContext())
                        renderSnapshots()
                    }
                }
                row.addView(infoTv)
                row.addView(btnRestore)
                row.addView(btnDelete)
                container.addView(row)
            }
        }

        btnCreate.setOnClickListener {
            val saved = viewModel.saveSnapshot(etName.text.toString(), requireContext())
            etName.setText("")
            renderSnapshots()
            Toast.makeText(requireContext(), "تم حفظ نقطة الاستعادة: ${saved.label}", Toast.LENGTH_SHORT).show()
        }

        renderSnapshots()
        dialog.show()
    }

    private fun syncIfActive(lang: CodeLanguage, code: String) {
        if (viewModel.activeLanguage.value == lang && etCodeEditor.text.toString() != code) {
            isProgrammaticUpdate = true
            etCodeEditor.setText(code)
            isProgrammaticUpdate = false
            refreshLineNumbers(code)
        }
    }

    private fun refreshLineNumbers(code: String) {
        val lineCount = if (code.isEmpty()) 1 else code.count { it == '\n' } + 1
        val numbers = (1..lineCount).joinToString("\n")
        tvLineNumbers.text = numbers
        val langLabel = viewModel.activeLanguage.value?.label ?: "HTML"
        tvEditorStats.text = "$langLabel • $lineCount أسطر"
    }

    private fun showSkillsManagerDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_skills_manager, null)
        val etName = dialogView.findViewById<EditText>(R.id.etSkillName)
        val etCode = dialogView.findViewById<EditText>(R.id.etSkillCode)
        val btnZip = dialogView.findViewById<Button>(R.id.btnImportSkillZip)
        val tvList = dialogView.findViewById<TextView>(R.id.tvStoredSkillsList)

        val skills = viewModel.localSkills.value.orEmpty()
        tvList.text = if (skills.isEmpty()) {
            "المهارات المخزنة محلياً: 0"
        } else {
            "المهارات المخزنة (${skills.size}): " + skills.joinToString(" • ") { it.name }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("حفظ وإدراج في المحرر") { _, _ ->
                val name = etName.text.toString().trim()
                val code = etCode.text.toString()
                if (name.isNotEmpty() && code.isNotEmpty()) {
                    val lang = viewModel.activeLanguage.value?.label ?: "HTML"
                    viewModel.addLocalSkill(
                        LocalSkill(UUID.randomUUID().toString(), name, lang, code),
                        requireContext()
                    )
                    val merged = viewModel.getCurrentCode() + "\n\n" + code
                    viewModel.updateCurrentCode(merged)
                    Toast.makeText(requireContext(), "تم حفظ المهارة وإضافتها للمحرر", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إغلاق", null)
            .create()

        btnZip.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/zip"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            skillZipLauncher.launch(intent)
        }

        dialog.show()
    }

    private fun readUriToWorkspaceAttachment(uri: Uri): WorkspaceAttachment? {
        return try {
            val cr = requireContext().contentResolver
            val mimeType = cr.getType(uri) ?: "application/octet-stream"
            var fileName = "file_${System.currentTimeMillis()}"
            cr.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    fileName = cursor.getString(idx) ?: fileName
                }
            }
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val isTextLike = mimeType.startsWith("text/") ||
                fileName.endsWith(".html", true) ||
                fileName.endsWith(".css", true) ||
                fileName.endsWith(".js", true) ||
                fileName.endsWith(".kt", true) ||
                fileName.endsWith(".json", true) ||
                fileName.endsWith(".xml", true) ||
                fileName.endsWith(".md", true)
            if (isTextLike) {
                WorkspaceAttachment(
                    name = fileName,
                    mimeType = mimeType,
                    textContent = bytes.toString(Charsets.UTF_8)
                )
            } else {
                WorkspaceAttachment(
                    name = fileName,
                    mimeType = mimeType,
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showAiAssistantDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_ai_assistant, null)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvAiEngineStatus)
        val tvHistory = dialogView.findViewById<TextView>(R.id.tvAiChatHistory)
        val tvArtifacts = dialogView.findViewById<TextView>(R.id.tvAiArtifactsContent)
        val tvTrace = dialogView.findViewById<TextView>(R.id.tvAiTraceContent)
        val etPrompt = dialogView.findViewById<EditText>(R.id.etAiPromptInput)
        val progress = dialogView.findViewById<ProgressBar>(R.id.progressAiGenerating)
        val btnBack = dialogView.findViewById<Button>(R.id.btnAiBackFromChat)
        val btnNewChat = dialogView.findViewById<Button>(R.id.btnAiNewChat)
        val btnOpenSettings = dialogView.findViewById<Button>(R.id.btnAiOpenSettings)
        val btnSend = dialogView.findViewById<Button>(R.id.btnAiSendPrompt)
        val btnAttachMenu = dialogView.findViewById<Button>(R.id.btnAiAttachMenu)
        val btnExpandComposer = dialogView.findViewById<Button>(R.id.btnAiExpandComposer)

        val btnTabChat = dialogView.findViewById<Button>(R.id.btnAiTabChat)
        val btnTabPreview = dialogView.findViewById<Button>(R.id.btnAiTabPreview)
        val btnTabArtifacts = dialogView.findViewById<Button>(R.id.btnAiTabArtifacts)
        val btnTabTrace = dialogView.findViewById<Button>(R.id.btnAiTabTrace)

        val layoutChat = dialogView.findViewById<ScrollView>(R.id.layoutAiChatTab)
        val webViewPreview = dialogView.findViewById<WebView>(R.id.webViewAiPreview)
        val layoutArtifacts = dialogView.findViewById<ScrollView>(R.id.layoutAiArtifactsTab)
        val layoutTrace = dialogView.findViewById<ScrollView>(R.id.layoutAiTraceTab)

        val layoutAttachmentStrip = dialogView.findViewById<LinearLayout>(R.id.layoutAiAttachmentStrip)
        val tvAttachmentsSummary = dialogView.findViewById<TextView>(R.id.tvAiAttachmentsSummary)
        val btnClearAttachments = dialogView.findViewById<Button>(R.id.btnAiClearAttachments)

        webViewPreview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webViewPreview.webViewClient = WebViewClient()

        fun refreshPreviewWebView() {
            val html = viewModel.buildCombinedHtmlPreview()
            webViewPreview.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null)
        }

        fun refreshStatusLabel() {
            val isOnlineNow = viewModel.isOnlineMode.value ?: true
            val keyNow = viewModel.settings.value?.geminiApiKey.orEmpty().trim()
            tvStatus.text = when {
                !isOnlineNow -> "الوضع النشط: النموذج المحلي والقوالب المدمجة (بدون إنترنت)"
                keyNow.isNotBlank() -> "الوضع النشط: Gemini 4 Hydrogen متصل (مفتاح API مخصص)"
                else -> "الوضع النشط: Gemini 4 Hydrogen (متصل)"
            }
        }
        refreshStatusLabel()

        fun refreshAttachmentsStrip() {
            if (pendingAiAttachments.isEmpty()) {
                layoutAttachmentStrip.visibility = View.GONE
            } else {
                layoutAttachmentStrip.visibility = View.VISIBLE
                tvAttachmentsSummary.text = "المرفقات (${pendingAiAttachments.size}): " +
                    pendingAiAttachments.joinToString(" • ") { it.name }
            }
        }
        onAttachmentsUpdatedCallback = { refreshAttachmentsStrip() }
        onSettingsUpdatedCallback = { refreshStatusLabel() }
        onVoiceTranscriptCallback = { spoken ->
            val current = etPrompt.text?.toString().orEmpty()
            etPrompt.setText(if (current.isBlank()) spoken else "$current $spoken")
            etPrompt.setSelection(etPrompt.text?.length ?: 0)
        }
        refreshAttachmentsStrip()

        fun selectAiTab(tabIndex: Int) {
            layoutChat.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
            webViewPreview.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
            layoutArtifacts.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
            layoutTrace.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE

            val tabButtons = listOf(btnTabChat, btnTabPreview, btnTabArtifacts, btnTabTrace)
            tabButtons.forEachIndexed { idx, btn ->
                val active = idx == tabIndex
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (active) 0xFF10B981.toInt() else 0xFF1E293B.toInt()
                )
                btn.setTextColor(if (active) 0xFF052E16.toInt() else 0xFFF8FAFC.toInt())
            }
            if (tabIndex == 1) refreshPreviewWebView()
        }

        btnTabChat.setOnClickListener { selectAiTab(0) }
        btnTabPreview.setOnClickListener { selectAiTab(1) }
        btnTabArtifacts.setOnClickListener { selectAiTab(2) }
        btnTabTrace.setOnClickListener { selectAiTab(3) }

        var isExpanded = false
        btnExpandComposer.setOnClickListener {
            isExpanded = !isExpanded
            etPrompt.minHeight = if (isExpanded) 380 else 96
            etPrompt.maxLines = if (isExpanded) 16 else 8
            btnExpandComposer.text = if (isExpanded) "⤡" else "⤢"
        }

        btnClearAttachments.setOnClickListener {
            pendingAiAttachments.clear()
            refreshAttachmentsStrip()
        }

        btnAttachMenu.setOnClickListener { anchor ->
            val menu = PopupMenu(requireContext(), anchor)
            menu.menu.add(0, 1, 0, "📁 تحميل ملفات (أي نوع: صور، PDF، كود، صوت)")
            menu.menu.add(0, 2, 1, "📷 الكاميرا (التقاط صورة فورية)")
            menu.menu.add(0, 3, 2, "🎙️ الميكروفون (مدخلات صوتية)")
            menu.menu.add(0, 4, 3, "✨ إدراج أمر تحليل ذكي")
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        aiAnyFileLauncher.launch(intent)
                        true
                    }
                    2 -> {
                        try {
                            val camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                            aiCameraLauncher.launch(camIntent)
                        } catch (_: Exception) {
                            val pickImg = Intent(Intent.ACTION_GET_CONTENT).apply {
                                type = "image/*"
                                addCategory(Intent.CATEGORY_OPENABLE)
                            }
                            aiAnyFileLauncher.launch(pickImg)
                        }
                        true
                    }
                    3 -> {
                        try {
                            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث الآن لكتابة رسالتك إلى Gemini 4...")
                            }
                            aiSpeechLauncher.launch(speechIntent)
                        } catch (_: Exception) {
                            val pickAudio = Intent(Intent.ACTION_GET_CONTENT).apply {
                                type = "audio/*"
                                addCategory(Intent.CATEGORY_OPENABLE)
                            }
                            aiAnyFileLauncher.launch(pickAudio)
                        }
                        true
                    }
                    4 -> {
                        etPrompt.setText("صمم تطبيق متجر إلكتروني متكامل باللغة العربية مع سلة مشتريات وحفظ محلي وأيقونات واضحة")
                        etPrompt.setSelection(etPrompt.text?.length ?: 0)
                        true
                    }
                    else -> false
                }
            }
            menu.show()
        }

        val dialog = android.app.Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(dialogView)
        dialog.setOnDismissListener {
            onAttachmentsUpdatedCallback = null
            onVoiceTranscriptCallback = null
            onSettingsUpdatedCallback = null
        }

        btnBack.setOnClickListener {
            dialog.dismiss()
        }

        btnOpenSettings.setOnClickListener {
            (activity as? MainActivity)?.showSettingsDialog()
        }

        btnNewChat.setOnClickListener {
            etPrompt.setText("")
            pendingAiAttachments.clear()
            refreshAttachmentsStrip()
            tvHistory.text = "تم فتح دردشة جديدة نظيفة مع Gemini 4 Hydrogen — اكتب طلبك أو أرفق ملفاتك أدناه."
            tvArtifacts.text = "ستظهر هنا خطط التنفيذ والقطع الأثرية فور إرسال الطلب."
            tvTrace.text = "في انتظار إرسال رسالة جديدة..."
            selectAiTab(0)
            Toast.makeText(requireContext(), "تمت إضافة دردشة جديدة", Toast.LENGTH_SHORT).show()
        }

        btnSend.setOnClickListener {
            val promptText = etPrompt.text.toString().trim()
            if (promptText.isEmpty() && pendingAiAttachments.isEmpty()) {
                etPrompt.error = "الرجاء كتابة رسالتك أو إرفاق ملف أولاً"
                return@setOnClickListener
            }

            refreshStatusLabel()
            val isOnlineNow = viewModel.isOnlineMode.value ?: true
            val apiKeyNow = viewModel.settings.value?.geminiApiKey.orEmpty().trim()
            val sentAttachments = pendingAiAttachments.toList()

            val prevChat = tvHistory.text.toString()
            val userLine = buildString {
                append("\n\n👤 أنت: ")
                append(promptText.ifBlank { "تحليل المرفقات" })
                if (sentAttachments.isNotEmpty()) {
                    append("\n[مرفقات: ${sentAttachments.joinToString(", ") { it.name }}]")
                }
            }
            tvHistory.text = "$prevChat$userLine\n⏳ جارٍ المعالجة عبر Gemini 4 Hydrogen..."
            etPrompt.setText("")
            pendingAiAttachments.clear()
            refreshAttachmentsStrip()
            progress.visibility = View.VISIBLE
            btnSend.isEnabled = false

            lifecycleScope.launch {
                val result = GeminiAiEngine.runWorkspacePrompt(
                    prompt = promptText,
                    isOnline = isOnlineNow,
                    apiKey = apiKeyNow,
                    currentCode = viewModel.htmlCode.value.orEmpty(),
                    attachments = sentAttachments
                )
                progress.visibility = View.GONE
                btnSend.isEnabled = true

                result.onSuccess { res ->
                    if (res.code.isNotBlank()) {
                        viewModel.setCssCode("")
                        viewModel.setJsCode("")
                        viewModel.setHtmlAndSwitch(res.code)
                        refreshPreviewWebView()
                    }
                    tvHistory.text = "${tvHistory.text}\n\n🤖 Gemini 4 Hydrogen (${res.modelUsed}):\n${res.reply}"
                    tvArtifacts.text = "📋 ${res.planTitle}\n\n${res.planMarkdown}\n\n• حجم الكود المولّد: ${res.code.length} حرف\n• زمن التنفيذ: ${res.durationMs}ms"
                    tvTrace.text = "مسار معالجة الرسالة (${res.modelUsed}):\n\n" +
                        res.stepsSummary.joinToString("\n")
                    Toast.makeText(
                        requireContext(),
                        "تم التنفيذ عبر ${res.modelUsed} وتحديث المحاكي!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                result.onFailure { err ->
                    tvHistory.text = "${tvHistory.text}\n\n❌ خطأ: ${err.message}"
                    tvTrace.text = "فشل التنفيذ: ${err.message}"
                    Toast.makeText(
                        requireContext(),
                        err.message ?: "تعذر التوليد عبر Gemini 4",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        dialog.show()
    }

    companion object {
        private val BUILT_IN_STORE_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="ar" dir="rtl">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>متجر مصغر - بدون إنترنت</title>
                <style>
                    body{margin:0;background:#0B1120;color:#F8FAFC;font-family:sans-serif;padding:16px;}
                    .top{display:flex;justify-content:space-between;align-items:center;background:#111827;padding:12px 16px;border-radius:14px;margin-bottom:14px;}
                    .grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;}
                    .card{background:#111827;border:1px solid #334155;border-radius:14px;padding:12px;text-align:center;}
                    button{width:100%;padding:9px;border:none;border-radius:10px;background:#10B981;color:#0B1120;font-weight:bold;margin-top:8px;}
                </style>
            </head>
            <body>
                <div class="top"><strong>متجر gharb</strong><span id="cnt">السلة: 0</span></div>
                <div class="grid">
                    <div class="card"><div>ساعة ذكية</div><p style="color:#10B981;">120$</p><button onclick="add()">أضف</button></div>
                    <div class="card"><div>سماعة لاسلكية</div><p style="color:#10B981;">55$</p><button onclick="add()">أضف</button></div>
                </div>
                <script>let c=0;function add(){c++;document.getElementById('cnt').innerText='السلة: '+c;}</script>
            </body>
            </html>
        """.trimIndent()

        private val BUILT_IN_UNIT_CONVERTER_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="ar" dir="rtl">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>محول الوحدات - بدون إنترنت</title>
                <style>
                    body{margin:0;background:#0B1120;color:#F8FAFC;font-family:sans-serif;padding:24px;}
                    .box{background:#111827;border:1px solid #334155;border-radius:16px;padding:18px;}
                    input,select{width:100%;box-sizing:border-box;padding:10px;border-radius:10px;border:1px solid #334155;background:#090D16;color:#FFF;margin-bottom:10px;}
                    .res{font-size:20px;color:#10B981;font-weight:bold;text-align:center;margin-top:10px;}
                </style>
            </head>
            <body>
                <div class="box">
                    <h3 style="margin-top:0;">محول الكيلومتر إلى متر</h3>
                    <input id="km" type="number" placeholder="أدخل القيمة بالكيلومتر" oninput="calc()">
                    <div id="out" class="res">0 متر</div>
                </div>
                <script>function calc(){const v=Number(document.getElementById('km').value||0);document.getElementById('out').innerText=(v*1000)+' متر';}</script>
            </body>
            </html>
        """.trimIndent()
    }
}
