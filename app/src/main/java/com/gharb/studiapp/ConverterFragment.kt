package com.gharb.studiapp

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.gharb.studiapp.engine.AppBuildEngine
import com.gharb.studiapp.model.StudioViewModel
import com.google.android.material.card.MaterialCardView

class ConverterFragment : Fragment() {

    private val viewModel: StudioViewModel by activityViewModels()
    private lateinit var tvBuildEngineLog: TextView
    private lateinit var layoutExtractionBanner: LinearLayout
    private lateinit var tvExtractionMessage: TextView
    private lateinit var pbExtractionProgress: ProgressBar
    private val mainHandler = Handler(Looper.getMainLooper())

    private val zipConverterLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val displayName = resolveDisplayName(uri) ?: "project.zip"
                runSmartExtraction(
                    logStart = "بدء فك ضغط مشروع ZIP ($displayName)...",
                    action = {
                        val extracted = AppBuildEngine.extractZipProject(requireContext(), uri)
                        if (extracted.entryHtml.isNotEmpty()) {
                            viewModel.setPreviewUrl(null)
                            viewModel.setImportedAssets(extracted.assets)
                            viewModel.setCssCode("")
                            viewModel.setJsCode("")
                            viewModel.setHtmlAndSwitch(extracted.entryHtml)

                            val detectedName = AppBuildEngine.resolveExtractedAppName(
                                htmlContent = extracted.entryHtml,
                                sourceUrl = null,
                                filename = displayName
                            )
                            if (!detectedName.isNullOrBlank()) {
                                viewModel.updateAppName(detectedName)
                                viewModel.appendBuildLog("تم استخراج اسم التطبيق تلقائياً: $detectedName")
                            }
                            viewModel.appendBuildLog("تم استخراج ${extracted.fileCount} ملف من الأرشيف وتجهيز شجرة الملفات.")
                            true
                        } else {
                            viewModel.appendBuildLog("خطأ: الأرشيف لا يحتوي على ملف index.html.")
                            false
                        }
                    }
                )
            }
        }
    }

    private val htmlConverterLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val displayName = resolveDisplayName(uri) ?: "index.html"
                runSmartExtraction(
                    logStart = "استيراد وقراءة ملف HTML ($displayName)...",
                    action = {
                        val rawHtml = requireContext().contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }
                        if (!rawHtml.isNullOrEmpty()) {
                            val htmlContent = com.gharb.studiapp.engine.GeminiAiEngine.cleanMarkdownCodeBlock(rawHtml)
                            viewModel.setPreviewUrl(null)
                            viewModel.setImportedAssets(emptyMap())
                            viewModel.setCssCode("")
                            viewModel.setJsCode("")
                            viewModel.setHtmlAndSwitch(htmlContent)
                            val detectedName = AppBuildEngine.resolveExtractedAppName(
                                htmlContent = htmlContent,
                                sourceUrl = null,
                                filename = displayName
                            )
                            if (!detectedName.isNullOrBlank()) {
                                viewModel.updateAppName(detectedName)
                                viewModel.appendBuildLog("تم استخراج اسم التطبيق تلقائياً: $detectedName")
                            }
                            true
                        } else {
                            viewModel.appendBuildLog("خطأ: تعذر قراءة محتوى ملف HTML.")
                            false
                        }
                    }
                )
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_converter, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvBuildEngineLog = view.findViewById(R.id.tvBuildEngineLog)
        layoutExtractionBanner = view.findViewById(R.id.layoutExtractionBanner)
        tvExtractionMessage = view.findViewById(R.id.tvExtractionMessage)
        pbExtractionProgress = view.findViewById(R.id.pbExtractionProgress)

        view.findViewById<MaterialCardView>(R.id.cardConvertZip).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/zip"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            zipConverterLauncher.launch(intent)
        }

        view.findViewById<MaterialCardView>(R.id.cardConvertHtml).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "text/html"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            htmlConverterLauncher.launch(intent)
        }

        view.findViewById<MaterialCardView>(R.id.cardConvertUrl).setOnClickListener {
            showUrlConverterDialog()
        }

        viewModel.buildLog.observe(viewLifecycleOwner) { logText ->
            tvBuildEngineLog.text = logText
        }
    }

    private fun showUrlConverterDialog() {
        val input = EditText(requireContext()).apply {
            hint = "https://example.com"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("تحويل رابط موقع URL إلى تطبيق")
            .setView(input)
            .setPositiveButton("تحويل وحقن") { _, _ ->
                val rawUrl = input.text.toString().trim()
                val normalizedUrl = AppBuildEngine.normalizeExternalUrl(rawUrl)
                if (normalizedUrl.isNotEmpty()) {
                    runSmartExtraction(
                        logStart = "ربط ومعاينة رابط الموقع: $normalizedUrl",
                        action = {
                            val wrapperHtml = AppBuildEngine.generateUrlWrapperHtml(normalizedUrl)
                            viewModel.setImportedAssets(emptyMap())
                            viewModel.setPreviewUrl(normalizedUrl)
                            viewModel.setHtmlAndSwitch(wrapperHtml)

                            val detectedName = AppBuildEngine.resolveExtractedAppName(
                                htmlContent = null,
                                sourceUrl = normalizedUrl,
                                filename = null
                            )
                            if (!detectedName.isNullOrBlank()) {
                                viewModel.updateAppName(detectedName)
                                viewModel.appendBuildLog("تم تعيين اسم التطبيق من النطاق: $detectedName")
                            }
                            true
                        }
                    )
                } else {
                    Toast.makeText(requireContext(), "الرجاء إدخال رابط صالح", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun runSmartExtraction(logStart: String, action: () -> Boolean) {
        viewModel.clearBuildLog(logStart)
        layoutExtractionBanner.visibility = View.VISIBLE
        tvExtractionMessage.text = "جاري استخراج الملفات وقراءة بيانات المشروع..."
        pbExtractionProgress.progress = 30
        Toast.makeText(requireContext(), "جاري استخراج الملفات وقراءة بيانات المشروع...", Toast.LENGTH_SHORT).show()

        mainHandler.postDelayed({
            if (!isAdded) return@postDelayed
            pbExtractionProgress.progress = 75
            val ok = action()
            if (ok) {
                pbExtractionProgress.progress = 100
                tvExtractionMessage.text = "تم استخراج البيانات! جاري الانتقال إلى البناء والمعاينة..."
                mainHandler.postDelayed({
                    if (!isAdded) return@postDelayed
                    layoutExtractionBanner.visibility = View.GONE
                    (activity as? MainActivity)?.navigateToPage(2)
                }, 420)
            } else {
                layoutExtractionBanner.visibility = View.GONE
                Toast.makeText(requireContext(), "تعذر استخراج بيانات المشروع", Toast.LENGTH_SHORT).show()
            }
        }, 280)
    }

    private fun resolveDisplayName(uri: Uri): String? {
        var name: String? = null
        val cursor: Cursor? = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment
    }
}
