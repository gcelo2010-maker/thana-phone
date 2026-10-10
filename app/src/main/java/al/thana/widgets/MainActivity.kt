package al.thana.widgets

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

enum class Tab(val label: String, val icon: ImageVector, val key: String) {
    Sot("Sot", Icons.Filled.Home, "sot"),
    Financa("Financa", Icons.Filled.DateRange, "financa"),
    Shitje("Shitje", Icons.Filled.ShoppingCart, "shitje"),
    Aprovime("Aprovime", Icons.Filled.CheckCircle, "aprovime"),
    AI("THA'NA AI", Icons.Filled.Star, "ai"),
    Cilesime("Cilësime", Icons.Filled.Settings, "cilesime");
    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } }
}

class MainActivity : ComponentActivity() {
    private val requestedTab = mutableStateOf<Tab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedTab.value = Tab.of(intent?.getStringExtra("tab"))
        setContent { ThanaTheme { App(requestedTab) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Tab.of(intent.getStringExtra("tab"))?.let { requestedTab.value = it }
    }
}

// ---------------------------------------------------------------- app shell

@Composable
fun App(requestedTab: MutableState<Tab?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var logged by remember { mutableStateOf(Repo.token(ctx) != null) }
    var snap by remember { mutableStateOf(Repo.cached(ctx)) }
    var refreshing by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(Tab.Sot) }

    LaunchedEffect(requestedTab.value) { requestedTab.value?.let { tab = it; requestedTab.value = null } }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { Repo.fetch(ctx) }
            snap = Repo.cached(ctx)
            banner = when (r) {
                Repo.Fetch.OK -> null
                Repo.Fetch.NETWORK -> "Pa lidhje. Po shfaqen të dhënat e fundit të ruajtura."
                Repo.Fetch.AUTH, Repo.Fetch.NO_SESSION -> { logged = false; null }
            }
            withContext(Dispatchers.IO) { Widgets.updateAll(ctx) }
            refreshing = false
        }
    }

    LaunchedEffect(logged) { if (logged) { Sync.schedule(ctx); refresh() } }

    Surface(Modifier.fillMaxSize(), color = T.Bg) {
        if (!logged) {
            LoginScreen { logged = true }
            return@Surface
        }
        val wide = LocalConfiguration.current.screenWidthDp >= 600
        val logout = {
            Repo.logout(ctx); logged = false; snap = null
            scope.launch(Dispatchers.IO) { Widgets.updateAll(ctx) }
            Unit
        }
        val content: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                banner?.let { Banner(it) }
                when (tab) {
                    Tab.Sot -> TodayScreen(snap, refreshing, ::refresh, { tab = it })
                    Tab.Financa -> FinanceScreen(snap)
                    Tab.Shitje -> SalesScreen(snap)
                    Tab.Aprovime -> ApprovalsScreen(snap) { refresh() }
                    Tab.AI -> AiScreen()
                    Tab.Cilesime -> SettingsScreen(Repo.ownerName(ctx), ::refresh, logout)
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().systemBarsPadding()) {
                SideNav(tab, snap?.pending) { tab = it }
                content(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                content(Modifier.weight(1f))
                BottomNav(tab) { tab = it }
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    Text(text, style = T.Small.copy(color = T.Warn), modifier = Modifier.fillMaxWidth().background(T.Raised).padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun BottomNav(current: Tab, onPick: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(T.Bg).border(1.dp, T.Border).padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceAround) {
        listOf(Tab.Sot, Tab.Financa, Tab.Shitje, Tab.Aprovime, Tab.AI).forEach { t ->
            val on = t == current
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPick(t) }.padding(horizontal = 10.dp, vertical = 6.dp).heightIn(min = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, t.label, tint = if (on) T.Brass else T.Muted, modifier = Modifier.size(22.dp))
                Text(if (t == Tab.AI) "AI" else t.label, fontSize = 11.sp, color = if (on) T.Brass else T.Muted)
            }
        }
    }
}

@Composable
private fun SideNav(current: Tab, pending: Int?, onPick: (Tab) -> Unit) {
    Column(Modifier.width(232.dp).fillMaxHeight().background(T.Surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp, top = 8.dp)) {
            Text("T", style = T.Title.copy(color = T.Brass)); Spacer(Modifier.width(10.dp)); Text("THA'NA", style = T.Body.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp))
        }
        Tab.entries.forEach { t ->
            val on = t == current
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) T.Brass.copy(alpha = 0.16f) else Color.Transparent).clickable { onPick(t) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, null, tint = if (on) T.Brass else T.Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(t.label, color = if (on) T.Ink else T.Muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (t == Tab.Aprovime && (pending ?: 0) > 0) Pill("$pending", T.Bad)
            }
        }
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
fun CardBox(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(T.Surface).border(1.dp, T.Border, RoundedCornerShape(24.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content,
    )
}

@Composable fun Lbl(t: String) = Text(t.uppercase(), style = T.Label)

@Composable
fun Delta(v: Double?, suffix: String = "") {
    if (v == null) { Text("—", style = T.Small); return }
    Text((if (v >= 0) "▲ " else "▼ ") + Money.pct(v) + suffix, style = T.Small.copy(color = if (v >= 0) T.Good else T.Bad, fontWeight = FontWeight.SemiBold))
}

@Composable
fun Pill(t: String, c: Color) =
    Text(t, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(c.copy(alpha = 0.15f)).padding(horizontal = 9.dp, vertical = 3.dp))

private fun hhmm(iso: String?) = iso?.let { runCatching {
    OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).toLocalTime().toString().take(5)
}.getOrNull() } ?: "—"

@Composable
fun Header(title: String, sub: String?, refreshing: Boolean = false, onRefresh: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.Title)
            sub?.let { Text(it, style = T.Small) }
        }
        if (onRefresh != null) {
            if (refreshing) CircularProgressIndicator(Modifier.size(22.dp), color = T.Brass, strokeWidth = 2.dp)
            else IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Refresh, "Rifresko", tint = T.Muted) }
        }
    }
}

@Composable
fun Empty(text: String) = CardBox(Modifier.fillMaxWidth()) { Text(text, style = T.Small) }

// ---------------------------------------------------------------- login

@Composable
fun LoginScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp).widthIn(max = 420.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Text("T", style = T.Display.copy(fontSize = 64.sp, color = T.Brass))
        Text("THA'NA", style = T.Title.copy(letterSpacing = 4.sp))
        Text("Inteligjencë për biznese reale", style = T.Small)
        Spacer(Modifier.height(40.dp))
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = T.Brass, unfocusedBorderColor = T.Border, focusedContainerColor = T.Surface,
            unfocusedContainerColor = T.Surface, cursorColor = T.Brass, focusedLabelColor = T.Brass,
        )
        OutlinedTextField(user, { user = it }, label = { Text("Emri i përdoruesit") }, singleLine = true,
            colors = fieldColors, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit) }, label = { Text("PIN") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            colors = fieldColors, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        err?.let { Text(it, color = T.Bad, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp).fillMaxWidth()) }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                busy = true; err = null
                scope.launch {
                    val e = withContext(Dispatchers.IO) { Repo.login(ctx, user, pin) }
                    busy = false
                    if (e == null) onDone() else err = e
                }
            },
            enabled = !busy && user.isNotBlank() && pin.length >= 4,
            colors = ButtonDefaults.buttonColors(containerColor = T.Brass, contentColor = T.OnBrass),
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (busy) "Duke hyrë…" else "Hyr", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(16.dp))
        Text("Vetëm për drejtorë dhe administratorë", style = T.Small)
    }
}

// ---------------------------------------------------------------- Sot

private fun greeting() = when (LocalTime.now().hour) { in 5..11 -> "Mirëmëngjes"; in 12..17 -> "Mirëdita"; else -> "Mirëmbrëma" }

@Composable
fun TodayScreen(s: Snapshot?, refreshing: Boolean, onRefresh: () -> Unit, go: (Tab) -> Unit) {
    val first = s?.owner?.substringBefore(' ')
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("${greeting()}${first?.let { ", $it" } ?: ""}", "Përditësuar ${hhmm(s?.generatedAt)}", refreshing, onRefresh)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardBox(Modifier.fillMaxWidth(), onClick = { go(Tab.Shitje) }) {
                Lbl("Shitjet sot")
                Text(Money.full(s?.salesToday), style = T.Display)
                Delta(s?.salesDelta, " vs e njëjta ditë javën e kaluar")
                if (!s?.series.isNullOrEmpty()) TrendChart(s!!.series, Modifier.fillMaxWidth().height(72.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CardBox(Modifier.weight(1f)) {
                    Lbl("Shëndeti i biznesit")
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { HealthRing(s?.health) }
                }
                CardBox(Modifier.weight(1f), onClick = { go(Tab.Financa) }) {
                    Lbl("Borxh i vonuar")
                    Text(s?.overduePct?.let { Money.pct(it, false) } ?: "—", style = T.Display.copy(fontSize = 32.sp))
                    Text("${s?.overdueCustomers ?: "—"} klientë", style = T.Small)
                    Text("Paguajnë pas ${s?.dso?.toInt() ?: "—"} ditësh", style = T.Small.copy(color = T.Warn))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CardBox(Modifier.weight(1f), onClick = { go(Tab.Shitje) }) {
                    Lbl("Muaji")
                    Text(Money.compact(s?.mtd), style = T.Title)
                    Delta(s?.mtdDelta)
                }
                CardBox(Modifier.weight(1f), onClick = { go(Tab.Aprovime) }) {
                    Lbl("Aprovime")
                    Text(s?.pending?.toString() ?: "—", style = T.Title.copy(color = T.Brass))
                    Text("${s?.approvals?.count { it.priority == "critical" } ?: "—"} kritike", style = T.Small.copy(color = T.Bad))
                }
            }
            if (!s?.signals.isNullOrEmpty()) CardBox(Modifier.fillMaxWidth()) {
                Lbl("Sinjale · 7 ditë: ${s?.signals7d ?: "—"}")
                s!!.signals.forEach { g ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(if (g.severity == "critical") T.Bad else if (g.severity == "warn") T.Warn else T.Muted))
                        Spacer(Modifier.width(10.dp)); Text(g.title, style = T.Body.copy(fontSize = 14.sp))
                    }
                }
            }
            if (s == null) Empty("Ende pa të dhëna. Tërhiq rifreskimin lart djathtas.")
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---------------------------------------------------------------- Financa

@Composable
fun FinanceScreen(s: Snapshot?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Financa", "Borxhet: ${s?.debtsFreshness?.take(10) ?: "—"}")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardBox(Modifier.fillMaxWidth()) {
                Lbl("Të arkëtueshme")
                Text(Money.full(s?.recvTotal), style = T.Display.copy(fontSize = 34.sp))
                val p = ((s?.overduePct ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(T.Border)) {
                    Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(T.Bad))
                }
                Text("${s?.overduePct?.let { Money.pct(it, false) } ?: "—"} me afat të kaluar · ${Money.compact(s?.overdue)}", style = T.Small)
            }
            CardBox(Modifier.fillMaxWidth()) {
                Lbl("Ditët mesatare të pagesës (DSO)")
                Text("${s?.dso?.toInt() ?: "—"} ditë", style = T.Display.copy(fontSize = 34.sp, color = if ((s?.dso ?: 0.0) > (s?.dsoTarget ?: 45.0)) T.Warn else T.Ink))
                Text("Synimi: ${s?.dsoTarget?.toInt() ?: "—"} ditë", style = T.Small)
            }
            CardBox(Modifier.fillMaxWidth()) {
                Lbl("Top klientë me borxh të vonuar")
                if (s?.debtors.isNullOrEmpty()) Text("—", style = T.Small)
                s?.debtors?.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = T.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Pagesa e fundit: ${d.lastPayment ?: "—"}", style = T.Small)
                        }
                        Text(Money.full(d.amount), style = T.Num)
                    }
                }
            }
            CardBox(Modifier.fillMaxWidth()) {
                Lbl("Cash flow dhe detyrimet")
                Text("Do shfaqen kur backend-i t'i ekspozojë. Nuk shfaqim shifra të pa-verifikuara.", style = T.Small)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---------------------------------------------------------------- Shitje

@Composable
fun SalesScreen(s: Snapshot?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Shitje", "Të dhënat deri më ${s?.salesDate ?: "—"}")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardBox(Modifier.fillMaxWidth()) {
                Lbl("Shitjet këtë muaj")
                Text(Money.full(s?.mtd), style = T.Display.copy(fontSize = 34.sp))
                Delta(s?.mtdDelta, " vs e njëjta periudhë muajin e kaluar")
            }
            CardBox(Modifier.fillMaxWidth()) {
                Lbl("Shitjet ditore · 14 ditë")
                if (s?.series.isNullOrEmpty()) Text("—", style = T.Small)
                else {
                    Bars(s!!.series, Modifier.fillMaxWidth().height(140.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(s.series.first().day.drop(5), style = T.Small); Text(s.series.last().day.drop(5), style = T.Small)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CardBox(Modifier.weight(1f)) { Lbl("Sot"); Text(Money.compact(s?.salesToday), style = T.Title); Delta(s?.salesDelta) }
                CardBox(Modifier.weight(1f)) { Lbl("Agjentë që shitën sot"); Text(s?.agentsToday?.toString() ?: "—", style = T.Title) }
            }
            CardBox(Modifier.fillMaxWidth()) {
                Lbl("Rajone, kanale, agjentë")
                Text("Vijnë në versionin tjetër, kur t'i shtojmë te owner-snapshot.", style = T.Small)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---------------------------------------------------------------- Aprovime

private fun prioColor(p: String) = when (p) { "critical" -> T.Bad; "high" -> T.Warn; "medium" -> T.Brass; else -> T.Muted }
private fun prioLabel(p: String) = when (p) { "critical" -> "Kritike"; "high" -> "E lartë"; "medium" -> "Mesatare"; else -> "E ulët" }

@Composable
fun ApprovalsScreen(s: Snapshot?, afterDecision: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("all") }
    var open by remember { mutableStateOf<Approval?>(null) }
    val done = remember { mutableStateListOf<String>() }
    val list = (s?.approvals ?: emptyList()).filter { it.id !in done }
        .filter { filter == "all" || it.priority == filter }

    Column(Modifier.fillMaxSize()) {
        Header("Aprovime", "${s?.pending ?: "—"} në pritje")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "Të gjitha", "critical" to "Kritike", "high" to "Të larta").forEach { (k, l) ->
                FilterChip(filter == k, { filter = k }, { Text(l) }, colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = T.Brass.copy(alpha = 0.18f), selectedLabelColor = T.Brass, labelColor = T.Muted, containerColor = T.Surface))
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (list.isEmpty()) item { Empty("Asgjë në pritje në këtë filtër.") }
            items(list, key = { it.id }) { a ->
                CardBox(Modifier.fillMaxWidth(), onClick = { open = a }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.customer ?: a.type.replace('_', ' '), style = T.Body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Pill(prioLabel(a.priority), prioColor(a.priority))
                    }
                    Text(a.reason, style = T.Small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if ((s?.pending ?: 0) > (s?.approvals?.size ?: 0)) item {
                Text("Shfaqen ${s?.approvals?.size} më të rëndësishmet nga ${s?.pending}.", style = T.Small, modifier = Modifier.padding(8.dp))
            }
        }
    }

    open?.let { a ->
        var note by remember(a.id) { mutableStateOf("") }
        var confirm by remember(a.id) { mutableStateOf<Boolean?>(null) }   // true = aprovo, false = refuzo
        var busy by remember(a.id) { mutableStateOf(false) }
        var result by remember(a.id) { mutableStateOf<Repo.Result?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) open = null },
            containerColor = T.Surface,
            title = { Text(a.customer ?: "Veprim", style = T.Title.copy(fontSize = 22.sp)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row { Pill(prioLabel(a.priority), prioColor(a.priority)); Spacer(Modifier.width(8.dp)); Text(a.type.replace('_', ' '), style = T.Small) }
                    Text(a.reason, style = T.Body)
                    a.value?.let { Text("Vlera: ${Money.full(it)}", style = T.Num) }
                    if (result == null) OutlinedTextField(note, { note = it }, label = { Text("Shënim (opsional)") }, modifier = Modifier.fillMaxWidth())
                    result?.let { Text(it.message, color = if (it.ok) T.Good else T.Bad, fontWeight = FontWeight.SemiBold) }
                    if (confirm != null && result == null)
                        Text(if (confirm == true) "Konfirmo aprovimin. Backend-i do ta ekzekutojë veprimin." else "Konfirmo refuzimin.", style = T.Small.copy(color = T.Warn))
                }
            },
            confirmButton = {
                when {
                    result != null -> TextButton({ open = null; if (result?.ok == true) { done += a.id; afterDecision() } }) { Text("Mbyll", color = T.Brass) }
                    confirm == null -> Button({ confirm = true }, colors = ButtonDefaults.buttonColors(containerColor = T.Good, contentColor = T.OnBrass)) { Text("Aprovo") }
                    else -> Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                result = withContext(Dispatchers.IO) { Repo.decide(ctx, a.id, confirm == true, note) }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = if (confirm == true) T.Good else T.Bad, contentColor = T.OnBrass),
                    ) { Text(if (busy) "Duke dërguar…" else "Po, konfirmo") }
                }
            },
            dismissButton = {
                if (result == null) {
                    if (confirm == null) OutlinedButton({ confirm = false }) { Text("Refuzo", color = T.Ink) }
                    else TextButton({ confirm = null }, enabled = !busy) { Text("Kthehu", color = T.Muted) }
                }
            },
        )
    }
}

// ---------------------------------------------------------------- THA'NA AI

data class Msg(val mine: Boolean, val text: String)

@Composable
fun AiScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgs = remember { mutableStateListOf<Msg>() }
    var input by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun send(q: String) {
        if (q.isBlank() || busy) return
        msgs += Msg(true, q); msgs += Msg(false, "…"); input = ""; busy = true
        val idx = msgs.lastIndex
        scope.launch {
            session = withContext(Dispatchers.IO) {
                Repo.ask(ctx, q, session) { t -> scope.launch { msgs[idx] = Msg(false, t) } }
            }
            busy = false
        }
    }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Header("THA'NA AI", "Pyet për shitjet, borxhet, klientët")
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (msgs.isEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Si po ecën biznesi sot?", "Kush më ka borxh më shumë?", "Çfarë duhet të aprovoj sot?").forEach { q ->
                        AssistChip({ send(q) }, { Text(q) }, colors = AssistChipDefaults.assistChipColors(labelColor = T.Ink, containerColor = T.Surface))
                    }
                }
            }
            items(msgs) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(m.text, style = T.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        modifier = Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(18.dp))
                            .background(if (m.mine) T.Raised else T.Surface).border(1.dp, T.Border, RoundedCornerShape(18.dp)).padding(12.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, placeholder = { Text("Pyet diçka…") }, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = T.Brass, unfocusedBorderColor = T.Border,
                    focusedContainerColor = T.Surface, unfocusedContainerColor = T.Surface))
            Spacer(Modifier.width(8.dp))
            FilledIconButton({ send(input) }, enabled = !busy && input.isNotBlank(), modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = T.Brass, contentColor = T.OnBrass)) {
                Icon(Icons.Filled.Send, "Dërgo")
            }
        }
    }
}

// ---------------------------------------------------------------- Cilësime

private fun homeAlias(ctx: Context) = ComponentName(ctx, "al.thana.widgets.HomeAlias")

@Composable
fun SettingsScreen(name: String?, onRefresh: () -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    var asHome by remember {
        mutableStateOf(ctx.packageManager.getComponentEnabledSetting(homeAlias(ctx)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    }
    var askLogout by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Cilësime", name)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardBox(Modifier.fillMaxWidth()) {
                Lbl("Llogaria"); Text(name ?: "—", style = T.Body)
                Text("Sesioni vlen 30 ditë dhe ruhet i enkriptuar në Android Keystore.", style = T.Small)
            }
            CardBox(Modifier.fillMaxWidth(), onClick = onRefresh) {
                Lbl("Sinkronizimi"); Text("Rifresko tani", style = T.Body.copy(color = T.Brass))
                Text("Widget-et rifreskohen vetë rreth çdo 15 min. Android mund ta vonojë kur bateria kursehet.", style = T.Small)
            }
            CardBox(Modifier.fillMaxWidth()) {
                Lbl("Pajisje e dedikuar")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("THA'NA si ekran kryesor (Home)", style = T.Body, modifier = Modifier.weight(1f))
                    Switch(asHome, { on ->
                        ctx.packageManager.setComponentEnabledSetting(homeAlias(ctx),
                            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP)
                        asHome = on
                        if (on) ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }, colors = SwitchDefaults.colors(checkedThumbColor = T.OnBrass, checkedTrackColor = T.Brass))
                }
                Text("Pas aktivizimit zgjidh THA'NA te 'Home app'. Për ta kthyer, fike këtu dhe zgjidh launcher-in e vjetër.", style = T.Small)
            }
            CardBox(Modifier.fillMaxWidth()) {
                Lbl("Widget-et")
                Text("Shtyp gjatë mbi home screen → Widgets → THA'NA: Shitjet sot, Shëndeti, Të arkëtueshme, Aprovime, Sinjale, Shitjet 14 ditë, Pyet THA'NA.", style = T.Small)
            }
            CardBox(Modifier.fillMaxWidth()) {
                Lbl("Rreth THA'NA"); Text("THA'NA Phone ${BuildConfig.VERSION_NAME}", style = T.Body)
                Text("Android vetëm shfaq dhe kërkon. Shifrat, lejet dhe vendimet i konfirmon THA'NA OS.", style = T.Small)
            }
            OutlinedButton({ askLogout = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                Text("Dil nga llogaria", color = T.Bad)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (askLogout) AlertDialog(
        onDismissRequest = { askLogout = false }, containerColor = T.Surface,
        title = { Text("Dil nga THA'NA?") }, text = { Text("Widget-et do fshijnë të dhënat derisa të hysh përsëri.") },
        confirmButton = { TextButton({ askLogout = false; onLogout() }) { Text("Dil", color = T.Bad) } },
        dismissButton = { TextButton({ askLogout = false }) { Text("Anulo", color = T.Muted) } },
    )
}
