package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.GuangqiSelfTest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [GuangqiSelfTest] with a start button and its results. The run belongs to the process, not to this
 * screen: it switches to the radio and Siri may bring CarPlay forward. Each run is added to
 * `logs/self-test.log`, which the exported diagnostic report includes; the screen also shows the
 * latest earlier runs from it.
 */
class CarSelfTestActivity : ComponentActivity() {
    private lateinit var start: Button
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 17, 27))
            setPadding(menuDp(24), menuDp(18), menuDp(24), menuDp(18))
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(menuText(getString(R.string.car_self_test), 22f, Color.WHITE, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        start = button("", primary = true) { if (Run.running) Run.stop() else Run.begin(applicationContext) }
        bar.addView(start, LinearLayout.LayoutParams(-2, menuDp(52)))
        bar.addView(button(getString(R.string.done), primary = false) { finish() },
            LinearLayout.LayoutParams(-2, menuDp(52)).apply { marginStart = menuDp(12) })
        root.addView(bar)
        root.addView(menuText(getString(R.string.car_self_test_note), 14f, MENU_SECONDARY)
            .apply { setPadding(0, menuDp(8), 0, menuDp(8)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        Run.load(applicationContext)
        Run.watch(this)
        if (savedInstanceState == null) startIfAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startIfAsked(intent)
    }

    override fun onDestroy() {
        Run.unwatch(this)
        super.onDestroy()
    }

    /** `am start … --ez run true`, for the remote console. */
    private fun startIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_RUN, false) == true && !Run.running) Run.begin(applicationContext)
    }

    private fun render() {
        start.text = getString(if (Run.running) R.string.car_self_test_stop else R.string.car_self_test_start)
        list.removeAllViews()
        for (result in Run.results) {
            val (mark, color) = when (result.outcome) {
                GuangqiSelfTest.Outcome.PASS -> "✓" to Color.rgb(120, 220, 140)
                GuangqiSelfTest.Outcome.FAIL -> "✗" to Color.rgb(255, 120, 110)
                GuangqiSelfTest.Outcome.UNSURE -> "?" to Color.rgb(255, 205, 100)
                GuangqiSelfTest.Outcome.SKIPPED -> "–" to MENU_SECONDARY
            }
            row("$mark  ${result.name}", color, result.detail)
        }
        Run.step?.let { row("…  $it", MENU_ACCENT, null) }
        Run.question?.let { question ->
            row(question, Color.WHITE, null)
            list.addView(LinearLayout(this).apply {
                addView(button(getString(R.string.car_self_test_heard), primary = true) { Run.answer(true) },
                    LinearLayout.LayoutParams(-2, menuDp(60)))
                addView(button(getString(R.string.car_self_test_not_heard), primary = false) { Run.answer(false) },
                    LinearLayout.LayoutParams(-2, menuDp(60)).apply { marginStart = menuDp(16) })
            })
        }
        var summary: View? = null
        if (!Run.running && Run.results.isNotEmpty()) {
            fun count(outcome: GuangqiSelfTest.Outcome) = Run.results.count { it.outcome == outcome }
            summary = row(getString(R.string.car_self_test_summary, count(GuangqiSelfTest.Outcome.PASS), count(GuangqiSelfTest.Outcome.FAIL),
                count(GuangqiSelfTest.Outcome.UNSURE), count(GuangqiSelfTest.Outcome.SKIPPED)), Color.WHITE, null)
        }
        if (!Run.running && Run.earlier.isNotEmpty()) {
            row(getString(R.string.car_self_test_earlier), MENU_ACCENT, null)
            row("", Color.WHITE, Run.earlier)
        }
        // Earlier runs stand below the summary, which is what a finished run should show.
        scroll.post {
            if (Run.running) scroll.fullScroll(View.FOCUS_DOWN)
            else scroll.scrollTo(0, ((summary?.bottom ?: 0) - scroll.height).coerceAtLeast(0))
        }
    }

    private fun row(title: String, color: Int, detail: String?): View {
        val line = menuText(title, 18f, color).apply { setPadding(0, menuDp(10), 0, 0) }
        list.addView(line)
        if (detail != null) list.addView(menuText(detail, 14f, MENU_SECONDARY).apply { setPadding(menuDp(26), menuDp(3), 0, 0) })
        return line
    }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(if (primary) MENU_BUTTON_TEXT else Color.WHITE)
        val fill: GradientDrawable = menuButtonBackground(if (primary) MENU_ACCENT else MENU_BUTTON)
        @Suppress("DEPRECATION")
        setBackgroundDrawable(fill)
        setPadding(menuDp(22), 0, menuDp(22), 0)
        minimumWidth = ViewGroup.LayoutParams.WRAP_CONTENT
        setOnClickListener { onClick() }
    }

    /** The one run of this process. Fields are written on the main thread. */
    private object Run : GuangqiSelfTest.Host {
        private val main = Handler(Looper.getMainLooper())
        private val answers = ArrayBlockingQueue<Boolean>(1)
        private var screen: CarSelfTestActivity? = null
        private var app: Context? = null
        private var test: GuangqiSelfTest? = null
        private var file: File? = null
        private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val results = mutableListOf<GuangqiSelfTest.Result>()
        /** The runs before the one on the screen, newest first. */
        var earlier = ""
            private set
        var step: String? = null
            private set
        var question: String? = null
            private set
        @Volatile var running = false
            private set

        fun watch(activity: CarSelfTestActivity) {
            screen = activity
            activity.render()
        }

        fun unwatch(activity: CarSelfTestActivity) {
            if (screen === activity) screen = null
        }

        private fun report(context: Context) = File(context.filesDir, "logs/$REPORT_NAME")

        /** After DiPlay was restarted the results are only in the report. */
        fun load(context: Context) {
            if (!running && results.isEmpty()) earlier = summary(SelfTestReport.runs(read(report(context))))
        }

        private fun read(report: File) = runCatching { report.readText() }.getOrDefault("")

        private fun summary(runs: List<String>) = runs.takeLast(SHOWN_RUNS).reversed().joinToString("\n\n") { it.trim() }

        fun begin(context: Context) {
            running = true
            app = context
            results.clear()
            step = null
            val report = report(context).also { file = it }
            runCatching {
                report.parentFile?.mkdirs()
                val runs = SelfTestReport.runs(read(report)).takeLast(KEPT_RUNS - 1)
                earlier = summary(runs)
                report.writeText(runs.joinToString("") + SelfTestReport.HEADER +
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "\n")
            }
            val created = GuangqiSelfTest(context, this).also { test = it }
            screen?.render()
            Thread({
                runCatching { created.run() }.onFailure { log("自检出错：$it") }
                update {
                    running = false
                    step = null
                    question = null
                }
            }, "diplay-self-test").start()
        }

        fun stop() {
            test?.cancel()
            answers.clear()
            answers.offer(false)
        }

        fun answer(heard: Boolean) {
            answers.clear()
            answers.offer(heard)
        }

        override fun siri(): (() -> Boolean)? =
            controller()?.let { controller -> { controller.requestSiri() } }

        private fun controller() = CarPlayBackgroundSession.snapshot()?.controller?.takeIf { CarPlayBackgroundSession.active }

        override fun connecting() = CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.active

        override fun wired() = controller()?.transport == CarPlayTransport.WIRED

        override fun pauseMusic() {
            controller()?.sendMediaButton(CarPlayMediaButton.PAUSE)
        }

        override fun started(step: String) = update { this.step = step }

        override fun finished(result: GuangqiSelfTest.Result) = update {
            step = null
            results += result
        }

        override fun log(line: String) {
            android.util.Log.i("DiPlay-SelfTest", line)
            runCatching { file?.appendText("${clock.format(Date())}  $line\n") }
        }

        override fun ask(question: String): Boolean? {
            answers.clear()
            update { this.question = question }
            show()
            return answers.poll(ANSWER_SECONDS, TimeUnit.SECONDS).also {
                log("   问：$question 答：$it")
                update { this.question = null }
            }
        }

        override fun show() {
            main.post {
                val context = app ?: return@post
                // Closed by the driver: the run goes on without its screen unless it has a question.
                if (screen != null || question != null) runCatching { open(context) }
            }
        }

        private fun update(change: () -> Unit) {
            main.post {
                change()
                screen?.render()
            }
        }
    }

    companion object {
        const val REPORT_NAME = "self-test.log"
        private const val EXTRA_RUN = "run"
        private const val ANSWER_SECONDS = 45L
        private const val KEPT_RUNS = 6
        private const val SHOWN_RUNS = 2

        fun open(context: Context) {
            context.startActivity(Intent(context, CarSelfTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
