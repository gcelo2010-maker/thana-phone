package al.thana.widgets

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class VState { Idle, Listening, Thinking, Speaking }
data class Turn(val mine: Boolean, val text: String)

/** E përbashkët për bisedën live (Gemini) dhe modalitetin e thjeshtë (zëri i telefonit). */
interface VoiceSession {
    val state: MutableState<VState>
    val partial: MutableState<String>
    val level: MutableFloatState
    val turns: SnapshotStateList<Turn>
    val message: MutableState<String?>
    val toggle: MutableState<Boolean>
    val toggleLabel: String
    val title: String
    fun statusText(): String
    fun onToggle(v: Boolean) { toggle.value = v }
    fun start()
    fun tap()
    fun stop()
    fun release()
}

/** Teksti i përgjigjes → tekst i lexueshëm me zë në shqip. */
fun speechify(t: String): String {
    var s = t.replace(Regex("[*#_`>|]"), " ")
    s = s.replace(Regex("(?<=\\d),(?=\\d{3}(\\D|$))"), "")                       // 3,214,500 → 3214500
    s = s.replace(Regex("(\\d+)(?:[.,](\\d+))?\\s?M\\s?L\\b")) {                   // 3.2M L → 3,2 milion lekë
        val dec = it.groupValues[2]
        it.groupValues[1] + (if (dec.isNotEmpty()) ",$dec" else "") + " milion lekë"
    }
    s = s.replace(Regex("(?<=\\d)\\s?(L|ALL|lek)\\b"), " lekë")
    s = s.replace(Regex("(?<=\\d)\\.(?=\\d)"), ",")                                // 8.2 → 8,2 (presje dhjetore shqip)
    s = s.replace("%", " për qind").replace("▲", "rritje ").replace("▼", "rënie ").replace("—", ", ")
    return s.replace(Regex("\\s+"), " ").trim()
}

/** Bisedë me zë: njohje zëri (sq-AL) → brain-chat (stream) → zë, fjali pas fjalie. */
class VoiceEngine(private val ctx: Context) : VoiceSession, RecognitionListener, TextToSpeech.OnInitListener {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    override val state = mutableStateOf(VState.Idle)
    override val partial = mutableStateOf("")
    override val level = mutableFloatStateOf(0f)
    override val turns = mutableStateListOf<Turn>()
    override val message = mutableStateOf<String?>(null)
    override val toggle = mutableStateOf(true)          // bisedë e vazhdueshme
    override val toggleLabel = "Bisedë e vazhdueshme"
    override val title = "Modaliteti i thjeshtë"
    override fun statusText() = when (state.value) {
        VState.Listening -> "Po dëgjoj…"; VState.Thinking -> "THA'NA po analizon të dhënat…"
        VState.Speaking -> "THA'NA po flet · prek orb-in për ta ndërprerë"; VState.Idle -> "Prek orb-in për të folur"
    }
    override fun start() = listen()

    private var session: String? = null
    private var recognizer: SpeechRecognizer? = null
    private val tts = TextToSpeech(ctx, this)
    private var ttsReady = false
    private var pending = 0
    private var streamDone = true
    private var turnId = 0
    private var utt = 0
    private var job: Job? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { message.value = "Ky telefon s'ka motor zëri. Përgjigjet shfaqen me shkrim."; return }
        val r = tts.setLanguage(Locale("sq", "AL"))
        ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ttsReady) message.value = "Mungon zëri shqip. Cilësime → Sistemi → Gjuha → Text-to-speech → Speech Services by Google → shkarko Shqip. Deri atëherë përgjigjet shfaqen me shkrim."
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { scope.launch { pending--; finishIfDone() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { scope.launch { pending--; finishIfDone() } }
        })
    }

    fun listen() {
        if (Repo.token(ctx) == null) { message.value = "Hyr në THA'NA para se të flasësh."; return }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message.value = "Lejo mikrofonin për THA'NA."; return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { message.value = "Njohja e zërit nuk është e disponueshme në këtë telefon."; return }
        turnId++; job?.cancel(); tts.stop(); pending = 0; streamDone = true
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(this) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "sq-AL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        partial.value = ""
        if (ttsReady) message.value = null   // paralajmërimi për zërin shqip mbetet derisa të instalohet
        state.value = VState.Listening
        recognizer!!.startListening(i)
    }

    /** Prekja e orb-it: ndalon dëgjimin, ose ndërpret THA'NA-n dhe të dëgjon ty. */
    override fun tap() {
        when (state.value) {
            VState.Listening -> recognizer?.stopListening()
            else -> listen()
        }
    }

    override fun stop() { turnId++; job?.cancel(); tts.stop(); recognizer?.cancel(); pending = 0; state.value = VState.Idle }

    override fun release() { stop(); scope.cancel(); recognizer?.destroy(); tts.shutdown() }

    private fun ask(q: String) {
        val my = ++turnId
        turns += Turn(true, q); turns += Turn(false, "…")
        val idx = turns.lastIndex
        state.value = VState.Thinking; streamDone = false
        var spoken = 0
        job = scope.launch {
            val sid = withContext(Dispatchers.IO) {
                Repo.ask(ctx, q, session) { full ->
                    scope.launch {
                        if (my != turnId) return@launch
                        turns[idx] = Turn(false, full)
                        val cut = sentenceEnd(full, spoken)
                        if (cut > spoken) { say(full.substring(spoken, cut)); spoken = cut }
                    }
                }
            }
            if (my != turnId) return@launch
            session = sid
            val full = turns[idx].text
            if (full.length > spoken) say(full.substring(spoken))
            streamDone = true
            finishIfDone()
        }
    }

    private fun sentenceEnd(s: String, from: Int): Int {
        var cut = from
        for (i in from until s.length - 1) {
            val c = s[i]
            if ((c == '.' || c == '!' || c == '?' || c == '\n' || c == ';') && s[i + 1].isWhitespace() && i + 1 - from >= 12) cut = i + 1
        }
        return cut
    }

    private fun say(text: String) {
        val clean = speechify(text)
        if (clean.isBlank() || !ttsReady) return
        state.value = VState.Speaking
        pending++
        tts.speak(clean, TextToSpeech.QUEUE_ADD, null, "u${++utt}")
    }

    private fun finishIfDone() {
        if (!streamDone || pending > 0) return
        state.value = VState.Idle
        if (toggle.value) scope.launch { delay(350); if (state.value == VState.Idle) listen() }
    }

    // --- RecognitionListener
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) { level.floatValue = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() { level.floatValue = 0f }
    override fun onEvent(eventType: Int, params: Bundle?) {}
    override fun onPartialResults(b: Bundle?) {
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial.value = it }
    }
    override fun onResults(b: Bundle?) {
        val q = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        partial.value = ""
        if (q.isNullOrBlank()) { state.value = VState.Idle; return }
        ask(q)
    }
    override fun onError(error: Int) {
        level.floatValue = 0f
        if (state.value != VState.Listening) return
        state.value = VState.Idle
        message.value = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nuk dëgjova asgjë. Prek orb-in për të folur."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Pa internet për njohjen e zërit."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Lejo mikrofonin për THA'NA."
            else -> "Mikrofoni s'u hap (kodi $error). Prek orb-in për të provuar përsëri."
        }
    }
}

class VoiceActivity : ComponentActivity() {
    private val current = mutableStateOf<VoiceSession?>(null)
    private lateinit var askMic: androidx.activity.result.ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        current.value = LiveEngine(this)
        askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) current.value?.start()
            else current.value?.message?.value = "Pa mikrofon nuk mund të flasësh me THA'NA. Lejoje te Cilësime → Apps → THA'NA."
        }
        setContent {
            ThanaTheme {
                current.value?.let { e ->
                    VoiceScreen(e, onClose = { finish() },
                        onFallback = if (e is LiveEngine) ({ switchToSimple() }) else null)
                }
            }
        }
        when {
            Repo.token(this) == null -> current.value?.message?.value = "Hyr në THA'NA para se të flasësh."
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> current.value?.start()
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun switchToSimple() {
        current.value?.release()
        current.value = VoiceEngine(this).also { it.start() }
    }

    override fun onDestroy() { current.value?.release(); super.onDestroy() }
}

@Composable
fun VoiceOrb(state: VState, level: Float, onTap: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "b")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "s")
    val label = when (state) {
        VState.Listening -> "Po dëgjoj"; VState.Thinking -> "THA'NA po analizon"
        VState.Speaking -> "THA'NA po flet, prek për ta ndërprerë"; VState.Idle -> "Prek për të folur"
    }
    Canvas(
        Modifier.size(240.dp).semantics { contentDescription = label }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onTap)
    ) {
        val r0 = size.minDimension / 2
        val pulse = when (state) { VState.Listening -> 0.9f + level * 0.22f; VState.Speaking -> breathe; else -> 0.94f }
        for (k in 3 downTo 1) drawCircle(T.Brass.copy(alpha = 0.06f * k * (if (state == VState.Idle) 0.6f else 1f)), r0 * (0.62f + 0.12f * k) * pulse)
        drawCircle(
            Brush.radialGradient(listOf(T.Ink.copy(alpha = 0.9f), T.Brass, T.Brass.copy(alpha = 0.5f), T.Bg), Offset(size.width * 0.38f, size.height * 0.32f), r0 * 0.9f),
            r0 * 0.56f * pulse,
        )
        if (state == VState.Thinking) drawArc(T.Brass, spin, 80f, false, Offset(r0 * 0.3f, r0 * 0.3f),
            androidx.compose.ui.geometry.Size(r0 * 1.4f, r0 * 1.4f), style = Stroke(3.dp.toPx()))
    }
}

@Composable
fun VoiceScreen(e: VoiceSession, onClose: () -> Unit, onFallback: (() -> Unit)? = null) {
    val list = rememberLazyListState()
    LaunchedEffect(e.turns.size, e.turns.lastOrNull()?.text?.length) { if (e.turns.isNotEmpty()) list.scrollToItem(e.turns.lastIndex) }
    Column(Modifier.fillMaxSize().background(T.Bg).systemBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("THA'NA · TRURI", style = T.Label.copy(color = T.Brass))
                Text(e.title, style = T.Title.copy(fontSize = 24.sp))
            }
            IconButton({ e.stop(); onClose() }, Modifier.size(48.dp)) { Icon(Icons.Filled.Close, "Mbyll", tint = T.Muted) }
        }
        Spacer(Modifier.height(8.dp))
        VoiceOrb(e.state.value, e.level.floatValue) { e.tap() }
        Text(e.statusText(), style = T.Small.copy(fontSize = 13.sp))
        if (e.partial.value.isNotBlank()) Text("“${e.partial.value}”", style = T.Body.copy(fontStyle = FontStyle.Italic, color = T.Muted), modifier = Modifier.padding(top = 8.dp))
        e.message.value?.let {
            Text(it, style = T.Small.copy(color = T.Warn), modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
                .clip(RoundedCornerShape(14.dp)).background(T.Surface).padding(12.dp))
            if (onFallback != null && e.state.value == VState.Idle)
                TextButton(onFallback) { Text("Provo modalitetin e thjeshtë (zëri i telefonit)", color = T.Brass) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e.turns.isEmpty()) item {
                Text("Provo: “Si mbylli dita?” · “Kush ka borxh mbi 60 ditë?” · “Pse ra Shkodra këtë muaj?”", style = T.Small, modifier = Modifier.padding(8.dp))
            }
            itemsIndexed(e.turns) { _, m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(m.text, style = T.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = if (m.mine) T.Muted else T.Ink),
                        modifier = Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(18.dp)).background(if (m.mine) T.Bg else T.Surface)
                            .border(1.dp, T.Border, RoundedCornerShape(18.dp)).padding(12.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(e.toggleLabel, style = T.Body, modifier = Modifier.weight(1f))
            Switch(e.toggle.value, { e.onToggle(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = T.OnBrass, checkedTrackColor = T.Brass))
        }
        Text("Shifrat vijnë nga THA'NA OS. Zëri vetëm i lexon.", style = T.Small.copy(fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp))
    }
}

/** Butoni i mikrofonit (vizatuar, pa varësi nga ikonat ekstra). */
@Composable
fun MicGlyph(size: androidx.compose.ui.unit.Dp = 22.dp, color: androidx.compose.ui.graphics.Color = T.OnBrass) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = Stroke(w * 0.09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawRoundRect(color, Offset(w * 0.36f, h * 0.08f), androidx.compose.ui.geometry.Size(w * 0.28f, h * 0.5f),
            androidx.compose.ui.geometry.CornerRadius(w * 0.14f), style = s)
        drawArc(color, 0f, 180f, false, Offset(w * 0.22f, h * 0.3f), androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.44f), style = s)
        drawLine(color, Offset(w / 2, h * 0.74f), Offset(w / 2, h * 0.92f), s.width, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
