package com.gharb.studiapp.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GeminiAiEngine {

    suspend fun generateCode(
        prompt: String,
        isOnline: Boolean,
        apiKey: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isOnline || apiKey.isBlank()) {
            return@withContext Result.success(generateOfflineTemplate(prompt))
        }

        try {
            val endpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 25000
            }

            val instruction = """
                أنت مهندس برمجيات خبير داخل تطبيق gharb studi app.
                قم بتوليد صفحة HTML5 كاملة ومستقلة (تتضمن CSS و JavaScript داخلياً) باللغة العربية (dir="rtl") متجاوبة مع شاشة الهاتف وتعمل داخل Android WebView بدون الحاجة لملفات خارجية.
                أرجع كود HTML الصافي فقط بدون أي شرح إضافي.
                الطلب: $prompt
            """.trimIndent()

            val payload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", instruction) })
                        })
                    })
                })
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val rawResponse = stream.bufferedReader().use(BufferedReader::readText)

            if (status !in 200..299) {
                return@withContext Result.success(generateOfflineTemplate(prompt))
            }

            val json = JSONObject(rawResponse)
            val text = json
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            val cleaned = cleanMarkdownCodeBlock(text)
            Result.success(cleaned)
        } catch (ex: Exception) {
            Result.success(generateOfflineTemplate(prompt))
        }
    }

    suspend fun modifyProjectFile(
        fileName: String,
        fileContent: String,
        prompt: String,
        isOnline: Boolean,
        apiKey: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isOnline || apiKey.isBlank()) {
            val comment = if (fileName.endsWith(".html", ignoreCase = true)) {
                "<!-- تعديل محلي: $prompt -->"
            } else {
                "/* تعديل محلي: $prompt */"
            }
            return@withContext Result.success("$comment\n$fileContent")
        }

        try {
            val endpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 25000
            }

            val instruction = """
                أنت مهندس برمجيات خبير داخل تطبيق gharb studi app.
                قم بتعديل الملف "$fileName" فقط حسب طلب المستخدم وأعد محتوى الملف الكامل المحدّث بدون أي شرح أو علامات Markdown.
                طلب التعديل: $prompt

                المحتوى الحالي للملف ($fileName):
                $fileContent
            """.trimIndent()

            val payload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", instruction) })
                        })
                    })
                })
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val rawResponse = stream.bufferedReader().use(BufferedReader::readText)
            if (status !in 200..299) {
                return@withContext Result.success(fileContent)
            }

            val json = JSONObject(rawResponse)
            val text = json
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            Result.success(cleanMarkdownCodeBlock(text))
        } catch (_: Exception) {
            Result.success(fileContent)
        }
    }

    private fun cleanMarkdownCodeBlock(raw: String): String {
        var result = raw.trim()
        if (result.startsWith("```")) {
            val firstNewline = result.indexOf('\n')
            if (firstNewline != -1) {
                result = result.substring(firstNewline + 1)
            }
            if (result.endsWith("```")) {
                result = result.substring(0, result.length - 3)
            }
        }
        return result.trim()
    }

    fun generateOfflineTemplate(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            lower.contains("حاسبة") || lower.contains("calc") -> """
                <!DOCTYPE html>
                <html lang="ar" dir="rtl">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>آلة حاسبة - gharb studi</title>
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
                            <button class="clr" onclick="clr()">C</button>
                            <button onclick="add('/')">÷</button>
                            <button onclick="add('*')">×</button>
                            <button onclick="back()">⌫</button>
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

            lower.contains("مهام") || lower.contains("todo") -> """
                <!DOCTYPE html>
                <html lang="ar" dir="rtl">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>مهامي - وضع بدون إنترنت</title>
                    <style>
                        body{margin:0;background:#0B1120;color:#F8FAFC;font-family:sans-serif;padding:20px;}
                        .box{background:#111827;border:1px solid #334155;border-radius:16px;padding:16px;}
                        .row{display:flex;gap:8px;margin-bottom:14px;}
                        input{flex:1;padding:10px;border-radius:10px;border:1px solid #334155;background:#090D16;color:#FFF;}
                        button{padding:10px 16px;border-radius:10px;border:none;background:#10B981;color:#0B1120;font-weight:bold;}
                        li{background:#1E293B;padding:10px;border-radius:10px;margin-bottom:8px;display:flex;justify-content:space-between;list-style:none;}
                    </style>
                </head>
                <body>
                    <div class="box">
                        <h3>قائمة المهام (بدون إنترنت)</h3>
                        <div class="row">
                            <input id="t" placeholder="أدخل مهمة جديدة...">
                            <button onclick="add()">إضافة</button>
                        </div>
                        <ul id="l" style="padding:0;margin:0;"></ul>
                    </div>
                    <script>
                        let items=JSON.parse(localStorage.getItem('gharb_tasks')||'[]');
                        function draw(){
                            document.getElementById('l').innerHTML=items.map((x,i)=>`<li><span>${'$'}{x}</span><button style="background:#EF4444;color:#fff;padding:4px 10px;" onclick="del(${'$'}{i})">حذف</button></li>`).join('');
                            localStorage.setItem('gharb_tasks',JSON.stringify(items));
                        }
                        function add(){const i=document.getElementById('t');if(i.value.trim()){items.push(i.value.trim());i.value='';draw();}}
                        function del(idx){items.splice(idx,1);draw();}
                        draw();
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
                    <title>تطبيق مولد محلياً</title>
                    <style>
                        body{margin:0;background:#0B1120;color:#F8FAFC;font-family:sans-serif;padding:24px;}
                        .card{background:#111827;border:1px solid #334155;border-radius:18px;padding:20px;text-align:center;}
                        .btn{background:#10B981;color:#0B1120;border:none;padding:12px 20px;border-radius:10px;font-weight:bold;margin-top:12px;}
                    </style>
                </head>
                <body>
                    <div class="card">
                        <h2 style="color:#10B981;">تطبيق تم إنشاؤه محلياً</h2>
                        <p>الفكرة: ${prompt.ifBlank { "تطبيق ويب متجاوب" }}</p>
                        <button class="btn" onclick="document.getElementById('status').innerText='تم التفاعل بنجاح بدون إنترنت!'">تشغيل الإجراء</button>
                        <p id="status" style="color:#38BDF8;margin-top:14px;"></p>
                    </div>
                </body>
                </html>
            """.trimIndent()
        }
    }
}
