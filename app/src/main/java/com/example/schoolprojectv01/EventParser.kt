package com.example.schoolprojectv01

import java.util.Calendar
import java.util.Locale

data class EventDraft(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
)

/** A deliberately small, predictable Russian date/time parser for the project prototype. */
object EventParser {
    private val months = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )
    private val hourWords = mapOf(
        "один" to 1, "одна" to 1, "одну" to 1, "два" to 2, "две" to 2,
        "три" to 3, "четыре" to 4, "пять" to 5, "шесть" to 6,
        "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
        "одиннадцать" to 11, "двенадцать" to 12,
    )

    fun parse(text: String, now: Calendar = Calendar.getInstance()): EventDraft? {
        val source = text.trim().lowercase(Locale.forLanguageTag("ru"))
        if (source.isBlank()) return null

        val date = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var hasDate = false
        when {
            Regex("\\bпослезавтра\\b").containsMatchIn(source) -> {
                date.add(Calendar.DAY_OF_MONTH, 2); hasDate = true
            }
            Regex("\\bзавтра\\b").containsMatchIn(source) -> {
                date.add(Calendar.DAY_OF_MONTH, 1); hasDate = true
            }
            Regex("\\bсегодня\\b").containsMatchIn(source) -> hasDate = true
        }

        val monthPattern = months.joinToString("|")
        val textualDate = Regex("\\b(\\d{1,2})\\s+($monthPattern)\\b").find(source)
        val numericDate = Regex("\\b(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?\\b").find(source)
        if (textualDate != null) {
            val day = textualDate.groupValues[1].toInt()
            val month = months.indexOf(textualDate.groupValues[2])
            if (!setDate(date, day, month, now.get(Calendar.YEAR))) return null
            if (date.before(today(now))) date.add(Calendar.YEAR, 1)
            hasDate = true
        } else if (numericDate != null) {
            val day = numericDate.groupValues[1].toInt()
            val month = numericDate.groupValues[2].toInt() - 1
            val explicitYear = numericDate.groupValues[3]
            val year = explicitYear.takeIf { it.isNotBlank() }?.toInt()?.let { if (it < 100) 2000 + it else it }
                ?: now.get(Calendar.YEAR)
            if (!setDate(date, day, month, year)) return null
            if (explicitYear.isBlank() && date.before(today(now))) date.add(Calendar.YEAR, 1)
            hasDate = true
        }
        if (!hasDate) return null

        var allDay = true
        val numericTime = Regex("\\bв\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(утра|дня|вечера|ночи)?\\b")
            .find(source)
            ?: Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(source)
        val wordTime = Regex("\\bв\\s+(один|одна|одну|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать)\\b")
            .find(source)

        val hour: Int?
        val minute: Int
        if (numericTime != null) {
            hour = numericTime.groupValues[1].toIntOrNull()
            minute = numericTime.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val period = numericTime.groupValues.getOrNull(3).orEmpty()
            if (hour != null) {
                var adjustedHour = hour
                when (period) {
                    "вечера" -> if (adjustedHour in 1..11) adjustedHour += 12
                    "дня" -> if (adjustedHour in 1..11) adjustedHour += 12
                    "ночи" -> if (adjustedHour == 12) adjustedHour = 0
                    "" -> if (adjustedHour in 1..7) adjustedHour += 12
                }
                if (adjustedHour !in 0..23 || minute !in 0..59) return null
                date.set(Calendar.HOUR_OF_DAY, adjustedHour)
                date.set(Calendar.MINUTE, minute)
                allDay = false
            }
        } else if (wordTime != null) {
            hour = hourWords[wordTime.groupValues[1]]
            minute = 0
            if (hour != null) {
                date.set(Calendar.HOUR_OF_DAY, if (hour in 1..7) hour + 12 else hour)
                date.set(Calendar.MINUTE, 0)
                allDay = false
            }
        }

        if (allDay) {
            date.set(Calendar.HOUR_OF_DAY, 0)
            date.set(Calendar.MINUTE, 0)
        }
        date.set(Calendar.SECOND, 0)
        date.set(Calendar.MILLISECOND, 0)
        val end = (date.clone() as Calendar).apply {
            if (allDay) add(Calendar.DAY_OF_MONTH, 1) else add(Calendar.HOUR_OF_DAY, 1)
        }

        val title = source
            .replace(Regex("\\b(сегодня|завтра|послезавтра)\\b"), " ")
            .replace(Regex("\\b\\d{1,2}\\s+(?:$monthPattern)\\b"), " ")
            .replace(Regex("\\b\\d{1,2}[./]\\d{1,2}(?:[./]\\d{2,4})?\\b"), " ")
            .replace(Regex("\\bв\\s*\\d{1,2}(?::\\d{2})?\\s*(?:утра|дня|вечера|ночи)?\\b"), " ")
            .replace(Regex("\\bв\\s+(?:один|одна|одну|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', '.', '—', '-', ':')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.forLanguageTag("ru")) else it.toString() }
            .ifBlank { "Событие" }

        return EventDraft(title, date.timeInMillis, end.timeInMillis, allDay)
    }

    private fun setDate(date: Calendar, day: Int, month: Int, year: Int): Boolean {
        if (month !in 0..11 || day !in 1..31) return false
        date.set(year, month, 1)
        if (day > date.getActualMaximum(Calendar.DAY_OF_MONTH)) return false
        date.set(Calendar.DAY_OF_MONTH, day)
        return true
    }

    private fun today(source: Calendar) = (source.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
}
