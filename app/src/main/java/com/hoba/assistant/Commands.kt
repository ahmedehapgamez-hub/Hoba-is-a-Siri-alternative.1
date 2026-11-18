package com.hoba.assistant

import android.content.Context

/** تنظيف النص العربي عشان المقارنة تبقى سهلة (تشكيل، همزات، ياء/ألف مقصورة، تاء مربوطة). */
object Norm {
    private val marks = Regex("[\\u064B-\\u0652\\u0640]")
    private val spaces = Regex("\\s+")
    private const val ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"

    fun norm(s: String): String = s.lowercase()
        .replace(marks, "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي').replace('ة', 'ه')
        .replace(spaces, " ")
        .trim()

    fun latinDigits(s: String): String = buildString {
        for (c in s) {
            val i = ARABIC_DIGITS.indexOf(c)
            append(if (i >= 0) ('0' + i) else c)
        }
    }

    private val words: Map<String, Int> = mapOf(
        "واحد" to 1, "واحده" to 1, "اتنين" to 2, "اثنين" to 2, "تلاته" to 3, "ثلاثه" to 3,
        "اربعه" to 4, "خمسه" to 5, "سته" to 6, "سبعه" to 7, "تمانيه" to 8, "ثمانيه" to 8,
        "تسعه" to 9, "عشره" to 10,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )

    fun number(s: String): Int? {
        val t = latinDigits(norm(s))
        return t.toIntOrNull() ?: words[t]
    }
}

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("hoba", Context.MODE_PRIVATE)

    fun language(c: Context): String = p(c).getString("lang", "ar-EG") ?: "ar-EG"
    fun setLanguage(c: Context, v: String) {
        p(c).edit().putString("lang", v).apply()
    }

    fun btnX(c: Context, def: Int): Int = p(c).getInt("bx", def)
    fun btnY(c: Context, def: Int): Int = p(c).getInt("by", def)
    fun setBtnPos(c: Context, x: Int, y: Int) {
        p(c).edit().putInt("bx", x).putInt("by", y).apply()
    }

    /** المعلومات المحفوظة: سطر لكل معلومة بالشكل  الاسم: القيمة */
    fun snippetsRaw(c: Context): String = p(c).getString("snippets", "") ?: ""
    fun setSnippetsRaw(c: Context, v: String) {
        p(c).edit().putString("snippets", v).apply()
    }

    fun snippets(c: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in snippetsRaw(c).lines()) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            val k = line.substring(0, i).trim()
            val v = line.substring(i + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
        }
        return out
    }
}

sealed class Cmd {
    object Back : Cmd()
    object Home : Cmd()
    object Recents : Cmd()
    object Notifications : Cmd()
    object QuickSettings : Cmd()
    object ScrollDown : Cmd()
    object ScrollUp : Cmd()
    object Screenshot : Cmd()
    object Lock : Cmd()
    object Wake : Cmd()
    object Unlock : Cmd()
    object PowerMenu : Cmd()
    object VolUp : Cmd()
    object VolDown : Cmd()
    object ShowNumbers : Cmd()
    object HideNumbers : Cmd()
    object ClearText : Cmd()
    object Stop : Cmd()
    class OpenApp(val name: String) : Cmd()
    class Click(val target: String) : Cmd()
    class ClickNumber(val n: Int) : Cmd()
    class Type(val text: String) : Cmd()
    class Fill(val name: String) : Cmd()
    class Call(val name: String) : Cmd()
    class Message(val kind: String, val name: String, val text: String) : Cmd()
}

/** بيحوّل الجملة اللي اتقالت لأمر. بيفهم عربي مصري وإنجليزي. */
object Parser {
    private class Rest(val raw: String, val norm: String)

    private fun exact(t: String, vararg keys: String): Boolean = keys.any { Norm.norm(it) == t }

    /** لو الجملة بتبدأ بواحدة من الكلمات (الأطول الأول) بيرجع الباقي بعدها. */
    private fun rest(raw: String, vararg keys: String): Rest? {
        val words = raw.trim().split(Regex("\\s+"))
        val nw = words.map { Norm.norm(it) }
        for (k in keys) {
            val kw = Norm.norm(k).split(" ")
            if (nw.size > kw.size && nw.subList(0, kw.size) == kw) {
                val r = words.drop(kw.size).joinToString(" ")
                return Rest(r, Norm.norm(r))
            }
        }
        return null
    }

    private val separators: Set<String> =
        listOf("قوله", "وقوله", "قولو", "وقولو", "نصها", "رسالة", "saying", "say").map { Norm.norm(it) }.toSet()

    private val whatsappKeys = arrayOf(
        "ابعت رسالة واتساب", "ابعت رسالة واتس اب", "ارسل رسالة واتساب",
        "ابعت واتساب", "ابعت واتس اب", "ارسل واتساب",
        "send whatsapp message", "send whatsapp", "واتساب", "whatsapp"
    )
    private val smsKeys = arrayOf(
        "ابعت رسالة نصية", "ابعت رسالة", "ارسل رسالة", "send sms", "send message", "sms"
    )

    /** ابعت واتساب لـ [اسم] وقوله [الرسالة] */
    private fun parseMessage(raw: String): Cmd.Message? {
        var kind = "whatsapp"
        var r = rest(raw, *whatsappKeys)
        if (r == null) {
            kind = "sms"
            r = rest(raw, *smsKeys)
        }
        if (r == null) return null

        val words = r.raw.trim().split(Regex("\\s+"))
        val nw = words.map { Norm.norm(it) }
        val sep = nw.indexOfFirst { it in separators }
        if (sep < 0) return Cmd.Message(kind, "", "")

        val tokens = nw.take(sep).toMutableList()
        val wa = tokens.indexOfFirst { it == "واتساب" || it == "واتس" || it == "whatsapp" }
        if (wa >= 0) {
            kind = "whatsapp"
            tokens.removeAt(wa)
            if (tokens.getOrNull(wa) == "اب") tokens.removeAt(wa)
            if (wa > 0 && tokens.getOrNull(wa - 1) in setOf("علي", "عبر", "on", "via")) tokens.removeAt(wa - 1)
        }
        while (tokens.isNotEmpty() && tokens[0] in setOf("ل", "الي", "to")) tokens.removeAt(0)

        val name = tokens.joinToString(" ")
        val text = words.drop(sep + 1).joinToString(" ")
        return Cmd.Message(kind, name, text)
    }

    fun parse(raw: String): Cmd? {
        val t = Norm.norm(raw)
        if (t.isEmpty()) return null

        when {
            exact(t, "ارجع", "رجوع", "ارجع لورا", "back", "go back") -> return Cmd.Back
            exact(t, "الرئيسية", "الصفحة الرئيسية", "روح الرئيسية", "هوم", "home", "go home") -> return Cmd.Home
            exact(t, "التطبيقات الاخيرة", "اخر التطبيقات", "recents", "recent apps") -> return Cmd.Recents
            exact(t, "الاشعارات", "افتح الاشعارات", "notifications") -> return Cmd.Notifications
            exact(t, "الاعدادات السريعة", "quick settings") -> return Cmd.QuickSettings
            exact(t, "انزل", "نزل", "تحت", "انزل تحت", "scroll down", "down") -> return Cmd.ScrollDown
            exact(t, "اطلع", "فوق", "اطلع فوق", "scroll up", "up") -> return Cmd.ScrollUp
            exact(t, "صور الشاشة", "سكرين شوت", "لقطة شاشة", "screenshot", "take screenshot") -> return Cmd.Screenshot
            exact(t, "اقفل الشاشة", "قفل الشاشة", "اقفل التليفون", "قفل التليفون", "lock", "lock screen") -> return Cmd.Lock
            exact(t, "صحي الشاشة", "شغل الشاشة", "نور الشاشة", "wake", "wake up", "wake screen") -> return Cmd.Wake
            exact(t, "افتح القفل", "فك القفل", "افتح الشاشة", "افتح التليفون", "unlock") -> return Cmd.Unlock
            exact(t, "قائمة الطاقة", "power menu") -> return Cmd.PowerMenu
            exact(t, "ارفع الصوت", "علي الصوت", "volume up") -> return Cmd.VolUp
            exact(t, "وطي الصوت", "اخفض الصوت", "قلل الصوت", "volume down") -> return Cmd.VolDown
            exact(t, "اظهر الارقام", "ورني الارقام", "ارقم", "show numbers") -> return Cmd.ShowNumbers
            exact(t, "اخفي الارقام", "شيل الارقام", "hide numbers") -> return Cmd.HideNumbers
            exact(t, "امسح", "امسح النص", "clear", "clear text") -> return Cmd.ClearText
            exact(t, "وقف", "وقف الاستماع", "stop", "stop listening") -> return Cmd.Stop
        }

        parseMessage(raw)?.let { return it }

        rest(raw, "افتح تطبيق", "افتح", "شغل", "open app", "open", "launch")?.let {
            return Cmd.OpenApp(it.norm)
        }
        rest(raw, "اضغط علي", "اضغط", "دوس علي", "دوس", "اختار", "click on", "click", "tap", "press")?.let {
            val n = Norm.number(it.norm)
            return if (n != null) Cmd.ClickNumber(n) else Cmd.Click(it.norm)
        }
        rest(raw, "املا", "املي", "ادخل", "fill", "enter")?.let {
            return Cmd.Fill(it.norm)
        }
        rest(raw, "اكتب", "type", "write")?.let {
            return Cmd.Type(it.raw)
        }
        rest(raw, "اتصل ب", "اتصل", "كلم", "call")?.let {
            return Cmd.Call(it.norm)
        }
        return null
    }
}
