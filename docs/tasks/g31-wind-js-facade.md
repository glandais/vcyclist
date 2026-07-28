# g31 — Façade JS pour `dominantHeadwindDirection`

## Goal

`Path.dominantHeadwindDirection()` (g26) est en `commonMain` de `:engine` et **n'a aucun appelant
dans le dépôt**. La fiche g26 avait laissé la façade JS optionnelle, faute de besoin identifié
côté démo ; l'absence d'appelant est justement ce qui rend la fonction invisible.

Cette fiche l'expose à JavaScript, et fait de la démo son premier consommateur — c'est là que le
résultat a le plus de sens : l'utilisateur charge une trace, la démo lui propose « vent le plus
défavorable » en un clic, et la simulation repart avec ce réglage.

## Depends on

- `g26` (livrée)
- Indépendante de `g29`, qui rattrape un retard sur d'autres fonctions ; les deux touchent
  `EngineJsApi.kt` et devraient donc être séquencées, pas menées en parallèle.

## Inputs

- `engine/src/commonMain/…/path/PathWind.kt` — la fonction, son repère est-nord, son `null`
- `engine/src/jsMain/…/EngineJsApi.kt` — conventions d'export, DTO existants
- `engine/src/commonMain/…/physics/wind/WindProviderConstant.kt` — la cible du réglage
- [`docs/kotlin-js-jvm-webp.md`](../kotlin-js-jvm-webp.md) — **à lire avant** de toucher `jsMain`
- `demo/src/` — le formulaire de vent, `engine-shim.ts`

## Steps

### 1. Forme de l'export

`Vector3D` vient de `:elevation` et n'est pas `@JsExport`-able. Trois formes possibles :

| Forme | Signature | Commentaire |
|---|---|---|
| **A. Azimut** | `fun dominantHeadwindAzimuth(path: Path): Double` | Un cap en degrés, 0 = nord, 90 = est. `NaN` pour « pas de réponse » |
| **B. Composantes** | `fun dominantHeadwind(path: Path): DoubleArray?` | `[x, y]` est-nord, `null` si pas de réponse |
| **C. DTO** | `external interface WindDto { val azimuthDeg: Double }` | Cohérent avec `ClimbDto`, extensible |

**Recommandation : A.** Le consommateur veut régler un vent, et un vent se règle par une
direction et une vitesse — pas par un vecteur unitaire dont il devrait retirer l'azimut lui-même.
`WindProviderConstant` prend d'ailleurs un cap, ce qui rend la conversion inutile des deux côtés.

Pour le cas « pas de réponse » : **`Double.NaN`**, pas `null`. Kotlin/JS rend `Double?` en
`number | null` et un appelant TypeScript doit alors tester deux choses ; `Number.isNaN` est un
test unique, idiomatique, et impossible à confondre avec 0° (plein nord), qui est une réponse
valide. Le documenter explicitement dans le KDoc **et** dans le `.d.ts` par le KDoc.

Prévoir aussi la variante multi-path, en miroir de la surface Kotlin :

```kotlin
@JsExport fun dominantHeadwindAzimuth(path: Path): Double
@JsExport fun dominantHeadwindAzimuthOfTracks(paths: Array<Path>): Double
```

### 2. Conversion azimut

L'azimut se lit `atan2(x, y)` — `x` est-ouest, `y` nord-sud —, ramené dans `[0, 360)`. C'est
exactement ce que fait `PathWindTest.azimuthDeg()`, écrit pour les besoins des tests de g26. **Ne
pas le dupliquer** : le remonter dans `PathWind.kt` comme fonction publique
(`Vector3D.azimuthDeg()` ou `Path.dominantHeadwindAzimuthDeg()`), et faire pointer le test et la
façade dessus. Une définition d'azimut en double exemplaire est une divergence de signe en
attente.

### 3. Vérifier la convention de cap attendue par `WindProviderConstant`

Point à contrôler **avant** de câbler la démo : un vent « de secteur nord » désigne en météo un
vent qui **vient** du nord. `dominantHeadwindDirection` renvoie la direction **vers laquelle** le
vent souffle (c'est l'opposé de la marche). Selon la convention retenue par
`WindProviderConstant`, il faut ajouter 180° ou non.

C'est le seul vrai risque de cette fiche : une erreur ici donne un vent parfaitement favorable là
où l'utilisateur en demandait un défavorable, et rien dans les types ne l'attrape. Un test qui
enchaîne `dominantHeadwindAzimuth` → réglage du provider → simulation, et vérifie que la vitesse
moyenne **baisse** par rapport à un vent nul, est le garde-fou approprié.

### 4. Démo

Un bouton ou une case « vent le plus défavorable » à côté des champs de vent existants, qui
remplit la direction. La vitesse reste choisie par l'utilisateur : cette fonction ne dit rien de
la force du vent, seulement de son orientation — le préciser dans le libellé ou une infobulle.

Mettre à jour `demo/src/engine-shim.ts`.

## Outputs

Modifiés :

- `engine/src/commonMain/…/path/PathWind.kt` (azimut public)
- `engine/src/jsMain/…/EngineJsApi.kt`
- `engine/src/commonTest/…/path/PathWindTest.kt` (pointe sur l'azimut public)
- `demo/src/…`, `demo/src/engine-shim.ts`
- `README.md` — section JS/TS si l'API y est listée

## Validation

```bash
./gradlew :engine:allTests
./gradlew check ktlintCheck
./gradlew :demo:assemble
```

| # | Cas | Attendu |
|---|---|---|
| 1 | Trace plein nord | azimut 180° ± 1 |
| 2 | Trace plein est | azimut 270° ± 1 |
| 3 | Path de 3 points | `NaN` |
| 4 | Deux paths opposés | `NaN` |
| 5 | Azimut public vs `PathWindTest` pré-g31 | mêmes valeurs, le test ne fait que changer de source |
| 6 | `dominantHeadwindAzimuth` depuis Node | valeur numérique, pas `undefined` |
| 7 | Simulation avec ce vent vs vent nul | vitesse moyenne **plus basse** — la convention de cap est la bonne |
| 8 | `.d.ts` généré | la fonction y figure, son KDoc explique le `NaN` |

Le cas 7 est le seul qui teste ce qui compte vraiment : que le vent produit soit effectivement
défavorable.

## Done when

- [x] Azimut exposé en `commonMain`, une seule définition dans le dépôt
- [x] `@JsExport` mono-path et multi-path
- [x] Convention de cap vérifiée **par une simulation**, pas par lecture du code
- [x] `NaN` documenté dans le KDoc, visible dans le `.d.ts`
- [x] `engine-shim.ts` à jour ; pas de câblage UI, voir Résultat
- [x] `./gradlew check` + `ktlintCheck` verts

## Résultat

### Le risque annoncé était réel, et l'inverse de ce qui était écrit

La fiche prévenait qu'une erreur de 180° donnerait un vent parfaitement favorable là où
l'utilisateur en demande un défavorable, et imposait de trancher **par simulation**. Bien vu :

1. Première implémentation, déduite du KDoc d'`AeroPowerProvider` (« [Wind.directionRad] suit la
   convention météorologique, 0 = Nord ») : `azimut = vecteur + 180°`.
2. Test de simulation : parcours plein nord, vent de 8 m/s au cap ainsi calculé → **15,0 m/s de
   moyenne contre 10,1 m/s sans vent**. Soit 50 % plus rapide. C'était un vent arrière.
3. Cause trouvée : `Path.computeBearing` renvoie `atan2(-dy, dx)` sur un `y` orienté nord, donc un
   cycliste plein nord a un cap de `−π/2` et non `+π/2`. Ce signe inverse le sens de l'angle
   `alpha` dans la formule d'Isvan. Il est porté **verbatim** de `Path.ts`
   (`// Negative dy for correct bearing`), donc structurant pour la parité TS : on ne le corrige
   pas, on le documente.

**Convention effective du moteur** : `Wind.directionRad` désigne la direction vers laquelle le
vent souffle, `0` = nord. Le KDoc d'`AeroPowerProvider` et celui de `WindDto` disaient l'inverse
depuis l'origine ; les deux sont corrigés, avec la raison et le renvoi au test.

`dominantHeadwindAzimuthDeg` renvoie donc l'azimut du vecteur **tel quel**, sans bascule.

### API

- `Path.dominantHeadwindAzimuthDeg()` et `List<Path>.dominantHeadwindAzimuthDeg()` en
  `commonMain` : une seule définition de l'azimut dans le dépôt, celle que la façade et les tests
  utilisent.
- `@JsExport fun dominantHeadwindAzimuth(path)` et `dominantHeadwindAzimuthOfTracks(paths)`.
- **`NaN`, pas `null`**, pour « pas de réponse » : un `Double?` traverse vers JS en
  `number | null` et impose deux tests à l'appelant, alors que `0` est une réponse valide qui ne
  doit pas être confondue avec l'absence de réponse. `Number.isNaN` est un test unique.

### Démo : shim seulement

`dominantHeadwindAzimuth` est exposé et typé dans `demo/src/engine-shim.ts`, mais aucun bouton
n'est ajouté. Le `WindTab` a bien un champ de direction ; y greffer un bouton « vent le plus
défavorable » est une fonctionnalité de démo à part entière (libellé, comportement quand la
réponse est `NaN`, interaction avec le curseur existant), pas le portage d'API que cette fiche
couvre. La prise est posée, typée, testée.

### Vérification

- 7 cas dans `PathWindAzimuthTest` (commonTest) × 3 cibles, dont **deux par simulation** : le vent
  annoncé ralentit la course (cas 06) et son opposé l'accélère (cas 07). Sans le second, le
  premier ne prouverait rien.
- 3 cas dans `EngineJsApiCatchupTest` (jsTest) pour la façade.
- Un nom de test de g26 corrigé au passage : « a line due north gives a wind from the south »
  décrivait l'inverse de ce que la fonction renvoie.
- `./gradlew check` + `ktlintCheck` + `:demo:assemble` verts.

## Notes

- **Pourquoi maintenant.** g26 a porté la fonction parce qu'un consommateur externe la demandait ;
  elle reste sans appelant interne, donc sans preuve d'usage. La démo est le moyen le moins cher
  de la valider en conditions réelles.
- **Le CLI est un autre candidat** — une option `--wind-worst-case` sur `enhance` réglerait le
  vent à partir de la trace. Volontairement hors de cette fiche : à ouvrir séparément si l'usage
  se confirme côté navigateur, pour ne pas câbler deux surfaces sur une convention de cap qui
  n'aura été vérifiée qu'une fois.
- **`isCrossing` reste non porté** (décision de g26). Rien ici ne la remet en cause.
