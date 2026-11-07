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
import android.media.audiofx.LoudnessEnhancer
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
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
 *
 * v0.9.5 (WO-CLIENT-V094, 07/11/2026) — Gemini jo-bllokues: klienti deklaron `async_tools: true` (VoiceContract), serveri (v19+)
 * i jep mjetit `thana_mendo` behavior NON_BLOCKING dhe prompt të lirë; zëri vazhdon të flasë/dëgjojë sa punon Truri dhe rezultati
 * dërgohet me `scheduling` (WHEN_IDLE). Plus: watchdog i lidhjes (20 s), prekja e dytë e orb-it e rinis lidhjen e ngecur,
 * ri-lidhje max 3 me 1/2/4 s, `toolCallCancellation`, log diagnostik «ThanaVoice» pa PII (asnjë transkript, asnjë token).
 */
class LiveEngine(private val ctx: Context, private val preferred: String = "openai") : VoiceSession {
    private companion object { const val TAG = "ThanaVoice" }
    override val state = mutableStateOf(VState.Idle)
    override val partial = mutableStateOf("")
    override val level = mutableFloatStateOf(0f)
    override val turns = mutableStateListOf<Turn>()
    /** Kujtesa e bisedës për Claude: pyetjet dhe përgjigjet e plota (edhe kur zëri ndërpritet). */
    private val memo = Repo.loadMemo(ctx).toMutableList()
    override val message = mutableStateOf<String?>(null)
    override val toggle = mutableStateOf(true)            // mikrofoni hapur
    override val toggleLabel = "Mikrofoni"
    override val title = "Bisedë live"
    /** "openai" (GPT Realtime) ose "gemini" (Gemini Live) — vendoset nga serveri. */
    val engine = mutableStateOf(preferred)
    private val oa get() = engine.value == "openai"
    private val rate get() = if (oa) 24000 else 16000

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
    private var boost: LoudnessEnhancer? = null
    private var focus: AudioFocusRequest? = null
    private var lastRoute = 0L
    @Volatile private var userStopped = false
    private var reconnects = 0
    /** Mënyra asinkrone e dhënë nga serveri për KËTË sesion (vetëm Gemini, vetëm kur e kërkuam). */
    private val asyncTools get() = !oa && VoiceContract.asyncTools(cfg)
    /** Mjete në punë në sfond (mënyra asinkrone): vetëm shenjë në status, zëri nuk ndalon. */
    private var pendingTools = 0
    /** Thirrje që modeli i anuloi (`toolCallCancellation`): rezultati i tyre nuk dërgohet. */
    private val cancelledTools = HashSet<String>()
    private var connectWatch: Runnable? = null
    /** Numri i tentativës së lidhjes: rezultati i një voice-session të vjetër (pas rinisjes nga orb-i) injorohet. */
    private var attempt = 0

    /** Mbaj zërin në altoparlant gjatë gjithë bisedës (Android mund ta kalojë te receptori pas pak minutash). */
    private fun routeSpeaker() {
        if (audio.mode != AudioManager.MODE_IN_COMMUNICATION) audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val sp = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (sp != null && audio.communicationDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) audio.setCommunicationDevice(sp)
        } else {
            @Suppress("DEPRECATION") if (!audio.isSpeakerphoneOn) audio.isSpeakerphoneOn = true
        }
    }
    private val playQ = LinkedBlockingQueue<ByteArray>()
    // Ringjallja e jehonës: kur THA'NA flet, zëri i saj nga altoparlanti nuk dërgohet si "fol i përdoruesit".
    private val pre = ArrayDeque<ByteArray>()
    private var loudFrames = 0
    private var userIdx = -1
    // GPT: përgjigjja e Claude-it vjen me streaming; fjalia e parë niset menjëherë, pjesa tjetër si vazhdim.
    private class Stream(val id: String) { val buf = StringBuilder(); var sentFirst = false; var finished = false; var cancelled = false }
    private var stream: Stream? = null
    private var responseActive = false
    private var pendingCreate = false   // response.create në pritje: GPT lejon vetëm një përgjigje aktive njëherësh
    private var modelIdx = -1

    override fun statusText() = when {
        !connected && state.value == VState.Thinking -> "Po lidhem me THA'NA…"
        !connected -> "Prek orb-in për të filluar bisedën"
        muted -> "Mikrofoni është i mbyllur"
        state.value == VState.Speaking -> "THA'NA po flet · fol për ta ndërprerë"
        state.value == VState.Thinking -> "Po kontrolloj të dhënat te Truri THA'NA…"
        pendingTools > 0 -> "Po dëgjoj · Truri po përgatit përgjigjen në sfond"
        else -> "Po dëgjoj · fol lirshëm"
    }

    override fun onToggle(v: Boolean) { toggle.value = v; muted = !v }

    override fun tap() {
        if (connected) { onToggle(!toggle.value); return }
        // E ngecur «duke u lidhur» (07/11 01:24: prekja vetëm hapte/mbyllte mikrofonin dhe asnjë kërkesë s'arrinte te serveri):
        // prekja e dytë e mbyll tentativën dhe e rinis nga e para, me sesion të ri.
        if (state.value == VState.Thinking) { Log.i(TAG, "tap while connecting → restart"); end(null) }
        reconnects = 0
        start()
    }

    override fun start() {
        if (connected || state.value == VState.Thinking) return
        if (Repo.token(ctx) == null) { message.value = "Hyr në THA'NA para se të flasësh."; return }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message.value = "Lejo mikrofonin për THA'NA."; return
        }
        state.value = VState.Thinking; message.value = null; userStopped = false
        val gen = ++attempt
        armConnectWatch()
        scope.launch {
            // Çdo tentativë lidhjeje merr çelës TË RI nga voice-session (çelësi i Gemini-t hap sesion të ri vetëm 2 min pas lëshimit).
            val c = withContext(Dispatchers.IO) { Repo.voiceSession(ctx, preferred) }
            if (gen != attempt || userStopped) { Log.i(TAG, "stale voice-session result ignored (attempt $gen)"); return@launch }
            if (!c.has("token")) {
                Log.w(TAG, "voice-session refused: ${c.optString("error").take(80)}")
                end(c.optString("error", "Biseda live nuk u hap.")); return@launch
            }
            engine.value = c.optString("engine", "gemini")
            cfg = c; urlIndex = 0
            Log.i(TAG, "session engine=${engine.value} async=$asyncTools client=${BuildConfig.VERSION_NAME} reconnect=$reconnects")
            connect()
        }
    }

    /** Lidhja që nuk hapet brenda 20 s kthehet në Idle me mesazh — kurrë «duke menduar» pa fund. */
    private fun armConnectWatch() {
        connectWatch?.let { main.removeCallbacks(it) }
        val r = Runnable {
            if (!connected && !userStopped && state.value == VState.Thinking) {
                Log.w(TAG, "connect watchdog fired (${VoiceContract.CONNECT_TIMEOUT_MS} ms)")
                end("Nuk u lidha me zërin brenda 20 sekondash. Prek orb-in për të provuar përsëri.")
            }
        }
        connectWatch = r; main.postDelayed(r, VoiceContract.CONNECT_TIMEOUT_MS)
    }

    private fun clearConnectWatch() { connectWatch?.let { main.removeCallbacks(it) }; connectWatch = null }

    private fun connect() {
        val c = cfg ?: return
        if (oa) {                                          // GPT Realtime: një URL, çelësi i përkohshëm si header
            if (urlIndex > 0) { end("Nuk u lidh me GPT. Provo përsëri ose zgjidh Gemini."); return }
            setupDone = false
            val req = Request.Builder().url(c.getString("ws_url")).header("Authorization", "Bearer " + c.getString("token")).build()
            ws = http.newWebSocket(req, listener())
            return
        }
        val urls = c.getJSONArray("ws_urls")
        if (urlIndex >= urls.length()) { end("Nuk u lidh me zërin live. Kontrollo internetin dhe provo përsëri."); return }
        setupDone = false
        val url = urls.getString(urlIndex) + "?access_token=" + URLEncoder.encode(c.getString("token"), "UTF-8")
        ws = http.newWebSocket(Request.Builder().url(url).build(), listener())
    }

    private fun listener() = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "ws open engine=${engine.value} url=$urlIndex http=${response.code}")
                if (!oa) cfg?.let { webSocket.send(setupMessage(it).toString()) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) { main.post { handle(text) } }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { val t = bytes.utf8(); main.post { handle(t) } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "ws closing code=$code reason=${reason.take(120)} setup=$setupDone connected=$connected")
                webSocket.close(1000, null); main.post { closed(reason) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure http=${response?.code} ${t.javaClass.simpleName}: ${t.message?.take(120)} setup=$setupDone connected=$connected")
                main.post { closed(t.message ?: "") }
            }
    }

    private fun closed(reason: String) {
        if (!running && !setupDone && cfg != null && state.value == VState.Thinking) {   // s'u hap: provo endpoint-in tjetër
            urlIndex++; connect(); return
        }
        if (running || connected) {
            // Lidhja ra gjatë bisedës: rilidhu vetë (biseda dhe kujtesa mbeten) — max 3 herë, me pritje 1/2/4 s dhe sesion të ri
            // çdo herë. Më parë: 5 herë pa pritje → 07/11 00:26Z: 4 sesione brenda 45 s («lëmsh»). Pas të tretës: mesazh, jo lak.
            if (!userStopped && reconnects < VoiceContract.MAX_RECONNECTS) {
                reconnects++
                val wait = VoiceContract.backoffMs(reconnects)
                Log.i(TAG, "reconnect $reconnects/${VoiceContract.MAX_RECONNECTS} in $wait ms (${reason.take(60)})")
                end(null); stream = null; responseActive = false
                message.value = "Po rilidhem… ($reconnects/${VoiceContract.MAX_RECONNECTS})"
                main.postDelayed({ if (!userStopped) start() }, wait)
            } else end(if (reason.isNotBlank()) "Biseda u mbyll ($reason). Prek orb-in për një bisedë të re." else "Biseda u mbyll. Prek orb-in për një bisedë të re.")
        }
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

    private fun sendAudio(b: ByteArray) = if (oa)
        send(JSONObject().put("type", "input_audio_buffer.append").put("audio", Base64.encodeToString(b, Base64.NO_WRAP)))
    else send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
        .put("data", Base64.encodeToString(b, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000"))))

    private fun interrupted() {
        playQ.clear(); track?.pause(); track?.flush(); track?.play()
        state.value = VState.Listening; modelIdx = -1
    }

    private fun userText(t: String, append: Boolean) {
        if (userIdx < 0 || !append) { turns += Turn(true, t.trimStart()); userIdx = turns.lastIndex; modelIdx = -1 }
        else turns[userIdx] = Turn(true, turns[userIdx].text + t)
    }

    private fun modelText(t: String) {
        if (modelIdx < 0) { turns += Turn(false, t.trimStart()); modelIdx = turns.lastIndex; userIdx = -1 }
        else turns[modelIdx] = Turn(false, turns[modelIdx].text + t)
    }

    /** Ngjarjet e GPT Realtime. */
    private fun handleOpenAI(m: JSONObject) {
        when (m.optString("type")) {
            "session.created" -> {
                setupDone = true; connected = true; message.value = null; clearConnectWatch()
                Log.i(TAG, "session.created (openai)")
                startAudio(); state.value = VState.Listening
                synchronized(memo) { memo.takeLast(6).toList() }.takeIf { it.isNotEmpty() }?.let { last ->
                    val ctxText = "Biseda e fundit me pronarin (për kontekst, mos e lexo):\n" +
                        last.joinToString("\n") { (if (it.mine) "Ai: " else "THA'NA: ") + it.text.take(400) }
                    send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject().put("type", "message").put("role", "system")
                        .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", ctxText)))))
                }
                cfg?.optString("greeting")?.takeIf { it.isNotBlank() && reconnects == 0 }?.let { g ->
                    send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject().put("type", "message").put("role", "user")
                        .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", g)))))
                    send(JSONObject().put("type", "response.create"))
                }
            }
            "response.output_audio.delta" -> m.optString("delta").takeIf { it.isNotEmpty() }?.let {
                playQ.offer(Base64.decode(it, Base64.DEFAULT)); state.value = VState.Speaking
            }
            "response.output_audio_transcript.delta" -> m.optString("delta").takeIf { it.isNotEmpty() }?.let { modelText(it) }
            "conversation.item.input_audio_transcription.completed" -> m.optString("transcript").trim().takeIf { it.isNotEmpty() }?.let {
                // Transkripti vjen pas fillimit të përgjigjes: vendose para saj.
                val at = if (modelIdx >= 0) modelIdx else turns.size
                turns.add(at, Turn(true, it)); if (modelIdx >= 0) modelIdx++
                userIdx = -1
            }
            "input_audio_buffer.speech_started" -> {
                stream?.let { st -> if (st.sentFirst) { st.cancelled = true; st.buf.setLength(0) } }
                if (state.value == VState.Speaking || playQ.isNotEmpty()) interrupted()
            }
            "response.created" -> responseActive = true
            "response.done" -> {
                modelIdx = -1; userIdx = -1; responseActive = false
                if (pendingCreate) createResponse() else pump()
            }
            "response.function_call_arguments.done" -> {
                val args = runCatching { JSONObject(m.optString("arguments")) }.getOrElse { JSONObject() }
                runTool(m.optString("call_id"), m.optString("name"), args)
            }
            "error" -> m.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() && !it.contains("buffer", true) && !it.contains("active response", true) }
                ?.let { message.value = "GPT: $it" }
        }
    }

    private fun handle(text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (oa) { handleOpenAI(m); return }
        when {
            m.has("setupComplete") -> {
                setupDone = true; connected = true; message.value = null; clearConnectWatch()
                Log.i(TAG, "setupComplete (gemini) async=$asyncTools")
                startAudio()
                state.value = VState.Listening
                cfg?.optString("greeting")?.takeIf { it.isNotBlank() && reconnects == 0 }?.let { g ->
                    send(JSONObject().put("clientContent", JSONObject()
                        .put("turns", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", g)))))
                        .put("turnComplete", true)))
                }
            }
            m.has("serverContent") -> server(m.getJSONObject("serverContent"))
            m.has("toolCall") -> tools(m.getJSONObject("toolCall").optJSONArray("functionCalls") ?: JSONArray())
            // Modeli i anuloi thirrjet (p.sh. përdoruesi e ndërpreu): rezultati i tyre nuk dërgohet më.
            m.has("toolCallCancellation") -> cancelled(m.getJSONObject("toolCallCancellation").optJSONArray("ids"))
            m.has("goAway") -> message.value = "Biseda po afron limitin e kohës. Pas pak, prek orb-in për një bisedë të re."
        }
    }

    private fun cancelled(ids: JSONArray?) {
        ids ?: return
        for (i in 0 until ids.length()) ids.optString(i).takeIf { it.isNotBlank() }?.let { cancelledTools += it }
        Log.i(TAG, "toolCallCancellation n=${ids.length()}")
    }

    private fun server(sc: JSONObject) {
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { userText(it, true) }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { modelText(it) }
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val d = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                if (d.isNotEmpty()) { playQ.offer(Base64.decode(d, Base64.DEFAULT)); state.value = VState.Speaking }
            }
        }
        if (sc.optBoolean("interrupted")) interrupted()   // e ndërpreve: ndal zërin menjëherë
        if (sc.optBoolean("turnComplete")) { modelIdx = -1; userIdx = -1 }
    }

    private fun tools(calls: JSONArray) {
        for (i in 0 until calls.length()) {
            val fc = calls.getJSONObject(i)
            runTool(fc.optString("id"), fc.optString("name"), fc.optJSONObject("args") ?: JSONObject())
        }
    }

    /** Fund i fjalisë së parë (pas të paktën `min` shkronjash) ose i fjalisë së fundit të plotë; -1 nëse s'ka. */
    private fun cut(sb: StringBuilder, first: Boolean): Int {
        var best = -1
        for (i in 0 until sb.length - 1) {
            val ch = sb[i]
            if ((ch == '.' || ch == '?' || ch == '!') && sb[i + 1].isWhitespace() && i >= (if (first) 40 else 0)) {
                best = i + 1
                if (first) return best
            }
        }
        return best
    }

    private fun take(sb: StringBuilder, n: Int): String { val t = sb.substring(0, n); sb.delete(0, n); return t.replace(Regex("\\s+"), " ").trim() }

    private fun sendOutput(id: String, text: String, speak: Boolean = true) {
        send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
            .put("type", "function_call_output").put("call_id", id).put("output", text)))
        if (speak) createResponse()
    }

    private fun createResponse() {
        if (responseActive) { pendingCreate = true; return }
        send(JSONObject().put("type", "response.create")); responseActive = true; pendingCreate = false
    }

    private fun sendContinuation(text: String) {
        send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject().put("type", "message").put("role", "system")
            .put("content", JSONArray().put(JSONObject().put("type", "input_text")
                .put("text", "VAZHDIMI i përgjigjes së THA'NA-s. Lexoje menjëherë, fjalë për fjalë, si vazhdim i drejtpërdrejtë, pa hyrje dhe pa koment: $text")))))
        createResponse()
    }

    /** Dërgon tekstin e gatshëm te zëri: fjalinë e parë sa më shpejt, pastaj vazhdimin kur zëri është i lirë. */
    private fun pump() {
        val st = stream ?: return
        if (st.cancelled) { if (!st.sentFirst && st.finished) { sendOutput(st.id, "(ndërprerë)", false); stream = null }; return }
        if (!st.sentFirst) {
            val n = if (st.finished) st.buf.length else cut(st.buf, true)
            if (n > 0) {
                val t = take(st.buf, n)
                if (t.isNotBlank()) {
                    sendOutput(st.id, t)
                    st.sentFirst = true
                }
            }
            if (st.finished && !st.sentFirst) { sendOutput(st.id, "Më fal, nuk mora përgjigje. Provo përsëri."); st.sentFirst = true }
        } else if (!responseActive) {
            val n = if (st.finished) st.buf.length else cut(st.buf, false)
            if (n > 0) { val t = take(st.buf, n); if (t.isNotBlank()) sendContinuation(t) }
        }
        if (st.finished && st.sentFirst && st.buf.isBlank()) stream = null
    }

    private fun runStreamed(id: String, args: JSONObject, history: List<Turn>) {
        val st = Stream(id); stream = st
        scope.launch {
            val full = withContext(Dispatchers.IO) {
                Repo.voiceToolStream(ctx, "thana_mendo", args, history) { d -> main.post { if (stream === st) { st.buf.append(d); pump() } } }
            }
            synchronized(memo) {
                memo += Turn(true, args.optString("pyetja")); memo += Turn(false, full)
                while (memo.size > 40) memo.removeAt(0)
                Repo.saveMemo(ctx, memo)
            }
            main.post {
                if (stream === st) {
                    if (!st.sentFirst && st.buf.isBlank()) st.buf.append(full)
                    st.finished = true; pump()
                }
                if (state.value == VState.Thinking) state.value = VState.Listening
            }
        }
    }

    private fun runTool(id: String, name: String, args: JSONObject) {
            val history = synchronized(memo) { memo.takeLast(30).toList() }
            val async = asyncTools
            val t0 = System.currentTimeMillis()
            Log.i(TAG, "toolCall name=$name async=$async pending=${pendingTools + 1}")
            turns += Turn(false, when (name) {
                "thana_mendo" -> "⟳ Po mendoj…"
                "thana_pyet" -> "⟳ Truri THA'NA: ${args.optString("pyetja")}"
                else -> "⟳ Po lexoj pamjen e sotme"
            })
            modelIdx = -1; userIdx = -1
            // Jo-bllokues (Gemini, serveri v19+ me async_tools): zëri vazhdon të flasë dhe të dëgjojë; vetëm një shenjë në status.
            // Bllokues (OpenAI, ose serveri pa async): sjellja e vjetër — «duke menduar» derisa të vijë rezultati.
            if (async) pendingTools++ else if (state.value != VState.Speaking) state.value = VState.Thinking
            if (oa && name == "thana_mendo") { runStreamed(id, args, history); return }
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    when (name) {
                        // v0.8: çdo pyetje e vërtetë përgjigjet nga Claude në server (me THA'NA + web).
                        "thana_mendo" -> Repo.voiceTool(ctx, name, args, history).also {
                            synchronized(memo) {
                                memo += Turn(true, args.optString("pyetja")); memo += Turn(false, it)
                                while (memo.size > 40) memo.removeAt(0)
                                Repo.saveMemo(ctx, memo)
                            }
                        }
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
                val ms = System.currentTimeMillis() - t0
                if (oa) {
                    send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                        .put("type", "function_call_output").put("call_id", id).put("output", result)))
                    send(JSONObject().put("type", "response.create"))
                } else if (cancelledTools.remove(id)) {
                    Log.i(TAG, "tool $name done after $ms ms but cancelled by model — no response sent")
                } else {
                    val sch = VoiceContract.schedulingFor(cfg, name)   // WHEN_IDLE në mënyrën asinkrone; pa fushë në të vjetrën
                    send(VoiceContract.toolResponse(id, name, result, sch))
                    Log.i(TAG, "toolResponse name=$name ms=$ms scheduling=${sch ?: "-"}")
                }
                if (async) pendingTools = (pendingTools - 1).coerceAtLeast(0)
                if (state.value == VState.Thinking) state.value = VState.Listening
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
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }.build().also { audio.requestAudioFocus(it) }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        routeSpeaker()
        val outMin = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(outMin * 4).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.setVolume(1f); it.play() }
        // Pak më shumë zë (+8 dB) që të dëgjohet qartë në altoparlant.
        boost = runCatching { LoudnessEnhancer(track!!.audioSessionId).also { it.setTargetGain(800); it.enabled = true } }.getOrNull()
        val sr = rate
        val inMin = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, sr * 2 / 5))
        rec = r
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true }
        running = true
        r.startRecording()

        thread(name = "thana-mic") {
            val buf = ByteArray(sr / 5)                  // 100 ms (16-bit mono)
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
                val now = System.currentTimeMillis()
                if (now - lastRoute > 10_000) { lastRoute = now; main.post { if (running) routeSpeaker() } }
            }
        }
    }

    private fun stopAudio() {
        running = false
        runCatching { rec?.stop() }; rec?.release(); rec = null
        aec?.release(); aec = null; ns?.release(); ns = null
        runCatching { track?.stop() }; track?.release(); track = null
        playQ.clear()
        boost?.release(); boost = null
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION") audio.isSpeakerphoneOn = false
    }

    private fun end(msg: String?) {
        clearConnectWatch()
        stopAudio()
        ws?.close(1000, null); ws = null
        connected = false; setupDone = false; cfg = null
        pendingTools = 0; cancelledTools.clear()
        level.floatValue = 0f; state.value = VState.Idle
        msg?.let { message.value = it }
    }

    override fun stop() { userStopped = true; end(null) }

    override fun release() { userStopped = true; end(null); scope.cancel(); http.dispatcher.executorService.shutdown() }
}
