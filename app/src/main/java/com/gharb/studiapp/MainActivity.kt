package com.gharb.studiapp

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.viewpager2.widget.ViewPager2
import com.gharb.studiapp.model.AppSettings
import com.gharb.studiapp.model.CodeLanguage
import com.gharb.studiapp.model.StudioViewModel
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.io.File

class MainActivity : AppCompatActivity() {

    private val viewModel: StudioViewModel by viewModels()
    private lateinit var viewPager: ViewPager2
    private val navigationHistory = ArrayDeque<Int>()

    private val uploadFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (text != null) {
                    viewModel.updateCurrentCode(text)
                    Toast.makeText(this, "تم رفع الملف إلى المحرر بنجاح", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel.loadPersistedData(this)

        viewPager = findViewById(R.id.viewPagerMain)
        viewPager.adapter = StudioPagerAdapter(this)
        viewPager.offscreenPageLimit = 2

        val tabLayout = findViewById<TabLayout>(R.id.pageTabIndicator)
        val tabTitles = arrayOf("الاستوديو", "المحول", "البناء والمعاينة")
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (navigationHistory.lastOrNull() != position) {
                    navigationHistory.addLast(position)
                }
            }
        })

        setupTopToolbar()
        setupBottomNavigation()
    }

    private fun setupTopToolbar() {
        val switchOnline = findViewById<MaterialSwitch>(R.id.switchOnlineMode)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)
        val btnCornerMenu = findViewById<ImageButton>(R.id.btnCornerMenu)

        switchOnline.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setOnlineMode(isChecked)
            switchOnline.text = if (isChecked) "متصل" else "بدون نت"
            Toast.makeText(
                this,
                if (isChecked) "تم التبديل إلى وضع الإنترنت (Gemini AI)" else "تم التبديل إلى وضع العمل بدون إنترنت",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        btnCornerMenu.setOnClickListener { anchor ->
            showCornerDropdownMenu(anchor)
        }
    }

    private fun showCornerDropdownMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.top_corner_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_download -> {
                    saveCurrentFileLocally()
                    true
                }
                R.id.action_copy -> {
                    copyCurrentCodeToClipboard()
                    true
                }
                R.id.action_paste -> {
                    pasteClipboardToEditor()
                    true
                }
                R.id.action_upload -> {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    uploadFileLauncher.launch(intent)
                    true
                }
                R.id.action_edit -> {
                    viewPager.setCurrentItem(0, true)
                    Toast.makeText(this, "وضع التحرير نشط في صفحة الاستوديو", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_modify -> {
                    showQuickModifyDialog()
                    true
                }
                R.id.action_share -> {
                    shareCurrentCode()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun setupBottomNavigation() {
        val btnBack = findViewById<LinearLayout>(R.id.btnNavBack)
        val btnHome = findViewById<LinearLayout>(R.id.btnNavHome)
        val btnOptions = findViewById<LinearLayout>(R.id.btnNavOptions)

        btnBack.setOnClickListener {
            if (navigationHistory.size > 1) {
                navigationHistory.removeLast()
                val prev = navigationHistory.last()
                viewPager.setCurrentItem(prev, true)
            } else if (viewPager.currentItem > 0) {
                viewPager.setCurrentItem(viewPager.currentItem - 1, true)
            } else {
                Toast.makeText(this, "أنت في الصفحة الرئيسية بالفعل", Toast.LENGTH_SHORT).show()
            }
        }

        btnHome.setOnClickListener {
            viewPager.setCurrentItem(0, true)
        }

        btnOptions.setOnClickListener { anchor ->
            val options = arrayOf(
                "الانتقال إلى الاستوديو",
                "الانتقال إلى المحول (ZIP / HTML / URL)",
                "الانتقال إلى البناء والتوقيع والمعاينة",
                "إعدادات المنصة والذكاء الاصطناعي"
            )
            AlertDialog.Builder(this)
                .setTitle("خيارات التنقل السريع")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> viewPager.setCurrentItem(0, true)
                        1 -> viewPager.setCurrentItem(1, true)
                        2 -> viewPager.setCurrentItem(2, true)
                        3 -> showSettingsDialog()
                    }
                }
                .show()
        }
    }

    fun navigateToPage(index: Int) {
        viewPager.setCurrentItem(index, true)
    }

    private fun saveCurrentFileLocally() {
        val lang = viewModel.activeLanguage.value ?: CodeLanguage.HTML
        val fileName = "gharb_project.${lang.extension}"
        val outFile = File(filesDir, fileName)
        outFile.writeText(viewModel.getCurrentCode())
        Toast.makeText(this, "تم حفظ الملف: ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun copyCurrentCodeToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("gharb_code", viewModel.getCurrentCode()))
        Toast.makeText(this, "تم نسخ الكود إلى الحافظة", Toast.LENGTH_SHORT).show()
    }

    private fun pasteClipboardToEditor() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (!clipText.isNullOrEmpty()) {
            viewModel.updateCurrentCode(clipText)
            viewPager.setCurrentItem(0, true)
            Toast.makeText(this, "تم لصق الكود في المحرر", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "الحافظة فارغة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showQuickModifyDialog() {
        val input = EditText(this).apply {
            hint = "مثال: تغيير لون الخلفية إلى #0F172A أو تحديث العنوان"
        }
        AlertDialog.Builder(this)
            .setTitle("تعديل الكود السريع (Search & Replace)")
            .setView(input)
            .setPositiveButton("تنسيق وتجميل") { _, _ ->
                val formatted = viewModel.getCurrentCode().trim()
                viewModel.updateCurrentCode(formatted)
                Toast.makeText(this, "تم تنظيف وتعديل الكود", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun shareCurrentCode() {
        val lang = viewModel.activeLanguage.value ?: CodeLanguage.HTML
        val shareFile = File(cacheDir, "shared_gharb.${lang.extension}")
        shareFile.writeText(viewModel.getCurrentCode())
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", shareFile)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, viewModel.getCurrentCode())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "مشاركة الكود عبر"))
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etFont = dialogView.findViewById<EditText>(R.id.etSettingsFontSize)
        val etPath = dialogView.findViewById<EditText>(R.id.etSettingsSavePath)
        val etKey = dialogView.findViewById<EditText>(R.id.etSettingsGeminiKey)
        val etGithubRepo = dialogView.findViewById<EditText>(R.id.etSettingsGithubRepo)
        val etGithubToken = dialogView.findViewById<EditText>(R.id.etSettingsGithubToken)
        val btnRestoreDefaults = dialogView.findViewById<View>(R.id.btnRestoreDefaultCloudSettings)
        val switchWrap = dialogView.findViewById<MaterialSwitch>(R.id.switchSettingsWordWrap)

        val current = viewModel.settings.value ?: AppSettings()
        etFont.setText(current.fontSizeSp.toString())
        etPath.setText(current.savePath)
        etKey.setText(current.geminiApiKey)
        etGithubRepo.setText(current.githubRepo.ifBlank { StudioViewModel.DEFAULT_GITHUB_REPO })
        etGithubToken.setText(current.githubToken.ifBlank { StudioViewModel.getDefaultFactoryToken() })
        switchWrap.isChecked = current.wordWrap

        btnRestoreDefaults.setOnClickListener {
            etGithubRepo.setText(StudioViewModel.DEFAULT_GITHUB_REPO)
            etGithubToken.setText(StudioViewModel.getDefaultFactoryToken())
            Toast.makeText(this, "تمت استعادة إعدادات المصنع السحابي الافتراضية", Toast.LENGTH_SHORT).show()
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("حفظ الإعدادات") { _, _ ->
                val updated = AppSettings(
                    fontSizeSp = etFont.text.toString().toIntOrNull() ?: 13,
                    savePath = etPath.text.toString().ifBlank { current.savePath },
                    geminiApiKey = etKey.text.toString().trim(),
                    githubRepo = etGithubRepo.text.toString().trim().ifBlank { StudioViewModel.DEFAULT_GITHUB_REPO },
                    githubToken = etGithubToken.text.toString().trim().ifBlank { StudioViewModel.getDefaultFactoryToken() },
                    wordWrap = switchWrap.isChecked
                )
                viewModel.updateSettings(updated, this)
                Toast.makeText(this, "تم حفظ الإعدادات بنجاح", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
