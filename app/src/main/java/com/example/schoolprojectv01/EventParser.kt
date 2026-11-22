package com.example.schoolprojectv01

import java.util.Calendar
import java.util.Locale

data class EventDraft(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
)

object EventParser {
    private val months = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )
    private val numberWords = mapOf(
        "один" to 1, "одна" to 1, "одну" to 1, "два" to 2, "две" to 2,
        "три" to 3, "четыре" to 4, "пять" to 5, "шесть" to 6,
        "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
        "одиннадцать" to 11, "двенадцать" to 12,
    )

    private data class ParsedDate(val value: Calendar)
    private data class Duration(val amount: Int, val unit: String)

    fun parse(text: String, now: Calendar = Calendar.getInstance()): EventDraft? {
        val source = text.trim().lowercase(Locale.forLanguageTag("ru"))
        if (source.isBlank()) return null

        val monthPattern = months.joinToString("|")
        val dateToken = "(?:\\d{1,2}\\s+(?:$monthPattern)|\\d{1,2}[./]\\d{1,2}(?:[./]\\d{2,4})?)"
        val dateRangePattern = Regex("\\bс\\s+($dateToken)\\s+по\\s+($dateToken)\\b")
        val dateRange = dateRangePattern.find(source)

        val startDate: Calendar
        var lastDate: Calendar? = null
        var durationEndExclusive: Calendar? = null
        var hasDate = false
        if (dateRange != null) {
            val parsedStart = parseDate(dateRange.groupValues[1], now, null) ?: return null
            val parsedEnd = parseDate(dateRange.groupValues[2], now, parsedStart.value) ?: return null
            if (parsedEnd.value.before(parsedStart.value)) return null
            startDate = parsedStart.value
            lastDate = parsedEnd.value
            hasDate = true
        } else {
            val date = (now.clone() as Calendar).apply { setMidnight() }
            when {
                Regex("\\bпослезавтра\\b").containsMatchIn(source) -> {
                    date.add(Calendar.DAY_OF_MONTH, 2); hasDate = true
                }
                Regex("\\bзавтра\\b").containsMatchIn(source) -> {
                    date.add(Calendar.DAY_OF_MONTH, 1); hasDate = true
                }
                Regex("\\bсегодня\\b").containsMatchIn(source) -> hasDate = true
            }

            val dateText = Regex("\\b\\d{1,2}\\s+(?:$monthPattern)\\b").find(source)?.value
            val numericDate = Regex("\\b\\d{1,2}[./]\\d{1,2}(?:[./]\\d{2,4})?\\b").find(source)?.value
            val explicitDate = dateText ?: numericDate
            if (explicitDate != null) {
                val parsed = parseDate(explicitDate, now, null) ?: return null
                date.timeInMillis = parsed.value.timeInMillis
                hasDate = true
            }
            startDate = date
        }
        if (!hasDate) return null

        val duration = parseDuration(source)
        if (dateRange == null && duration != null) {
            durationEndExclusive = (startDate.clone() as Calendar).apply {
                when (duration.unit) {
                    "день" -> add(Calendar.DAY_OF_MONTH, duration.amount)
                    "неделя" -> add(Calendar.DAY_OF_MONTH, duration.amount * 7)
                    "месяц" -> add(Calendar.MONTH, duration.amount)
                }
            }
        }

        val timeMatch = Regex("\\bв\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(утра|дня|вечера|ночи)?\\b")
            .find(source)
            ?: Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(source)
        val wordTime = Regex("\\bв\\s+(один|одна|одну|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать)\\b")
            .find(source)

        var allDay = timeMatch == null && wordTime == null
        if (!allDay) {
            val rawHour = timeMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: wordTime?.groupValues?.get(1)?.let(numberWords::get)
                ?: return null
            val minute = timeMatch?.groupValues?.get(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            var hour = rawHour
            when (timeMatch?.groupValues?.getOrNull(3).orEmpty()) {
                "вечера", "дня" -> if (hour in 1..11) hour += 12
                "ночи" -> if (hour == 12) hour = 0
                "" -> if (hour in 1..7) hour += 12
            }
            if (hour !in 0..23 || minute !in 0..59) return null
            startDate.set(Calendar.HOUR_OF_DAY, hour)
            startDate.set(Calendar.MINUTE, minute)
        } else {
            startDate.setMidnight()
        }
        startDate.set(Calendar.SECOND, 0)
        startDate.set(Calendar.MILLISECOND, 0)

        val endDate = when {
            allDay -> (durationEndExclusive ?: lastDate ?: startDate).clone() as Calendar
            lastDate != null -> (lastDate.clone() as Calendar).apply {
                // The spoken end date is inclusive; timed events end at that date and time.
                set(Calendar.HOUR_OF_DAY, startDate.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, startDate.get(Calendar.MINUTE))
                if (timeInMillis <= startDate.timeInMillis) add(Calendar.HOUR_OF_DAY, 1)
            }
            durationEndExclusive != null -> (durationEndExclusive.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, startDate.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, startDate.get(Calendar.MINUTE))
            }
            else -> (startDate.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, 1) }
        }
        if (allDay && durationEndExclusive == null) endDate.add(Calendar.DAY_OF_MONTH, 1)
        endDate.set(Calendar.SECOND, 0)
        endDate.set(Calendar.MILLISECOND, 0)

        val durationPattern = Regex("\\bна\\s+(?:(?:всю\\s+)?неделю|(?:(\\d+|один|одна|одну|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать)\\s+)?(день|дня|дней|неделю|недели|недель|месяц|месяца|месяцев))\\b")
        val title = source
            .replace(dateRangePattern, " ")
            .replace(durationPattern, " ")
            .replace(Regex("\\b(сегодня|завтра|послезавтра)\\b"), " ")
            .replace(Regex("\\b\\d{1,2}\\s+(?:$monthPattern)\\b"), " ")
            .replace(Regex("\\b\\d{1,2}[./]\\d{1,2}(?:[./]\\d{2,4})?\\b"), " ")
            .replace(Regex("\\bв\\s*\\d{1,2}(?::\\d{2})?\\s*(?:утра|дня|вечера|ночи)?\\b"), " ")
            .replace(Regex("\\bв\\s+(?:один|одна|одну|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', '.', '—', '-', ':')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.forLanguageTag("ru")) else it.toString() }
            .ifBlank { "Событие" }

        return EventDraft(title, startDate.timeInMillis, endDate.timeInMillis, allDay)
    }

    private fun parseDuration(source: String): Duration? {
        val match = Regex("\\bна\\s+(?:(всю\\s+)?неделю|(?:(\\d+|один|одна|одну|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать)\\s+)?(день|дня|дней|неделю|недели|недель|месяц|месяца|месяцев))\\b")
            .find(source) ?: return null
        val unitText = match.groupValues[3]
        val unit = when {
            match.groupValues[1].isNotBlank() || unitText.isBlank() -> "неделя"
            unitText in setOf("день", "дня", "дней") -> "день"
            unitText in setOf("неделю", "недели", "недель") -> "неделя"
            else -> "месяц"
        }
        val numberText = match.groupValues[2]
        val amount = when {
            numberText.isBlank() -> 1
            numberText.toIntOrNull() != null -> numberText.toInt()
            else -> numberWords[numberText] ?: return null
        }
        val maxAmount = when (unit) {
            "день" -> 365
            "неделя" -> 52
            else -> 12
        }
        if (amount !in 1..maxAmount) return null
        return Duration(amount, unit)
    }

    private fun parseDate(token: String, now: Calendar, reference: Calendar?): ParsedDate? {
        val date = (now.clone() as Calendar).apply { setMidnight() }
        val textDate = Regex("(\\d{1,2})\\s+(${months.joinToString("|")})").find(token)
        val numericDate = Regex("(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?").find(token)
        val day: Int
        val month: Int
        val explicitYear: Int?
        if (textDate != null) {
            day = textDate.groupValues[1].toIntOrNull() ?: return null
            month = months.indexOf(textDate.groupValues[2])
            explicitYear = null
        } else if (numericDate != null) {
            day = numericDate.groupValues[1].toIntOrNull() ?: return null
            month = numericDate.groupValues[2].toIntOrNull()?.minus(1) ?: return null
            explicitYear = numericDate.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull()?.let { if (it < 100) 2000 + it else it }
        } else return null

        val year = explicitYear ?: reference?.get(Calendar.YEAR) ?: now.get(Calendar.YEAR)
        if (month !in 0..11 || day !in 1..31) return null
        date.set(year, month, 1)
        if (day > date.getActualMaximum(Calendar.DAY_OF_MONTH)) return null
        date.set(Calendar.DAY_OF_MONTH, day)
        if (explicitYear == null) {
            when {
                reference != null && date.before(reference) -> date.add(Calendar.YEAR, 1)
                reference == null && date.before(today(now)) -> date.add(Calendar.YEAR, 1)
            }
        }
        return ParsedDate(date)
    }

    private fun Calendar.setMidnight() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun today(source: Calendar) = (source.clone() as Calendar).apply { setMidnight() }
}
