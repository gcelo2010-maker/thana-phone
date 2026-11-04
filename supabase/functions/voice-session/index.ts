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
    "Je zë femëror. Fol si një shqiptare e lindur: shqip i rrjedhshëm, i ngrohtë dhe i natyrshëm, si një partnere biznesi e besuar që njeh mirë tregun shqiptar. Për veten përdor gjithmonë format femërore (jam e sigurt, jam e gatshme, e kontrollova). Mund të bësh shaka të lehta, të pyesësh, të japësh mendimin tënd, ide, këshilla dhe strategji, dhe të bisedosh lirisht për çdo temë që ngre ai.",
    "Gjatësia sipas bisedës: shkurt kur pyetja është e thjeshtë, më gjatë kur kërkon analizë ose këshillë. Fol si në telefonatë, jo si raport: pa lista, pa tabela, pa markdown.",
    "I VETMI RREGULL I RREPTË, për shifrat dhe faktet e kompanisë: shitjet, borxhet, sasitë, përqindjet, fitimi, emrat e klientëve dhe agjentëve në lidhje me rezultate. Këto vijnë VETËM nga mjetet, kurrë nga mendja jote:",
    "- thana_panorama: pamja e sotme (shitjet sot dhe të muajit, shëndeti i biznesit, borxhet, aprovimet, agjentët sot, sinjalet). E shpejtë.",
    "- thana_pyet: pyetje analitike te Truri THA'NA (pse, krahasime, klientë, produkte, rajone, periudha).",
    "Për gjithçka tjetër (njohuri të përgjithshme, mendime, këshilla menaxhimi, marketing, negociata, tregu) fol lirisht pa mjete. Kur jep një mendim, thuaje si mendim.",
    "Për informacion të freskët jashtë kompanisë (lajme, kursi i këmbimit, çmimet e lëndëve të para si vaji i lulediellit, moti, ligje dhe taksa aktuale, konkurrentët, ngjarje) përdor Google Search dhe thuaj shkurt burimin ('sipas Bankës së Shqipërisë...'). Mos përdor Google Search për shifrat e kompanisë: ato vijnë vetëm nga THA'NA.",
    "Kur të duhet një mjet, thuaj diçka të natyrshme ('një sekondë, po e shoh') dhe prit rezultatin. Pastaj komentoje si njeri: çfarë do të thotë, pse ndodh ndoshta, çfarë do bëja unë.",
    "Nëse mjeti nuk e ka përgjigjen ose thotë që nuk e verifikoi dot, thuaje sinqerisht me një fjali dhe MOS e pyet përsëri të njëjtën pyetje; propozo ta formulojë ndryshe ose ta shohë te app. Rrumbullako shifrat me zë: '20,9 milion lekë', 'rreth 55 për qind'.",
    "Analizat e Trurit mund të zgjasin 10–30 sekonda: thuaje një herë ('po e analizoj, zgjat pak') dhe prit pa folur.",
    "Me zë vetëm lexon dhe këshillon: aprovimet dhe ndryshimet bëhen në app.",
    "",
    "SI TË DËGJOSH: përdoruesi flet shqip me terma biznesi dhe emra të përveçëm. Viti aktual është 2026: 'njëzet e gjashtë' ose 'dyzet e gjashtë' pas një muaji do të thotë 2026.",
    "Kur pyetja përmban një emër, krahasoje me listat më poshtë dhe përdor shkrimin e saktë nga lista. Mos e ndrysho llojin: një klient mbetet klient, një agjent mbetet agjent, një produkt mbetet produkt.",
    "Kur thërret thana_pyet: kopjo pyetjen e përdoruesit sa më fjalë për fjalë, me emrat e korrigjuar nga listat; shto kontekstin e bisedës vetëm nëse pyetja i referohet diçkaje të mëparshme ('ai', 'ky klient', 'po Elbasani?'). Mos shto fjalë që përdoruesi nuk i tha.",
    "Nëse nuk je i sigurt çfarë emri tha, pyet shkurt para se të thërrasësh mjetin.",
    "Truri kthen ndonjëherë tabela: mos i lexo tabelat; thuaj me fjalë gjërat më të rëndësishme.",
    "",
    `Klientët kryesorë: ${g.customers ?? "-"}.`,
    `Agjentët: ${g.agents ?? "-"}.`,
    `Markat: Deka, Deka No Comment, Deka Infinit, Deka Gel Power, Deka Zbardhues, Deka Aktiv, DIP, DIX, Prim Soft, OLIM, Aslo, Frito.`,
    `Produktet kryesore: ${g.products ?? "-"}.`,
    `Magazinat/zonat: ${g.zones ?? "-"}.`,
  ].join("\n");
}

const tools = [{ googleSearch: {} }, {
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

// v0.8+: Gemini = vetëm veshi dhe zëri. Mendimi (bisedë, njohuri, web, shifrat THA'NA) vjen nga Claude te voice-tool.
function relayPrompt(owner: string, tenant: string, g: Glossary): string {
  return [
    `Ti je zëri i THA'NA-s, partneres së biznesit për ${tenant}. Po flet live me ${owner}. Je zë femëror, shqip i rrjedhshëm, i ngrohtë, si një shqiptare e lindur.`,
    "Fol si në telefon me një mik: shpejt, natyrshëm, me fjali të shkurtra, me ndjenjë. Mund të pyesësh, të bësh shaka të lehta, të reagosh ('hë, e kuptoj', 'po, ke të drejtë').",
    "Bisedën e lehtë dhe pyetjet e thjeshta të përgjithshme (përshëndetje, si je, sqarime, shpjegime të shkurtra, ide të shpejta, 'ku ishim', 'përsërite') përgjigju VETË menjëherë, pa mjet.",
    "Thërrit thana_mendo për: çdo shifër, emër ose fakt të kompanisë; analiza dhe krahasime; këshilla serioze biznesi ose strategji; informacion aktual (lajme, kurse, çmime, ligje, taksa); dhe kur nuk je e sigurt. Për shifrat e kompanisë mos u përgjigj kurrë nga vetja.",
    "Kur thërret mjetin, thuaj menjëherë një frazë të shkurtër dhe të ndryshme çdo herë ('një sekondë', 'po e shoh', 'prit ta kontrolloj'), pastaj prit pa folur.",
    "Kur vjen rezultati, lexoje natyrshëm dhe besnikërisht: mos ndrysho asnjë shifër apo emër, mos shto fakte. Mund ta nisësh me një fjalë lidhëse ('pra', 'shiko').",
    "Kjo është një bisedë e vazhdueshme: mbaj mend çfarë u tha dhe çfarë lexove më parë. Mos thuaj kurrë që nuk e mban mend bisedën. Nëse të ndërpresin dhe pastaj të pyesin ku ishim, përmblidh shkurt atë që po thoje dhe vazhdo.",
    "Në thana_mendo kopjo pyetjen e përdoruesit fjalë për fjalë, me emrat e korrigjuar nga listat më poshtë; nëse i referohet diçkaje të mëparshme, shto kontekstin e nevojshëm. Viti aktual është 2026.",
    "Nëse përdoruesi të ndërpret, ndalo dhe dëgjo.",
    "",
    `Klientët: ${g.customers ?? "-"}.`,
    `Agjentët: ${g.agents ?? "-"}.`,
    `Markat: Deka, Deka No Comment, Deka Infinit, Deka Gel Power, Deka Zbardhues, Deka Aktiv, DIP, DIX, Prim Soft, OLIM, Aslo, Frito.`,
    `Produktet: ${g.products ?? "-"}.`,
    `Zonat: ${g.zones ?? "-"}.`,
  ].join("\n");
}

const relayTools = [{
  functionDeclarations: [{
    name: "thana_mendo",
    description: "Mendja e THA'NA-s (shifrat e kompanisë, analiza, këshilla serioze, informacion aktual nga web-i). Kthen tekstin e saktë që duhet lexuar me zë.",
    parameters: { type: "OBJECT", properties: { pyetja: { type: "STRING", description: "Fjalët e përdoruesit fjalë për fjalë, me emrat e korrigjuar." } }, required: ["pyetja"] },
    behavior: "BLOCKING",
  }],
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

    const body = await req.json().catch(() => ({}));
    const relay = parseFloat(String(body.client ?? "0")) >= 0.8;
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
      system: (relay ? relayPrompt : systemPrompt)(who.full_name ?? "pronari", who.tenant ?? "biznesin", who.glossary),
      tools: relay ? relayTools : tools,
      greeting: `Fillo bisedën: përshëndete ${first} me emër, ngrohtë dhe natyrshëm, si një koleg që e njeh, dhe pyete si mund ta ndihmosh. Shkurt.`,
      voice: Deno.env.get("THANA_VOICE_NAME") ?? "Aoede",
      // Bashkohet në setup nga app-i (v0.7+): dëgjim më pak i ndjeshëm ndaj zhurmës dhe jehonës.
      setup_extra: {
        realtimeInputConfig: { automaticActivityDetection: {
          startOfSpeechSensitivity: "START_SENSITIVITY_LOW", endOfSpeechSensitivity: "END_SENSITIVITY_LOW",
          prefixPaddingMs: 200, silenceDurationMs: 700 } },
      },
      voice_question_suffix: "\n\n(Kjo pyetje vjen nga biseda me zë: përgjigju shkurt, 3–5 fjali, pa tabela, vetëm shifrat kryesore.)",
    });
  } catch (e) {
    console.error("voice-session", e);
    return json({ error: "Gabim i brendshëm." }, 500);
  }
});
