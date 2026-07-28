# w12 — Encodeur FIT pur Kotlin (optionnel)

## Goal

`pathToFit` / `pathsToFit` sont les seules fonctions de `EngineJsApi` sans équivalent WASI : le
SDK Garmin (JVM) et `@garmin/fitsdk` (JS) n'ont pas de portage WASI, et w01 y a posé un `actual`
qui lève.

Cette fiche ferme le trou — **si le besoin apparaît**. Elle est optionnelle et volontairement
placée en fin de plan : produire un `.fit` depuis un hôte WASI n'a pas de demandeur identifié.

## Depends on

- `w01` (le stub qu'elle remplace), `w04` (la façade où l'export apparaîtrait).

## Inputs

- `fit/src/jvmMain/…/FitEncoder.jvm.kt` et `fit/src/jsMain/…/FitEncoder.js.kt` — le contrat
  exact à reproduire.
- `fit/src/commonTest/…` — les tests round-trip de g10 et le contrat multi-`Path` de g25.
- `docs/tasks/g10-fit-course-encoder.md` — messages Course / Lap / Records produits.
- Spécification FIT (Garmin, publique) — format des définitions et des enregistrements.

## Steps

1. **Cadrer au strict nécessaire.** vcyclist n'*encode* qu'un profil bien précis (Course, Lap,
   Records + File Id). Il ne décode rien et n'a pas besoin des messages développeur ni des
   champs compressés. C'est ce qui rend la fiche faisable.
2. **Implémentation en `commonMain`** (pas dans `wasmWasiMain`) : en-tête FIT 14 octets,
   messages de définition, messages de données, CRC-16 de la spec. Testée sur toutes les cibles.
3. **Décider du sort des implémentations natives.** Si l'encodeur Kotlin produit octet pour
   octet le même fichier que le SDK Garmin, supprimer `expect`/`actual` et les dépendances
   `com.garmin:fit` / `@garmin/fitsdk` — un gain net (une dépendance JVM et un paquet npm en
   moins). Sinon, garder les natifs et n'utiliser le Kotlin que sur WASI, en documentant l'écart.
4. **Exposer** `vcPathToFit` / `vcPathsToFit` dans la façade et retirer la ligne « non porté »
   de la table de correspondance (w04/w10).

## Outputs

- `fit/src/commonMain/…/` — encodeur Kotlin + tests.
- `fit/src/wasmWasiMain/…/FitEncoder.wasmWasi.kt` réécrit ou supprimé.
- Façade et documentation mises à jour.

## Validation

- [ ] Le `.fit` produit est identique (octet pour octet) à celui du SDK Garmin sur les fixtures
      de g10, ou l'écart est analysé et documenté.
- [ ] Le fichier est accepté par un lecteur FIT tiers (FitCSVTool, Garmin Connect).
- [ ] Le smoke CLI de la CI (qui vérifie le marqueur `.FIT`) reste vert.
- [ ] Taille du `.wasm` re-mesurée.

## Done when

`vcPathToFit` fonctionne depuis un hôte WASI, ou la fiche est fermée avec une décision écrite de
ne pas la faire.

## Notes

Fermer explicitement cette fiche est un résultat acceptable : « FIT reste hors WASI, l'hôte
convertit s'il en a besoin » est une position défendable, à condition qu'elle soit écrite dans
`docs/wasm-wasi-abi.md` §Limites (w10) plutôt que laissée en suspens.
