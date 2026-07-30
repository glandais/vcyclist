# w12 — Encodeur FIT pur Kotlin

## Goal

`pathToFit` / `pathsToFit` sont les seules fonctions de `EngineJsApi` sans équivalent WASI : le
SDK Garmin (JVM) et `@garmin/fitsdk` (JS) n'ont pas de portage WASI, et w01 y a posé un `actual`
qui lève.

Cette fiche ferme le trou.

**Réalisée autrement que prévu.** Les *Steps* ci-dessous décrivent l'écriture d'un encodeur FIT
à la main. Ce n'est pas ce qui a été fait : entre-temps
[`fit-kotlin-sdk`](https://github.com/glandais/fit-kotlin-sdk) existe — un SDK FIT Kotlin
Multiplatform généré depuis le profil, sans dépendance, tout en `commonMain`. `:fit` s'appuie
dessus, ce qui rend l'étape 2 sans objet et l'étape 3 tranchée par la suppression pure et simple
des implémentations natives.

## Depends on

- `w01` (le stub qu'elle remplace), `w04` (la façade où l'export apparaîtrait).

## Inputs

- `fit/src/jvmMain/…/FitEncoder.jvm.kt` et `fit/src/jsMain/…/FitEncoder.js.kt` — le contrat
  exact à reproduire (supprimés par cette tâche ; voir l'historique git).
- `fit/src/commonTest/…` — les tests round-trip de g10 et le contrat multi-`Path` de g25.
- `docs/tasks/g10-fit-course-encoder.md` — messages Course / Lap / Records produits.
- Spécification FIT (Garmin, publique) — format des définitions et des enregistrements.

## Steps (plan initial — voir Notes pour ce qui a réellement été fait)

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

- [x] L'écart avec la sortie des SDK Garmin est analysé et documenté : mêmes messages, mêmes
      valeurs, même taille (277 o sur `FitReferenceCourse`), définitions little-endian comme le
      SDK JavaScript et octet de version protocole `0x20` comme le SDK Java. `FitReferenceBytes`
      n'a plus qu'une constante au lieu de deux, et `FitEncoderTest` l'affirme sur les 4 cibles —
      l'identité octet pour octet inter-cibles était impossible avant.
- [x] Le fichier est accepté par un lecteur FIT tiers : `FitEncoderJsTest` décode les octets de
      référence avec `@garmin/fitsdk` (SDK vendeur) et vérifie son contrôle d'intégrité.
- [x] Le smoke CLI de la CI (qui vérifie le marqueur `.FIT`) reste vert, et le harnais WASI le
      vérifie aussi côté hôte (`test_fit_export_produces_a_file_a_reader_can_open`).
- [x] Taille du `.wasm` re-mesurée : 318 Ko → **501 547 o**, +183 Ko. Plafond porté à 1 Mo.

## Done when

- [x] `vcPathToFit` fonctionne depuis un hôte WASI, et `vcPathsToFit` avec lui.

## Notes

**Ce qui a été fait.** `expect object FitEncoder` et ses trois `actual` (JVM/`com.garmin:fit`,
JS/`@garmin/fitsdk`, wasmWasi/stub) sont remplacés par un `object FitEncoder` unique en
`commonMain`, écrit sur `io.github.glandais:fit-kotlin-sdk`. L'étape 3 de la fiche est donc
tranchée dans le sens « supprimer les natifs » — mais pour une raison plus forte que la parité
binaire : les deux SDK ne pouvaient pas produire le même fichier (endianness, octet de version),
et surtout `com.garmin:fit` déclare les mêmes noms de classes `com.garmin.fit.*` que le SDK
Kotlin, donc les deux **ne peuvent pas cohabiter sur un classpath**. Garder le natif JVM n'était
pas une option.

**Trois conséquences au-delà de WASI :**

- Les tests d'encodage/round-trip (`FitEncoderTest`, `FitRoundTripTest`, `EncoderBackedTest`)
  passent de `jvmTest` / `encodingTest` à `commonTest` : 26 cas de plus sur chaque cible, dont
  wasmWasi qui n'en exécutait aucun.
- `@garmin/fitsdk` disparaît des artefacts publiés (il reste en dépendance de test JS, pour
  décoder ce que l'encodeur écrit avec une implémentation qui ne sait rien de ce port).
  `@glandais/vcyclist-engine` ne tire plus 1,3 Mo de SDK vendeur, et l'alias Vite de la démo qui
  n'existait que pour résoudre cet import transitif disparaît aussi.
- `FitMessageNumbers` était une table de numéros de messages écrite pour l'`actual` JS : supprimée,
  le SDK typé est la source de vérité.

**Le coût, à surveiller.** +183 Ko de `.wasm` (+58 %). Ce n'est pas l'encodeur : `Mesg` référence
`Factory`, qui nomme les 123 classes de messages du profil, donc l'élimination de code mort les
garde toutes alors que cet encodeur en écrit cinq. Le corriger se ferait côté `fit-kotlin-sdk`
(sortir `Factory` du chemin d'écriture), pas ici.

**ABI.** `vcPathToFit` passe de `ERR_UNSUPPORTED` à un vrai fichier, et `vcPathsToFit` apparaît.
La signature ne change pas et aucun hôte qui traitait `-4` ne casse, donc la version d'ABI reste
à 1. Seule particularité : `startTimeEpochMs` est le seul champ de payload obligatoire de toute
l'ABI — un `Path` a une horloge relative, FIT n'a aucun moyen de le dire, et un défaut daterait
chaque course de 1989.
