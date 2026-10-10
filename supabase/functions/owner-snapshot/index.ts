// owner-snapshot · THA'NA Phone
// Read-only snapshot for the owner app + Android widgets.
// Auth: same PWA session as the director cockpit (app_sessions → app_users role director/admin).
// Safety: every query runs inside a READ ONLY transaction; nothing is written.
import postgres from "npm:postgres@3.4.4";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL")!, { prepare: false, max: 2, idle_timeout: 20 });

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-session-token",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

const clamp = (v: number) => Math.max(0, Math.min(100, v));

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });

  let token = req.headers.get("x-session-token") ?? "";
  if (!token && req.method === "POST") {
    try { token = (await req.json())?.session_token ?? ""; } catch { /* no body */ }
  }
  if (!token) return json({ error: "Mungon sesioni. Hyr me llogarinë e PWA-së." }, 401);

  try {
    const out = await sql.begin("read only", async (tx) => {
      const [who] = await tx`
        select u.full_name, u.role
        from app_sessions s join app_users u on u.id = s.user_id
        where lower(s.token) = lower(${token}) and s.expires_at > now() and coalesce(u.is_active, true)
        limit 1`;
      if (!who || !["director", "admin"].includes(who.role)) return null;

      const [{ pulse }] = await tx`select sv_director_pulse() as pulse`;

      const [{ snap }] = await tx`
        with d as (select (now() at time zone 'Europe/Tirane')::date t),
        s as (
          select sale_date, sum(total_value) v, count(distinct salesperson_code) ag
          from sv_sales, d
          where coalesce(is_cancelled, false) = false and sale_date between d.t - 13 and d.t
          group by 1)
        select jsonb_build_object(
          'date', (select t from d),
          'today', (select coalesce(sum(v), 0) from s, d where sale_date = d.t),
          'agents_today', (select coalesce(max(ag), 0) from s, d where sale_date = d.t),
          'same_day_last_week', (select coalesce(sum(v), 0) from s, d where sale_date = d.t - 7),
          'series14', (select jsonb_agg(jsonb_build_object('d', sale_date, 'v', round(v)) order by sale_date) from s),
          'stock_low_count', (select count(*) from sadn_stock where sasia > 0 and sasia < 10),
          'pending_count', (select count(*) from ai_actions_pending where status = 'pending'),
          'pending_top', (select jsonb_agg(x) from (
              select a.id, a.action_type, a.priority, a.reason, a.estimated_value, a.created_at,
                     a.target_customer_code, coalesce(c.trade_name, c.customer_name) customer_name, a.assigned_to_agent_code
              from ai_actions_pending a
              left join lateral (select trade_name, customer_name from sv_customers
                                 where customer_code = a.target_customer_code limit 1) c on true
              where a.status = 'pending'
              order by case a.priority when 'critical' then 0 when 'high' then 1 when 'medium' then 2 else 3 end,
                       a.estimated_value desc nulls last, a.created_at desc
              limit 40) x),
          'signals_7d', (select count(*) from thana_signals where not acknowledged and detected_at > now() - interval '7 days'),
          'signals_top', (select jsonb_agg(x) from (
              select severity, title, detected_at from thana_signals where not acknowledged
              order by case severity when 'critical' then 0 when 'warn' then 1 else 2 end, detected_at desc
              limit 5) x),
          'top_debtors', (select jsonb_agg(x) from (
              select customer_name, round(sum(debi_maturuar)) maturuar, max(last_payment_date) last_payment_date
              from sv_ar_debts where debi_maturuar > 0
              group by customer_name order by 2 desc limit 5) x)
        ) as snap`;

      return { who, pulse, snap };
    });

    if (!out) return json({ error: "Sesion i pavlefshëm ose rol i pamjaftueshëm." }, 403);
    const { who, pulse, snap } = out as { who: any; pulse: any; snap: any };

    // Business Health (0–100) — transparent formula, components returned alongside.
    const revDelta = Number(pulse?.revenue?.delta_pct ?? 0);
    const cashHealth = Number(pulse?.cash_health?.value ?? 0);
    const dso = Number(pulse?.dso?.value ?? 0);
    const dsoTarget = Number(pulse?.dso?.threshold ?? 45);
    const comp = {
      shitje: Math.round(clamp(50 + revDelta)),
      likuiditet: Math.round(clamp(cashHealth)),
      arketime: Math.round(dso > 0 ? clamp((100 * dsoTarget) / dso) : 100),
    };
    const score = Math.round(0.4 * comp.shitje + 0.4 * comp.likuiditet + 0.2 * comp.arketime);

    const today = Number(snap.today);
    const lastWeek = Number(snap.same_day_last_week);
    const ar = pulse?.cash_health?.breakdown ?? {};

    const alerts: string[] = [
      ...(snap.signals_top ?? []).map((s: any) => s.title),
    ].slice(0, 4);

    return json({
      owner: who.full_name,
      generated_at: new Date().toISOString(),
      data_freshness: { sales: snap.date, debts: pulse?.data_freshness ?? null },
      health: { score, components: comp, formula: "0.4·shitje + 0.4·likuiditet + 0.2·arkëtime" },
      sales: {
        today,
        same_day_last_week_full: lastWeek,
        agents_today: snap.agents_today,
        mtd: pulse?.revenue?.value ?? null,
        mtd_delta_pct: revDelta,
        series14: snap.series14 ?? [],
      },
      receivables: {
        total: ar.total_debt ?? null,
        overdue: ar.overdue_debt ?? null,
        overdue_pct: ar.overdue_pct ?? null,
        overdue_customers: ar.overdue_count ?? null,
        dso_days: dso,
        dso_target: dsoTarget,
        top_debtors: snap.top_debtors ?? [],
      },
      stock: { low_count: snap.stock_low_count },
      approvals: { pending: snap.pending_count, top: snap.pending_top ?? [] },
      signals: { last_7d: snap.signals_7d, top: snap.signals_top ?? [] },
      // Flat block read by the Android widgets (Repo.kt)
      widget: {
        salesToday: Math.round(today),
        salesDelta: lastWeek > 0 ? Math.round(((today - lastWeek) / lastWeek) * 1000) / 10 : 0,
        health: score,
        mtd: pulse?.revenue?.value ?? 0,
        receivables: ar.total_debt ?? 0,
        overdue: ar.overdue_debt ?? 0,
        dso: dso,
        pending: snap.pending_count,
        alerts,
      },
    });
  } catch (e) {
    console.error("owner-snapshot", e);
    return json({ error: "Gabim i brendshëm gjatë leximit të të dhënave." }, 500);
  }
});
