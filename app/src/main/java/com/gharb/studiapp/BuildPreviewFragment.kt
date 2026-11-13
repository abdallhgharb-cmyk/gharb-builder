package com.gharb.studiapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.gharb.studiapp.engine.AppBuildEngine
import com.gharb.studiapp.engine.CodeLinterEngine
import com.gharb.studiapp.engine.GeminiAiEngine
import com.gharb.studiapp.engine.GitHubCloudBuildEngine
import com.gharb.studiapp.model.AppSettings
import com.gharb.studiapp.model.BuildConfig
import com.gharb.studiapp.model.StudioViewModel
import com.gharb.studiapp.model.TargetPlatform
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class BuildPreviewFragment : Fragment() {

    private val viewModel: StudioViewModel by activityViewModels()
    private var selectedIconUri: Uri? = null
    private var lastBuiltFile: File? = null
    private var lastBuiltFileName: String = ""
    private var lastDirectDownloadUrl: String? = null
    private var lastOfflinePwaHtml: File? = null
    private var lastOfflineConfig: BuildConfig? = null
    private var activeBuildJob: Job? = null

    private lateinit var ivAppIconPreview: ImageView
    private lateinit var tvIconStatus: TextView
    private lateinit var tvBuildLintStatus: TextView
    private lateinit var etAppName: TextInputEditText
    private lateinit var layoutExtractedFilesContainer: LinearLayout
    private lateinit var tvExtractedFilesHeader: TextView

    private val iconPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedIconUri = uri
                ivAppIconPreview.setImageURI(uri)
                tvIconStatus.text = "تم اختيار أيقونة مخصصة"
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_build_preview, container, false)
    }

    override fun onDestroyView() {
        activeBuildJob?.cancel()
        super.onDestroyView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toggleTarget = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleTargetOs)
        ivAppIconPreview = view.findViewById(R.id.ivAppIconPreview)
        tvIconStatus = view.findViewById(R.id.tvIconStatus)
        tvBuildLintStatus = view.findViewById(R.id.tvBuildLintStatus)
        val btnUploadIcon = view.findViewById<Button>(R.id.btnUploadAppIcon)
        etAppName = view.findViewById(R.id.etAppName)
        val etPackageName = view.findViewById<TextInputEditText>(R.id.etPackageName)
        val etVersionName = view.findViewById<TextInputEditText>(R.id.etVersionName)
        val etVersionCode = view.findViewById<TextInputEditText>(R.id.etVersionCode)
        val switchSigning = view.findViewById<MaterialSwitch>(R.id.switchDigitalSigning)
        val layoutKeystore = view.findViewById<LinearLayout>(R.id.layoutKeystoreFields)
        val etKeystoreAlias = view.findViewById<EditText>(R.id.etKeystoreAlias)
        val etKeystorePassword = view.findViewById<EditText>(R.id.etKeystorePassword)
        val btnLivePreview = view.findViewById<Button>(R.id.btnLivePreview)
        val btnStartBuild = view.findViewById<Button>(R.id.btnStartBuildExport)

        val layoutUrlPreviewCard = view.findViewById<LinearLayout>(R.id.layoutUrlPreviewCard)
        val tvPreviewUrlAddress = view.findViewById<TextView>(R.id.tvPreviewUrlAddress)
        val webViewInlineUrlPreview = view.findViewById<WebView>(R.id.webViewInlineUrlPreview)
        val btnOpenUrlExternal = view.findViewById<Button>(R.id.btnOpenUrlExternal)

        layoutExtractedFilesContainer = view.findViewById(R.id.layoutExtractedFilesContainer)
        tvExtractedFilesHeader = view.findViewById(R.id.tvExtractedFilesHeader)

        val pbCloudBuild = view.findViewById<ProgressBar>(R.id.pbCloudBuildProgress)
        val tvBuildPercent = view.findViewById<TextView>(R.id.tvBuildProgressPercent)
        val tvCurrentStep = view.findViewById<TextView>(R.id.tvCurrentBuildStep)
        val tvStep1 = view.findViewById<TextView>(R.id.tvBuildStep1)
        val tvStep2 = view.findViewById<TextView>(R.id.tvBuildStep2)
        val tvStep3 = view.findViewById<TextView>(R.id.tvBuildStep3)
        val tvStep4 = view.findViewById<TextView>(R.id.tvBuildStep4)
        val layoutApkSuccessBox = view.findViewById<LinearLayout>(R.id.layoutApkSuccessBox)
        val tvSuccessBoxTitle = view.findViewById<TextView>(R.id.tvSuccessBoxTitle)
        val tvApkFileDetails = view.findViewById<TextView>(R.id.tvApkFileDetails)
        val btnPinOfflineShortcut = view.findViewById<Button>(R.id.btnPinOfflineShortcut)
        val btnDownloadApkNow = view.findViewById<Button>(R.id.btnDownloadApkNow)

        webViewInlineUrlPreview.settings.javaScriptEnabled = true
        webViewInlineUrlPreview.settings.domStorageEnabled = true
        webViewInlineUrlPreview.webViewClient = WebViewClient()

        viewModel.buildConfig.observe(viewLifecycleOwner) { cfg ->
            if (etAppName.text?.toString() != cfg.appName) {
                etAppName.setText(cfg.appName)
            }
        }

        var currentPlatform = TargetPlatform.ANDROID_APK
        fun refreshStartBuildButtonLabel() {
            val isOnline = viewModel.isOnlineMode.value ?: true
            btnStartBuild.text = when {
                !isOnline -> "تصدير وتثبيت PWA محلياً"
                currentPlatform == TargetPlatform.ANDROID_APK -> "إنشاء وتصدير APK"
                else -> "إنشاء وتصدير IPA"
            }
        }

        viewModel.isOnlineMode.observe(viewLifecycleOwner) {
            refreshStartBuildButtonLabel()
        }

        viewModel.previewUrl.observe(viewLifecycleOwner) { url ->
            if (!url.isNullOrBlank()) {
                layoutUrlPreviewCard.visibility = View.VISIBLE
                tvPreviewUrlAddress.text = url
                webViewInlineUrlPreview.loadUrl(url)
                btnOpenUrlExternal.setOnClickListener {
                    openUrlInExternalBrowser(url)
                }
            } else {
                layoutUrlPreviewCard.visibility = View.GONE
            }
        }

        fun renderExtractedFilesTree() {
            val fileNames = viewModel.getProjectFileNames()
            tvExtractedFilesHeader.text = "قائمة ملفات المشروع المستخرجة (${fileNames.size})"
            layoutExtractedFilesContainer.removeAllViews()

            fileNames.forEach { fileName ->
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(16, 12, 16, 12)
                    setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.gharb_surface_elevated))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = 8
                    layoutParams = lp
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val tvName = TextView(requireContext()).apply {
                    text = fileName
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.gharb_text_primary))
                    typeface = android.graphics.Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(tvName)

                if (viewModel.isEditableTextFile(fileName)) {
                    val btnManualEdit = Button(requireContext()).apply {
                        text = "تحرير"
                        textSize = 11f
                        setOnClickListener { showManualFileEditorDialog(fileName) }
                    }
                    val btnAiEdit = Button(requireContext()).apply {
                        text = "تعديل بالذكاء الاصطناعي"
                        textSize = 11f
                        setOnClickListener { showAiFileEditorDialog(fileName) }
                    }
                    row.addView(btnManualEdit)
                    row.addView(btnAiEdit)
                }

                layoutExtractedFilesContainer.addView(row)
            }
        }

        viewModel.importedAssets.observe(viewLifecycleOwner) { renderExtractedFilesTree() }
        renderExtractedFilesTree()

        fun updateBuildLintBanner() {
            val report = CodeLinterEngine.analyze(
                html = viewModel.htmlCode.value.orEmpty(),
                js = viewModel.jsCode.value.orEmpty()
            )
            tvBuildLintStatus.text = report.summaryText
            val colorRes = when {
                report.errorCount > 0 -> R.color.gharb_danger
                report.warningCount > 0 -> R.color.gharb_warning
                else -> R.color.gharb_primary
            }
            tvBuildLintStatus.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        }

        viewModel.htmlCode.observe(viewLifecycleOwner) { updateBuildLintBanner() }
        viewModel.jsCode.observe(viewLifecycleOwner) { updateBuildLintBanner() }
        updateBuildLintBanner()

        toggleTarget.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentPlatform = if (checkedId == R.id.btnTargetApk) {
                    TargetPlatform.ANDROID_APK
                } else {
                    TargetPlatform.IOS_IPA
                }
                refreshStartBuildButtonLabel()
            }
        }

        switchSigning.setOnCheckedChangeListener { _, isChecked ->
            layoutKeystore.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnUploadIcon.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            iconPickerLauncher.launch(intent)
        }

        fun collectConfig(): BuildConfig {
            return BuildConfig(
                targetPlatform = currentPlatform,
                appName = etAppName.text?.toString()?.trim().orEmpty(),
                packageName = etPackageName.text?.toString()?.trim().orEmpty(),
                versionName = etVersionName.text?.toString()?.trim().orEmpty().ifBlank { "1.0.0" },
                versionCode = etVersionCode.text?.toString()?.toIntOrNull() ?: 1,
                iconUri = selectedIconUri,
                digitalSigningEnabled = switchSigning.isChecked,
                keystoreAlias = etKeystoreAlias.text.toString().trim(),
                keystorePassword = etKeystorePassword.text.toString()
            )
        }

        btnPinOfflineShortcut.setOnClickListener {
            val entryHtml = lastOfflinePwaHtml
            val cfg = lastOfflineConfig
            if (entryHtml != null && cfg != null) {
                val ok = AppBuildEngine.requestPinHomeScreenShortcut(requireContext(), cfg, entryHtml)
                Toast.makeText(
                    requireContext(),
                    if (ok) "تم إرسال طلب إضافة الأيقونة إلى الشاشة الرئيسية" else "تم فتح التطبيق محلياً في وضع ملء الشاشة",
                    Toast.LENGTH_SHORT
                ).show()
                if (!ok) {
                    val launchIntent = Intent(requireContext(), WebAppContainerActivity::class.java).apply {
                        putExtra(WebAppContainerActivity.EXTRA_LOCAL_HTML_PATH, entryHtml.absolutePath)
                        putExtra(WebAppContainerActivity.EXTRA_APP_TITLE, cfg.appName)
                    }
                    startActivity(launchIntent)
                }
            }
        }

        btnDownloadApkNow.setOnClickListener {
            val file = lastBuiltFile
            if (file != null && file.exists()) {
                try {
                    val uri = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        file
                    )
                    val mime = if (lastBuiltFileName.endsWith(".apk")) {
                        "application/vnd.android.package-archive"
                    } else {
                        "application/zip"
                    }
                    val openIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(Intent.createChooser(openIntent, "فتح أو تثبيت $lastBuiltFileName"))
                } catch (_: Exception) {
                    Toast.makeText(
                        requireContext(),
                        "الملف محفوظ في مجلد Downloads/GharbStudi/$lastBuiltFileName",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else if (!lastDirectDownloadUrl.isNullOrBlank()) {
                openUrlInExternalBrowser(lastDirectDownloadUrl!!)
            }
        }

        fun updateBuildStepsVisual(activeStep: Int, completed: Boolean) {
            val primaryColor = ContextCompat.getColor(requireContext(), R.color.gharb_primary)
            val mutedColor = ContextCompat.getColor(requireContext(), R.color.gharb_text_secondary)
            val stepViews = listOf(tvStep1, tvStep2, tvStep3, tvStep4)
            stepViews.forEachIndexed { idx, tv ->
                val stepNum = idx + 1
                tv.setTextColor(if (completed || stepNum <= activeStep) primaryColor else mutedColor)
            }
        }

        fun setBuildProgress(percent: Int, message: String) {
            pbCloudBuild.progress = percent.coerceIn(0, 100)
            tvBuildPercent.text = "${percent.coerceIn(0, 100)}%"
            tvCurrentStep.text = message
        }

        fun executeHybridBuild(config: BuildConfig) {
            if (config.appName.isBlank() || config.packageName.isBlank()) {
                Toast.makeText(requireContext(), "يرجى إدخال اسم التطبيق واسم الحزمة أولاً", Toast.LENGTH_SHORT).show()
                return
            }
            viewModel.updateBuildConfig(config)
            activeBuildJob?.cancel()
            layoutApkSuccessBox.visibility = View.GONE
            btnPinOfflineShortcut.visibility = View.GONE
            lastBuiltFile = null
            lastDirectDownloadUrl = null

            val isOnline = viewModel.isOnlineMode.value ?: true

            // وضع بدون نت: توليد حزمة PWA محلية وتثبيت اختصار الشاشة الرئيسية فوراً
            if (!isOnline) {
                updateBuildStepsVisual(4, true)
                setBuildProgress(100, "تم تجهيز حزمة PWA المحلية (بدون إنترنت)")

                val pwaResult = AppBuildEngine.buildOfflinePwaAndPinShortcut(
                    context = requireContext(),
                    config = config,
                    htmlContent = viewModel.buildCombinedHtmlPreview(),
                    extraAssets = viewModel.importedAssets.value.orEmpty(),
                    pinShortcutNow = false
                )
                lastOfflinePwaHtml = pwaResult.entryHtmlFile
                lastOfflineConfig = config
                lastBuiltFile = pwaResult.zipPackageFile
                lastBuiltFileName = pwaResult.zipDisplayName

                val sizeLabel = AppBuildEngine.formatFileSize(pwaResult.sizeBytes)
                tvSuccessBoxTitle.text = "تطبيق PWA المحلي جاهز للعمل بدون إنترنت!"
                tvApkFileDetails.text = "${pwaResult.zipDisplayName} • $sizeLabel"
                btnPinOfflineShortcut.visibility = View.VISIBLE
                btnDownloadApkNow.text = "فتح / مشاركة حزمة PWA (ZIP)"
                layoutApkSuccessBox.visibility = View.VISIBLE
                Toast.makeText(requireContext(), "تم تجهيز حزمة PWA وتثبيتها محلياً بنجاح", Toast.LENGTH_SHORT).show()
                return
            }

            // وضع متصل: التجميع الحقيقي في السحابة عبر GitHub Actions
            val currentSettings = viewModel.settings.value ?: AppSettings()
            val githubRepo = currentSettings.githubRepo.ifBlank { StudioViewModel.DEFAULT_GITHUB_REPO }
            val githubToken = currentSettings.githubToken.ifBlank { StudioViewModel.getDefaultFactoryToken() }

            updateBuildStepsVisual(1, false)
            setBuildProgress(10, "جاري رفع ملفات المشروع إلى $githubRepo...")
            btnStartBuild.isEnabled = false

            activeBuildJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val startRes = GitHubCloudBuildEngine.startCloudBuild(
                        context = requireContext(),
                        githubRepo = githubRepo,
                        githubToken = githubToken,
                        config = config,
                        htmlContent = viewModel.buildCombinedHtmlPreview(),
                        extraAssets = viewModel.importedAssets.value.orEmpty(),
                        previewUrl = viewModel.previewUrl.value
                    )

                    startRes.onFailure { err ->
                        setBuildProgress(0, "تعذر بدء البناء: ${err.message}")
                        btnStartBuild.isEnabled = true
                        AlertDialog.Builder(requireContext())
                            .setTitle("تعذر الاتصال بمصنع البناء السحابي")
                            .setMessage(err.message ?: "تحقق من اتصال الإنترنت أو إعدادات GitHub.")
                            .setPositiveButton("حسناً", null)
                            .show()
                        return@launch
                    }

                    val started = startRes.getOrThrow()
                    updateBuildStepsVisual(2, false)
                    setBuildProgress(20, "تم دفع المشروع بنجاح. في انتظار GitHub Actions...")

                    var finished = false
                    var attempts = 0
                    while (isActive && !finished && attempts < 90) {
                        attempts++
                        delay(4000)

                        val pollRes = GitHubCloudBuildEngine.pollBuildStatus(
                            owner = started.owner,
                            repo = started.repo,
                            commitSha = started.commitSha,
                            githubToken = githubToken
                        )
                        val status = pollRes.getOrNull() ?: continue

                        val stepIndex = when {
                            status.progressPercent >= 100 -> 4
                            status.progressPercent >= 65 -> 3
                            status.progressPercent >= 25 -> 2
                            else -> 1
                        }
                        updateBuildStepsVisual(stepIndex, status.status == "completed")
                        setBuildProgress(status.progressPercent, status.currentStepLabel)

                        if (status.status == "completed") {
                            finished = true
                            lastDirectDownloadUrl = status.apkDownloadUrl ?: status.runUrl
                            lastBuiltFileName = status.apkFileName ?: started.apkFileName

                            setBuildProgress(98, "جاري تنزيل ملف APK الحقيقي إلى ذاكرة الهاتف...")
                            val dlResult = GitHubCloudBuildEngine.downloadRealApkToDevice(
                                context = requireContext(),
                                owner = started.owner,
                                repo = started.repo,
                                githubToken = githubToken,
                                directDownloadUrl = status.apkDownloadUrl,
                                artifactId = status.artifactId,
                                preferredFileName = lastBuiltFileName
                            )
                            dlResult.onSuccess { localApk ->
                                lastBuiltFile = localApk
                            }

                            setBuildProgress(100, "تم إنشاء وتوقيع ملف APK الحقيقي بنجاح!")
                            val sizeBytes = lastBuiltFile?.length() ?: status.apkSizeBytes
                            val sizeLabel = if (sizeBytes > 0) AppBuildEngine.formatFileSize(sizeBytes) else "Signed APK"
                            tvSuccessBoxTitle.text = "ملف APK الحقيقي جاهز للتثبيت!"
                            tvApkFileDetails.text = "$lastBuiltFileName • $sizeLabel"
                            btnPinOfflineShortcut.visibility = View.GONE
                            btnDownloadApkNow.text = "تحميل وتثبيت APK الحقيقي"
                            layoutApkSuccessBox.visibility = View.VISIBLE
                            Toast.makeText(
                                requireContext(),
                                "تم تجميع وتنزيل $lastBuiltFileName بنجاح!",
                                Toast.LENGTH_LONG
                            ).show()
                        } else if (status.status == "failed") {
                            finished = true
                            AlertDialog.Builder(requireContext())
                                .setTitle("فشل التجميع السحابي")
                                .setMessage("${status.currentStepLabel}\n${status.runUrl.orEmpty()}")
                                .setPositiveButton("حسناً", null)
                                .show()
                        }
                    }
                } finally {
                    btnStartBuild.isEnabled = true
                }
            }
        }

        btnLivePreview.setOnClickListener {
            val config = collectConfig()
            viewModel.updateBuildConfig(config)
            showLivePreviewDialog(config.appName.ifBlank { "gharb studi app" })
        }

        btnStartBuild.setOnClickListener {
            val lintReport = CodeLinterEngine.analyze(
                html = viewModel.htmlCode.value.orEmpty(),
                js = viewModel.jsCode.value.orEmpty()
            )
            if (lintReport.errorCount > 0) {
                AlertDialog.Builder(requireContext())
                    .setTitle("تنبيه محلل الكود قبل التصدير")
                    .setMessage("تم اكتشاف ${lintReport.errorCount} أخطاء في الكود قد تمنع البناء:\n\n" +
                        lintReport.issues.joinToString("\n") { "• (${it.source}) ${it.message}" })
                    .setPositiveButton("تجاهل ومتابعة") { _, _ ->
                        executeHybridBuild(collectConfig())
                    }
                    .setNegativeButton("عودة للتصحيح", null)
                    .show()
                return@setOnClickListener
            }
            executeHybridBuild(collectConfig())
        }
    }

    private fun showManualFileEditorDialog(fileName: String) {
        val currentText = viewModel.readProjectFileText(fileName)
        val editor = EditText(requireContext()).apply {
            setText(currentText)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            minLines = 10
            maxLines = 16
            setPadding(24, 16, 24, 16)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("تحرير الملف: $fileName")
            .setView(editor)
            .setPositiveButton("حفظ التعديلات") { _, _ ->
                viewModel.updateProjectFileText(fileName, editor.text.toString())
                Toast.makeText(requireContext(), "تم حفظ التعديلات على $fileName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showAiFileEditorDialog(fileName: String) {
        val promptInput = EditText(requireContext()).apply {
            hint = "اكتب التعديل المطلوب على $fileName..."
            minLines = 3
        }

        AlertDialog.Builder(requireContext())
            .setTitle("تعديل بالذكاء الاصطناعي: $fileName")
            .setView(promptInput)
            .setPositiveButton("تطبيق التعديل") { _, _ ->
                val prompt = promptInput.text.toString().trim()
                if (prompt.isEmpty()) return@setPositiveButton
                val currentText = viewModel.readProjectFileText(fileName)
                val isOnline = viewModel.isOnlineMode.value ?: true
                val apiKey = viewModel.settings.value?.geminiApiKey.orEmpty()

                Toast.makeText(requireContext(), "جاري تعديل $fileName...", Toast.LENGTH_SHORT).show()
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = GeminiAiEngine.modifyProjectFile(fileName, currentText, prompt, isOnline, apiKey)
                    result.onSuccess { updated ->
                        viewModel.updateProjectFileText(fileName, updated)
                        Toast.makeText(requireContext(), "تم تحديث $fileName بالذكاء الاصطناعي", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun openUrlInExternalBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "تعذر فتح الرابط في المتصفح الخارجي", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showLivePreviewDialog(appTitle: String) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_live_preview, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvPreviewPhoneTitle)
        val tvResolution = dialogView.findViewById<TextView>(R.id.tvEmulatorResolution)
        val deviceFrame = dialogView.findViewById<LinearLayout>(R.id.layoutEmulatorDeviceFrame)
        val btnOrientation = dialogView.findViewById<Button>(R.id.btnEmulatorOrientation)
        val btnSize = dialogView.findViewById<Button>(R.id.btnEmulatorSize)
        val btnColor = dialogView.findViewById<Button>(R.id.btnEmulatorColor)
        val btnOpenExternal = dialogView.findViewById<Button>(R.id.btnOpenExternalBrowser)
        val webView = dialogView.findViewById<WebView>(R.id.webViewPhoneSimulator)

        tvTitle.text = "$appTitle • Android 14"
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()

        val activeUrl = viewModel.previewUrl.value
        if (!activeUrl.isNullOrBlank()) {
            btnOpenExternal.visibility = View.VISIBLE
            btnOpenExternal.setOnClickListener { openUrlInExternalBrowser(activeUrl) }
            webView.loadUrl(activeUrl)
        } else {
            btnOpenExternal.visibility = View.GONE
            val combinedHtml = viewModel.buildCombinedHtmlPreview()
            webView.loadDataWithBaseURL(null, combinedHtml, "text/html", "UTF-8", null)
        }

        var isLandscape = false
        val sizeLabels = listOf("صغير", "قياسي", "كبير")
        val sizeDims = listOf(280 to 440, 320 to 500, 360 to 560)
        var sizeIndex = 1

        val colorOptions = listOf(
            "أوبسيديان" to "#111827",
            "تيتانيوم" to "#334155",
            "زمردي" to "#064E3B",
            "كوبالت" to "#1E3A8A"
        )
        var colorIndex = 0

        fun applyEmulatorState() {
            val (baseW, baseH) = sizeDims[sizeIndex]
            val wDp = if (isLandscape) baseH else baseW
            val hDp = if (isLandscape) baseW else baseH
            val density = resources.displayMetrics.density

            btnOrientation.text = if (isLandscape) "أفقي Landscape" else "عمودي Portrait"
            btnSize.text = "الحجم: ${sizeLabels[sizeIndex]}"
            btnColor.text = "اللون: ${colorOptions[colorIndex].first}"
            tvResolution.text = "${wDp}×${hDp} • 100%"

            deviceFrame.setBackgroundColor(Color.parseColor(colorOptions[colorIndex].second))
            val params = deviceFrame.layoutParams
            params.width = (wDp * density).toInt().coerceAtMost(resources.displayMetrics.widthPixels - 48)
            params.height = (hDp * density).toInt().coerceAtMost((resources.displayMetrics.heightPixels * 0.68f).toInt())
            deviceFrame.layoutParams = params
        }

        btnOrientation.setOnClickListener {
            isLandscape = !isLandscape
            applyEmulatorState()
        }
        btnSize.setOnClickListener {
            sizeIndex = (sizeIndex + 1) % sizeLabels.size
            applyEmulatorState()
        }
        btnColor.setOnClickListener {
            colorIndex = (colorIndex + 1) % colorOptions.size
            applyEmulatorState()
        }

        applyEmulatorState()

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("إغلاق المحاكي", null)
            .show()
    }
}
