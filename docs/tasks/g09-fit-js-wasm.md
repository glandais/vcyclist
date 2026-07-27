# g09 — `:fit` : implémentation JS et Wasm (`@garmin/fitsdk`)

## Goal

Fournir les `actual` JS et Wasm de `FitEncoder`, adossés au SDK JavaScript officiel
`@garmin/fitsdk` (classe `Encoder`), pour que l'export FIT fonctionne dans le navigateur, sous
Node et sous Bun.

C'est la tâche qui rend l'export FIT réellement multiplateforme — sans elle, le module `:fit`
reste un wrapper JVM.

## Depends on

- `g08` (module `:fit`, `expect object FitEncoder`, `FitCourse`, `FitUnits`)

## Inputs

- `elevation/src/jsMain/…/TileFetcher.js.kt` (précédent de référence : dépendance npm consommée
  depuis Kotlin/JS **et** Kotlin/Wasm, avec détection de runtime)
- `elevation/webpack.config.d/externals.js` (montage webpack pour une dépendance npm)
- `docs/kotlin-wasm-jvm-webp.md` §5-§6 (interop et pattern `expect`/`actual`)
- Documentation de `@garmin/fitsdk` : `Encoder`, `Stream`, `Profile`, `Utils`

## Steps

### 1. Déclarer la dépendance npm

Dans `fit/build.gradle.kts` :

```kotlin
jsMain.dependencies { implementation(npm("@garmin/fitsdk", "<version>")) }
wasmJsMain.dependencies { implementation(npm("@garmin/fitsdk", "<version>")) }
```

Épingler une version exacte, pas une plage : un encodeur binaire qui change de comportement
sur un `npm install` est un cauchemar à diagnostiquer.

### 2. Interop Kotlin/JS

`fit/src/jsMain/kotlin/io/github/glandais/fit/FitEncoder.js.kt` :

`external` sur les classes du SDK, construction des messages sous forme d'objets JS. L'`Encoder`
du SDK consomme des messages de la même forme que la sortie du `Decoder`, et son `close()`
renvoie un `Uint8Array` — à convertir en `ByteArray` Kotlin.

Points d'attention :

- **Nom des messages** : le SDK JS travaille avec des noms de messages et de champs
  (`fileIdMesg`, `recordMesg`, …) là où le SDK Java a des classes typées. Vérifier la
  correspondance exacte via `Profile`.
- **Échelles et offsets** : contrairement aux setters typés du SDK Java, le SDK JS peut
  attendre des valeurs déjà mises à l'échelle. C'est précisément ce que `FitUnits` (g08)
  centralise — un test croisé JVM/JS sur les octets produits tranchera.
- **`Uint8Array` → `ByteArray`** : conversion explicite, attention au signe (les octets Kotlin
  sont signés).

### 3. Interop Kotlin/Wasm

`fit/src/wasmJsMain/kotlin/io/github/glandais/fit/FitEncoder.wasmJs.kt`.

Wasm ne peut pas manipuler d'objets JS arbitraires directement : suivre le pattern
`@JsFun("(…) => ({ … })")` documenté en `docs/kotlin-wasm-jvm-webp.md` §4, comme le fait
`TileFetcher.wasmJs.kt`.

Le transfert du `Uint8Array` vers Wasm est le point coûteux : copier explicitement dans un
`ByteArray` Kotlin, octet par octet ou via l'API de copie disponible. Mesurer sur un fichier
réaliste (~10 000 records) et documenter le résultat.

### 4. Montage webpack

`@garmin/fitsdk` est un paquet ESM Node + navigateur. Selon la façon dont il est résolu, il
faudra le même traitement que `@jsquash/webp` : `fit/webpack.config.d/externals.js` pour ne pas
l'inliner dans le bundle navigateur, ou au contraire le laisser bundler.

Décider en mesurant : construire le bundle avec et sans, comparer les tailles, et vérifier que
les tests Karma en headless Chrome passent dans les deux configurations. Documenter le choix
dans les Notes.

### 5. Tests

- `jsTest` : encoder un `FitCourse` de 3 records, ré-ouvrir avec le `Decoder` du SDK JS,
  vérifier les valeurs.
- `wasmJsTest` : idem.
- **Test croisé, le plus important** : le même `FitCourse` encodé en JVM et en JS doit produire
  des octets identiques, ou des différences explicables (horodatage de création dans
  `FileIdMesg`, ordre des champs optionnels). Committer les octets attendus en fixture
  commonTest et asserter des deux côtés.

Si les sorties diffèrent de façon non explicable, c'est un bug d'unités ou d'ordre de
messages : ne pas relâcher l'assertion pour faire passer le test.

## Outputs

Créés :

- `fit/src/jsMain/…/fit/FitEncoder.js.kt`
- `fit/src/wasmJsMain/…/fit/FitEncoder.wasmJs.kt`
- `fit/src/jsTest/…/fit/FitEncoderJsTest.kt`
- `fit/src/wasmJsTest/…/fit/FitEncoderWasmTest.kt`
- `fit/src/commonTest/…/fit/FitBytesFixture.kt` (octets de référence)
- éventuellement `fit/webpack.config.d/externals.js`

Modifiés :

- `fit/build.gradle.kts` (dépendances npm)
- `fit/src/{jsMain,wasmJsMain}` : suppression des `actual` provisoires de g08

## Validation

```bash
./gradlew :fit:allTests
./gradlew :fit:jsBrowserTest :fit:wasmJsBrowserTest   # Karma headless Chrome
./gradlew :fit:jsNodeTest
./gradlew ktlintCheck
./gradlew check
```

Critères :

- Les 4 cibles encodent, et le résultat est relisible par le `Decoder` du SDK.
- Le test croisé JVM/JS passe sur des octets identiques, ou les écarts sont documentés champ
  par champ.
- Aucun `NotImplementedError` ne subsiste.

## Done when

- [ ] `@garmin/fitsdk` épinglé en version exacte, jsMain + wasmJsMain
- [ ] `actual` JS implémenté et testé
- [ ] `actual` Wasm implémenté et testé
- [ ] Montage webpack décidé après mesure, documenté
- [ ] Test croisé JVM/JS sur octets, écarts documentés s'il y en a
- [ ] Tests verts en Node **et** en navigateur headless
- [ ] `./gradlew check` et `ktlintCheck` verts

## Notes

- **Risque principal, identifié dans le plan** : `@garmin/fitsdk` en Karma headless Chrome. Si
  le paquet ne se charge pas dans ce contexte, chercher d'abord côté configuration webpack
  avant de conclure à une incompatibilité. En dernier recours, restreindre les tests navigateur
  et documenter la limitation — mais l'export FIT navigateur est la raison d'être de cette
  tâche, donc c'est un échec partiel à signaler, pas à masquer.
- **Version épinglée** : `npm("@garmin/fitsdk", "1.2.3")`, pas `"^1.2.3"`.
- **Le SDK JS supporte bien l'encodage** (classe `Encoder`, vérifié) — ce n'est pas un SDK
  decode-only, contrairement à ce que laissent croire d'anciennes versions de sa documentation.
- Si les octets JVM et JS divergent sur `FileIdMesg.timeCreated`, c'est normal : ce champ porte
  l'instant de création du fichier. L'exclure explicitement de la comparaison plutôt que de
  relâcher l'assertion globale.
