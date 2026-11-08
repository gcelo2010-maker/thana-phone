package al.thana.shitje

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** THA'NA Shitje: paneli personal /w në ekran të plotë + widget-et e ekranit kryesor. */
class MainActivity : ComponentActivity() {
    private val openReq = mutableStateOf<String?>(null)
    private val sharedLink = mutableStateOf<String?>(null)
    private var web: WebView? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)); fileCb = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        take(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { web?.takeIf { it.canGoBack() }?.goBack() ?: finish() }
        })
        setContent { ThanaTheme { Surface(Modifier.fillMaxSize(), color = T.Bg) { Root() } } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); take(intent) }

    private fun take(i: Intent?) {
        i?.getStringExtra("open")?.let { openReq.value = it }
        i?.dataString?.let { if (PanelLink.parse(it) != null) sharedLink.value = it }
    }

    @Composable
    private fun Root() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var on by remember { mutableStateOf(Panel.connected(ctx)) }
        if (!on) Login(sharedLink.value) { on = true; Sync.schedule(ctx); Sync.now(ctx) }
        else Board(onLogout = {
            Panel.logout(ctx); web = null; on = false
            scope.launch { PanelWidgets.updateAll(ctx) }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun Board(onLogout: () -> Unit) {
        val ctx = LocalContext.current
        val l = remember { Panel.link(ctx)!! }
        val open = openReq.value
        var menu by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                WebView(c).apply {
                    setBackgroundColor(AColor.parseColor("#0B1A27"))
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                            if (r.url.host == PanelLink.HOST) return false
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, r.url)) }; return true   // lidhjet e jashtme → shfletuesi
                        }
                        override fun onPageFinished(v: WebView, url: String) { CookieManager.getInstance().flush(); Sync.now(c) }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                            fileCb?.onReceiveValue(null); fileCb = cb
                            return runCatching { pick.launch(p.createIntent()); true }.getOrElse { fileCb = null; false }   // foto e raftit: kamera / galeria
                        }
                    }
                    web = this
                    loadUrl(l.base + (open?.let { "?t=${System.currentTimeMillis()}#open=$it" } ?: ""))
                    openReq.value = null
                }
            }, update = { v -> openReq.value?.let { v.loadUrl("${l.base}?t=${System.currentTimeMillis()}#open=$it"); openReq.value = null } })
            Box(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                SmallFloatingActionButton({ menu = true }, shape = CircleShape, containerColor = T.Surface.copy(alpha = 0.85f), contentColor = T.Muted) { Text("⋯", fontSize = 18.sp) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rifresko widget-et") }, { menu = false; Sync.now(ctx) })
                    DropdownMenuItem({ Text("Si t'i shtoj widget-et") }, { menu = false; web?.evaluateJavascript("alert('Mbaj gishtin mbi ekranin kryesor → Widgets → THA\\'NA Shitje → tërhiq widget-in që do.')", null) })
                    DropdownMenuItem({ Text("Dil (${PanelLink.ROLE_NAME[l.role] ?: ""})") }, { menu = false; onLogout() })
                }
            }
        }
    }

    @Composable
    private fun Login(shared: String?, onDone: () -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var link by remember { mutableStateOf(shared ?: clipLink(ctx) ?: "") }
        var pin by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = T.Brass, unfocusedBorderColor = T.Border,
            focusedContainerColor = T.Surface, unfocusedContainerColor = T.Surface, cursorColor = T.Brass, focusedLabelColor = T.Brass,
            focusedTextColor = T.Ink, unfocusedTextColor = T.Ink)
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(56.dp))
            Text("T", style = T.Display.copy(fontSize = 64.sp, color = T.Brass))
            Text("THA'NA", style = T.Title.copy(letterSpacing = 4.sp))
            Text("SHITJE", style = T.Label.copy(letterSpacing = 6.sp, color = T.Brass))
            Spacer(Modifier.height(36.dp))
            Text("Ngjit linkun tënd personal që të është dërguar dhe PIN-in. Bëhet vetëm një herë.", style = T.Small, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(link, { link = it.trim(); err = null }, label = { Text("Linku personal") }, singleLine = true, colors = colors,
                shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6); err = null }, label = { Text("PIN") }, singleLine = true, colors = colors,
                shape = RoundedCornerShape(14.dp), visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
            err?.let { Text(it, color = T.Bad, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp).fillMaxWidth()) }
            Spacer(Modifier.height(20.dp))
            Button({
                busy = true; err = null
                scope.launch {
                    val e = withContext(Dispatchers.IO) { Panel.login(ctx, link, pin) }
                    busy = false; if (e == null) onDone() else err = e
                }
            }, enabled = !busy && link.isNotBlank() && pin.length >= 4, shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = T.Brass, contentColor = T.OnBrass),
                modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (busy) "Po hyn…" else "Hyr", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(16.dp))
            Text("Linkun dhe PIN-in i merr nga drejtori. Mos ia jep askujt.", style = T.Small)
        }
    }

    private fun clipLink(ctx: Context): String? = runCatching {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
            ?.takeIf { PanelLink.parse(it) != null }
    }.getOrNull()
}
