package com.gharb.studiapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
import com.gharb.studiapp.model.CodeLanguage
import com.gharb.studiapp.model.LocalSkill
import com.gharb.studiapp.model.StudioViewModel
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch
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
        }

        viewModel.isOnlineMode.observe(viewLifecycleOwner) { online ->
            tvAiModeBadge.text = if (online) "وضع AI: Gemini متصل" else "وضع AI: قوالب محلية (بدون نت)"
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

    private fun showAiAssistantDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_ai_assistant, null)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvAiEngineStatus)
        val etPrompt = dialogView.findViewById<EditText>(R.id.etAiPromptInput)
        val progress = dialogView.findViewById<ProgressBar>(R.id.progressAiGenerating)

        val isOnline = viewModel.isOnlineMode.value ?: true
        val apiKey = viewModel.settings.value?.geminiApiKey.orEmpty()
        tvStatus.text = if (isOnline && apiKey.isNotBlank()) {
            "الوضع النشط: Gemini 2.5 Flash API (متصل)"
        } else {
            "الوضع النشط: النموذج المحلي والقوالب المدمجة (بدون إنترنت)"
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("توليد الكود الآن", null)
            .setNegativeButton("إلغاء", null)
            .create()

        dialog.setOnShowListener {
            val generateBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            generateBtn.setOnClickListener {
                val promptText = etPrompt.text.toString().trim()
                if (promptText.isEmpty()) {
                    etPrompt.error = "الرجاء كتابة فكرة التطبيق أولاً"
                    return@setOnClickListener
                }
                progress.visibility = View.VISIBLE
                generateBtn.isEnabled = false

                lifecycleScope.launch {
                    val result = GeminiAiEngine.generateCode(promptText, isOnline, apiKey)
                    progress.visibility = View.GONE
                    generateBtn.isEnabled = true

                    result.onSuccess { generatedHtml ->
                        viewModel.setCssCode("")
                        viewModel.setJsCode("")
                        viewModel.setHtmlAndSwitch(generatedHtml)
                        Toast.makeText(
                            requireContext(),
                            "تم توليد الكود ووضعه في المحرر بنجاح",
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    }
                    result.onFailure { err ->
                        Toast.makeText(
                            requireContext(),
                            "تعذر التوليد عبر Gemini: ${err.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
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
