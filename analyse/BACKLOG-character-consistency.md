# Backlog — character-consistentie in Flow/Omni-clips

Opgesteld 2026-06-18 n.a.v. Auke's bevindingen over kleine verschillen tussen clips.
Status: **BL-0 canon beslist + BL-1/BL-2/BL-3 doorgevoerd in `bible/channel.yml`** (renderStyle.veoLook, dna.veoKey pip/mo/bo, dna.antiAccessory Mo) — **wacht op `mvn test` + golden-snapshot-refresh + redeploy compiler/script-service**. BL-4 (ref-audit) en BL-5 (AccessoryGuard-bug) staan nog open. Tot redeploy: post-correctie via override-blok / Flow-edit.

## Bevindingen (waargenomen)
- Pip mist soms de rode hanekam (onder/bij de hoed); wimpers soms wel/soms niet.
- Bo heeft soms een rode kam en/of rode lellen die er niet horen; mist soms de groene sjaal.
- Mo is soms glad, soms geveerd; soms rode lellen, soms niet.

## Kernoorzaak
Omni behandelt de character-refs als *zachte* hint. Alles wat de gecompileerde
Veo-prompt niet hard vastlegt, vult het model per generatie anders in. Drie mechanismen:
1. **Interne tegenstrijdigheid** in de prompt (gladde "smooth matte / no down feathers"
   in Visual Style vs. "down / ball-of-fluff / ultra-soft down" in de veoKey/feathers).
2. **Onderspecificatie** in de lean-veoKey (kam, wimpers, lellen niet benoemd).
3. **Geen expliciete negatieven** (model geeft kippen standaard kam+lellen; Bo hoort die niet te hebben).

---

## BL-0 (P1, vereist Auke-beslissing) — Canon vastleggen
Vóór de promptfixes moet de canon eenduidig zijn. Beslis per kenmerk:
- **Body-look:** gladde matte/clay-stijl OF zachte donsveren? (Kan niet allebei — zie BL-1.)
- **Pip:** kleine rode kam zichtbaar bij de hoed — ja/nee? Wimpers — ja (lijkt canon)?
- **Bo:** kam — nee; lellen — nee (bevestigen); cowlick-kuif + bril + groene sjaal — altijd.
- **Mo:** tall red comb — ja (staat al in veoKey); lellen — ja/nee? Rode sjaal — altijd.
- **Eendje:** geel, geen accessoire (al goed).
Leg dit vast in `bible/channel.yml` als single source of truth.

## BL-1 (P1) — Los de veren-vs-glad-tegenstrijdigheid op
Het Visual-Style-blok ("smooth matte bodies, no fluffy down feathers, no micro-texture")
botst met de DNA-velden (`dna.veoKey`, `dna.feathers`, `coreColor`: "PURE WHITE down",
"ball-of-fluff", "ultra-soft down"). Maak ze consistent met de in BL-0 gekozen look.
- Bestanden: `bible/channel.yml` (dna.veoKey + dna.feathers + coreColor-bewoording van Pip/Mo/Bo/eendje) en de Visual-Style-string (compiler `VeoPromptCompiler` of bible).
- Acceptatie: in de gecompileerde Veo-prompt staat nergens nog "down/fluff" náást "smooth matte/no feathers".

## BL-2 (P1) — veoKey volledig maken + expliciete negatieven
Herschrijf elke `dna.veoKey` zodat élk bepalend kenmerk genoemd wordt én verkeerde kenmerken
expliciet worden uitgesloten:
- **Pip:** + "kleine rode kam zichtbaar bij de strohoed" (indien canon), + "dikke donkere wimpers", + "GEEN lellen".
- **Bo:** + "GEEN kam, GEEN lellen", cowlick-kuif, bril + groene sjaal altijd zichtbaar.
- **Mo:** kam al benoemd; voeg expliciet "lellen: ja/nee" toe conform BL-0.
- Bestand: `bible/channel.yml` (dna.veoKey per personage). Compiler hoeft alleen door te geven.
- Acceptatie: golden/lean-tests bijwerken; per personage staat kam/lellen/wimpers expliciet (positief óf negatief) in de prompt.

## BL-3 (P2) — Accessoire-persistentie hard vergrendelen
Accessoires die juist wél moeten (Bo's groene sjaal, Mo's sjaal, Pip's hoed/bandana, Bo's bril)
vallen soms weg. Voeg per accessoire "altijd duidelijk zichtbaar, nooit afwezig" toe én neem ze op
in de Negative-Constraints ("nooit zonder zijn sjaal/bril/hoed").
- Bestand: `VeoPromptCompiler` (identity/negative-constraints opbouw).

## BL-4 (P2) — Reference-images auditen op interne consistentie
Per personage staan ~2 ref-images in Flow (Ingredients). Als die onderling verschillen (bv. één
Mo-ref glad, één geveerd; kam wel/niet) dwingt dat drift af. Controleer/normaliseer de ref-set per
personage zodat alle refs de gekozen canon tonen. (Mitigatie van de zachte ref-conditionering.)

## BL-5 (P2) — Bug: AccessoryGuard verhaspelt tekst (scène 5)
De accessory-guard herschreef Bo's "green scarf bouncing, glasses glinting" tot het ongrammaticale
"her straw farmer her round thin-framed eyeglasses bouncing". De rewrite levert kapotte zinnen op.
- Bestand: `AccessoryGuard.sanitize` (orchestrator). + unit-test met deze scène-5-case.
- Onschuldig voor het beeld, maar vervuilt de prompt.

## BL-6 (P3, optioneel) — QC-automatisering voor drift
Per-clip vision-QC of checklist die ontbrekende sjaal/kam/wimpers/lellen automatisch flagt, zodat
drift niet met het blote oog hoeft te worden gevangen.

---
**Restrisico:** ook met BL-1 t/m BL-3 blijft er enige drift omdat Omni de refs zacht gebruikt.
De fixes verkleinen de variatie; voor 100% identieke karakters is sterkere ref-locking of
post-correctie nodig (buiten scope van deze backlog).
