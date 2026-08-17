# 41 — Façade JS : rattrapage sur R9, R15, R16, R18 et R19

## Goal

Cinq entrées du ledger recherche ont livré dans le cœur **et** dans le CLI sans toucher
`EngineJsApi`. Un consommateur JavaScript — la démo, mais aussi n'importe quel appelant npm — n'a
aucun moyen de les atteindre :

| Manque | Livré par | CLI | Façade JS |
|---|---|---|---|
| Condition de route sèche/mouillée | R9 | `--road-condition` | ✗ |
| Rider « critical power » (W′ dépensé, retour vers CP) | R16 | `--cyclist-model=critical-power`, `--cyclist-cp`, `--cyclist-wprime` | ✗ |
| Limite de pente de puissance (50 W/s) | R18 | `--cyclist-slew` | ✗ |
| Allure terrain (plus dur en montée / vent de face) | R19 | `--cyclist-pacing` | ✗ |
| CP et W′ du champ W′bal | R15 | `WPrimeBalanceOptions` | ✗ |

C'est le même constat que `g29`, sur une autre série de tâches : cinq fiches d'affilée ont oublié
la même surface. R10 et R17 sont les deux seules à l'avoir relayée.

La fiche ne fait que **rendre atteignable** : aucune UI, aucun changement de comportement par
défaut.

## Depends on

- R9, R15, R16, R17, R18, R19 (toutes livrées)
- `40` (réparation `constant_tiring`) — indépendante techniquement, mais livrer celle-ci sur une
  démo cassée n'aurait pas de sens
- Bloque `42`

## Inputs

- `engine/src/jsMain/…/EngineJsApi.kt` — `EnhanceOptionsDto:105`, `CyclistDto:121`,
  `PowerProviderDto:167`, `toCyclistPowerProvider:455`, `toEnhanceOptions:369`
- [`docs/kotlin-js-jvm-webp.md`](../kotlin-js-jvm-webp.md) — **à lire avant** de toucher `jsMain`
- `cli/src/main/kotlin/…/mixin/CyclistMixin.kt` — la surface de référence, elle a tout
- `engine/src/commonMain/…/EnhanceOptions.kt` — `WPrimeBalanceOptions`
- `docs/research/improvements-ledger.md` — R9, R15, R16, R18, R19
- `demo/src/engine-shim.ts` — les types TS à suivre si la surface bouge
- `docs/tasks/g29-js-facade-catchup.md` — la règle de compatibilité déjà posée

## Steps

### 1. Règle de compatibilité (héritée de g29)

Aucune signature existante ne change de sens. Kotlin/JS ne connaît pas la surcharge. Donc :
paramètre optionnel à défaut neutre quand une option s'ajoute, fonction nouvelle quand la
**forme** d'un argument change. Ici tout passe par des champs `?` sur les DTO existants — aucune
fonction nouvelle n'est nécessaire.

### 2. R9 — condition de route sur `CyclistDto`

```kotlin
external interface CyclistDto {
    …
    /** `"dry"` (défaut) ou `"wet"`. Applique le preset [RoadCondition] correspondant. */
    val roadCondition: String?
}
```

Une **chaîne**, pas l'`enum class` : `g29` a déjà tranché que les énumérations Kotlin traversent
vers JS sous une forme que personne n'a envie d'importer. Valeur inconnue → `error(…)`, comme
`PowerProviderDto.type`.

Point à trancher explicitement dans la fiche d'implémentation : `roadCondition` et
`maxLeanAngleDeg` / `maxBrakeG` sont deux façons de dire la même chose. Le preset s'applique
**après** les champs bruts et les écrase — c'est l'ordre du CLI, et le seul qui rende
`{maxLeanAngleDeg: 42, roadCondition: 'wet'}` prévisible.

### 3. R16 + R18 + R19 — `PowerProviderDto`

```kotlin
external interface PowerProviderDto {
    val type: String            // + "critical-power"
    val power: Double?
    val useHarmonics: Boolean?
    val criticalPower: Double?
    val wPrime: Double?         // R16, J
    val pacing: Boolean?        // R19, défaut false
    val slew: Boolean?          // R18, défaut false
}
```

R18 et R19 sont des **décorateurs**, pas des types : ils composent par-dessus n'importe quel
`type`. D'où deux booléens plutôt que trois valeurs de `type` de plus (l'alternative aurait été
`"critical-power+pacing+slew"`, ce qui est une machine à erreurs de frappe).

L'ordre de composition n'est pas libre — le CLI câble `base → pacing → slew` et le KDoc de
`PowerProviderSlewLimited` explique pourquoi le slew est le plus externe. La façade **doit**
réutiliser le même ordre, pas le redécider.

Les magnitudes (50 W/s, gains de pente, fenêtre de 300 m, tolérance du compte d'énergie) ne sont
**pas** exposées à ce stade : ce sont des paramètres « à nous » au sens du ledger, et une démo qui
les offre en sliders invite à lire comme réglable ce qui n'est pas sourcé. `taperStartFraction`
non plus.

### 4. R15 — CP et W′ du champ `wPrimeBalance`

```kotlin
external interface EnhanceOptionsDto {
    …
    val wPrimeBalanceEnabled: Boolean?      // défaut : celui de EnhanceOptions (true)
    val wPrimeBalanceCriticalPower: Double?
    val wPrimeBalanceWPrime: Double?
}
```

À noter, et à documenter dans le KDoc : le champ est **déjà calculé** aujourd'hui côté JS, puisque
`EnhanceOptions.wPrimeBalance.enabled` vaut `true` par défaut et que `toEnhanceOptions` ne le
touche pas. La démo affiche donc déjà une trace W′bal, à CP 250 / W′ 20 kJ non modifiables. Cette
fiche ne l'allume pas, elle la rend calibrable.

Piège de cohérence : rien n'oblige `wPrimeBalanceCriticalPower` à valoir le `criticalPower` du
`PowerProviderDto`. Deux CP différents dans le même appel donnent une trace W′bal qui ne décrit
pas le coureur simulé. Ne pas les fusionner de force (R15 documente pourquoi CP/W′ vivent sur les
options et pas sur `Cyclist`), mais le dire.

### 5. `.d.ts` et shim

Vérifier le `.d.ts` **généré**, pas le supposé — et celui de `compileSync/js/main/…`, pas celui de
`build/dist/js/productionLibrary/` qui n'est pas régénéré par `jsBrowserDistribution` (piège
relevé par `g29`). Répercuter dans `demo/src/engine-shim.ts`, qui est écrit à la main.

## Outputs

Modifiés :

- `engine/src/jsMain/…/EngineJsApi.kt`
- `demo/src/engine-shim.ts` — types seuls, pas d'UI (c'est `42`)
- `README.md` — section « Use from JavaScript / TypeScript », si les extraits touchent la puissance
- `docs/research/improvements-ledger.md` — la colonne Status de R9/R15/R16/R18/R19 ne change pas,
  mais chaque entrée gagne une ligne « côté JS » : le ledger est la surface de suivi de la
  recherche, et « livré dans le cœur » y a été lu comme « livré » cinq fois de suite

Créés :

- Tests dans `engine/src/jsTest/…` — passage de paramètre uniquement. Le comportement de R9/R16/
  R18/R19 est déjà couvert en `commonTest`, il n'est pas à retester ici.

## Validation

```bash
./gradlew :engine:jsNodeTest :engine:jsBrowserTest
./gradlew check ktlintCheck
```

| # | Cas | Attendu |
|---|---|---|
| 1 | DTO sans aucun champ nouveau | sortie **bit-identique** à pré-41 |
| 2 | `roadCondition: 'dry'` | identique au cas 1 (le ledger garantit `DRY` bit-pour-bit) |
| 3 | `roadCondition: 'wet'` sur `stelvio.gpx` | plus lent, de l'ordre de +7 % (R9 mesure 576 → 617 s) |
| 4 | `roadCondition: 'bogus'` | lève |
| 5 | `type: 'critical-power'` sur `sample.gpx` | plus lent que `constant`, ~+8 % (R16) |
| 6 | `slew: true` | `\|ΔP/Δt\|` max entre points pédalés ≤ 50 W/s |
| 7 | `pacing: true` sur `strava.gpx` | plus **rapide** de ~3 % à puissance moyenne plus basse (R19) |
| 8 | `pacing + slew` | ordre de composition identique à celui du CLI, vérifié sur la sortie |
| 9 | `wPrimeBalanceCriticalPower` bougé | seul `wPrimeBalance` bouge, les 37 autres champs identiques |
| 10 | `.d.ts` généré | les champs nouveaux y sont bien optionnels |

Le cas 1 est le plus important : c'est lui qui autorise à livrer sans casser la démo ni les
consommateurs npm. Le cas 9 rejoue la garantie que R15 avait fait épingler par un test.

Les seuils des cas 3, 5 et 7 sont repris des mesures du ledger et servent d'ancrage : un écart de
signe ou d'ordre de grandeur signale un câblage faux, pas une tolérance à élargir.

## Done when

- [x] `roadCondition` sur `CyclistDto`, préséance sur les champs bruts documentée
- [x] `wPrime`, `pacing`, `maxSlewWPerS` et `type: "critical-power"` sur `PowerProviderDto`
- [x] Ordre de composition `base → pacing → slew` partagé avec le CLI, pas redécidé
- [x] Trois champs W′bal sur `EnhanceOptionsDto`
- [x] Cas 1 vérifié (aucune sortie ne bouge à DTO inchangé)
- [x] `.d.ts` inspecté, `engine-shim.ts` à jour
- [x] `./gradlew check` + `ktlintCheck` verts

## Résultat

11 tests dans `EngineJsApiLedgerTest` (jsTest), verts sur Node et navigateur. `./gradlew check` +
`ktlintCheck` + typecheck/lint de la démo verts.

### Deux écarts assumés par rapport à la fiche

**Le slew est un nombre, pas un booléen.** La fiche prévoyait `slew: Boolean?` au motif que les
magnitudes de R18/R19 ne devaient pas sortir. Mais le CLI expose déjà `--cyclist-slew` en W/s avec
`0` pour désactiver : inventer une forme différente côté JS aurait créé exactement l'écart entre
surfaces que la fiche `43` cherche à empêcher. La règle devient donc « suivre le CLI », plus simple
et plus défendable que « ne pas exposer les magnitudes » : le slew sort en `maxSlewWPerS: Double?`,
et les magnitudes de *pacing* (gains de pente, fenêtre de 300 m, compte d'énergie) restent cachées —
le CLI ne les expose pas non plus.

**`roadCondition` écrase, alors que le CLI laisse gagner l'explicite.** Les champs de `CyclistDto`
ne sont pas nullables : un appelant JS fournit toujours les six, donc « l'explicite gagne » rendrait
le preset inatteignable. Documenté dans le KDoc avec la raison, et l'omission conserve le
comportement pré-R9.

### Ce que les tests ont appris

Trois échecs sur les premiers jets, tous instructifs :

- **`pCyclistProvidedOptimalPower` n'est pas la sortie des décorateurs.** Il est écrit par
  `CyclistPowerProviderBase`, donc par le provider le plus *interne* : il enregistre l'intention du
  coureur avant pacing et avant slew. La valeur décorée est celle que reçoit
  `MuscularPowerProvider`, qui écrit `pCyclistProvidedMuscular`. Mesurer le slew sur le mauvais
  champ donnait 551 W/s pour une limite à 50.
- **Le dernier point d'une trace n'est jamais simulé** : le provider n'y est pas appelé et le champ
  reste à sa valeur d'initialisation. Une chute 300 → 0 W apparaît donc en fin de trace et n'est pas
  un freinage. Le test `commonTest` de R18 l'ignorait déjà, via son filtre sur les zéros posé pour
  une autre raison (R10).
- **Le cut-off anti-pédale (R10) se déclenche en ligne droite.** `MaxSpeedComputer` sature le rayon
  à 200 m et `atan(v²/(g·200))` dépasse 20° vers 28 m/s : une descente rapide lève les pédales sans
  le moindre virage. Les cas R18 passent donc `maxPedalingLeanAngleDeg: 90`.

### Une assertion retirée parce qu'elle était fausse

La fiche demandait de vérifier que le run *non* limité viole la borne de 50 W/s, pour prouver que le
test n'est pas vide. **Il ne la viole pas** : mesuré à 0 W/s. Avec une cible de puissance constante
il n'y a rien à lisser sauf le départ — ce que le ledger dit noir sur blanc de R18, qui ne devient
porteur qu'avec un provider réactif au terrain. L'assertion discriminante est donc le départ : sans
limite le coureur apparaît à 300 W au premier point, avec limite il part plus bas et monte à
exactement 50 W/s. Garder la borne seule aurait produit un test vert et vide.

### Le `.d.ts` ne peut pas servir de source pour le shim

Vérifié sur le fichier généré : Kotlin/JS **n'émet aucun corps** pour un `external interface`. Les
DTO n'apparaissent que par leur *nom* dans la signature de `enhanceWithCourse` ; les seules
interfaces avec un corps dans le `.d.ts` (`FitDecodeError`…) viennent d'une dépendance tierce
`@JsExport`ée. C'est la raison pour laquelle `demo/src/engine-shim.ts` est écrit à la main, et cela
**invalide l'étape 4 de la fiche `43`** telle qu'elle était rédigée : on ne peut pas dériver le shim
du `.d.ts`. La fiche est corrigée en conséquence.

## Notes

- **Pourquoi pas de sliders pour les magnitudes.** Le ledger est explicite sur R19 : « seule
  l'asymétrie est sourcée, toutes les magnitudes sont les nôtres ». Les exposer dans une API
  publique leur donnerait un statut qu'elles n'ont pas. Elles restent atteignables en Kotlin.
- **R14 et R20 ne sont pas dans cette fiche** : ils ne sont pas implémentés dans le cœur. Rien à
  relayer.
- **R22 ne le sera jamais** : le ledger le rejette comme comportement par défaut du coureur.
