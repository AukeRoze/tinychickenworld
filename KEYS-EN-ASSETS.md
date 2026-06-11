# Keys & Assets — wat moet jij aanleveren om alles te ontgrendelen

Overzicht van precies welke **API-keys** (in `.env`) en **asset-bestanden** (in `bible/`) nodig zijn om de geblokkeerde functies aan te zetten. Geverifieerd tegen `.env.example`, `docker-compose.yml` en de echte code/assetmappen op 2026-06-09.

Korte conclusie: de **assets zijn grotendeels al aanwezig** (muziek, character-refs, per-character sfx, fx, whoosh). De echte blokkades zijn **API-keys**. Eén assetmap is nog leeg (`bible/sfx/ambient`).

---

## 1. API-keys (in `.env` — kopieer `.env.example` → `.env`)

### Verplicht om überhaupt een video te maken

| Env var | Service | Ontgrendelt | Waar vandaan |
|---|---|---|---|
| `ANTHROPIC_API_KEY` | script-service, orchestrator | Script-generatie + titel/omschrijving/tags. Zonder dit draait niets. ⚠️ Een Claude.ai-abonnement geeft **geen** API-toegang. | console.anthropic.com (apart gefactureerd) |
| `OPENAI_API_KEY` | image-service (default), thumbnail-service | Scène-beelden + thumbnails (default provider). | platform.openai.com |
| `YOUTUBE_CLIENT_SECRETS` → `/secrets/client_secret.json` + OAuth-flow → `/secrets/yt-creds/` | youtube-upload-service | Upload naar YouTube. Eenmalige OAuth-consent levert de `StoredCredential` op. | Google Cloud Console → OAuth client (Desktop) |

### Stemmen — grootste kwaliteitssprong ("diavoorstelling" → "cartoon")

| Env var | Ontgrendelt | Opmerking |
|---|---|---|
| `VOICE_ENABLED=true` | Zet ElevenLabs aan (false = stille MP3's, voor goedkoop testen) | staat al default true |
| `ELEVENLABS_API_KEY` | Echte TTS-stemmen | elevenlabs.io |
| `VOICE_ID_PIP` / `VOICE_ID_MO` / `VOICE_ID_BO` | Per-personage stem (curious / calm / playful) | kies 3 distincte stemmen uit je Voice Library |
| `ELEVENLABS_VOICE_ID` | Fallback-narrator als een personage geen stem heeft | |

> Alternatief zonder key: de **sounds-mode** (kipgeluiden) werkt al — de per-character sfx staan er (zie §2). Taal-onafhankelijk, gratis.

### Veo image-to-video (optioneel, opt-in per video)

| Env var | Ontgrendelt |
|---|---|
| `GCP_PROJECT_ID` + `GCP_REGION` | Vertex AI Veo-project |
| `GCS_OUTPUT_BUCKET` (+ `GCS_OUTPUT_PREFIX`) | Bucket waar Veo de clips schrijft |
| `/secrets/gcp-sa.json` (`GOOGLE_APPLICATION_CREDENTIALS`) | Service-account met `roles/aiplatform.user` + Storage Object Admin |

Eenmalige setup: GCP-project + Vertex AI API aan, bucket aanmaken, service-account-key downloaden. ⚠️ Veo 3.1 staat in **preview** — je project heeft mogelijk Model Garden-toegang nodig, anders valt het terug op de GA `veo-3.0`. (Zie ook P2 in `EXPERT-REVIEW-RONDETAFEL.md`.)

### Optioneel — originele muziek & extra platforms

| Env var(s) | Ontgrendelt |
|---|---|
| `SUNO_ENABLED=true` + `SUNO_SESSION_ID` + `SUNO_COOKIE` + `SUNO_SECRET_TOKEN` | Originele AI-muziek per video (eigen Suno-abonnement). Zonder dit: royalty-free tracks uit `bible/music/` (die er al staan). |
| `REPLICATE_API_TOKEN` + `REPLICATE_FLUX_MODEL` + `CAST_LORA_URL` | Image-provider `replicate` (Flux+LoRA, ~95% character consistency) i.p.v. OpenAI |
| `TIKTOK_ACCESS_TOKEN` | Distributie naar TikTok |
| `INSTAGRAM_ACCESS_TOKEN` + `INSTAGRAM_USER_ID` | Instagram Reels (Meta Graph API) |
| `FACEBOOK_PAGE_ACCESS_TOKEN` + `FACEBOOK_PAGE_ID` | Facebook Page video posting |

---

## 2. Asset-bestanden (in `bible/`)

| Pad | Wat het ontgrendelt | Status |
|---|---|---|
| `bible/channel.yml` | De volledige cast/stijl/locaties/voices-config | ✅ aanwezig |
| `bible/refs/{pip,mo,bo}.png` | Reference-anchors → character consistency (Gemini-provider) | ✅ aanwezig (pip, mo, bo) |
| `bible/music/*.mp3` | Royalty-free achtergrondmuziek (3 tracks) | ✅ aanwezig (curious_clouds, gentle_morning, sunny_adventure) |
| `bible/sfx/{pip,mo,bo}/<emotie>-N.mp3` | Per-personage kipgeluiden (sounds-mode + signatuur-cues) | ✅ aanwezig (volledige emotie-set per personage) |
| `bible/sfx/transitions/whoosh.mp3` | Whoosh op scène-overgangen | ✅ aanwezig |
| `bible/fx/ambient.mov` | Ambient-overlay (vlinders/vuurvliegjes over scènes) | ✅ aanwezig |
| `bible/fx/bell.png` | Schuddend bel-icoon op de eindkaart | ✅ aanwezig |
| `bible/intro.mp4` / `bible/outro.mp4` / `bible/sting.mp3` / `bible/logo.png` | Intro/outro/sting/logo | ✅ aanwezig |
| **`bible/sfx/ambient/*.mp3`** | **Per-locatie sfeergeluid (coop kraakt, vijver/kikkers, tuin zoemt)** | ❌ **LEEG** — dit is de enige ontbrekende assetmap |

**Te doen voor de laatste assetlaag:** zet per-locatie ambient-loops neer in `bible/sfx/ambient/` (bv. `coop.mp3`, `pond.mp3`, `garden.mp3`). De code (`AmbientMixer` / `ambientByLocation`) pikt ze automatisch op zodra ze bestaan.

---

## 3. Prioriteit — om "live" te gaan

1. **`ANTHROPIC_API_KEY`** + **`OPENAI_API_KEY`** → pipeline produceert end-to-end een video (Ken Burns, stille of sounds-mode audio).
2. **YouTube OAuth** (`client_secret.json` + consent) → daadwerkelijk uploaden.
3. **ElevenLabs** (`ELEVENLABS_API_KEY` + 3 voice-IDs) → echte stemmen = de grootste kwaliteitssprong.
4. *(Optioneel)* **Veo** (GCP-creds) → echte beweging op hero-scènes.
5. *(Optioneel)* **Suno**, **Replicate**, **TikTok/Instagram/Facebook** → originele muziek, betere consistency, multi-platform.
6. *(Laatste laag)* **`bible/sfx/ambient/`** vullen → per-locatie sfeergeluid.

Alleen punt 6 is een asset; de rest zijn keys.
