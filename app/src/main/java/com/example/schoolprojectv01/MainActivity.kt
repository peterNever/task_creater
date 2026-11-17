package com.example.schoolprojectv01

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class MainActivity : Activity(), RecognitionListener {
    private lateinit var phraseInput: EditText
    private lateinit var status: TextView
    private lateinit var listenButton: Button
    private val mainThread = android.os.Handler(android.os.Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildScreen()
        prepareModel()
    }

    private fun buildScreen() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(24))
            setBackgroundColor(0xFFF6F7FB.toInt())
        }
        content.addView(TextView(this).apply {
            text = "Голосовой календарь"
            textSize = 27f
            setTextColor(0xFF17233C.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, match())
        content.addView(TextView(this).apply {
            text = "Скажите событие по-русски. Распознавание работает на телефоне, без интернета."
            textSize = 16f
            setTextColor(0xFF566176.toInt())
            setPadding(0, dp(10), 0, dp(18))
        }, match())

        listenButton = Button(this).apply {
            text = "🎙  Сказать событие"
            isEnabled = false
            setOnClickListener { if (isListening) stopListening() else requestMicrophoneAndListen() }
        }
        content.addView(listenButton, match())

        status = TextView(this).apply {
            text = "Готовлю офлайн-модель Vosk… При первом запуске это может занять немного времени."
            textSize = 14f
            setTextColor(0xFF53617A.toInt())
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(status, match())

        content.addView(TextView(this).apply {
            text = "Распознанная фраза"
            textSize = 14f
            setTextColor(0xFF53617A.toInt())
            setPadding(0, dp(10), 0, dp(6))
        }, match())
        phraseInput = EditText(this).apply {
            hint = "Например: завтра в три встреча с врачом"
            minLines = 2
            maxLines = 4
            textSize = 17f
            gravity = Gravity.TOP
            setPadding(dp(14), dp(12), dp(14), dp(12))
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        content.addView(phraseInput, match())

        content.addView(Button(this).apply {
            text = "Проверить и открыть календарь"
            setOnClickListener { showEventPreview() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        content.addView(TextView(this).apply {
            text = "Поддерживаются сегодня, завтра, послезавтра, даты вида «12 мая» или «12.05.2027» и время вроде «в 15:30» или «в три» (здесь это 15:00). Если время не указано, событие будет на весь день. Проверьте результат перед сохранением."
            textSize = 13f
            setTextColor(0xFF6B7485.toInt())
            setPadding(0, dp(14), 0, 0)
        }, match())

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
    }

    private fun prepareModel() {
        worker.execute {
            try {
                val modelDir = File(filesDir, "vosk-model-small-ru-0.22")
                val ready = File(modelDir, ".ready")
                if (!ready.isFile) unpackBundledModel(modelDir)
                val loaded = Model(modelDir.absolutePath)
                mainThread.post {
                    model = loaded
                    status.text = "Офлайн-модель готова. Можно сказать событие или ввести фразу вручную."
                    listenButton.isEnabled = true
                }
            } catch (error: Exception) {
                mainThread.post {
                    status.text = "Не удалось подготовить модель Vosk: ${error.localizedMessage ?: "ошибка файлов"}. Можно пока ввести фразу вручную."
                }
            }
        }
    }

    private fun unpackBundledModel(destination: File) {
        val temporary = File(filesDir, "vosk-model-extracting")
        temporary.deleteRecursively()
        if (!temporary.mkdirs() && !temporary.isDirectory) error("Не удалось создать папку модели")
        val rootName = "vosk-model-small-ru-0.22/"
        assets.open("vosk-model-small-ru-0.22.zip").use { asset ->
            ZipInputStream(asset.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val relative = entry.name.removePrefix(rootName)
                    if (relative.isNotBlank()) {
                        val output = File(temporary, relative)
                        val rootPath = temporary.canonicalPath + File.separator
                        if (!output.canonicalPath.startsWith(rootPath)) error("Некорректный архив модели")
                        if (entry.isDirectory) {
                            output.mkdirs()
                        } else {
                            output.parentFile?.mkdirs()
                            FileOutputStream(output).use { file -> zip.copyTo(file) }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        if (!File(temporary, "conf/model.conf").isFile) error("В архиве нет файлов модели")
        File(temporary, ".ready").writeText("ok")
        destination.deleteRecursively()
        if (!temporary.renameTo(destination)) error("Не удалось установить модель")
    }

    private fun requestMicrophoneAndListen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
        } else startListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
            else status.text = "Разрешите доступ к микрофону, чтобы диктовать событие."
        }
    }

    private fun startListening() {
        val loadedModel = model
        if (loadedModel == null) {
            status.text = "Модель ещё готовится. Попробуйте через несколько секунд."
            return
        }
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = SpeechService(Recognizer(loadedModel, 16_000.0f), 16_000.0f)
            isListening = true
            listenButton.text = "Остановить запись"
            status.text = "Слушаю… Скажите фразу, затем подождите немного."
            speechService?.startListening(this)
        } catch (error: Exception) {
            isListening = false
            listenButton.text = "🎙  Сказать событие"
            status.text = "Не удалось начать запись: ${error.localizedMessage ?: "проверьте микрофон"}"
        }
    }

    private fun stopListening() {
        speechService?.stop()
        isListening = false
        listenButton.text = "🎙  Сказать событие"
        status.text = "Запись остановлена. Проверьте фразу и нажмите кнопку ниже."
    }

    override fun onPartialResult(hypothesis: String?) {
        val partial = recognizedText(hypothesis)
        if (partial.isNotBlank()) mainThread.post { status.text = "Распознано: $partial" }
    }

    override fun onResult(hypothesis: String?) = acceptRecognizedText(hypothesis)

    override fun onFinalResult(hypothesis: String?) {
        acceptRecognizedText(hypothesis)
        mainThread.post {
            isListening = false
            listenButton.text = "🎙  Сказать событие"
            status.text = if (phraseInput.text.isNullOrBlank()) "Речь не распознана. Попробуйте ещё раз или введите фразу." else "Проверьте фразу и нажмите «Проверить и открыть календарь»."
        }
    }

    private fun acceptRecognizedText(hypothesis: String?) {
        val text = recognizedText(hypothesis)
        if (text.isNotBlank()) mainThread.post { phraseInput.setText(text) }
    }

    private fun recognizedText(hypothesis: String?): String = try {
        JSONObject(hypothesis.orEmpty()).optString("text")
    } catch (_: Exception) { "" }

    override fun onError(exception: Exception?) {
        mainThread.post {
            isListening = false
            listenButton.text = "🎙  Сказать событие"
            status.text = "Ошибка распознавания: ${exception?.localizedMessage ?: "попробуйте ещё раз"}"
        }
    }

    override fun onTimeout() {
        mainThread.post {
            isListening = false
            listenButton.text = "🎙  Сказать событие"
            status.text = "Запись завершена. Проверьте распознанную фразу."
        }
    }

    private fun showEventPreview() {
        val draft = EventParser.parse(phraseInput.text.toString())
        if (draft == null) {
            status.text = "Не нашёл дату. Добавьте, например, «завтра» или «12 мая»."
            return
        }
        val start = Calendar.getInstance().apply { timeInMillis = draft.startMillis }
        val dateText = android.text.format.DateFormat.format("EEEE, d MMMM", start).toString()
        val timeText = if (draft.allDay) "Весь день" else android.text.format.DateFormat.format("HH:mm", start).toString()
        AlertDialog.Builder(this)
            .setTitle("Проверьте событие")
            .setMessage("${draft.title}\n$dateText · $timeText\n\nОткрыть календарь с этими данными?")
            .setNegativeButton("Исправить") { _, _ -> status.text = "Исправьте фразу и проверьте ещё раз." }
            .setPositiveButton("Открыть календарь") { _, _ -> openCalendar(draft) }
            .show()
    }

    private fun openCalendar(draft: EventDraft) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, draft.title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, draft.startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, draft.endMillis)
            putExtra(CalendarContract.Events.ALL_DAY, draft.allDay)
        }
        try {
            startActivity(intent)
            status.text = "Календарь открыт. Сохраните событие там после проверки."
        } catch (_: Exception) {
            status.text = "На телефоне не нашлось приложения календаря. Фраза осталась в поле для редактирования."
        }
    }

    override fun onDestroy() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        model?.close()
        model = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    companion object { private const val REQUEST_MICROPHONE = 51 }
}
