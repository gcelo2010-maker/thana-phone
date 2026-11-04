package al.thana.widgets

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Bisedë LIVE me zë (Gemini Live, speech-to-speech) me Trurin THA'NA si "tools".
 * Mikrofoni transmetohet vazhdimisht (16 kHz PCM); zëri vjen në kohë reale (24 kHz PCM).
 * Mund ta ndërpresësh THA'NA-n duke folur. Shifrat merren vetëm nga thana_panorama / thana_pyet.
 */
class LiveEngine(private val ctx: Context) : VoiceSession {
    override val state = mutableStateOf(VState.Idle)
    override val partial = mutableStateOf("")
    override val level = mutableFloatStateOf(0f)
    override val turns = mutableStateListOf<Turn>()
    override val message = mutableStateOf<String?>(null)
    override val toggle = mutableStateOf(true)            // mikrofoni hapur
    override val toggleLabel = "Mikrofoni"
    override val title = "Bisedë live"

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val audio = ctx.getSystemService(AudioManager::class.java)

    private var ws: WebSocket? = null
    private var cfg: JSONObject? = null
    private var urlIndex = 0
    private var setupDone = false
    private var connected = false
    @Volatile private var running = false
    @Volatile private var muted = false
    private var rec: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var track: AudioTrack? = null
    private val playQ = LinkedBlockingQueue<ByteArray>()
    // Ringjallja e jehonës: kur THA'NA flet, zëri i saj nga altoparlanti nuk dërgohet si "fol i përdoruesit".
    private val pre = ArrayDeque<ByteArray>()
    private var loudFrames = 0
    private var userIdx = -1
    private var modelIdx = -1

    override fun statusText() = when {
        !connected && state.value == VState.Thinking -> "Po lidhem me THA'NA…"
        !connected -> "Prek orb-in për të filluar bisedën"
        muted -> "Mikrofoni është i mbyllur"
        state.value == VState.Speaking -> "THA'NA po flet · fol për ta ndërprerë"
        state.value == VState.Thinking -> "Po kontrolloj të dhënat te Truri THA'NA…"
        else -> "Po dëgjoj · fol lirshëm"
    }

    override fun onToggle(v: Boolean) { toggle.value = v; muted = !v }

    override fun tap() { if (!connected && state.value == VState.Idle) start() else onToggle(!toggle.value) }

    override fun start() {
        if (connected || state.value == VState.Thinking) return
        if (Repo.token(ctx) == null) { message.value = "Hyr në THA'NA para se të flasësh."; return }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message.value = "Lejo mikrofonin për THA'NA."; return
        }
        state.value = VState.Thinking; message.value = null
        scope.launch {
            val c = withContext(Dispatchers.IO) { Repo.voiceSession(ctx) }
            if (!c.has("token")) { state.value = VState.Idle; message.value = c.optString("error", "Biseda live nuk u hap."); return@launch }
            cfg = c; urlIndex = 0; connect()
        }
    }

    private fun connect() {
        val c = cfg ?: return
        val urls = c.getJSONArray("ws_urls")
        if (urlIndex >= urls.length()) { end("Nuk u lidh me zërin live. Kontrollo internetin dhe provo përsëri."); return }
        setupDone = false
        val url = urls.getString(urlIndex) + "?access_token=" + URLEncoder.encode(c.getString("token"), "UTF-8")
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setupMessage(c).toString()) }
            override fun onMessage(webSocket: WebSocket, text: String) { main.post { handle(text) } }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { val t = bytes.utf8(); main.post { handle(t) } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); main.post { closed(reason) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { main.post { closed(t.message ?: "") } }
        })
    }

    private fun closed(reason: String) {
        if (!running && !setupDone && cfg != null && state.value == VState.Thinking) {   // s'u hap: provo endpoint-in tjetër
            urlIndex++; connect(); return
        }
        if (running || connected) end(if (reason.isNotBlank()) "Biseda u mbyll ($reason). Prek orb-in për një bisedë të re." else "Biseda u mbyll. Prek orb-in për një bisedë të re.")
    }

    private fun setupMessage(c: JSONObject) = JSONObject().put("setup", JSONObject()
        .put("model", c.getString("model"))
        .put("generationConfig", JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                .put("prebuiltVoiceConfig", JSONObject().put("voiceName", c.optString("voice", "Charon"))))))
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", c.getString("system")))))
        .put("tools", c.getJSONArray("tools"))
        .put("inputAudioTranscription", JSONObject())
        .put("outputAudioTranscription", JSONObject())
        .also { setup ->                                   // cilësime shtesë nga serveri, pa APK të re
            c.optJSONObject("setup_extra")?.let { x -> x.keys().forEach { k -> setup.put(k, x.get(k)) } }
        })

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    private fun sendAudio(b: ByteArray) = send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
        .put("data", Base64.encodeToString(b, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000"))))

    private fun handle(text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        when {
            m.has("setupComplete") -> {
                setupDone = true; connected = true; message.value = null
                startAudio()
                state.value = VState.Listening
                cfg?.optString("greeting")?.takeIf { it.isNotBlank() }?.let { g ->
                    send(JSONObject().put("clientContent", JSONObject()
                        .put("turns", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", g)))))
                        .put("turnComplete", true)))
                }
            }
            m.has("serverContent") -> server(m.getJSONObject("serverContent"))
            m.has("toolCall") -> tools(m.getJSONObject("toolCall").optJSONArray("functionCalls") ?: JSONArray())
            m.has("goAway") -> message.value = "Biseda po afron limitin e kohës. Pas pak, prek orb-in për një bisedë të re."
        }
    }

    private fun server(sc: JSONObject) {
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { t ->
            if (userIdx < 0) { turns += Turn(true, t.trimStart()); userIdx = turns.lastIndex; modelIdx = -1 }
            else turns[userIdx] = Turn(true, turns[userIdx].text + t)
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { t ->
            if (modelIdx < 0) { turns += Turn(false, t.trimStart()); modelIdx = turns.lastIndex; userIdx = -1 }
            else turns[modelIdx] = Turn(false, turns[modelIdx].text + t)
        }
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val d = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                if (d.isNotEmpty()) { playQ.offer(Base64.decode(d, Base64.DEFAULT)); state.value = VState.Speaking }
            }
        }
        if (sc.optBoolean("interrupted")) {             // e ndërpreve: ndal zërin menjëherë
            playQ.clear(); track?.pause(); track?.flush(); track?.play()
            state.value = VState.Listening; modelIdx = -1
        }
        if (sc.optBoolean("turnComplete")) { modelIdx = -1; userIdx = -1 }
    }

    private fun tools(calls: JSONArray) {
        for (i in 0 until calls.length()) {
            val fc = calls.getJSONObject(i)
            val id = fc.optString("id"); val name = fc.optString("name"); val args = fc.optJSONObject("args") ?: JSONObject()
            val history = turns.filter { !it.text.startsWith("⟳") && it.text.isNotBlank() }.takeLast(12)
            turns += Turn(false, when (name) {
                "thana_mendo" -> "⟳ Po mendoj…"
                "thana_pyet" -> "⟳ Truri THA'NA: ${args.optString("pyetja")}"
                else -> "⟳ Po lexoj pamjen e sotme"
            })
            modelIdx = -1; userIdx = -1
            if (state.value != VState.Speaking) state.value = VState.Thinking
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    when (name) {
                        // v0.8: çdo pyetje e vërtetë përgjigjet nga Claude në server (me THA'NA + web).
                        "thana_mendo" -> Repo.voiceTool(ctx, name, args, history)
                        "thana_panorama" -> panorama()
                        "thana_pyet" -> {
                            // Çdo pyetje në sesion të ri të Trurit: konteksti i bisedës e mban Gemini;
                            // kështu Pakti nuk bllokon përgjigjet si "kontradiktë" me pyetjet e mëparshme.
                            var last = ""
                            val q = args.optString("pyetja") + (cfg?.optString("voice_question_suffix") ?: "")
                            Repo.ask(ctx, q, null) { last = it }
                            last.ifBlank {
                                "Truri THA'NA nuk e verifikoi dot këtë përgjigje tani. Mos e pyet përsëri të njëjtën pyetje; " +
                                    "thuaji pronarit ta formulojë ndryshe ose ta shohë te app."
                            }
                        }
                        else -> "Mjet i panjohur."
                    }
                }
                send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(
                    JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", result))))))
                if (state.value == VState.Thinking) state.value = VState.Listening
            }
        }
    }

    /** Pamja e sotme si tekst për modelin. Vetëm shifra nga owner-snapshot; ato që mungojnë thuhen "pa të dhëna". */
    private fun panorama(): String {
        Repo.fetch(ctx)
        val s = Repo.cached(ctx) ?: return "Pamja e sotme nuk është e disponueshme tani."
        fun m(v: Double?) = Money.full(v).replace("—", "pa të dhëna")
        fun p(v: Double?) = v?.let { Money.pct(it) } ?: "pa të dhëna"
        val agents = s.agents.take(5).mapIndexed { i, a -> "${i + 1}. ${a.name} (${a.zone ?: "pa zonë"}): ${m(a.today)}, ndryshim vs java e kaluar ${p(a.delta)}" }
        return buildString {
            appendLine("Të dhënat e shitjeve deri më ${s.salesDate ?: "pa datë"}; borxhet përditësuar më ${s.debtsFreshness?.take(10) ?: "pa datë"}.")
            appendLine("Shitjet sot: ${m(s.salesToday)} (vs e njëjta ditë javën e kaluar: ${p(s.salesDelta)}). Agjentë me shitje sot: ${s.agentsToday ?: "pa të dhëna"}.")
            appendLine("Shitjet e muajit deri sot: ${m(s.mtd)} (vs e njëjta periudhë e muajit të kaluar: ${p(s.mtdDelta)}).")
            appendLine("Shëndeti i biznesit: ${s.health ?: "pa të dhëna"}/100 (shitje ${s.hSales ?: "-"}, likuiditet ${s.hLiquidity ?: "-"}, arkëtime ${s.hCollections ?: "-"}).")
            appendLine("Borxhet: total ${m(s.recvTotal)}, me afat të kaluar ${m(s.overdue)} (${s.overduePct?.let { Money.pct(it, false) } ?: "pa të dhëna"}), ${s.overdueCustomers ?: "pa të dhëna"} klientë me vonesë; paguajnë mesatarisht pas ${s.dso?.toInt() ?: "pa të dhëna"} ditësh (objektivi ${s.dsoTarget?.toInt() ?: 45}).")
            if (s.debtors.isNotEmpty()) appendLine("Debitorët kryesorë: " + s.debtors.joinToString("; ") { "${it.name} ${m(it.amount)}" } + ".")
            appendLine("Aprovime në pritje: ${s.pending ?: "pa të dhëna"}.")
            if (agents.isNotEmpty()) appendLine("Agjentët sot: " + agents.joinToString("; ") + ".")
            if (s.signals.isNotEmpty()) appendLine("Sinjale: " + s.signals.joinToString("; ") { it.title } + ".")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (running) return
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION") audio.isSpeakerphoneOn = true
        val outMin = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(outMin * 4).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
        val inMin = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, 6400))
        rec = r
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true }
        running = true
        r.startRecording()

        thread(name = "thana-mic") {
            val buf = ByteArray(3200)                    // 100 ms
            while (running) {
                val n = rec?.read(buf, 0, buf.size) ?: break
                if (n <= 0) continue
                var sum = 0.0
                var i = 0
                while (i + 1 < n) { val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble(); sum += v * v; i += 2 }
                val lvl = (sqrt(sum / (n / 2)) / 3000.0).toFloat().coerceIn(0f, 1f)
                main.post { level.floatValue = if (muted) 0f else lvl }
                if (muted) continue
                val frame = buf.copyOf(n)
                val speaking = playQ.isNotEmpty() || state.value == VState.Speaking
                if (!speaking) {
                    while (pre.isNotEmpty()) sendAudio(pre.removeFirst())
                    loudFrames = 0
                    sendAudio(frame)
                } else {
                    // Gjatë zërit të THA'NA-s: dërgo vetëm kur flet qartë (ndërprerje e vërtetë), jo jehonën.
                    loudFrames = if (lvl > 0.28f) loudFrames + 1 else 0
                    pre.addLast(frame); if (pre.size > 4) pre.removeFirst()
                    if (loudFrames >= 3) { while (pre.isNotEmpty()) sendAudio(pre.removeFirst()) }
                }
            }
        }
        thread(name = "thana-play") {
            while (running) {
                val chunk = playQ.poll(200, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    main.post { if (playQ.isEmpty() && state.value == VState.Speaking) state.value = VState.Listening }
                    continue
                }
                track?.write(chunk, 0, chunk.size)
            }
        }
    }

    private fun stopAudio() {
        running = false
        runCatching { rec?.stop() }; rec?.release(); rec = null
        aec?.release(); aec = null; ns?.release(); ns = null
        runCatching { track?.stop() }; track?.release(); track = null
        playQ.clear()
        audio.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION") audio.isSpeakerphoneOn = false
    }

    private fun end(msg: String?) {
        stopAudio()
        ws?.close(1000, null); ws = null
        connected = false; setupDone = false; cfg = null
        level.floatValue = 0f; state.value = VState.Idle
        msg?.let { message.value = it }
    }

    override fun stop() = end(null)

    override fun release() { end(null); scope.cancel(); http.dispatcher.executorService.shutdown() }
}
