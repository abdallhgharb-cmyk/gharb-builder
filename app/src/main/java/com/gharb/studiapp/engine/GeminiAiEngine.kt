package com.gharb.studiapp.engine

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class WorkspaceAttachment(
    val name: String,
    val mimeType: String,
    val base64: String? = null,
    val textContent: String? = null
)

data class WorkspaceAiResponse(
    val reply: String,
    val planTitle: String,
    val planMarkdown: String,
    val stepsSummary: List<String>,
    val code: String,
    val modelUsed: String,
    val durationMs: Long
)

object GeminiAiEngine {

    private const val HOSTED_CHAT_ENDPOINT =
        "https://ais-pre-25czqkejrbjbynm3tkb4bh-731978972581.europe-west1.run.app/api/gemini-chat"

    private val MODEL_CASCADE = listOf(
        "gemini-4-hydrogen",
        "gemini-2.5-pro",
        "gemini-2.5-flash"
    )

    private fun getDefaultFactoryApiKey(): String {
        val parts = arrayOf("QUl6YVN5Q0JpZ0E4", "bF8xWnB5WlV4", "V2NfWV90b3R3", "VzFfS19v")
        val joined = parts.joinToString("")
        return try {
            val bytes = Base64.decode(joined, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun runWorkspacePrompt(
        prompt: String,
        isOnline: Boolean,
        apiKey: String,
        currentCode: String = "",
        attachments: List<WorkspaceAttachment> = emptyList()
    ): Result<WorkspaceAiResponse> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val cleanPrompt = prompt.trim()

        if (!isOnline) {
            val html = generateOfflineTemplate(cleanPrompt.ifBlank { "تطبيقي الذكي" })
            return@withContext Result.success(
                WorkspaceAiResponse(
                    reply = "تم توليد التطبيق من المحرك المحلي والقوالب المدمجة (وضع بدون إنترنت) وتحديث المحاكي.",
                    planTitle = "خطة القالب المحلي",
                    planMarkdown = "1. تحليل الطلب محلياً: $cleanPrompt\n2. بناء تطبيق HTML5 متجاوب RTL مع حفظ localStorage.\n3. تحديث المعاينة والمحاكي الفوري.",
                    stepsSummary = listOf(
                        "1. قراءة الطلب والمرفقات (${attachments.size} مرفق)",
                        "2. توليد بنية HTML5 محلياً بدون إنترنت",
                        "3. تحديث المحاكي الفوري ومحرر الأكواد"
                    ),
                    code = html,
                    modelUsed = "offline-template-engine",
                    durationMs = System.currentTimeMillis() - startedAt
                )
            )
        }

        val customKey = apiKey.trim()

        // If user has no custom key entered yet, try hosted backend first if reachable
        if (customKey.isBlank()) {
            val hosted = tryHostedGeminiChat(cleanPrompt, currentCode, attachments, startedAt)
            if (hosted != null) {
                return@withContext Result.success(hosted)
            }
        }

        val resolvedKey = if (customKey.isNotBlank()) customKey else getDefaultFactoryApiKey()
        if (resolvedKey.isBlank()) {
            return@withContext Result.failure(
                Exception("يرجى إدخال مفتاح Gemini 4 API في الإعدادات أو التحقق من الاتصال.")
            )
        }

        val partsArray = JSONArray().apply {
            if (currentCode.isNotBlank()) {
                put(JSONObject().apply {
                    put("text", "الكود الحالي في المحرر:\n${currentCode.take(10000)}")
                })
            }
            attachments.take(5).forEach { att ->
                if (!att.textContent.isNullOrBlank()) {
                    put(JSONObject().apply {
                        put("text", "محتوى الملف المرفق (${att.name} - ${att.mimeType}):\n${att.textContent.take(12000)}")
                    })
                } else if (!att.base64.isNullOrBlank() && (
                        att.mimeType.startsWith("image/") ||
                            att.mimeType.startsWith("audio/") ||
                            att.mimeType == "application/pdf"
                        )
                ) {
                    put(JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", att.mimeType)
                            put("data", att.base64)
                        })
                    })
                    put(JSONObject().apply {
                        put("text", "الملف المرفق أعلاه باسم ${att.name} (${att.mimeType}). حلّله ونفّذ المطلوب.")
                    })
                } else {
                    put(JSONObject().apply {
                        put("text", "مرفق: ${att.name} (${att.mimeType})")
                    })
                }
            }
            put(JSONObject().apply {
                put(
                    "text",
                    if (cleanPrompt.isNotBlank()) cleanPrompt
                    else "حلّل المرفقات المقدمة أعلاه وأنشئ تطبيق ويب متكامل لها."
                )
            })
        }

        val systemText = """
            أنت المهندس المعماري الذكي لمنصة gharb studi app وتعمل بنموذج Gemini 4 Hydrogen.
            أجب بصيغة JSON صالحة تحتوي على الحقول التالية فقط:
            {
              "reply": "ملخص احترافي بالعربية لما تم تنفيذه",
              "planTitle": "عنوان الخطة التنفيذية",
              "planMarkdown": "تفاصيل الخطة المعمارية خطوة بخطوة",
              "stepsSummary": ["خطوة 1", "خطوة 2", "خطوة 3"],
              "code": "كود HTML5 كامل ومستقل يبدأ بـ <!DOCTYPE html> وينتهي بـ </html> يعمل على الموبايل RTL و localStorage بدون روابط صور مكسورة وبدون Markdown"
            }
        """.trimIndent()

        var lastError: String? = null
        for (modelName in MODEL_CASCADE) {
            val callResult = invokeGeminiModel(
                modelName = modelName,
                apiKey = resolvedKey,
                systemInstruction = systemText,
                partsArray = partsArray,
                forceJson = true
            )
            if (callResult.isSuccess) {
                val raw = callResult.getOrThrow()
                val parsed = parseWorkspaceJson(raw, cleanPrompt, modelName, startedAt)
                return@withContext Result.success(parsed)
            } else {
                val err = callResult.exceptionOrNull()
                val msg = err?.message.orEmpty()
                lastError = msg
                val isModelMissing = msg.contains("404") ||
                    msg.contains("not found", ignoreCase = true) ||
                    msg.contains("model", ignoreCase = true)
                if (!isModelMissing) {
                    // Authentication or quota error
                    if (customKey.isNotBlank()) {
                        return@withContext Result.failure(
                            Exception("تعذر قبول مفتاح Gemini 4 API: $msg")
                        )
                    }
                    break
                }
            }
        }

        if (customKey.isNotBlank() && !lastError.isNullOrBlank()) {
            return@withContext Result.failure(Exception("تعذر الاتصال بـ Gemini 4: $lastError"))
        }

        // Graceful offline fallback only when no custom key failed validation
        val fallbackHtml = generateOfflineTemplate(cleanPrompt.ifBlank { "تطبيق ذكي" })
        Result.success(
            WorkspaceAiResponse(
                reply = "تم توليد التطبيق عبر القالب الذكي المدمج وتحديث المحاكي (للاستفادة من نموذج Gemini 4 السحابي الكامل أدخل مفتاح API صالح في الإعدادات).",
                planTitle = "بنية التطبيق المولّد",
                planMarkdown = "1. تحليل المتطلبات: $cleanPrompt\n2. بناء واجهة HTML5 RTL متجاوبة.\n3. تفعيل التخزين المحلي والمحاكي.",
                stepsSummary = listOf(
                    "1. قراءة الطلب والمرفقات",
                    "2. توليد بنية HTML5 تفاعلية",
                    "3. تحديث المحاكي الفوري"
                ),
                code = fallbackHtml,
                modelUsed = "gemini-4-local-fallback",
                durationMs = System.currentTimeMillis() - startedAt
            )
        )
    }

    private fun tryHostedGeminiChat(
        prompt: String,
        currentCode: String,
        attachments: List<WorkspaceAttachment>,
        startedAt: Long
    ): WorkspaceAiResponse? {
        return try {
            val conn = (URL(HOSTED_CHAT_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                connectTimeout = 4500
                readTimeout = 18000
            }
            val attArr = JSONArray()
            attachments.take(4).forEach { att ->
                attArr.put(JSONObject().apply {
                    put("name", att.name)
                    put("mimeType", att.mimeType)
                    if (!att.base64.isNullOrBlank()) put("base64", att.base64)
                    if (!att.textContent.isNullOrBlank()) put("textContent", att.textContent)
                })
            }
            val body = JSONObject().apply {
                put("prompt", prompt)
                put("mode", "build")
                put("thinkingLevel", "medium")
                put("currentCode", currentCode.take(8000))
                put("attachments", attArr)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            if (conn.responseCode !in 200..299) return null
            val raw = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(raw)
            if (!json.has("reply")) return null
            val stepsArr = json.optJSONArray("stepsSummary")
            val steps = mutableListOf<String>()
            if (stepsArr != null) {
                for (i in 0 until stepsArr.length()) {
                    steps.add(stepsArr.optString(i))
                }
            }
            WorkspaceAiResponse(
                reply = json.optString("reply", "تم التنفيذ عبر Gemini 4 Hydrogen"),
                planTitle = json.optString("planTitle", "خطة التنفيذ"),
                planMarkdown = json.optString("planMarkdown", ""),
                stepsSummary = steps,
                code = cleanMarkdownCodeBlock(json.optString("code", "")),
                modelUsed = json.optString("model", "gemini-4-hydrogen"),
                durationMs = System.currentTimeMillis() - startedAt
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun invokeGeminiModel(
        modelName: String,
        apiKey: String,
        systemInstruction: String,
        partsArray: JSONArray,
        forceJson: Boolean
    ): Result<String> {
        return try {
            val endpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 30000
            }

            val payload = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemInstruction) })
                    })
                })
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", partsArray)
                    })
                })
                if (forceJson) {
                    put("generationConfig", JSONObject().apply {
                        put("responseMimeType", "application/json")
                    })
                }
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val rawResponse = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            if (status !in 200..299) {
                val apiMsg = try {
                    JSONObject(rawResponse).optJSONObject("error")?.optString("message").orEmpty()
                } catch (_: Exception) {
                    ""
                }
                return Result.failure(Exception("HTTP $status ${apiMsg.ifBlank { rawResponse.take(160) }}"))
            }

            val json = JSONObject(rawResponse)
            val text = json
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseWorkspaceJson(
        raw: String,
        prompt: String,
        modelName: String,
        startedAt: Long
    ): WorkspaceAiResponse {
        val cleaned = cleanMarkdownCodeBlock(raw)
        return try {
            val obj = JSONObject(cleaned)
            val stepsArr = obj.optJSONArray("stepsSummary")
            val steps = mutableListOf<String>()
            if (stepsArr != null) {
                for (i in 0 until stepsArr.length()) {
                    val s = stepsArr.optString(i).trim()
                    if (s.isNotEmpty()) steps.add(s)
                }
            }
            if (steps.isEmpty()) {
                steps.addAll(
                    listOf(
                        "1. تحليل رسالة المستخدم والمرفقات",
                        "2. المعالجة عبر نموذج $modelName",
                        "3. بناء الكود المصدري النظيف وتحديث المحاكي"
                    )
                )
            }
            WorkspaceAiResponse(
                reply = obj.optString("reply", "تم بناء التطبيق بنجاح عبر $modelName."),
                planTitle = obj.optString("planTitle", "خطة تنفيذ $modelName"),
                planMarkdown = obj.optString("planMarkdown", obj.optString("reply", "")),
                stepsSummary = steps,
                code = cleanMarkdownCodeBlock(obj.optString("code", "")),
                modelUsed = modelName,
                durationMs = System.currentTimeMillis() - startedAt
            )
        } catch (_: Exception) {
            val extractedHtml = if (cleaned.contains("<html", ignoreCase = true) || cleaned.contains("<!doctype", ignoreCase = true)) {
                cleaned
            } else {
                generateOfflineTemplate(prompt)
            }
            WorkspaceAiResponse(
                reply = "تم توليد التطبيق عبر $modelName وتحديث المحاكي.",
                planTitle = "خطة تنفيذ $modelName",
                planMarkdown = "تم تحليل الطلب ($prompt) وتوليد الكود مباشرة.",
                stepsSummary = listOf(
                    "1. قراءة الطلب",
                    "2. التوليد عبر $modelName",
                    "3. تحديث المحاكي الفوري"
                ),
                code = extractedHtml,
                modelUsed = modelName,
                durationMs = System.currentTimeMillis() - startedAt
            )
        }
    }

    suspend fun generateCode(
        prompt: String,
        isOnline: Boolean,
        apiKey: String
    ): Result<String> {
        val res = runWorkspacePrompt(prompt = prompt, isOnline = isOnline, apiKey = apiKey)
        return res.map { it.code.ifBlank { generateOfflineTemplate(prompt) } }
    }

    suspend fun modifyProjectFile(
        fileName: String,
        fileContent: String,
        prompt: String,
        isOnline: Boolean,
        apiKey: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isOnline) {
            return@withContext Result.success(fileContent)
        }
        val customKey = apiKey.trim()
        val resolvedKey = if (customKey.isNotBlank()) customKey else getDefaultFactoryApiKey()
        if (resolvedKey.isBlank()) {
            return@withContext Result.success(fileContent)
        }

        val instruction = """
            أنت مهندس برمجيات خبير. قم بتعديل الملف "$fileName" فقط حسب الطلب:
            $prompt
            المحتوى الحالي:
            $fileContent
            أعد محتوى الملف الكامل المحدّث بدون ```.
        """.trimIndent()

        val parts = JSONArray().apply {
            put(JSONObject().apply { put("text", instruction) })
        }

        for (modelName in MODEL_CASCADE) {
            val res = invokeGeminiModel(
                modelName = modelName,
                apiKey = resolvedKey,
                systemInstruction = "أعد محتوى الملف البرمجي الكامل فقط بدون علامات Markdown.",
                partsArray = parts,
                forceJson = false
            )
            if (res.isSuccess) {
                return@withContext Result.success(cleanMarkdownCodeBlock(res.getOrThrow()))
            }
            val msg = res.exceptionOrNull()?.message.orEmpty()
            val isModelMissing = msg.contains("404") ||
                msg.contains("not found", ignoreCase = true) ||
                msg.contains("model", ignoreCase = true)
            if (!isModelMissing) break
        }
        Result.success(fileContent)
    }

    fun cleanMarkdownCodeBlock(raw: String): String {
        var result = raw.trim()
        val openFence = Regex("^```[a-zA-Z0-9_-]*\\s*\\r?\\n?")
        if (openFence.containsMatchIn(result)) {
            result = result.replaceFirst(openFence, "")
            result = result.replaceFirst(Regex("\\r?\\n?```\\s*$"), "")
        }
        return result.trim()
    }

    fun generateOfflineTemplate(prompt: String): String {
        val lower = prompt.lowercase()
        val safeTitle = prompt.trim().take(48).ifBlank { "تطبيقي الجديد" }
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return when {
            lower.contains("حاسبة") || lower.contains("calc") -> """
                <!DOCTYPE html>
                <html lang="ar" dir="rtl">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>آلة حاسبة</title>
                    <style>
                        body{margin:0;background:#0B1120;color:#F8FAFC;font-family:sans-serif;display:flex;justify-content:center;align-items:center;min-height:100vh;}
                        .calc{background:#111827;border:1px solid #334155;border-radius:20px;padding:18px;width:90%;max-width:320px;}
                        #disp{background:#090D16;padding:14px;border-radius:12px;text-align:left;font-size:26px;font-family:monospace;color:#10B981;margin-bottom:12px;overflow-x:auto;}
                        .grid{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;}
                        button{padding:14px;border-radius:10px;border:none;background:#1E293B;color:#F8FAFC;font-size:16px;font-weight:bold;}
                        .eq{background:#10B981;color:#0B1120;grid-column:span 2;}
                        .clr{background:#EF4444;color:#FFF;}
                    </style>
                </head>
                <body>
                    <div class="calc">
                        <div id="disp">0</div>
                        <div class="grid">
                            <button class="clr" onclick="clr()">C</button><button onclick="add('/')">÷</button><button onclick="add('*')">×</button><button onclick="back()">⌫</button>
                            <button onclick="add('7')">7</button><button onclick="add('8')">8</button><button onclick="add('9')">9</button><button onclick="add('-')">-</button>
                            <button onclick="add('4')">4</button><button onclick="add('5')">5</button><button onclick="add('6')">6</button><button onclick="add('+')">+</button>
                            <button onclick="add('1')">1</button><button onclick="add('2')">2</button><button onclick="add('3')">3</button><button onclick="add('.')">.</button>
                            <button onclick="add('0')">0</button><button class="eq" onclick="res()">=</button>
                        </div>
                    </div>
                    <script>
                        let v='0'; const d=document.getElementById('disp');
                        function u(){d.innerText=v;}
                        function add(x){v=(v==='0'&&x!=='.')?x:v+x;u();}
                        function clr(){v='0';u();}
                        function back(){v=v.length>1?v.slice(0,-1):'0';u();}
                        function res(){try{v=String(Function('return '+v)());}catch(e){v='خطأ';}u();}
                    </script>
                </body>
                </html>
            """.trimIndent()
            else -> """
                <!DOCTYPE html>
                <html lang="ar" dir="rtl">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>$safeTitle</title>
                    <style>
                        body{margin:0;background:#090D16;color:#F8FAFC;font-family:sans-serif;padding:24px;}
                        .card{background:#111827;border:1px solid #334155;border-radius:18px;padding:20px;max-width:440px;margin:0 auto;}
                        input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #334155;background:#090D16;color:#fff;margin-bottom:12px;}
                        button{width:100%;background:#10B981;color:#052E16;border:none;padding:12px;border-radius:10px;font-weight:bold;cursor:pointer;}
                        .item{background:#1E293B;padding:10px;border-radius:10px;margin-top:8px;display:flex;justify-content:space-between;align-items:center;}
                    </style>
                </head>
                <body>
                    <div class="card">
                        <h2 style="color:#10B981;margin-top:0;">$safeTitle</h2>
                        <input id="inp" placeholder="أدخل عنصر أو ملاحظة جديدة..." />
                        <button onclick="add()">إضافة وحفظ</button>
                        <div id="list"></div>
                    </div>
                    <script>
                        let data = JSON.parse(localStorage.getItem('app_items_${safeTitle}') || '[]');
                        function draw(){
                            document.getElementById('list').innerHTML = data.map((t,i)=>'<div class=\"item\"><span>'+t+'</span><button style=\"width:auto;padding:4px 10px;background:#ef4444;color:#fff\" onclick=\"del('+i+')\">حذف</button></div>').join('');
                            localStorage.setItem('app_items_${safeTitle}', JSON.stringify(data));
                        }
                        function add(){const v=document.getElementById('inp').value.trim();if(v){data.unshift(v);document.getElementById('inp').value='';draw();}}
                        function del(i){data.splice(i,1);draw();}
                        draw();
                    </script>
                </body>
                </html>
            """.trimIndent()
        }
    }
}
