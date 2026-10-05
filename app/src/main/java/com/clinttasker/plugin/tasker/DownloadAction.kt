package com.clinttasker.plugin.tasker

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import com.clinttasker.plugin.core.DetectedMedia
import com.clinttasker.plugin.core.Downloader
import com.clinttasker.plugin.core.FindOptions
import com.clinttasker.plugin.core.Finder
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
class DownloadInput @JvmOverloads constructor(
    @field:TaskerInputField("url") var url: String? = null,
    @field:TaskerInputField("directory") var directory: String? = null,
    @field:TaskerInputField("fileName") var fileName: String? = null,
    @field:TaskerInputField("timeoutSeconds") var timeoutSeconds: String? = "25",
    @field:TaskerInputField("userAgent") var userAgent: String? = null,
    @field:TaskerInputField("desktopSite") var desktopSite: Boolean = false,
    @field:TaskerInputField("lowestQuality") var lowestQuality: Boolean = false,
    @field:TaskerInputField("separateAudio") var separateAudio: Boolean = true
)

@TaskerOutputObject
class DownloadOutput(
    @get:TaskerOutputVariable("clint_file") val file: String,
    @get:TaskerOutputVariable("clint_audio_file") val audioFile: String,
    @get:TaskerOutputVariable("clint_size") val size: String,
    @get:TaskerOutputVariable("clint_source_url") val sourceUrl: String
)

class DownloadRunner : TaskerPluginRunnerAction<DownloadInput, DownloadOutput>() {
    override fun run(context: Context, input: TaskerInput<DownloadInput>): TaskerPluginResult<DownloadOutput> {
        val i = input.regular
        val url = i.url?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return TaskerPluginResultError(1, "URL invalide (http/https attendu) : $url")
        }
        val timeoutMs = ((i.timeoutSeconds?.trim()?.toLongOrNull() ?: 25L).coerceIn(5L, 120L)) * 1000L
        return try {
            val found = runBlocking {
                Finder.find(
                    context,
                    FindOptions(url = url, timeoutMs = timeoutMs, userAgent = i.userAgent?.trim(), desktop = i.desktopSite)
                )
            }
            val chosen: DetectedMedia = (if (i.lowestQuality) Ranking.worst(found.all) else Ranking.best(found.all))
                ?: return TaskerPluginResultError(2, "Aucune vidéo détectée sur : $url")
            val res = Downloader.download(
                ctx = context,
                media = chosen,
                allDetected = found.all,
                dir = i.directory?.trim()?.ifBlank { null },
                fileName = i.fileName,
                withSeparateAudio = i.separateAudio
            )
            TaskerPluginResultSucess(
                DownloadOutput(
                    file = res.path,
                    audioFile = res.audioPath ?: "",
                    size = res.bytes.toString(),
                    sourceUrl = chosen.url
                )
            )
        } catch (e: Exception) {
            TaskerPluginResultError(3, e.message ?: e.javaClass.simpleName)
        }
    }
}

class DownloadConfigActivity : Activity(), TaskerPluginConfig<DownloadInput> {
    override val context: Context get() = applicationContext

    private lateinit var etUrl: EditText
    private lateinit var etDir: EditText
    private lateinit var etName: EditText
    private lateinit var etTimeout: EditText
    private lateinit var etUa: EditText
    private lateinit var cbDesktop: CheckBox
    private lateinit var cbLowest: CheckBox
    private lateinit var cbAudio: CheckBox
    private var initial: DownloadInput = DownloadInput()

    override val inputForTasker: TaskerInput<DownloadInput>
        get() = TaskerInput(
            DownloadInput(
                url = etUrl.text.toString(),
                directory = etDir.text.toString().ifBlank { null },
                fileName = etName.text.toString().ifBlank { null },
                timeoutSeconds = etTimeout.text.toString(),
                userAgent = etUa.text.toString().ifBlank { null },
                desktopSite = cbDesktop.isChecked,
                lowestQuality = cbLowest.isChecked,
                separateAudio = cbAudio.isChecked
            )
        )

    override fun assignFromInput(input: TaskerInput<DownloadInput>) {
        initial = input.regular
        if (::etUrl.isInitialized) {
            etUrl.setText(initial.url ?: "")
            etDir.setText(initial.directory ?: "")
            etName.setText(initial.fileName ?: "")
            etTimeout.setText(initial.timeoutSeconds ?: "25")
            etUa.setText(initial.userAgent ?: "")
            cbDesktop.isChecked = initial.desktopSite
            cbLowest.isChecked = initial.lowestQuality
            cbAudio.isChecked = initial.separateAudio
        }
    }

    private val helper by lazy { DownloadConfigHelper(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = Form(this)
        f.title("Clint : détecter et télécharger la vidéo")
        etUrl = f.field("URL de la page ou du média", initial.url, "https://… ou %url")
        etDir = f.field("Dossier de sortie (vide = Téléchargements/ClintTasker)", initial.directory)
        etName = f.field("Nom du fichier (sans extension, optionnel)", initial.fileName)
        etTimeout = f.field("Délai max de détection (secondes)", initial.timeoutSeconds ?: "25", "25", numeric = true)
        etUa = f.field("User-Agent (optionnel)", initial.userAgent)
        cbDesktop = f.check("Charger la version desktop du site", initial.desktopSite)
        cbLowest = f.check("Prendre la plus basse qualité", initial.lowestQuality)
        cbAudio = f.check("Télécharger aussi la piste audio séparée (HLS)", initial.separateAudio)
        f.note("Pensez à régler le champ « Délai » de l'action Tasker à 0 (illimité) ou à une valeur élevée. Variables : %clint_file, %clint_audio_file, %clint_size, %clint_source_url")
        f.button("Enregistrer") { helper.finishForTasker() }
        f.show()
        helper.onCreate()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        helper.finishForTasker()
    }
}

class DownloadConfigHelper(config: TaskerPluginConfig<DownloadInput>) :
    TaskerPluginConfigHelper<DownloadInput, DownloadOutput, DownloadRunner>(config) {
    override val runnerClass: Class<DownloadRunner> get() = DownloadRunner::class.java
    override val inputClass: Class<DownloadInput> get() = DownloadInput::class.java
    override val outputClass: Class<DownloadOutput> get() = DownloadOutput::class.java
}
