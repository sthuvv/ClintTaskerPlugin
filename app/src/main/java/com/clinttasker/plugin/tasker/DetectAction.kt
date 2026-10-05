package com.clinttasker.plugin.tasker

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import com.clinttasker.plugin.core.FindOptions
import com.clinttasker.plugin.core.Finder
import com.clinttasker.plugin.core.MediaKind
import com.clinttasker.plugin.core.Ranking
import com.joaomgcd.taskerpluginlibrary.action.TaskerPluginRunnerAction
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfigHelper
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputField
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputRoot
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputObject
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputVariable
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResult
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultError
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultSucess
import kotlinx.coroutines.runBlocking

@TaskerInputRoot
class DetectInput @JvmOverloads constructor(
    @field:TaskerInputField("url") var url: String? = null,
    @field:TaskerInputField("timeoutSeconds") var timeoutSeconds: String? = "25",
    @field:TaskerInputField("userAgent") var userAgent: String? = null,
    @field:TaskerInputField("desktopSite") var desktopSite: Boolean = false
)

@TaskerOutputObject
class DetectOutput(
    @get:TaskerOutputVariable("clint_count") val count: String,
    @get:TaskerOutputVariable("clint_best_url") val bestUrl: String,
    @get:TaskerOutputVariable("clint_best_format") val bestFormat: String,
    @get:TaskerOutputVariable("clint_best_quality") val bestQuality: String,
    @get:TaskerOutputVariable("clint_best_audio_url") val bestAudioUrl: String,
    @get:TaskerOutputVariable("clint_best_headers") val bestHeaders: String,
    @get:TaskerOutputVariable("clint_urls") val urls: String,
    @get:TaskerOutputVariable("clint_json") val json: String
)

class DetectRunner : TaskerPluginRunnerAction<DetectInput, DetectOutput>() {
    override fun run(context: Context, input: TaskerInput<DetectInput>): TaskerPluginResult<DetectOutput> {
        val i = input.regular
        val url = i.url?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return TaskerPluginResultError(1, "URL invalide (http/https attendu) : $url")
        }
        val timeoutMs = ((i.timeoutSeconds?.trim()?.toLongOrNull() ?: 25L).coerceIn(5L, 120L)) * 1000L
        val result = runBlocking {
            Finder.find(
                context,
                FindOptions(url = url, timeoutMs = timeoutMs, userAgent = i.userAgent?.trim(), desktop = i.desktopSite)
            )
        }
        val videos = Ranking.sortedVideos(result.all)
        val best = result.best
        val out = DetectOutput(
            count = videos.size.toString(),
            bestUrl = best?.url ?: "",
            bestFormat = best?.format ?: "",
            bestQuality = best?.let { Ranking.quality(it) } ?: "",
            bestAudioUrl = result.bestAudio?.url ?: "",
            bestHeaders = best?.let { Ranking.headersToLines(it.requestHeaders) } ?: "",
            urls = videos.joinToString("\n") { it.url },
            json = Ranking.toJson(result.all.filter { it.kind != MediaKind.SUBTITLE }, result.all)
        )
        return TaskerPluginResultSucess(out)
    }
}

class DetectConfigActivity : Activity(), TaskerPluginConfig<DetectInput> {
    override val context: Context get() = applicationContext

    private lateinit var etUrl: EditText
    private lateinit var etTimeout: EditText
    private lateinit var etUa: EditText
    private lateinit var cbDesktop: CheckBox
    private var initial: DetectInput = DetectInput()

    override val inputForTasker: TaskerInput<DetectInput>
        get() = TaskerInput(
            DetectInput(
                url = etUrl.text.toString(),
                timeoutSeconds = etTimeout.text.toString(),
                userAgent = etUa.text.toString().ifBlank { null },
                desktopSite = cbDesktop.isChecked
            )
        )

    override fun assignFromInput(input: TaskerInput<DetectInput>) {
        initial = input.regular
        if (::etUrl.isInitialized) {
            etUrl.setText(initial.url ?: "")
            etTimeout.setText(initial.timeoutSeconds ?: "25")
            etUa.setText(initial.userAgent ?: "")
            cbDesktop.isChecked = initial.desktopSite
        }
    }

    private val helper by lazy { DetectConfigHelper(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = Form(this)
        f.title("Clint : détecter la vidéo d'une page")
        etUrl = f.field("URL de la page ou du média", initial.url, "https://… ou %url")
        etTimeout = f.field("Délai max (secondes)", initial.timeoutSeconds ?: "25", "25", numeric = true)
        etUa = f.field("User-Agent (optionnel)", initial.userAgent)
        cbDesktop = f.check("Charger la version desktop du site", initial.desktopSite)
        f.note("Variables : %clint_count, %clint_best_url, %clint_best_format, %clint_best_quality, %clint_best_audio_url, %clint_best_headers, %clint_urls, %clint_json")
        f.button("Enregistrer") { helper.finishForTasker() }
        f.show()
        helper.onCreate()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        helper.finishForTasker()
    }
}

class DetectConfigHelper(config: TaskerPluginConfig<DetectInput>) :
    TaskerPluginConfigHelper<DetectInput, DetectOutput, DetectRunner>(config) {
    override val runnerClass: Class<DetectRunner> get() = DetectRunner::class.java
    override val inputClass: Class<DetectInput> get() = DetectInput::class.java
    override val outputClass: Class<DetectOutput> get() = DetectOutput::class.java
}
