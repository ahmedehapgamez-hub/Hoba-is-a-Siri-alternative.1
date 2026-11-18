package com.hoba.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

class HobaService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: HobaService? = null

        /** أسماء بتتقال بالعربي → اسم التطبيق زي ما بيظهر على التليفون (إنجليزي). */
        private val ALIASES: Map<String, String> = mapOf(
            "واتساب" to "whatsapp", "واتس اب" to "whatsapp", "واتس" to "whatsapp",
            "فيسبوك" to "facebook", "فيس بوك" to "facebook", "ماسنجر" to "messenger",
            "يوتيوب" to "youtube", "كروم" to "chrome", "انستجرام" to "instagram",
            "انستغرام" to "instagram", "تيك توك" to "tiktok", "تليجرام" to "telegram",
            "تلغرام" to "telegram", "سناب" to "snapchat", "نتفليكس" to "netflix",
            "كاميرا" to "camera", "الكاميرا" to "camera", "اعدادات" to "settings",
            "الاعدادات" to "settings", "تليفون" to "phone", "هاتف" to "phone",
            "رسايل" to "messages", "رسائل" to "messages", "معرض" to "gallery",
            "صور" to "photos", "ساعه" to "clock", "حاسبه" to "calculator",
            "الة حاسبه" to "calculator", "خرائط" to "maps", "جيميل" to "gmail",
            "تقويم" to "calendar", "ملفات" to "files", "مذكرات" to "notes"
        ).mapKeys { Norm.norm(it.key) }
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var button: TextView? = null
    private var statusView: TextView? = null
    private var numbersLayer: FrameLayout? = null
    private var numbered: List<AccessibilityNodeInfo> = emptyList()

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var continuous = false

    private val hideStatus = Runnable {
        try {
            statusView?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        statusView = null
    }

    // ───────────────────────── دورة حياة الخدمة ─────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (button == null) addButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        continuous = false
        stopRecognizer()
        hideNumbers()
        handler.removeCallbacksAndMessages(null)
        try {
            statusView?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        statusView = null
        try {
            button?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        button = null
        super.onDestroy()
    }

    /** بيتنادى من شاشة التطبيق بعد ما تدّي إذن "العرض فوق التطبيقات". */
    fun rebuildOverlay() {
        if (!::wm.isInitialized) return
        try {
            button?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        button = null
        addButton()
    }

    /** لو الإذن موجود بنستخدم نافذة تطبيق عادية (بتسمح بفتح التطبيقات)، وإلا نافذة إمكانية الوصول. */
    private fun overlayType(): Int =
        if (Settings.canDrawOverlays(this)) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    // ───────────────────────── زرار الميكروفون العايم (قابل للسحب) ─────────────────────────

    private fun addButton() {
        val dm = resources.displayMetrics
        val size = (56 * dm.density).toInt()
        val slop = ViewConfiguration.get(this).scaledTouchSlop

        val tv = TextView(this).apply {
            text = "🎤"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6C47FF"))
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.btnX(this@HobaService, dm.widthPixels - size - 24)
            y = Prefs.btnY(this@HobaService, dm.heightPixels / 2)
        }

        tv.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false
            var longFired = false
            val longPress = Runnable {
                if (!moved) {
                    longFired = true
                    toggleContinuous()
                }
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                        longFired = false
                        handler.postDelayed(longPress, 600)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - touchX
                        val dy = e.rawY - touchY
                        if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                            moved = true
                            handler.removeCallbacks(longPress)
                        }
                        if (moved) {
                            lp.x = (startX + dx).toInt()
                            lp.y = (startY + dy).toInt()
                            try {
                                wm.updateViewLayout(tv, lp)
                            } catch (ex: Exception) {
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPress)
                        if (moved) {
                            Prefs.setBtnPos(this@HobaService, lp.x, lp.y)
                        } else if (!longFired) {
                            toggleListening()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
                }
                return true
            }
        })

        wm.addView(tv, lp)
        button = tv
    }

    private fun updateButton() {
        val color = when {
            listening -> "#E53935"
            continuous -> "#2E7D32"
            else -> "#6C47FF"
        }
        (button?.background as? GradientDrawable)?.setColor(Color.parseColor(color))
    }

    // ───────────────────────── شريط الحالة (بيوريك اللي سمعه وبينفذه) ─────────────────────────

    private fun buildStatusView(): TextView? {
        val v = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(36, 20, 36, 20)
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(Color.parseColor("#E6222222"))
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 160
        }
        return try {
            wm.addView(v, lp)
            statusView = v
            v
        } catch (e: Exception) {
            null
        }
    }

    private fun toast(msg: String) {
        if (!::wm.isInitialized) return
        handler.removeCallbacks(hideStatus)
        val view = statusView ?: buildStatusView() ?: return
        view.text = msg
        handler.postDelayed(hideStatus, 4000)
    }

    // ───────────────────────── التعرف على الصوت ─────────────────────────

    private fun toggleListening() {
        if (listening) {
            continuous = false
            stopRecognizer()
            updateButton()
        } else {
            startListening()
        }
    }

    private fun toggleContinuous() {
        continuous = !continuous
        toast(if (continuous) "الاستماع المستمر شغال (قول \"وقف\" عشان يقف)" else "الاستماع المستمر اتقفل")
        if (continuous && !listening) startListening()
        if (!continuous) stopRecognizer()
        updateButton()
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("افتح تطبيق هوبا وادّي إذن الميكروفون")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("خدمة التعرف على الصوت مش متاحة على التليفون ده")
            return
        }
        stopRecognizer()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.language(this@HobaService))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        listening = true
        updateButton()
        toast("🎤 اتكلم...")
        recognizer?.startListening(intent)
    }

    private fun stopRecognizer() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
        listening = false
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            listening = false
            updateButton()
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!quiet) toast("مشكلة في التعرف على الصوت (كود $error)")
            val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                error == SpeechRecognizer.ERROR_CLIENT
            if (continuous && !fatal) {
                handler.postDelayed({ if (continuous && !listening) startListening() }, 600)
            } else if (fatal) {
                continuous = false
                updateButton()
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            updateButton()
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            handleSpeech(heard)
            if (continuous) {
                handler.postDelayed({ if (continuous && !listening) startListening() }, 900)
            }
        }
    }

    private fun handleSpeech(heard: List<String>) {
        if (heard.isEmpty()) {
            toast("ما سمعتش حاجة")
            return
        }
        for (h in heard) {
            val cmd = Parser.parse(h) ?: continue
            toast("🎤 $h")
            execute(cmd)
            return
        }
        toast("ما فهمتش: ${heard[0]}")
    }

    // ───────────────────────── تنفيذ الأوامر ─────────────────────────

    private fun execute(cmd: Cmd) {
        when (cmd) {
            is Cmd.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            is Cmd.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            is Cmd.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            is Cmd.Notifications -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            is Cmd.QuickSettings -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            is Cmd.Screenshot -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            is Cmd.Lock -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            is Cmd.PowerMenu -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            is Cmd.Wake -> wakeScreen()
            is Cmd.Unlock -> unlockScreen()
            is Cmd.ScrollDown -> scroll(true)
            is Cmd.ScrollUp -> scroll(false)
            is Cmd.VolUp -> volume(true)
            is Cmd.VolDown -> volume(false)
            is Cmd.ShowNumbers -> showNumbers()
            is Cmd.HideNumbers -> hideNumbers()
            is Cmd.ClearText -> setFieldText("", append = false)
            is Cmd.Stop -> {
                continuous = false
                stopRecognizer()
                updateButton()
            }
            is Cmd.OpenApp -> openApp(cmd.name)
            is Cmd.Click -> clickByText(cmd.target)
            is Cmd.ClickNumber -> {
                val node = numbered.getOrNull(cmd.n - 1)
                if (node == null) {
                    toast("قول \"اظهر الارقام\" الأول")
                } else {
                    hideNumbers()
                    clickNode(node)
                }
            }
            is Cmd.Type -> setFieldText(findSnippet(cmd.text) ?: cmd.text, append = true)
            is Cmd.Fill -> {
                val v = findSnippet(cmd.name)
                if (v == null) toast("مفيش معلومة محفوظة باسم: ${cmd.name}")
                else setFieldText(v, append = true)
            }
            is Cmd.Call -> call(cmd.name)
            is Cmd.Message -> {
                if (cmd.name.isEmpty() || cmd.text.isEmpty()) {
                    toast("قول: ابعت واتساب لـ [اسم] وقوله [الرسالة]")
                } else {
                    sendMessage(cmd.kind, cmd.name, cmd.text)
                }
            }
        }
    }

    // ── فتح التطبيقات ──

    private fun openApp(spoken: String) {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)

        val names = LinkedHashSet<String>()
        for (v in listOf(spoken, spoken.removePrefix("ال"))) {
            if (v.isEmpty()) continue
            names.add(v)
            ALIASES[v]?.let { names.add(it) }
        }

        val exact = apps.firstOrNull { ri ->
            val l = Norm.norm(ri.loadLabel(pm).toString())
            names.any { it == l }
        }
        val hit = exact ?: apps.firstOrNull { ri ->
            val l = Norm.norm(ri.loadLabel(pm).toString())
            names.any { l.contains(it) }
        }
        if (hit == null) {
            toast("مش لاقي تطبيق: $spoken")
            return
        }
        val target = hit.activityInfo.packageName
        val intent = pm.getLaunchIntentForPackage(target)
        if (intent == null) {
            toast("مقدرتش أفتح: $spoken")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("مقدرتش أفتح: $spoken")
            return
        }
        // أندرويد ساعات بيمنع فتح التطبيقات من الخلفية من غير أي رسالة، فبنتأكد ونقول السبب.
        handler.postDelayed({
            val cur = rootInActiveWindow?.packageName?.toString()
            if (cur != null && cur != target && cur != packageName) {
                if (!Settings.canDrawOverlays(this)) {
                    toast("لو التطبيق مفتحش: فعّل \"العرض فوق التطبيقات\" من تطبيق هوبا")
                } else {
                    toast("لو التطبيق مفتحش: من إعدادات هوبا اسمح بالتشغيل في الخلفية")
                }
            }
        }, 1800)
    }

    // ── قراءة الشاشة والضغط ──

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, clickableOnly: Boolean) {
        if (n == null || !n.isVisibleToUser) return
        val useful = if (clickableOnly) {
            n.isClickable
        } else {
            n.isClickable || n.text != null || n.contentDescription != null
        }
        if (useful) out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), out, clickableOnly)
    }

    private fun label(n: AccessibilityNodeInfo): String =
        Norm.norm(listOfNotNull(n.text, n.contentDescription).joinToString(" "))

    private fun clickByText(target: String) {
        val root = rootInActiveWindow ?: return
        val want = Norm.norm(target)
        if (want.isEmpty()) return
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(root, all, clickableOnly = false)
        val match = all.firstOrNull { label(it) == want }
            ?: all.firstOrNull { label(it).isNotEmpty() && label(it).contains(want) }
        if (match == null) {
            toast("مش لاقي على الشاشة: $target")
            return
        }
        clickNode(match)
    }

    private fun clickNode(n: AccessibilityNodeInfo) {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            cur = cur.parent
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        tap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 300) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ── Scroll ──

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null || !n.isVisibleToUser) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            findScrollable(n.getChild(i))?.let { return it }
        }
        return null
    }

    private fun scroll(forward: Boolean) {
        val node = findScrollable(rootInActiveWindow)
        val ok = node?.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        ) ?: false
        if (!ok) {
            // لو مفيش عنصر بيتسكرول (متصفح مثلًا) بنعمل سحبة بالصباع
            val dm = resources.displayMetrics
            val x = dm.widthPixels / 2f
            val top = dm.heightPixels * 0.3f
            val bottom = dm.heightPixels * 0.7f
            if (forward) swipe(x, bottom, x, top) else swipe(x, top, x, bottom)
        }
    }

    // ── ترقيم العناصر ──

    private fun showNumbers() {
        hideNumbers()
        val root = rootInActiveWindow ?: return
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(root, all, clickableOnly = true)
        val items = all.take(99)
        numbered = items

        val layer = FrameLayout(this)
        items.forEachIndexed { i, n ->
            val r = Rect()
            n.getBoundsInScreen(r)
            val badge = TextView(this).apply {
                text = "${i + 1}"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#CC6C47FF"))
                setPadding(8, 2, 8, 2)
            }
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = r.left
            lp.topMargin = r.top
            layer.addView(badge, lp)
        }
        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(layer, wlp)
        numbersLayer = layer
    }

    private fun hideNumbers() {
        try {
            numbersLayer?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        numbersLayer = null
        numbered = emptyList()
    }

    // ── الكتابة والمعلومات المحفوظة ──

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            findEditable(n.getChild(i))?.let { return it }
        }
        return null
    }

    private fun setFieldText(text: String, append: Boolean) {
        val root = rootInActiveWindow ?: return
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
        if (field == null) {
            toast("مفيش خانة كتابة على الشاشة")
            return
        }
        val old = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val newText = if (append && old.isNotEmpty()) "$old $text" else text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun snippetKey(s: String): String = Norm.norm(s).removePrefix("ال")

    private fun findSnippet(spoken: String): String? {
        val q = snippetKey(spoken)
        if (q.isEmpty()) return null
        return Prefs.snippets(this).entries.firstOrNull { snippetKey(it.key) == q }?.value
    }

    // ── الصوت ──

    private fun volume(up: Boolean) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    // ── الشاشة: إيقاظ وفتح القفل ──

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "hoba:wake"
        )
        wl.acquire(3000)
    }

    private fun unlockScreen() {
        wakeScreen()
        handler.postDelayed({
            val dm = resources.displayMetrics
            val x = dm.widthPixels / 2f
            swipe(x, dm.heightPixels * 0.85f, x, dm.heightPixels * 0.25f, 350)
        }, 800)
        toast("لو التليفون عليه بصمة أو رقم سري، لازم تدخله بنفسك")
    }

    // ── جهات الاتصال والاتصال ──

    private fun lookupNumber(name: String): String? {
        val q = Norm.norm(name)
        if (q.isEmpty()) return null
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            ),
            null, null, null
        ) ?: return null
        cursor.use {
            while (it.moveToNext()) {
                val display = Norm.norm(it.getString(1) ?: "")
                if (display.contains(q)) return it.getString(0)
            }
        }
        return null
    }

    /** بيرجع رقم من اسم جهة اتصال (أو من أرقام اتقالت). لو مش لاقي بيعرض رسالة ويرجع null. */
    private fun resolveNumber(spoken: String): String? {
        val digits = Norm.latinDigits(spoken).filter { it.isDigit() }
        if (digits.length >= 5) return digits

        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            toast("ادّي إذن جهات الاتصال من تطبيق هوبا")
            return null
        }
        // لو الاسم لزق فيه حرف الباء أو اللام ("اتصل باحمد") جرّب بدونه كمان
        val variants = listOfNotNull(
            spoken,
            if (spoken.length > 2 && (spoken[0] == 'ب' || spoken[0] == 'ل')) spoken.drop(1) else null
        )
        for (v in variants) {
            lookupNumber(v)?.let { return it }
        }
        toast("مش لاقي جهة اتصال: $spoken")
        return null
    }

    private fun call(spoken: String) {
        val number = resolveNumber(spoken) ?: return
        val canCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(
            if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.parse("tel:" + Uri.encode(number))
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("مقدرتش أعمل اتصال")
        }
    }

    // ── الرسائل (واتساب / SMS) ──

    private fun countryIso(): String {
        val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
        val iso = tm?.networkCountryIso?.takeIf { it.isNotEmpty() }
            ?: tm?.simCountryIso?.takeIf { it.isNotEmpty() }
            ?: Locale.getDefault().country
        return iso.uppercase().ifEmpty { "EG" }
    }

    private fun installed(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun sendMessage(kind: String, spokenName: String, text: String) {
        val number = resolveNumber(spokenName) ?: return
        try {
            if (kind == "whatsapp") {
                val e164 = PhoneNumberUtils.formatNumberToE164(number, countryIso()) ?: number
                val digits = e164.filter { it.isDigit() }
                val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(text)}")
                val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(it) }?.let {
                    intent.setPackage(it)
                }
                startActivity(intent)
            } else {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                    .putExtra("sms_body", text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (e: Exception) {
            toast("مقدرتش أفتح تطبيق الرسائل")
            return
        }
        toast("بجهز الرسالة...")
        autoSend(0)
    }

    private fun isSendLabel(l: String): Boolean =
        l == "send" || l == "ارسال" || l.startsWith("send ") || l.startsWith("ارسال ")

    /** بيستنى الشات يفتح وبعدين يدوس على زرار الإرسال. */
    private fun autoSend(attempt: Int) {
        if (attempt > 8) {
            toast("الرسالة جاهزة، دوس إرسال بإيدك")
            return
        }
        handler.postDelayed({
            val root = rootInActiveWindow
            var target: AccessibilityNodeInfo? = null
            if (root != null) {
                for (id in listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")) {
                    target = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
                    if (target != null) break
                }
                if (target == null) {
                    val all = ArrayList<AccessibilityNodeInfo>()
                    collect(root, all, clickableOnly = false)
                    target = all.firstOrNull { isSendLabel(label(it)) && it.isEnabled }
                }
            }
            if (target != null) {
                clickNode(target)
                toast("✅ اتبعتت")
            } else {
                autoSend(attempt + 1)
            }
        }, if (attempt == 0) 2000L else 800L)
    }
}
