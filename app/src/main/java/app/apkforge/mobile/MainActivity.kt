package app.apkforge.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.database.Cursor
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

class MainActivity : Activity() {
    private val pickZipRequest = 501
    private lateinit var status: TextView
    private lateinit var details: TextView
    private var selectedName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val bg = Color.rgb(14, 17, 24)
        val panel = Color.rgb(25, 30, 41)
        val accent = Color.rgb(85, 135, 245)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        content.addView(TextView(this).apply {
            text = "APK Forge Mobile"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Мобильная мастерская проектов"
            textSize = 15f
            setTextColor(Color.rgb(176, 188, 210))
            setPadding(0, dp(6), 0, dp(20))
        })
        content.addView(Button(this).apply {
            text = "＋  Выбрать ZIP-проект"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = rounded(accent, 16)
            setOnClickListener { chooseZip() }
        }, matchWrap())
        status = TextView(this).apply {
            text = "Пока проект не выбран"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(panel, 16)
        }
        content.addView(status, matchWrap().apply { topMargin = dp(16) })
        content.addView(sectionTitle("Поддержка проектов"))
        content.addView(infoCard(panel, "🌐  HTML / CSS / JS", "Определяет веб-проект и ищет index.html. Упаковка сайта в APK — следующий этап."))
        content.addView(infoCard(panel, "🤖  Android Gradle", "Находит build.gradle, settings.gradle и gradlew. Не запускает код импортированного проекта."))
        content.addView(infoCard(panel, "📱  Работа с телефона", "Проект предусматривает сборку самого приложения через GitHub Actions; полноценная локальная сборка проектов пока не реализована."))
        details = TextView(this).apply {
            text = "Отчёт появится после выбора ZIP."
            textSize = 14f
            setTextColor(Color.rgb(210, 218, 232))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(panel, 16)
        }
        content.addView(details, matchWrap().apply { topMargin = dp(8) })
        content.addView(TextView(this).apply {
            text = "Версия 0.1.0 • Без root • Анализ без запуска чужого кода"
            textSize = 12f
            setTextColor(Color.rgb(135, 148, 171))
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(4))
        })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun chooseZip() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        startActivityForResult(intent, pickZipRequest)
    }

    @Deprecated("Deprecated by platform API; retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickZipRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedName = displayName(uri) ?: "project.zip"
        status.text = "Выбран файл: $selectedName\nАнализирую структуру…"
        Thread {
            val report = inspectZip(uri)
            runOnUiThread {
                status.text = "Файл: $selectedName\nТип: ${report.first}"
                details.text = report.second
            }
        }.start()
    }

    private fun inspectZip(uri: Uri): Pair<String, String> {
        val temp = File(cacheDir, "inspection-${System.currentTimeMillis()}.zip")
        return try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Не удалось открыть файл" }
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > 100L * 1024 * 1024) throw IllegalArgumentException("ZIP больше 100 МБ")
                        output.write(buffer, 0, n)
                    }
                }
            }
            val names = mutableListOf<String>()
            ZipFile(temp).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith("/") || name.split("/").any { it == ".." }) {
                        throw IllegalArgumentException("В архиве найден небезопасный путь: $name")
                    }
                    if (!entry.isDirectory) names.add(name)
                    if (names.size > 5000) throw IllegalArgumentException("Слишком много файлов (лимит 5000)")
                }
            }
            val normalized = names.map { it.lowercase(Locale.ROOT) }
            val hasHtml = normalized.any { it.substringAfterLast('/') == "index.html" } || normalized.any { it.endsWith(".html") }
            val hasGradle = normalized.any { it == "build.gradle" || it.endsWith("/build.gradle") || it == "build.gradle.kts" || it.endsWith("/build.gradle.kts") }
            val hasSettings = normalized.any { it == "settings.gradle" || it.endsWith("/settings.gradle") || it == "settings.gradle.kts" || it.endsWith("/settings.gradle.kts") }
            val hasWrapper = normalized.any { it == "gradlew" || it.endsWith("/gradlew") }
            val kind = when {
                hasGradle || hasSettings -> "Android/Gradle-проект"
                hasHtml -> "Веб-проект (HTML/CSS/JS)"
                else -> "Тип не определён"
            }
            val report = buildString {
                appendLine("ОТЧЁТ АНАЛИЗА")
                appendLine("Файлов: ${names.size}")
                appendLine("HTML: ${if (hasHtml) "найден" else "не найден"}")
                appendLine("Gradle build-файл: ${if (hasGradle) "найден" else "не найден"}")
                appendLine("Gradle settings: ${if (hasSettings) "найден" else "не найден"}")
                appendLine("Gradle wrapper: ${if (hasWrapper) "найден" else "не найден"}")
                appendLine()
                appendLine("Первые файлы:")
                names.take(35).forEach { appendLine("• $it") }
                appendLine()
                appendLine("Это только анализ структуры. Архив не распаковывается и его код не запускается.")
            }
            kind to report
        } catch (e: Exception) {
            "Ошибка анализа" to "Не удалось проверить ZIP. Причина: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            temp.delete()
        }
    }

    private fun displayName(uri: Uri): String? {
        var result: String? = null
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) result = it.getString(index)
            }
        }
        return result
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 19f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(0, dp(24), 0, dp(10))
    }

    private fun infoCard(color: Int, heading: String, body: String) = TextView(this).apply {
        text = "$heading\n$body"
        textSize = 14f
        setTextColor(Color.rgb(218, 225, 239))
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(color, 16)
        layoutParams = matchWrap().apply { bottomMargin = dp(10) }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
