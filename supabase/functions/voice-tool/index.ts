// voice-tool · THA'NA Phone
// Mendja e bisedës me zë. Gemini Live = vetëm veshi dhe zëri; çdo pyetje vjen këtu dhe përgjigjet Claude.
// - Bisedë e lirë, këshilla, njohuri të përgjithshme: Claude direkt.
// - Informacion i freskët (lajme, kurse, çmime, ligje): Claude + web search.
// - Shifrat e kompanisë: vetëm nga THA'NA (owner-snapshot / brain-chat / lexim i drejtpërdrejtë), me të njëjtin sesion PWA.
// Asgjë nuk shkruhet në databazë.
import postgres from "npm:postgres@3.4.4";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL")!, { prepare: false, max: 2, idle_timeout: 20 });
const SB_URL = Deno.env.get("SUPABASE_URL")!;
const ANON = Deno.env.get("SUPABASE_ANON_KEY")!;
const AKEY = Deno.env.get("ANTHROPIC_API_KEY_INTERACTIVE") ?? Deno.env.get("ANTHROPIC_API_KEY") ?? "";
const MODEL = Deno.env.get("THANA_VOICE_THINK_MODEL") ?? "claude-sonnet-4-6";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-session-token",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (b: unknown, s = 200) => new Response(JSON.stringify(b), { status: s, headers: { ...cors, "Content-Type": "application/json" } });

const BLOCKED = "Truri THA'NA nuk e verifikoi dot këtë shifër tani (mbrojtja e të dhënave e ndaloi përgjigjen).";

/** Pyet Trurin THA'NA (brain-chat, SSE) me sesionin e pronarit. Sesion i ri për çdo pyetje. */
async function brain(token: string, q: string): Promise<string> {
  const r = await fetch(`${SB_URL}/functions/v1/brain-chat`, {
    method: "POST",
    headers: { apikey: ANON, Authorization: `Bearer ${ANON}`, "x-session-token": token, "Content-Type": "application/json", Accept: "text/event-stream" },
    body: JSON.stringify({ message: q, session_token: token, stream: true, client_request_id: crypto.randomUUID() }),
    signal: AbortSignal.timeout(60_000),
  }).catch(() => null);
  if (!r) return "Truri THA'NA nuk u përgjigj në kohë.";
  if (!r.ok) return `Truri THA'NA nuk u përgjigj (${r.status}).`;
  if ((r.headers.get("content-type") ?? "").includes("json")) {
    const j = await r.json().catch(() => ({}));
    return (j.reply ?? "").trim() || BLOCKED;
  }
  const text = await r.text();
  let ev = "", out = "", err = "";
  for (const line of text.split("\n")) {
    if (line.startsWith("event:")) ev = line.slice(6).trim();
    else if (line.startsWith("data:")) {
      try {
        const d = JSON.parse(line.slice(5).trim());
        if (ev === "text") out += d.text ?? "";
        if (ev === "error") err = d.message ?? d.error ?? "";
      } catch { /* */ }
    }
  }
  return out.trim() || (err ? `Truri THA'NA: ${err}` : BLOCKED);
}

/** Pamja e sotme nga owner-snapshot (vetëm lexim). */
async function snapshot(token: string): Promise<string> {
  const r = await fetch(`${SB_URL}/functions/v1/owner-snapshot`, {
    headers: { apikey: ANON, Authorization: `Bearer ${ANON}`, "x-session-token": token },
    signal: AbortSignal.timeout(20_000),
  }).catch(() => null);
  if (!r || !r.ok) return "Pamja e sotme nuk është e disponueshme tani.";
  const s = await r.json();
  return JSON.stringify({
    data_freshness: s.data_freshness, health: s.health,
    sales: { today: s.sales?.today, same_day_last_week: s.sales?.same_day_last_week_full, month_to_date: s.sales?.mtd,
             mtd_change_pct_vs_last_month: s.sales?.mtd_delta_pct, agents_today: (s.sales?.agents ?? []).slice(0, 7) },
    receivables: s.receivables, approvals_pending: s.approvals?.pending, low_stock_items: s.stock?.low_count,
    signals: (s.signals?.top ?? []).map((x: any) => x.title),
  });
}

/** Ecuria e një produkti/marke sipas peshës (formatit): nga 1 janari deri sot vs e njëjta periudhë vjet. Vetëm lexim. */
async function byWeight(tenantId: string | null, marka: string): Promise<string> {
  const pat = "%" + marka.trim().replace(/[%_]/g, "").replace(/\s+/g, "%") + "%";
  const rows = await sql.begin("read only", (tx) => tx`
    with d as (select (now() at time zone 'Europe/Tirane')::date t),
    x as (
      select k.kg_per_unit kg, k.l_per_unit l, extract(year from s.sale_date)::int = extract(year from d.t)::int cur,
             sum(s.total_value) v, sum(s.quantity * coalesce(k.kg_per_unit, k.l_per_unit)) w
      from sv_sales s join sku_classification k on k.sku_code = s.sku_code, d
      where k.product_name ilike ${pat} and coalesce(k.kategori_e_imet, '') <> 'SET' and coalesce(s.is_cancelled, false) = false
        and (${tenantId}::uuid is null or s.tenant_id = ${tenantId}::uuid)
        and ((s.sale_date between date_trunc('year', d.t)::date and d.t)
          or (s.sale_date between (date_trunc('year', d.t) - interval '1 year')::date and (d.t - interval '1 year')::date))
      group by 1, 2, 3)
    select coalesce(kg::text || ' kg', l::text || ' litër', 'pa peshë') formati,
           round(sum(v) filter (where not cur)) vlera_vjet, round(sum(v) filter (where cur)) vlera_sivjet,
           round(sum(w) filter (where not cur)) sasia_vjet, round(sum(w) filter (where cur)) sasia_sivjet,
           round(100 * (sum(v) filter (where cur) / nullif(sum(v) filter (where not cur), 0) - 1), 1) ndryshimi_pct
    from x group by kg, l order by coalesce(kg, l) nulls last`);
  if (!rows.length) return `Nuk gjeta shitje për "${marka}".`;
  return JSON.stringify({ periudha: "1 janar deri sot, sivjet kundrejt të njëjtës periudhë vjet; vlera në lekë me TVSH; sasia në kg ose litra; pa setet", formatet: rows });
}

function system(owner: string, tenant: string): string {
  const today = new Date().toLocaleDateString("sq-AL", { timeZone: "Europe/Tirane", weekday: "long", day: "numeric", month: "long", year: "numeric" });
  return [
    `Ti je THA'NA, partnerja e biznesit e ${owner} për ${tenant} (distributor detergjentesh dhe vajrash në Shqipëri dhe Kosovë). Sot është ${today}.`,
    "Përgjigjja jote do lexohet me zë në një bisedë live. Shkruaj si flet një shqiptare e lindur: e ngrohtë, e drejtpërdrejtë, e zgjuar, me humor të lehtë kur i shkon. Për veten përdor format femërore.",
    "FORMAT I DETYRUESHËM: vetëm tekst i folur. Asnjë markdown, titull, listë, tabelë, emoji, yll apo simbol. Shifrat me fjalë të lexueshme: '20,9 milion lekë', 'rreth 29 për qind'.",
    "Gjatësia: 1–3 fjali për pyetje të thjeshta; deri në 5–6 fjali kur kërkon analizë, këshillë ose strategji. Mbaro me një pyetje ose propozim vetëm kur ka kuptim.",
    "Je e lirë të japësh mendime, ide, këshilla, strategji dhe njohuri të përgjithshme nga mendja jote. Kur është mendim, thuaje si mendim.",
    "RREGULL I RREPTË: shifrat dhe faktet e kompanisë (shitje, borxhe, fitim, sasi, përqindje, renditje klientësh/agjentësh/produktesh) VETËM nga mjetet pamja_sot, produkt_sipas_peshes ose te_dhenat_thana. Kurrë mos i shpik dhe kurrë mos i merr nga web-i.",
    "pamja_sot për pyetje për sot/këtë muaj (e shpejtë). produkt_sipas_peshes për ecurinë e një marke sipas peshës/formatit kundrejt vitit të kaluar (e shpejtë; thuaj cilat formate bien më shumë dhe cilat rriten). te_dhenat_thana për analiza (krahasime, periudha, pse, klientë, produkte); jepi pyetjen qartë në shqip me periudhën e plotë.",
    `Nëse mjeti thotë që nuk e verifikoi dot, thuaje sinqerisht me një fjali dhe propozo një pyetje më të ngushtë (p.sh. vetëm një produkt ose një muaj). Mos e provo të njëjtën gjë më shumë se një herë.`,
    "web_search vetëm për informacion të freskët jashtë kompanisë (lajme, kurse këmbimi, çmime tregu, ligje, taksa, moti, konkurrentë) dhe thuaj burimin shkurt.",
  ].join("\n");
}

const tools = [
  { name: "pamja_sot", description: "Pamja e sotme e biznesit nga THA'NA OS: shitjet sot/muaji, shëndeti, borxhet, aprovimet, agjentët sot, sinjalet.", input_schema: { type: "object", properties: {} } },
  { name: "te_dhenat_thana", description: "Pyetje analitike te Truri THA'NA mbi të dhënat reale të kompanisë (shitje, klientë, produkte, borxhe, agjentë, rajone, periudha, krahasime).", input_schema: { type: "object", properties: { pyetja: { type: "string" } }, required: ["pyetja"] } },
  { name: "produkt_sipas_peshes", description: "Ecuria e një marke/produkti sipas formatit (peshës kg ose litrave), nga 1 janari deri sot krahasuar me të njëjtën periudhë vitin e kaluar: vlera, sasia, ndryshimi %. Shembull: marka 'Deka No Comment'. E shpejtë dhe e saktë; përdore për çdo pyetje 'cila peshë/format bie ose rritet'.", input_schema: { type: "object", properties: { marka: { type: "string" } }, required: ["marka"] } },
  { type: "web_search_20250305", name: "web_search", max_uses: 2 },
];

async function think(token: string, tenantId: string | null, owner: string, tenant: string, question: string, history: Array<{ role: string; text: string }>): Promise<string> {
  const messages: any[] = [];
  for (const h of history.slice(-12)) {
    const role = h.role === "user" ? "user" : "assistant";
    if (!h.text?.trim()) continue;
    if (messages.length && messages[messages.length - 1].role === role) messages[messages.length - 1].content += "\n" + h.text;
    else messages.push({ role, content: h.text });
  }
  if (messages.length && messages[0].role !== "user") messages.shift();
  if (messages.length && messages[messages.length - 1].role === "user") messages.pop();   // pyetja aktuale shtohet më poshtë
  messages.push({ role: "user", content: question });

  for (let step = 0; step < 6; step++) {
    const r = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "x-api-key": AKEY, "anthropic-version": "2023-06-01", "content-type": "application/json" },
      body: JSON.stringify({ model: MODEL, max_tokens: 700, system: system(owner, tenant), tools, messages }),
      signal: AbortSignal.timeout(60_000),
    }).catch(() => null);
    if (!r) return "Më fal, nuk po lidhem dot tani. Provo përsëri pas pak.";
    const j = await r.json().catch(() => ({}));
    if (!r.ok) { console.error("voice-tool anthropic", r.status, JSON.stringify(j).slice(0, 300)); return "Më fal, pata një problem teknik. Provo përsëri."; }
    messages.push({ role: "assistant", content: j.content });
    if (j.stop_reason === "pause_turn") continue;                       // web search vazhdon
    if (j.stop_reason !== "tool_use") {
      return (j.content ?? []).filter((b: any) => b.type === "text").map((b: any) => b.text).join(" ")
        .replace(/[*#_`>|]/g, " ").replace(/\s+/g, " ").trim() || "Më fal, nuk kam përgjigje për këtë.";
    }
    const results = [];
    for (const b of j.content ?? []) {
      if (b.type !== "tool_use") continue;
      const out = b.name === "pamja_sot" ? await snapshot(token)
        : b.name === "produkt_sipas_peshes" ? await byWeight(tenantId, String(b.input?.marka ?? ""))
        : b.name === "te_dhenat_thana" ? await brain(token, `${b.input?.pyetja ?? question}\n\n(Pyetje nga biseda me zë: përgjigju shkurt, pa tabela, vetëm shifrat kryesore.)`)
        : "Mjet i panjohur.";
      results.push({ type: "tool_result", tool_use_id: b.id, content: out });
    }
    messages.push({ role: "user", content: results });
  }
  return "Më fal, kjo po zgjat shumë. Provo ta pyesësh më shkurt.";
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  const token = req.headers.get("x-session-token") ?? "";
  if (!token) return json({ error: "Mungon sesioni." }, 401);
  if (!AKEY) return json({ result: "Mendja e zërit nuk është konfiguruar." });
  try {
    const [who] = await sql.begin("read only", (tx) => tx`
      select u.full_name, u.role, coalesce(s.tenant_id, u.tenant_id)::text as tenant_id, coalesce(t.display_name, t.legal_name) as tenant
      from app_sessions s join app_users u on u.id = s.user_id
      left join sv_tenants t on t.id = coalesce(s.tenant_id, u.tenant_id)
      where lower(s.token) = lower(${token}) and s.expires_at > now() and coalesce(u.is_active, true)
      limit 1`);
    if (!who || !["director", "admin"].includes(who.role)) return json({ error: "Sesion i pavlefshëm ose rol i pamjaftueshëm." }, 403);

    const body = await req.json().catch(() => ({}));
    const name = String(body.name ?? "");
    const args = body.args ?? {};
    const history = Array.isArray(body.history) ? body.history : [];
    const owner = who.full_name ?? "pronari", tenant = who.tenant ?? "biznesin";

    let result: string;
    if (name === "thana_mendo") result = await think(token, who.tenant_id ?? null, owner, tenant, String(args.pyetja ?? ""), history);
    else if (name === "thana_panorama") result = await snapshot(token);
    else if (name === "thana_pyet") result = await brain(token, String(args.pyetja ?? ""));
    else result = "Mjet i panjohur.";
    return json({ result });
  } catch (e) {
    console.error("voice-tool", e);
    return json({ result: "Më fal, pata një problem teknik. Provo përsëri." });
  }
});
