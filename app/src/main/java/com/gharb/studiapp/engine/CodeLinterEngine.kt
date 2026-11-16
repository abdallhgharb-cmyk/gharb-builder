package com.gharb.studiapp.engine

enum class LintSeverity {
    ERROR,
    WARNING
}

data class LintIssue(
    val severity: LintSeverity,
    val source: String,
    val message: String
)

data class LintReport(
    val errorCount: Int,
    val warningCount: Int,
    val issues: List<LintIssue>
) {
    val summaryText: String
        get() = when {
            errorCount > 0 -> "محلل الكود: $errorCount أخطاء، $warningCount تنبيهات"
            warningCount > 0 -> "محلل الكود: 0 أخطاء، $warningCount تنبيهات"
            else -> "محلل الكود: الكود سليم (0 أخطاء)"
        }
}

object CodeLinterEngine {

    fun analyze(html: String, js: String): LintReport {
        val issues = mutableListOf<LintIssue>()
        val trimmedHtml = html.trim()
        val lowerHtml = trimmedHtml.lowercase()

        if (trimmedHtml.isEmpty()) {
            issues.add(LintIssue(LintSeverity.ERROR, "HTML", "محرر HTML فارغ؛ لا يمكن بناء تطبيق بدون محتوى."))
        } else {
            if (!lowerHtml.contains("<!doctype html")) {
                issues.add(LintIssue(LintSeverity.WARNING, "HTML", "يفضل إضافة إعلان <!DOCTYPE html> في بداية المستند."))
            }
            if (!lowerHtml.contains("name=\"viewport\"") && !lowerHtml.contains("name='viewport'")) {
                issues.add(LintIssue(LintSeverity.WARNING, "HTML", "وسم <meta name=\"viewport\"> مفقود مما قد يؤثر على مقاسات شاشة الأندرويد."))
            }

            val openHtml = Regex("<html\\b", RegexOption.IGNORE_CASE).findAll(trimmedHtml).count()
            val closeHtml = Regex("</html>", RegexOption.IGNORE_CASE).findAll(trimmedHtml).count()
            if (openHtml > 0 && closeHtml == 0) {
                issues.add(LintIssue(LintSeverity.ERROR, "HTML", "وسم <html> مفتوح بدون إغلاق </html>."))
            }

            val openBody = Regex("<body\\b", RegexOption.IGNORE_CASE).findAll(trimmedHtml).count()
            val closeBody = Regex("</body>", RegexOption.IGNORE_CASE).findAll(trimmedHtml).count()
            if (openBody > 0 && closeBody == 0) {
                issues.add(LintIssue(LintSeverity.ERROR, "HTML", "وسم <body> مفتوح بدون إغلاق </body>."))
            }

            val openScripts = Regex("<script\\b", RegexOption.IGNORE_CASE).findAll(trimmedHtml).count()
            val closeScripts = Regex("</script>", RegexOption.IGNORE_CASE).findAll(trimmedHtml).count()
            if (openScripts != closeScripts) {
                issues.add(LintIssue(LintSeverity.ERROR, "HTML", "عدم تطابق وسوم <script> ($openScripts فتح مقابل $closeScripts إغلاق)."))
            }

            val openDivs = Regex("<div\\b", RegexOption.IGNORE_CASE).findAll(trimmedHtml).count()
            val closeDivs = Regex("</div>", RegexOption.IGNORE_CASE).findAll(trimmedHtml).count()
            if (openDivs != closeDivs) {
                issues.add(LintIssue(LintSeverity.WARNING, "HTML", "عدد وسوم <div> ($openDivs) لا يطابق </div> ($closeDivs)."))
            }
        }

        // فحص موازنة الأقواس وتنبيهات JavaScript الشائعة
        val trimmedJs = js.trim()
        if (trimmedJs.isNotEmpty()) {
            if (!areBracketsBalanced(trimmedJs)) {
                issues.add(LintIssue(LintSeverity.ERROR, "JS", "عدم تطابق الأقواس () أو {} أو [] في كود JavaScript."))
            }
            if (Regex("\\beval\\s*\\(").containsMatchIn(trimmedJs)) {
                issues.add(LintIssue(LintSeverity.WARNING, "JS", "استخدام eval() غير آمن في بيئة Android WebView."))
            }
            if (Regex("document\\.write\\s*\\(").containsMatchIn(trimmedJs)) {
                issues.add(LintIssue(LintSeverity.WARNING, "JS", "تجنب document.write() واستخدم DOM API بدلاً منه."))
            }
        }

        val errors = issues.count { it.severity == LintSeverity.ERROR }
        val warnings = issues.count { it.severity == LintSeverity.WARNING }
        return LintReport(errorCount = errors, warningCount = warnings, issues = issues)
    }

    private fun areBracketsBalanced(code: String): Boolean {
        val stack = ArrayDeque<Char>()
        var inSingle = false
        var inDouble = false
        var escaped = false

        for (ch in code) {
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle
                continue
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble
                continue
            }
            if (inSingle || inDouble) continue

            when (ch) {
                '(', '{', '[' -> stack.addLast(ch)
                ')' -> if (stack.removeLastOrNull() != '(') return false
                '}' -> if (stack.removeLastOrNull() != '{') return false
                ']' -> if (stack.removeLastOrNull() != '[') return false
            }
        }
        return stack.isEmpty() && !inSingle && !inDouble
    }
}
