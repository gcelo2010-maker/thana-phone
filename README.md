# THA'NA Phone · Android (telefon + tablet) · v0.9.5

Android vetëm shfaq dhe kërkon. Shifrat, lejet dhe vendimet i konfirmon THA'NA OS (SalesVision AI).

## Çfarë ka
- **App:** Hyrje (PIN si PWA) · Sot · Financa · Shitje · Aprovime · THA'NA AI · Cilësime
- **Tablet:** menu anësore + përmbajtja (≥600dp), edhe në landscape
- **9 widget-e:** Shitjet sot (1×1) · Shëndeti (2×1) · Të arkëtueshme (2×2) · Aprovime (4×1) · Sinjale (4×2) · Shitjet 14 ditë (2×2) · Pyet THA'NA (4×1) · Agjentët sot (4×2) · Fol me THA'NA (1×1)
- **Bisedë live me zë (v0.9):** zgjidh zërin **GPT Realtime** (`gpt-realtime-2.1`, zëri `marin`, parazgjedhur) ose **Gemini Live**. Gemini Live (`gemini-3.8-live`) = vetëm veshi dhe zëri femëror. Çdo pyetje shkon te `voice-tool` → Claude (`claude-sonnet-4-6`): bisedë e lirë dhe këshilla direkt, web search për gjëra aktuale, shifrat e kompanisë vetëm nga THA'NA (`owner-snapshot`, `brain-chat`). Çelësat mbeten në server; `voice-session` jep çelës Gemini të përkohshëm. Rezervë: modaliteti i thjeshtë.
- **v0.9.5 — Gemini jo-bllokues (WO-CLIENT-V094, 07/11/2026):** aplikacioni i dërgon `voice-session` flamurin `async_tools: true` (vetëm Gemini; `VoiceContract.kt`). Serveri v19+ i përgjigjet me mjetin `thana_mendo` **NON_BLOCKING** dhe prompt të lirë: zëri flet vetë për bisedë të zakonshme, Truri thirret vetëm për shifra të kompanisë, dhe ndërkohë zëri **vazhdon të dëgjojë** (status: «Truri po përgatit përgjigjen në sfond»); rezultati dërgohet me `scheduling: WHEN_IDLE`. Pa flamur (ose server i vjetër) → sjellja e mëparshme, bllokuese. Plus: lidhja që s'hapet brenda 20 s kthehet në Idle (më parë mbetej «duke menduar» dhe orb-i vetëm hapte/mbyllte mikrofonin), prekja e dytë e orb-it e rinis, ri-lidhje max 3 me 1/2/4 s (më parë 5 pa pritje), `toolCallCancellation`, log `ThanaVoice` në logcat pa PII (asnjë transkript/token) — `adb logcat -s ThanaVoice`.
- **Launcher:** Cilësime → "THA'NA si ekran kryesor"

## Lidhja me THA'NA (pa ndryshime në tabela/funksione ekzistuese)
| Çfarë | Si |
|---|---|
| Hyrja | RPC `pin_login` → token 30 ditë, i enkriptuar me Android Keystore |
| Të dhënat | Edge Function `owner-snapshot` (READ ONLY) → cache atomik offline |
| Aprovimet | RPC `sv_action_approve` / `sv_action_reject` — e njëjta rrugë si PWA; backend-i kyç veprimin (`FOR UPDATE`), pranon vetëm `pending`, kontrollon rolin. Suksesi shfaqet vetëm pas konfirmimit nga backend-i. |
| AI | Edge Function `brain-chat` (SSE, `stream:true`) |

Vlerat që mungojnë shfaqen "—" (jo 0). Pa internet shfaqet snapshot-i i fundit me orën.

## APK
1. Ngarko folder-in në GitHub (përfshi `.github/`).
2. Actions: teste + build (~5 min).
3. Telefon/tablet: `https://github.com/<user>/<repo>/releases/latest/download/thana-widgets.apk`

## Ende jo në këtë version
- Njoftime push (FCM) — kërkon projekt Firebase (`google-services.json`)
- Kiosk / Device Owner — pas testimit të rikuperimit
- Rajone, kanale, agjentë, cash flow, detyrime — kur të shtohen te `owner-snapshot`
- Fontet Instrument Serif / Geist (tani: serif/sans të sistemit)

## Business Health
0.4·shitje + 0.4·likuiditet + 0.2·arkëtime, nga `sv_director_pulse()`.
