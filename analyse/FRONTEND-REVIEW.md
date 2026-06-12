# Frontend — structuur & verbeterplan

Senior-staff review van de feitelijke frontend. Verwijzingen naar echte bestanden/regels. Geen algemeenheden.

**Wat de frontend nu ís:** één operator-dashboard, volledig server-rendered als **inline HTML/CSS/JS binnen Java-controllers**. Geen framework, geen build-stap, geen `static/`- of `templates/`-map, geen `package.json`. De hele UI zit in:

- `services/orchestrator/.../review/DashboardController.java` — **3220 regels**: `baseCss()` (~430 regels CSS in een Java-string, regel 2277), `shell()` (regel 2713), en ~25 `render*`-methodes die HTML-fragmenten als strings bouwen. 35× `fetch(...)`, 18× `location.reload()`.
- `services/orchestrator/.../review/ReviewPreviewController.java` — 278 regels, een tweede, losse HTML-bron.

Belangrijk kader vooraf: dit is een **intern single-operator ops-dashboard**, geen publiek product met duizenden gebruikers. Dat stuurt elke aanbeveling — eenvoud en lage onderhoudslast voor een Java-team wegen zwaarder dan SPA-features. Daarom luidt het advies nadrukkelijk **niet** "introduceer React/Vue/Angular" (zie §5).

---

## 1. Huidige problemen

**Structureel (hoogste pijn)**
- **UI-god-class.** `DashboardController` is 3220 regels en mengt routing, data-ophalen uit de repo, HTML-opbouw, CSS en client-JS in één bestand. Elke CSS-tweak vereist een Java-hercompilatie + service-restart. Geen syntax-highlight, geen HTML/CSS/JS-linting, geen browser-devtools-mapping.
- **HTML/CSS/JS als Java-strings.** Stijl en gedrag zijn niet te testen of te hergebruiken buiten Java. `baseCss()` (~430 regels) wordt bij elke pagina-render opnieuw in de respons geserialiseerd en over de lijn gestuurd — niet cachebaar, geen ETag.
- **Twee HTML-bronnen.** `DashboardController` én `ReviewPreviewController` genereren los HTML/CSS → stijl divergeert onvermijdelijk.

**State management**
- **Full-page reload als update-mechanisme.** 18× `location.reload()` en een `setTimeout(() => location.reload(), 5000)` poll. Elke actie (approve, delete, lock) herlaadt de hele pagina → flikkering, verlies van scrollpositie en formulier-invoer, en de poll herrendert het volledige dashboard elke 5s i.p.v. alleen het veranderde stukje.
- **Geen client-side state.** Filters/selecties leven in de DOM; na een reload zijn ze weg (de bulk-select-checkboxes, de actieve filter-pill).

**UI-consistentie**
- Eén `baseCss()` is een goede basis, maar componenten zijn ad-hoc strings met one-off classes per `render*`-methode → geen afdwingbare design-tokens, knoppen/kaarten driften.

**Scheiding UI / state / data**
- `render*`-methodes mengen data-toegang (repo/`VideoJob`-getters) met HTML-opbouw. Client-JS mengt `fetch`, DOM-manipulatie en business-regels inline per knop. Geen data-laag, geen view-laag.

**Performance**
- Volledige dashboard-HTML + 430 regels CSS per request opnieuw; geen statische-asset-caching.
- 35 losse `fetch`-calls zonder gedeelde client → geen uniforme foutafhandeling, geen abort bij snel klikken, geen retry/back-off, geen loading-states.

**Veiligheid**
- Er is een `esc()`-helper (regel 2764) en die wordt gebruikt (bv. `esc(j.getTopic())`, regel 168) — goed. Maar escaping is **handmatig en opt-in**: één vergeten `esc()` op een nieuw veld = opgeslagen XSS in de ops-tool. Dit is fragiel by-design.

**Overbodige complexiteit**
- Theme-bootstrap (localStorage **én** cookie, regel 2713+) wordt inline in elke `shell()` meegestuurd. Prima feature, verkeerde plek — hoort in één statisch `theme.js`.

---

## 2. Structuurvoorstel (must-have)

Geen framework, geen build-stap. Verplaats de UI uit Java naar **statische assets die Spring serveert** (`src/main/resources/static/`), met een kleine, expliciete laagscheiding. Spring levert alleen nog **JSON**, niet langer HTML.

### Folderstructuur
```
services/orchestrator/src/main/resources/static/
  index.html                     # de shell (één keer, statisch, cachebaar)
  assets/
    css/
      tokens.css                 # design-tokens (kleuren, spacing, radius) — 1 bron
      base.css                   # reset + layout + thema (was: baseCss())
      components.css             # .btn, .card, .pill, .stepper … (1 plek)
    js/
      api.js                     # ÉÉN api-client: fetch-wrapper + fouten + abort
      store.js                   # client-state (filters, selectie, polling) in-memory
      render.js                  # kleine render-helper (html`` template + mount)
      theme.js                   # theme-bootstrap (was inline in shell())
      components/
        job-row.js               # herbruikbare UI-eenheden
        job-detail.js
        submit-form.js
        stepper.js
        qa-board.js
      pages/
        jobs.js                  # dashboard-pagina (lijst)
        job.js                   # detail-pagina
```

### Component-hiërarchie
```
App (router op /#/jobs, /#/job/:id)
├── Nav (filters, thema-toggle)
├── JobsPage
│   ├── SubmitForm
│   ├── FilterPills
│   └── JobRow*            ← herbruikbaar, 1 definitie
└── JobPage
    ├── Stepper
    ├── ScriptReview / ImageGrid / MasterReview / ThumbnailPicker
    ├── QaBoardCard / QualityCard / CostCard
    └── Actions            ← approve / retry / reassemble / delete
```
Elke component = één functie die data in → HTML-string (via een `html`` tagged-template) → in de DOM mount, met één `update(state)`-pad. Geen klassen, geen lifecycle-framework.

### State-aanpak
- **Eén in-memory store** (`store.js`): `{ jobs, filter, selection, polling }`. Acties muteren de store en triggeren een gerichte re-render van alleen het betroffen component — **geen `location.reload()` meer**.
- **Polling vervangt full-reload:** elke 5s `GET /api/v1/videos` → diff → alleen gewijzigde rijen herrenderen.

### API/data-laag
- **Eén `api.js`** met `get/post/del(url)` die JSON teruggeeft, HTTP-fouten centraal afhandelt (toast + console), en een `AbortController` per call gebruikt zodat snel klikken geen race veroorzaakt.
- Componenten roepen nooit `fetch` direct aan — alleen via `api.js`. Backend levert JSON; de frontend bouwt HTML. (Nu levert de backend HTML-fragmenten — zie §4.)

---

## 3. Concrete verbeteracties (geprioriteerd)

**HIGH — direct aanpassen**
1. **Extraheer `baseCss()` naar `static/assets/css/`** en serveer als statisch bestand met caching. Verwijdert ~430 regels uit Java en maakt CSS bewerkbaar zonder hercompilatie. *(½–1 dag, mechanisch.)*
2. **Verplaats de inline `<script>`-blokken naar `static/assets/js/` en maak één `api.js`.** Vervang de 35 losse `fetch`-calls door `api.get/post/del`, met centrale fout- + loading-afhandeling. *(1–2 dagen.)*
3. **Vervang `location.reload()` door gerichte DOM-updates** voor approve/delete/lock en de 5s-poll. Begin met de jobs-lijst (grootste winst, minste risico). *(1–2 dagen.)*
4. **Maak escaping niet-optioneel:** lever alle dynamische waarden als JSON aan de client en render client-side met een `html`` helper die standaard escapet, i.p.v. server-side string-concat met handmatige `esc()`. Sluit de XSS-voetangel. *(volgt uit actie 2/§4.)*

**MEDIUM — refactoren**
5. **Splits `DashboardController` op** in (a) een dunne `@RestController` die JSON levert en (b) statische assets. De 25 `render*`-methodes verdwijnen naar JS-componenten. Doe dit incrementeel, pagina per pagina (jobs-lijst eerst, dan detail). *(meerdere dagen, incrementeel.)*
6. **Eén componentbibliotheek** (`components.css` + `js/components/`): definieer `.btn`, `.card`, `.pill`, `.stepper` één keer; vervang one-off classes. *(parallel aan 5.)*
7. **Consolideer `ReviewPreviewController`-HTML** in dezelfde assets/componenten, zodat er één UI-bron is. *(½ dag.)*

**LOW — later / nice-to-have**
8. Theme-bootstrap naar `theme.js` (uit `shell()`). *(½ dag.)*
9. Hash-router (`/#/jobs`, `/#/job/:id`) i.p.v. aparte server-rendered pagina's. *(1 dag.)*
10. Toetsenbord-sneltoetsen + toasts voor operator-snelheid. *(optioneel.)*

**Volledig opnieuw ontwerpen:** niets. De backend-logica is gezond; alleen de **presentatielaag** moet uit Java naar statische assets. Een rewrite-from-scratch is niet nodig en zou risico zonder waarde toevoegen.

---

## 4. Backend-samenwerking (Java engineer)

De kern van de scheiding: **de backend stopt met HTML genereren en levert JSON.** Dat is de grootste contract-verbetering.

- **Wat de frontend mist:** stabiele JSON-DTO's. Nu komt UI-state als HTML-fragmenten uit `render*`-methodes; de frontend kan daar niets mee dan "in de DOM plakken". Lever in plaats daarvan:
  - `GET /api/v1/videos` → `[{ id, topic, status, qaScore, createdAt, … }]` (lijst-DTO).
  - `GET /api/v1/videos/{id}` → volledige job-DTO incl. scenes, scores, audit, kosten — de data die `renderPhaseView`/`renderQaBoardCard`/`renderCostCard` nu zelf uit de repo trekken.
- **API-verbeteringen:**
  - Eén **status-poll-endpoint** dat alleen veranderlijke velden teruggeeft (`status`, `qaScore`, `progress`) i.p.v. de hele job — scheelt bandbreedte bij de 5s-poll.
  - Consistente **veldnamen + nullability** over alle endpoints (de UI gokt nu op `j.getX() == null`).
  - **HTTP-statuscodes** netjes (404 job niet gevonden, 409 verkeerde state voor approve) zodat `api.js` ze uniform kan afhandelen.
- **Contract-borging:** leg de DTO's vast (een OpenAPI-spec of zelfs maar een gedeeld `types.js`/Java-record-paar) zodat front- en backend niet uiteenlopen. Nu is "het contract" de HTML-string — onzichtbaar en breekbaar.

> Netto voor de Java-engineer: minder werk, niet meer. HTML-opbouw verdwijnt uit Java; je levert alleen records als JSON (Spring doet dat gratis). De 3220-regelige controller krimpt naar een paar honderd regels REST.

---

## 5. Architectuur-aanbeveling (gemotiveerd)

**Aanbevolen: een no-build, vanilla-JS "feature/component"-structuur op statische assets, geserveerd door Spring.** Niet React/Vue/Angular.

Motivatie (senior-staff-afweging):
- **Het is een intern single-operator dashboard.** De waarde van een SPA-framework (virtual DOM, ecosystem, grote teams) weegt niet op tegen de kosten: een Node-toolchain, een build-pipeline, een framework-versie-onderhoud en een tweede taal-/skillset die een **Java-backend-team** moet dragen. Dat is complexiteit zonder bijpassende baat.
- **De huidige pijn is niet "geen framework" maar "alles in Java-strings".** 80% van de winst zit in het simpelweg **verplaatsen** van HTML/CSS/JS naar statische bestanden met een nette laagscheiding — dat kan vandaag, zonder build, zonder nieuwe dependency.
- **"Eenvoud boven complexiteit" + "optimaliseer voor Java-samenwerking"** wijzen allebei dezelfde kant op.

**Upgrade-pad (alleen als het dashboard fors groeit):** een **lichtgewicht, build-loze** lib via `<script type="importmap">` of CDN — **Alpine.js** (declaratief, minimaal) of **Preact + htm** (React-achtig, geen JSX/build). Beide draaien zonder Node-build en houden Spring als enige server. Stap hier pas op over als de vanilla-componenten te veel handmatig DOM-werk worden — niet eerder.

Vermijd: een volledige React/Vue/Angular-SPA met Webpack/Vite-build voor deze use-case. Overkill.

---

## 6. Production-ready checklist

**Productie-klaar**
- [ ] HTML/CSS/JS staan in `static/`, niet in Java-strings; CSS/JS worden met cache-headers (ETag / lange max-age + content-hash in filename) geserveerd.
- [ ] Alle dynamische waarden worden client-side ge-escaped via de `html`` helper; geen server-side HTML-string-concat van user-input meer. Eén XSS-testcase (topic met `<script>`) is groen.
- [ ] Eén `api.js`: elke call heeft fout-, loading- en lege-staat-afhandeling; HTTP-fouten tonen een toast i.p.v. stil falen.
- [ ] Geen `location.reload()` in actie-handlers; updates zijn gericht.

**Schaalbaar**
- [ ] Nieuwe pagina/feature = nieuw bestand in `js/pages/` + componenten, zonder bestaande aan te raken (open/closed).
- [ ] `DashboardController` is een dunne REST-controller (< ~300 regels); geen HTML meer.
- [ ] De 5s-poll vraagt alleen veranderlijke velden op, niet de hele job-lijst.

**Consistent**
- [ ] Eén `tokens.css` + `components.css`; geen one-off inline-styles of ad-hoc classes per fragment.
- [ ] Eén UI-bron — `ReviewPreviewController`-HTML is geconsolideerd in dezelfde componenten.
- [ ] Eén thema-implementatie in `theme.js`.

**Onderhoudbaar**
- [ ] Front- en backend delen een vastgelegd DTO-contract (OpenAPI of gedeelde types); een contractwijziging is zichtbaar in een diff.
- [ ] CSS/JS zijn los te openen, te linten (bv. eslint/stylelint) en in de browser te debuggen met bronmapping.
- [ ] Een nieuwe operator-actie toevoegen raakt één component + één api-call, niet een 3000-regelig bestand.

---

### Samenvatting in één zin
De backend is gezond; de frontend is goed bedóeld maar zit gevangen in Java-strings. De hoogste-ROI-stap is geen framework, maar het **verplaatsen van HTML/CSS/JS naar statische assets met een dunne JSON-API, één api-client en gerichte DOM-updates** — eenvoud die meteen schaalbaar en onderhoudbaar is, en de Java-engineer ontlast in plaats van belast.
