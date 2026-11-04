// voice-session · THA'NA Phone
// Gives the app a short-lived Gemini Live token for ONE voice conversation.
// The real GEMINI_API_KEY never leaves the server. Same PWA session gate as owner-snapshot (director/admin).
// Read-only on the database.
import postgres from "npm:postgres@3.4.4";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL")!, { prepare: false, max: 2, idle_timeout: 20 });
const MODEL = Deno.env.get("THANA_VOICE_MODEL") ?? "gemini-3.8-live";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-session-token",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (b: unknown, s = 200) => new Response(JSON.stringify(b), { status: s, headers: { ...cors, "Content-Type": "application/json" } });

type Glossary = { customers: string | null; agents: string | null; products: string | null; zones: string | null };

function systemPrompt(owner: string, tenant: string, g: Glossary): string {
  const today = new Date().toLocaleDateString("sq-AL", { timeZone: "Europe/Tirane", weekday: "long", day: "numeric", month: "long", year: "numeric" });
  return [
    `Ti je THA'NA, truri i biznesit për ${tenant}. Po flet live me zë me ${owner}, pronarin/drejtorin. Sot është ${today}.`,
    "Fol VETËM shqip, natyrshëm, si një drejtor financiar i besuar: fjali të shkurtra, 2–4 fjali për përgjigje, pa lista dhe pa markdown.",
    "RREGULL ABSOLUT: asnjë numër, shumë, përqindje, emër klienti apo agjenti nga mendja jote. Çdo shifër vjen vetëm nga mjetet:",
    "- thana_panorama: pamja e sotme (shitjet sot dhe të muajit, shëndeti i biznesit, borxhet, aprovimet, agjentët sot, sinjalet). Përdore për pyetje të shpejta.",
    "- thana_pyet: pyetje analitike te Truri THA'NA (pse, krahasime, klientë, produkte, rajone, periudha). Jepi pyetjen e plotë në shqip.",
    "Kur thirret një mjet, thuaj shkurt 'po e kontrolloj' dhe prit rezultatin. Mos shpik ndërkohë.",
    "Nëse mjeti nuk e ka përgjigjen, thuaje sinqerisht. Rrumbullako me zë: '20,9 milion lekë', '55 për qind'.",
    "Mos aprovo, mos fshi dhe mos ndrysho asgjë: ky kanal vetëm lexon dhe këshillon.",
    "",
    "SI TË DËGJOSH: përdoruesi flet shqip me terma biznesi dhe emra të përveçëm. Viti aktual është 2026: 'njëzet e gjashtë' ose 'dyzet e gjashtë' pas një muaji do të thotë 2026.",
    "Kur pyetja përmban një emër, krahasoje me listat më poshtë dhe përdor shkrimin e saktë nga lista. Mos e ndrysho llojin: një klient mbetet klient, një agjent mbetet agjent, një produkt mbetet produkt.",
    "Kur thërret thana_pyet: kopjo pyetjen e përdoruesit sa më fjalë për fjalë, me emrat e korrigjuar nga listat; shto kontekstin e bisedës vetëm nëse pyetja i referohet diçkaje të mëparshme ('ai', 'ky klient', 'po Elbasani?'). Mos shto fjalë që përdoruesi nuk i tha.",
    "Nëse nuk je i sigurt çfarë emri tha, pyet shkurt para se të thërrasësh mjetin.",
    "Truri kthen njëherë tabela: mos i lexo tabelat; thuaj 2–3 gjërat më të rëndësishme me fjalë.",
    "",
    `Klientët kryesorë: ${g.customers ?? "-"}.`,
    `Agjentët: ${g.agents ?? "-"}.`,
    `Markat: Deka, Deka No Comment, Deka Infinit, Deka Gel Power, Deka Zbardhues, Deka Aktiv, DIP, DIX, Prim Soft, OLIM, Aslo, Frito.`,
    `Produktet kryesore: ${g.products ?? "-"}.`,
    `Magazinat/zonat: ${g.zones ?? "-"}.`,
  ].join("\n");
}

const tools = [{
  functionDeclarations: [
    {
      name: "thana_panorama",
      description: "Pamja e sotme e biznesit nga THA'NA OS: shitjet sot dhe muaji, ndryshimi %, shëndeti 0–100, borxhet dhe afatet e kaluara, ditët e pagesës, aprovimet në pritje, agjentët sot, sinjalet kryesore.",
      parameters: { type: "OBJECT", properties: {} },
      behavior: "BLOCKING",
    },
    {
      name: "thana_pyet",
      description: "Bëji një pyetje analitike Trurit THA'NA (ka akses në të gjitha të dhënat e tenant-it: shitje, klientë, borxhe, produkte, agjentë, rajone, periudha). Kthen përgjigje me shifra të verifikuara.",
      parameters: { type: "OBJECT", properties: { pyetja: { type: "STRING", description: "Pyetja e përdoruesit fjalë për fjalë në shqip, me emrat e saktë nga listat; pa shtuar fjalë që ai nuk i tha." } }, required: ["pyetja"] },
      behavior: "BLOCKING",
    },
  ],
}];

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  const token = req.headers.get("x-session-token") ?? "";
  if (!token) return json({ error: "Mungon sesioni. Hyr në THA'NA." }, 401);
  const key = Deno.env.get("GEMINI_API_KEY");
  if (!key) return json({ error: "Zëri live nuk është konfiguruar (mungon GEMINI_API_KEY)." }, 503);

  try {
    const who = await sql.begin("read only", async (tx) => {
      const [u] = await tx`
        select u.full_name, u.role, coalesce(t.display_name, t.legal_name) as tenant
        from app_sessions s join app_users u on u.id = s.user_id
        left join sv_tenants t on t.id = coalesce(s.tenant_id, u.tenant_id)
        where lower(s.token) = lower(${token}) and s.expires_at > now() and coalesce(u.is_active, true)
        limit 1`;
      if (!u) return null;
      // Fjalor për dëgjimin e saktë të emrave (vetëm lexim, 90 ditët e fundit).
      const [g] = await tx`
        with d as (select (now() at time zone 'Europe/Tirane')::date t)
        select
          (select string_agg(n, ', ') from (
             select coalesce(c.trade_name, c.customer_name) n, sum(s.total_value) v
             from sv_sales s join sv_customers c on c.customer_code = s.customer_code, d
             where s.sale_date > d.t - 90 and coalesce(s.is_cancelled, false) = false
             group by 1 order by 2 desc limit 40) x) customers,
          (select string_agg(full_name, ', ') from (
             select distinct full_name from sv_agents
             where is_active and coalesce(is_test, false) = false and full_name is not null
               and full_name !~* '^(magazina|kosove|test|drejtoria)') a) agents,
          (select string_agg(n, ', ') from (
             select k.product_name n, sum(s.total_value) v
             from sv_sales s join sku_classification k on k.sku_code = s.sku_code, d
             where s.sale_date > d.t - 90 and coalesce(s.is_cancelled, false) = false and k.product_name is not null
             group by 1 order by 2 desc limit 30) y) products,
          (select string_agg(distinct zone_name, ', ') from sv_agents
             where is_active and zone_name is not null and zone_name !~* '^(test|drejtoria)') zones`;
      return { ...u, glossary: g as Glossary };
    });
    if (!who || !["director", "admin"].includes(who.role)) return json({ error: "Sesion i pavlefshëm ose rol i pamjaftueshëm." }, 403);

    const now = Date.now();
    const r = await fetch("https://generativelanguage.googleapis.com/v1alpha/auth_tokens", {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": key },
      body: JSON.stringify({
        uses: 2,                                                     // 1 bisedë + 1 riprovë lidhjeje
        expireTime: new Date(now + 30 * 60_000).toISOString(),       // biseda mund të zgjasë deri 30 min
        newSessionExpireTime: new Date(now + 2 * 60_000).toISOString(),
      }),
    });
    if (!r.ok) {
      console.error("voice-session token", r.status, (await r.text()).slice(0, 300));
      return json({ error: "Google nuk dha leje për bisedën. Provo përsëri." }, 502);
    }
    const { name } = await r.json();
    const first = (who.full_name ?? "").split(" ")[0] || "";
    return json({
      token: name,
      model: `models/${MODEL}`,
      ws_urls: [
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained",
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained",
      ],
      system: systemPrompt(who.full_name ?? "pronari", who.tenant ?? "biznesin", who.glossary),
      tools,
      greeting: `Fillo bisedën: përshëndet shkurt ${first} me emër dhe pyet çfarë do të dijë. Një fjali.`,
      voice: Deno.env.get("THANA_VOICE_NAME") ?? "Charon",
    });
  } catch (e) {
    console.error("voice-session", e);
    return json({ error: "Gabim i brendshëm." }, 500);
  }
});
