# g30 — Quelle puissance le GPX exporte-t-il ?

## Goal

Les deux formats de sortie ne lisent pas le même champ :

| Format | Champ source | Défini par |
|---|---|---|
| GPX (`GpxFromPath.toGpxTrack`) | `pInputPower` — `P_INPUT_POWER("pInputPower", "watts", "GPX input power")` | la trace **d'entrée** |
| FIT (`PathToFit`) | `pComputedPower` — `POWER("pComputedPower", "watts", "Total power (watts)")` | la **simulation** |

Conséquence, constatée en écrivant les tests CLI de g23 : `enhance` sur une trace sans capteurs
produit un GPX **sans aucune balise `<extensions>`**, alors que le FIT du même parcours porte la
puissance simulée point par point. L'utilisateur qui compare les deux fichiers voit une
information disparaître sans explication.

Ce n'est pas un bug isolé : c'est une question de conception jamais tranchée. Cette fiche la
tranche.

## Depends on

- Rien techniquement. À faire de préférence **avant** `g29`, qui exporte l'écriture GPX vers JS —
  autant que la sémantique soit fixée avant d'élargir le public.

## Inputs

- `gpx/src/commonMain/…/gpx/GpxFromPath.kt:36-60` — `powerW = pInputPower(i).takeUnless { it.isNaN() }`
- `fit/src/commonMain/…/PathToFit.kt` — `powerW = pComputedPower(i).optionalComputed()?.roundToInt()`
  et le KDoc qui justifie déjà ce choix pour FIT
- `gpx/src/commonMain/…/path/PointField.kt:111` (`P_INPUT_POWER`) et `:156` (`POWER`)
- `gpx/src/commonMain/…/gpx/GpxParser.kt` — `parseExtensions`, qui remplit `pInputPower` à la lecture
- `../virtual-cyclist/src/gpx/` — ce que fait la référence TS à l'écriture

## Steps

### 1. Établir ce que fait la référence TS

Avant de décider quoi que ce soit : `virtual-cyclist` écrit-il la puissance simulée dans son GPX
de sortie ? La réponse cadre la discussion, parce que la parité avec la référence est un
invariant du projet (cf. `docs/parity.md`) et qu'un écart délibéré doit être documenté comme tel.

Vérifier aussi le comportement de gpx2web (`GPXFileWriter.writePath`, quel `PropertyKey` il lit).

### 2. Trancher

| Option | Effet | Argument pour | Argument contre |
|---|---|---|---|
| **A. Statu quo** | GPX = puissance d'entrée | Aucune ambiguïté pour le consommateur : ce qui est écrit a été mesuré | Un GPX virtualisé perd la seule donnée que la simulation produit |
| **B. Puissance simulée quand elle existe** | `pComputedPower` si non nul, sinon `pInputPower` | Symétrie avec FIT ; l'utilisateur retrouve son résultat | Une puissance simulée devient indiscernable d'une mesure pour tout outil aval (Strava, Golden Cheetah…) |
| **C. Paramètre explicite** | `powerSource: PowerSource = INPUT` | L'appelant décide en connaissance de cause | Un paramètre de plus sur une signature qui en compte déjà cinq (cf. g23) |

**Recommandation : C, avec `INPUT` par défaut.** B est séduisant mais fait entrer une donnée
simulée dans un format que tout l'écosystème lit comme un enregistrement — c'est exactement le
genre de choix qui doit être demandé, pas subi. C préserve le défaut actuel et rend l'autre
comportement atteignable, y compris depuis le CLI (`--gpx-power computed`).

Si C est retenue : `enum class GpxPowerSource { INPUT, COMPUTED, COMPUTED_OR_INPUT }`, la
troisième valeur couvrant le cas « ma trace a des capteurs sur une partie seulement ».

### 3. Documenter la décision là où la question se pose

Quelle que soit l'option retenue, le KDoc de `toGpxTrack` doit dire **quel champ il écrit et
pourquoi**, en une phrase. Aujourd'hui il ne le mentionne pas, ce qui est la vraie raison pour
laquelle l'asymétrie a survécu si longtemps.

Ajouter une ligne dans `docs/gpx2web-coverage.md` § *Divergences* si le comportement s'écarte de
gpx2web ou de la référence TS.

### 4. Vérifier les autres champs au passage

`pInputPower` est le seul champ où l'entrée et la sortie de la simulation coexistent, mais
vérifier explicitement pour la fréquence cardiaque, la cadence et la température : le pipeline
les recopie-t-il, les recalcule-t-il ? Si un autre champ a le même double, il relève de la même
décision et doit être traité dans la même fiche.

## Outputs

Modifiés :

- `gpx/src/commonMain/…/gpx/GpxFromPath.kt` (+ `GpxWriter` si l'option C ajoute un paramètre)
- `cli/src/main/kotlin/…/command/{EnhanceCommand,ExportCommand}.kt` (option C)
- `cli/README.md`, `docs/gpx2web-coverage.md`
- Tests dans `gpx/src/commonTest/…/gpx/GpxFromPathTest.kt`

## Validation

```bash
./gradlew :gpx:allTests :cli:test
./gradlew check ktlintCheck
```

| # | Cas | Attendu |
|---|---|---|
| 1 | Trace avec `pInputPower`, défaut | sortie identique à pré-g30 |
| 2 | Trace virtualisée sans capteurs, défaut | comportement documenté, quel qu'il soit |
| 3 | Option C, `COMPUTED` | `<power>` porte la puissance simulée |
| 4 | Option C, `COMPUTED_OR_INPUT`, trace mixte | la simulée prime, l'entrée comble les trous |
| 5 | Round-trip GPX → parse → GPX | la valeur écrite revient dans `pInputPower` (par construction du parser) |
| 6 | GPX et FIT du même parcours | l'écart de contenu est celui qu'annonce la documentation, et rien de plus |

Le cas 5 mérite attention : ce qui est écrit comme puissance **redevient** de la puissance
d'entrée au re-parse. Un aller-retour `COMPUTED` transforme donc une valeur simulée en valeur
« mesurée ». C'est inhérent au format, pas corrigeable — mais c'est à écrire noir sur blanc.

## Done when

- [x] Comportement de la référence TS et de gpx2web établi
- [x] Option tranchée et justifiée dans la fiche
- [x] KDoc de `toGpxTrack` disant quel champ il écrit et pourquoi
- [x] Défaut préservant le comportement actuel
- [x] Le cas 5 (blanchiment de la puissance simulée au round-trip) documenté
- [x] `./gradlew check` + `ktlintCheck` verts

## Résultat

### Étape 1 : les deux références **ne font pas la même chose**

- **`virtual-cyclist` (TS)** écrit `pInputPower` : `GPXWriter.ts:197` garde sur
  `!isNaN(trackPoint.pInputPower)`. Sa simulation n'écrit jamais dans ce champ. Sortie = puissance
  d'entrée uniquement — exactement le comportement actuel de vcyclist.
- **gpx2web** écrit la puissance **simulée**, mais pas par choix : il n'a qu'un seul emplacement
  `power` (`PropertyKeys.power`), rempli à la lecture par `GPXFileReader:255` puis **écrasé** par
  `VirtualizeService:99` (`newPoints.get(i).setPower(cyclistPower)`). Le writer, lui, sérialise
  aveuglément tout ce que `Point.getGpxData()` contient.

L'écart entre les deux références n'est donc pas un désaccord de conception mais une conséquence
de modèles de données différents : gpx2web ne *peut pas* distinguer les deux puissances, vcyclist
et le port TS le peuvent.

### Étape 2 : option C retenue

`enum class GpxPowerSource { INPUT, COMPUTED, COMPUTED_OR_INPUT }`, paramètre sur
`toGpxTrack` / `toGpxDocument` / `pathsToGpxDocument`, **défaut `INPUT`**.

La recommandation de la fiche est suivie : écrire une donnée simulée dans un format que tout
l'écosystème lit comme un enregistrement est une décision qui doit être demandée, pas subie. Le
défaut préserve la parité TS et le comportement d'avant g30.

**Où le paramètre est posé — et où il ne l'est pas.** `powerSource` est sur les fonctions de
*conversion*, pas sur `GpxWriter.write`. Choisir quelle puissance porte le document est une
question de conversion `Path` → modèle GPX ; le writer, lui, sérialise un document déjà constitué.
Ce découpage a un effet de bord utile : il évite d'ajouter un troisième paramètre optionnel à
`GpxWriter.write`, seuil au-delà duquel g23 s'était engagée à refactorer en objet d'options. Un
appelant qui veut la puissance simulée écrit
`GpxWriter.write(path.toGpxDocument(powerSource = COMPUTED))`.

### Détection d'absence : `0.0` compte comme absent pour la puissance calculée

`pInputPower` est `NaN` quand le fichier n'avait pas de `<power>` — test d'absence simple.
`pComputedPower` est écrit par le pipeline dans un emplacement initialisé à zéro, et
`PowerComputer` y stocke légitimement `0.0` au point 0 et sur tout point en roue libre. Un path
jamais simulé est donc **tout à zéro**, indiscernable par la valeur seule d'une descente simulée.
`COMPUTED` traite donc `0.0` comme absent — même arbitrage que `PathToFit`, et pour la même
raison : une ligne plate à 0 W est pire que pas de ligne. Le cas de test 04 le fige.

### CLI

`--gpx-power-source <input|computed|computed-or-input>` sur `enhance`. **Le nom a dû changer** :
`--gpx-power` existait déjà, avec un sens opposé — il fait *entrer* la puissance enregistrée dans
la simulation (`PowerProviderFromData`) au lieu du modèle du cycliste. picocli refuse la collision
et l'a signalée immédiatement, ce qui est la bonne façon de l'apprendre. Les deux options sont
documentées côte à côte dans `cli/README.md`, avec la mise en garde.

La valeur est validée **dans `call()`**, à côté de `--start-time` : une faute de frappe est une
erreur d'usage (code 64), pas une erreur d'exécution, et elle ne doit pas être découverte après
que la moitié du lot a été écrite.

### Étape 4 : les autres champs

Vérifié : `heartRate`, `cadence` et `temperature` n'ont **pas** de double simulé. Le pipeline les
recopie, aucune étape ne les recalcule. `pInputPower` / `pComputedPower` est le seul couple, donc
la décision ne se généralise pas — rien d'autre à traiter.

### Vérification

- 10 cas dans `GpxPowerSourceTest` (commonTest) × 3 cibles, plus 3 cas CLI (22-24).
- Le cas 08 reproduit exactement la situation qui a fait remonter le problème en g23 (trace
  enhancée sans capteurs) et vérifie les deux comportements.
- Le cas 10 fige le **blanchiment** : un `<power>` simulé relu redevient un `pInputPower`, c'est-à-dire
  une donnée d'apparence mesurée. Inhérent au format, non corrigeable, et c'est la raison
  principale pour laquelle `INPUT` reste le défaut.
- `./gradlew check` + `ktlintCheck` verts.

## Notes

- **Comment le trou a été trouvé** : en écrivant un test CLI pour g23, qui affirmait qu'un GPX
  enhancé contient `<power>`. Il échouait, pour cette raison. Le test a été adapté (fixture avec
  capteurs en entrée) et le constat noté dans les *Notes* de g23.
- **Le point 0 n'a jamais de `<power>` en mode `COMPUTED`.** `PowerComputer` y écrit `0.0` par
  construction (il n'y a pas d'intervalle précédent d'où tirer une puissance), et `0.0` compte
  comme absent. Invisible sur une trace normale, très visible sur une sortie fortement simplifiée
  à deux points — constaté au smoke CLI. Ce n'est pas un défaut de g30 mais une propriété du
  champ ; la contourner supposerait d'inventer une valeur pour le premier point.
- **Ne pas confondre avec l'absence d'extensions.** `--no-extensions` (g23) est un choix de
  l'utilisateur ; ici il s'agit de ce que le writer met dans les extensions **quand elles sont
  demandées**. Les deux réglages sont orthogonaux.
- **FIT n'est pas à changer.** Son KDoc justifie déjà `pComputedPower` : sur un parcours
  virtualisé, `P_INPUT_POWER` est soit absent, soit la description d'une autre sortie.
