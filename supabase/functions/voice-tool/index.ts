// voice-tool · THA'NA Phone — mendja e bisedës me zë (Claude).
// Zëri (GPT Realtime / Gemini Live) vetëm dëgjon dhe flet; çdo pyetje e vërtetë vjen këtu.
// Claude ka: historinë e bisedës, lexim LIVE të databazës vetëm për tenant-in e përdoruesit,
// pamjen e sotme, Trurin THA'NA dhe kërkim në web. Asgjë nuk shkruhet në databazë.
import postgres from "npm:postgres@3.4.4";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL")!, { prepare: false, max: 3, idle_timeout: 20 });
const SB_URL = Deno.env.get("SUPABASE_URL")!;
const ANON = Deno.env.get("SUPABASE_ANON_KEY")!;
const AKEY = Deno.env.get("ANTHROPIC_API_KEY_INTERACTIVE") ?? Deno.env.get("ANTHROPIC_API_KEY") ?? "";
const MODELS = (Deno.env.get("THANA_VOICE_THINK_MODEL") ?? "claude-sonnet-5-5,claude-sonnet-4-6").split(",");

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-session-token",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (b: unknown, s = 200) => new Response(JSON.stringify(b), { status: s, headers: { ...cors, "Content-Type": "application/json" } });

// ---------- Lexim live i databazës, i izoluar për tenant-in ----------
// Për çdo pyetje: pamje të përkohshme (pg_temp) të filtruara me tenant_id, search_path = vetëm pg_temp,
// transaksion READ ONLY, një deklaratë e vetme, kohë maksimale 15 s, maksimumi 200 rreshta.
const TABLES: Record<string, string> = {
  sv_sales: "shitjet sipas rreshtit të faturës: sale_date, invoice_id, customer_code, salesperson_code, sku_code, quantity, unit_price, discount_value, vat_value, net_value (pa TVSH), total_value (me TVSH), is_cancelled (përjashtoji gjithmonë: coalesce(is_cancelled,false)=false), is_promotion, magazina_id, year, month",
  sv_customers: "klientët: customer_code, customer_name, trade_name, city, category, salesperson_code, tier, is_chain, chain_id, net_days, max_bilanci, is_suspended, is_pasive",
  sv_agents: "agjentët: employee_code, full_name, zone_name, is_active, is_test, sadn_force_shitese_id (= sv_sales.salesperson_code)",
  sku_classification: "produktet me peshë: sku_code, product_name, kg_per_unit, l_per_unit, kategori_e_imet ('SET' = sete), cmim, kosto, marzh_pct",
  sv_products: "katalogu: sku_code, product_name, brand, familja, linja, category, product_group, pesha_kg, unit_price, is_active",
  sv_ar_debts: "borxhet e klientëve (snapshot): customer_code, customer_name, zona, agent_name, debi_maturuar (me afat të kaluar), debi_pamaturuar, debi_total, last_invoice_date, last_payment_date, imported_at",
  v_ar_aging_current: "borxhet sipas faturës: customer_name, customer_code, city, agent_name, zone_name, invoice_number, invoice_date, due_date, balance, days_overdue, aging_bucket, snapshot_date",
  sv_ar_payments: "arkëtimet: payment_date, customer_name, amount, currency, bank",
  sv_orders: "porositë: order_number, order_date, client_code, customer_name, agent_code, status, total_net_price, total_value, delivery_date",
  sv_order_items: "rreshtat e porosive: order_id, sku_code, qty_ordered, qty_delivered, price_net, discount_pct",
  sv_contracts: "kontratat: client_code, off_invoice_pct, bonus_payment_pct, payment_days, target_monthly_leke, contract_start, contract_end, is_active",
  sv_offers: "ofertat: offer_name, sku_code, discount_pct, bonus_qty, valid_from, valid_to, is_active",
  sv_price_history: "çmimet reale: snapshot_date, sku_code, product_name, stored_list_price, real_price_avg, discrepancy_pct, total_volume",
  v_product_margin_real_v3: "marzhi 90 ditë: sku_code, product_name, brand, revenue_net_90d, qty_90d, margin_historical_pct_90d, margin_replacement_pct_90d",
  sv_visit_log: "vizitat: agent_code, customer_code, visit_date, had_sale, sale_value, duration_min",
  sv_zones: "zonat: zone_code, zone_name, region",
  sv_warehouses: "magazinat: magazina_id, display_name_sq, brand",
  wms_inventory: "stoku: warehouse_id, product_id, quantity, reserved_qty, min_stock, snapshot_date",
  fact_monthly_targets: "objektivat mujorë: year, month, salesperson_id, zone_id, category, target_value",
  fin_pnl_monthly: "fitim-humbje mujore: period_year, period_month, line_item, category, amount_all",
  fin_cashflow: "lëvizjet e parasë: date, type, category, amount",
  fin_expenses: "shpenzimet: date, category, description, amount",
};
const BAD = /;|\b(public|extensions|auth|storage|vault|net|cron|graphql|realtime|supabase_\w+|pg_\w+|information_schema)\s*\.|\b(set_config|pg_sleep|dblink|lo_\w+|copy|http\w*|current_setting)\s*\(/i;

async function dbQuery(tenantId: string, query: string): Promise<string> {
  const q = query.trim().replace(/;+\s*$/, "");
  if (!/^(select|with)\b/i.test(q) || BAD.test(q)) return "Refuzuar: vetëm një SELECT i thjeshtë mbi tabelat e lejuara, pa skema dhe pa funksione sistemi.";
  if (!/^[0-9a-f-]{36}$/i.test(tenantId)) return "Tenant i panjohur.";
  const c = await sql.reserve();
  try {
    for (const t of Object.keys(TABLES)) {
      await c.unsafe(`create temp view ${t} as select * from public.${t} where tenant_id = '${tenantId}'::uuid`);
    }
    await c.unsafe("begin transaction read only");
    let rows: any[];
    try {
      await c.unsafe("set local search_path = pg_temp");
      await c.unsafe("set local statement_timeout = '15s'");
      rows = await c.unsafe(`select * from (${q}) _q limit 200`);
    } finally {
      await c.unsafe("rollback").catch(() => {});
    }
    const out = JSON.stringify(rows);
    return rows.length === 0 ? "0 rreshta." : out.length > 12000 ? out.slice(0, 12000) + " …(e prerë)" : out;
  } catch (e) {
    return "Gabim SQL: " + String((e as Error).message ?? e).slice(0, 300);
  } finally {
    await c.unsafe("discard temp").catch(() => {});
    c.release();
  }
}

/** Pyet Trurin THA'NA (brain-chat, SSE) me sesionin e pronarit. */
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
    return (j.reply ?? "").trim() || "Truri THA'NA nuk e verifikoi dot (bllokuar nga Pakti). Përdor lexim_db.";
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
  return out.trim() || (err ? `Truri THA'NA: ${err}` : "Truri THA'NA nuk e verifikoi dot (bllokuar nga Pakti). Përdor lexim_db.");
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

function system(owner: string, tenant: string): string {
  const now = new Date();
  const today = now.toLocaleDateString("sq-AL", { timeZone: "Europe/Tirane", weekday: "long", day: "numeric", month: "long", year: "numeric" });
  const iso = now.toLocaleDateString("en-CA", { timeZone: "Europe/Tirane" });
  return [
    `Ti je THA'NA, partnerja e biznesit e ${owner} për ${tenant}. Sot është ${today} (${iso}). Je e zgjuar, kurioze, e drejtpërdrejtë dhe e ngrohtë, si një bashkëpunëtore e afërt që e njeh mirë biznesin dhe tregun shqiptar.`,
    "Kjo është një bisedë live me zë që vazhdon: mesazhet e mëparshme janë biseda juaj (edhe nga ditët e kaluara). Mbaje kontekstin: 'po', 'vazhdo', 'pse?', 'po Elbasani?', 'ajo që the' lidhen me sa u tha më parë. Mos thuaj kurrë që nuk e mban mend.",
    "Bashkëpunim fluid: lidh shifrat me idetë dhe me atë që ndodh jashtë (tregu, çmimet, konkurrentët, lajmet). Jep mendimin tënd, propozo hapin e radhës, bëj një pyetje kur ndihmon. Bisedë e lirë për çdo temë, edhe jashtë biznesit.",
    "FORMAT: tekst që lexohet me zë. Asnjë markdown, listë, tabelë, emoji apo simbol. Numrat të rrumbullakuar dhe të lexueshëm: '20,9 milion lekë', 'rreth 26 për qind'. Gjatësia: 1–3 fjali për gjëra të thjeshta, 4–7 për analiza; pa përsëritje.",
    "SHIFRAT E KOMPANISË: vetëm nga mjetet, kurrë nga kujtesa apo web-i. Për çdo pyetje me të dhëna përdor lexim_db (SQL live, i sakti dhe i shpejti). pamja_sot për një përmbledhje të sotme. te_dhenat_thana vetëm kur të duhet logjika e Trurit THA'NA (parashikime, sinjale).",
    "lexim_db: shkruaj një SELECT të vetëm (pa ';', pa emra skemash). Mund të bësh disa pyetje radhazi për të kuptuar më thellë. Kur krahason periudha, krahaso periudha të barabarta (p.sh. 1 janar deri sot kundrejt 1 janar deri e njëjta ditë vjet). Shitjet: përdor total_value (me TVSH) ose net_value (pa TVSH) dhe thuaje cilën.",
    "Tabelat (të gjitha tashmë vetëm për këtë tenant):",
    ...Object.entries(TABLES).map(([t, d]) => `- ${t}: ${d}`),
    "Lidhjet: sv_sales.customer_code = sv_customers.customer_code; sv_sales.sku_code = sku_classification.sku_code = sv_products.sku_code; sv_sales.salesperson_code = sv_agents.sadn_force_shitese_id.",
    "web_search për çdo gjë aktuale jashtë kompanisë (lajme, kurse, çmime tregu, ligje, taksa, konkurrentë, moti); thuaj burimin shkurt.",
    "Me zë vetëm lexon dhe këshillon: aprovimet dhe ndryshimet bëhen në app.",
  ].join("\n");
}

const tools = [
  { name: "lexim_db", description: "SQL live (vetëm lexim) mbi të dhënat e tenant-it: shitje, klientë, produkte, borxhe, arkëtime, porosi, agjentë, stok, fitim-humbje. Kthen rreshtat si JSON.", input_schema: { type: "object", properties: { sql: { type: "string", description: "Një SELECT i vetëm." } }, required: ["sql"] } },
  { name: "pamja_sot", description: "Përmbledhja e sotme nga THA'NA OS: shitjet sot/muaji, shëndeti, borxhet, aprovimet, agjentët sot, sinjalet.", input_schema: { type: "object", properties: {} } },
  { name: "te_dhenat_thana", description: "Pyetje te Truri THA'NA (parashikime, sinjale, analiza të gatshme). Mund të bllokohet nga Pakti; atëherë përdor lexim_db.", input_schema: { type: "object", properties: { pyetja: { type: "string" } }, required: ["pyetja"] } },
  { type: "web_search_20250305", name: "web_search", max_uses: 3 },
];

async function callClaude(body: Record<string, unknown>): Promise<{ ok: boolean; j: any }> {
  for (const model of MODELS) {
    const r = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "x-api-key": AKEY, "anthropic-version": "2023-06-01", "content-type": "application/json" },
      body: JSON.stringify({ ...body, model }),
      signal: AbortSignal.timeout(60_000),
    }).catch(() => null);
    if (!r) continue;
    const j = await r.json().catch(() => ({}));
    if (r.ok) return { ok: true, j };
    console.error("voice-tool anthropic", model, r.status, JSON.stringify(j).slice(0, 200));
    if (r.status !== 404 && r.status !== 400) return { ok: false, j };
  }
  return { ok: false, j: {} };
}

async function think(token: string, tenantId: string, owner: string, tenant: string, question: string, history: Array<{ role: string; text: string }>): Promise<string> {
  const messages: any[] = [];
  for (const h of history.slice(-30)) {
    const role = h.role === "user" ? "user" : "assistant";
    if (!h.text?.trim()) continue;
    if (messages.length && messages[messages.length - 1].role === role) messages[messages.length - 1].content += "\n" + h.text;
    else messages.push({ role, content: h.text });
  }
  if (messages.length && messages[0].role !== "user") messages.shift();
  if (messages.length && messages[messages.length - 1].role === "user") messages.pop();
  messages.push({ role: "user", content: question || "(heshtje)" });

  for (let step = 0; step < 8; step++) {
    const { ok, j } = await callClaude({ max_tokens: 900, system: system(owner, tenant), tools, messages });
    if (!ok) return "Më fal, pata një problem teknik. Provo përsëri.";
    if (j.content?.length) messages.push({ role: "assistant", content: j.content });
    if (j.stop_reason === "pause_turn") continue;
    if (j.stop_reason !== "tool_use") {
      const text = (j.content ?? []).filter((b: any) => b.type === "text").map((b: any) => b.text).join(" ")
        .replace(/[*#_`>|]/g, " ").replace(/\s+/g, " ").trim();
      if (text) return text;
      console.error("voice-tool empty", j.stop_reason, (j.content ?? []).map((b: any) => b.type).join(","));
      messages.push({ role: "user", content: "(Përgjigju tani me zë, me tekst të thjeshtë, duke përdorur atë që gjete.)" });
      continue;
    }
    const results = [];
    for (const b of j.content ?? []) {
      if (b.type !== "tool_use") continue;
      const out = b.name === "lexim_db" ? await dbQuery(tenantId, String(b.input?.sql ?? ""))
        : b.name === "pamja_sot" ? await snapshot(token)
        : b.name === "te_dhenat_thana" ? await brain(token, `${b.input?.pyetja ?? question}\n\n(Pyetje nga biseda me zë: përgjigju shkurt, pa tabela.)`)
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
    if (!who.tenant_id) return json({ result: "Ky përdorues nuk ka tenant të caktuar." });

    const body = await req.json().catch(() => ({}));
    const name = String(body.name ?? "");
    const args = body.args ?? {};
    const history = Array.isArray(body.history) ? body.history : [];
    const owner = who.full_name ?? "pronari", tenant = who.tenant ?? "biznesin";

    let result: string;
    if (name === "thana_mendo") result = await think(token, who.tenant_id, owner, tenant, String(args.pyetja ?? ""), history);
    else if (name === "thana_panorama") result = await snapshot(token);
    else if (name === "thana_pyet") result = await brain(token, String(args.pyetja ?? ""));
    else result = "Mjet i panjohur.";
    return json({ result });
  } catch (e) {
    console.error("voice-tool", e);
    return json({ result: "Më fal, pata një problem teknik. Provo përsëri." });
  }
});
