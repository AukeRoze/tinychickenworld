# Aflevering 1 — "Who's in the Egg?" — overgangsschema

## Kort: je pipeline doet dit al

De overgangen die je in Flow mist, zitten **niet** in Flow (die doet alleen harde
cuts) maar in je eigen **video-assembly-service**. Bij het hermonteren via de
pipeline (`import-flow-clips.bat <JOB-ID> 1`) plakt de `Concatenator` de clips aan
elkaar met een **per-fase gekozen xfade-overgang**, een korte **whoosh-SFX** op elke
cut, een **ambient-fade van 800 ms** op scènegrenzen, en een **2,0s dissolve** van de
intro naar scène 1. Je hoeft dus niets handmatig in CapCut te bouwen — tenzij je
buiten de pipeline om wilt monteren.

## Wat het systeem per fase doet (ingebouwd)

Bron: `Concatenator.transitionFor(phase)`. Overschrijfbaar in `channel.yml` onder
`assembly.transitions` (geen recompile nodig; wordt elke minuut opnieuw gelezen).

| Fase | ffmpeg xfade | Duur | Gevoel |
|------|--------------|------|--------|
| hook | `fade` | 0,10s | bijna harde cut, snappy |
| setup | `fade` | 0,15s | snelle cut, tempo |
| development | `fade` | 0,15s | snelle cuts, tempo erin |
| humor / emotion | `fade` (default) | 0,20s | iets meer adem |
| climax | `smoothleft` | 0,30s | accent dat de piek induwt |
| resolution | `dissolve` | 0,35s | rustig wegzakken |
| closer | `fadeblack` | 0,40s | zachte afsluiter |

Dit matcht precies het advies: vooral korte/harde cuts op energieke beats, en
dissolves/dip-to-black alleen op rustige of afsluitende beats.

## Concreet per scène-grens (EP1, 24 clips)

Fase-indeling is een **inschatting** op basis van de scene-mapping + de
episode-structuur in `channel.yml` — controleer ze tegen je eigen board en pas aan.
De overgang hoort bij de scène waar je *naartoe* gaat.

| Grens | Naar fase | Systeem-overgang | In CapCut/Resolve |
|-------|-----------|------------------|-------------------|
| 1 → 2 | hook | fade 0,10s | dissolve 0,1s (≈ zachte cut) |
| 2 → 3 | setup | fade 0,15s | dissolve 0,15s |
| 3 → 4 | setup | fade 0,15s | dissolve 0,15s |
| 4 → 5 | setup | fade 0,15s | dissolve 0,15s |
| 5 → 6 | setup | fade 0,15s | dissolve 0,15s |
| 6 → 7 | development | fade 0,15s | dissolve 0,15s |
| 7 → 8 | development | fade 0,15s | dissolve 0,15s |
| 8 → 9 | development | fade 0,15s | dissolve 0,15s |
| 9 → 10 | development | fade 0,15s | dissolve 0,15s |
| 10 → 11 | humor (Bo) | fade 0,20s | dissolve 0,2s |
| 11 → 12 | development | fade 0,15s | dissolve 0,15s |
| 12 → 13 | development | fade 0,15s | dissolve 0,15s |
| 13 → 14 | development | fade 0,15s | dissolve 0,15s |
| 14 → 15 | development | fade 0,15s | dissolve 0,15s |
| 15 → 16 | development | fade 0,15s | dissolve 0,15s |
| 16 → 17 | development | fade 0,15s | dissolve 0,15s |
| 17 → 18 | emotion | fade 0,20s | dissolve 0,2s (laat 'm landen) |
| 18 → 19 | development | fade 0,15s | dissolve 0,15s |
| 19 → 20 | development | fade 0,15s | dissolve 0,15s |
| 20 → 21 | climax (ei breekt) | smoothleft 0,30s | push/slide links 0,3s |
| 21 → 22 | climax | smoothleft 0,30s | push/slide links 0,3s |
| 22 → 23 | climax | smoothleft 0,30s | push/slide links 0,3s |
| 23 → 24 | resolution | dissolve 0,35s | cross-dissolve 0,35s |
| intro → 1 | — | dissolve 2,0s | (doet de pipeline) |
| laatste → outro | closer | fadeblack 0,40s | dip-to-black 0,4s |

## Twee manieren om de strakke video te krijgen

1. **Via de pipeline (aanbevolen, alles automatisch).**
   `import-flow-clips.bat <JOB-ID> 1` → de assembly-service zet bovenstaand schema,
   de whoosh, de muziek en intro/outro er meteen op. Geen handwerk.

2. **Handmatig in CapCut/Resolve.** Gebruik de laatste kolom hierboven. Vuistregel:
   standaard de korte dissolve (≈ zachte cut), `smoothleft` alleen op de climax,
   dip-to-black alleen helemaal aan het eind.

## Andere overgang dan standaard?

Voeg in `channel.yml` toe (voorbeeld), zonder recompile:

```yaml
assembly:
  transitions:
    climax:  { type: fadewhite, seconds: 0.35 }
    closer:  { type: circleclose, seconds: 0.45 }
    default: { type: fade, seconds: 0.20 }
```
