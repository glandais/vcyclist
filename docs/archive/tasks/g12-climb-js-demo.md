# g12 — Cols : façade `@JsExport` et intégration démo

## Goal

Exposer la détection de cols aux consommateurs JS/Wasm et l'afficher dans la démo Vue : liste
des ascensions et mise en évidence sur le profil d'élévation.

## Depends on

- `g11` (`ClimbDetector` en commonMain)

## Inputs

- `engine/src/{jsMain,wasmJsMain}/…/EngineJsApi.kt` (façades existantes)
- `docs/kotlin-wasm-jvm-webp.md` §4 (choix de DTO : `external interface` en JS vs `@JsFun`
  builder en Wasm)
- `demo/src/**` (17 composants Vue, Chart.js, Leaflet, 6 onglets, `useGPXDemo`)

## Steps

### 1. DTO et façade

Les `data class` Kotlin ne traversent pas la frontière `@JsExport` : il faut des DTO plats,
selon le pattern déjà utilisé pour `FieldDefinitionDto` et les DTO Cyclist/Bike/Wind.

```kotlin
@JsExport
fun detectClimbs(path: Path): Array<ClimbDto>

@JsExport
fun detectClimbsWithOptions(
    path: Path,
    minMinClimbElevationM: Double,
    maxMinClimbElevationM: Double,
    minClimbElevationRatio: Double,
    minGradePercent: Double,
    maxDiffRealGrade: Double,
    booster: Double,
): Array<ClimbDto>
```

`ClimbDto` porte les champs scalaires du `Climb` plus `parts: Array<ClimbPartDto>`. Vérifier
qu'un tableau imbriqué passe correctement en Wasm — sinon aplatir, et documenter.

Écrire les deux versions (jsMain et wasmJsMain) en suivant les façades existantes.

### 2. Intégration démo

**Nouvel onglet « Cols »** dans la barre existante :

- Tableau : n°, distance de départ (km), longueur (km), dénivelé (m), pente moyenne (%),
  pente max des portions.
- Clic sur une ligne → recentrage de la carte Leaflet sur le col, et zoom du graphique sur la
  plage de distance correspondante.

**Sur le profil d'élévation** (Chart.js) : colorer les segments appartenant à un col. Utiliser
les couleurs de pente habituelles du cyclisme (vert < 5 %, jaune 5-8 %, orange 8-11 %, rouge
> 11 %) appliquées par `ClimbPart`.

**Sur la carte** : tracer les portions de col dans une couleur distincte du reste de la trace.

### 3. Réactivité

La détection est un calcul pur sur le `Path` enhancé. La lancer une fois après `enhance`, pas à
chaque rendu. Suivre le schéma de `useGPXDemo` : résultat mémorisé, recalcul uniquement quand
le `Path` change.

Si les options de détection deviennent réglables dans l'UI, un recalcul par changement
d'option est acceptable — c'est un calcul local rapide, mais le mesurer sur `stelvio.gpx`
avant de l'ajouter.

### 4. Persistance

La démo persiste déjà la configuration (`useGPXDemo`). Si des options de détection sont
exposées, les inclure dans le même mécanisme, avec les mêmes défauts que `ClimbOptions.DEFAULT`.

## Outputs

Modifiés :

- `engine/src/jsMain/…/EngineJsApi.kt`
- `engine/src/wasmJsMain/…/EngineJsApi.kt`
- `demo/src/**` : nouveau composant d'onglet, coloration du chart, coloration de la carte,
  types TypeScript, `useGPXDemo`

Créés :

- `engine/src/jsTest/…/EngineJsApiClimbTest.kt`
- `engine/src/wasmJsTest/…/EngineJsApiClimbTest.kt`

## Validation

```bash
./gradlew :engine:jsBrowserTest :engine:wasmJsBrowserTest :engine:jsNodeTest
./gradlew :demo:assemble
./gradlew ktlintCheck
cd demo && npm run typecheck && npm run lint
```

Validation manuelle, obligatoire :

- Charger `stelvio.gpx`, cliquer Enhance : l'onglet Cols affiche une ascension au dénivelé
  plausible (~1500 m).
- Charger `sample.gpx` : les cols détectés correspondent visuellement aux montées du profil.
- Cliquer une ligne du tableau : carte et graphique se recentrent.
- Les couleurs de pente correspondent aux portions.

## Done when

- [x] `detectClimbs` + `detectClimbsWithOptions` exportés en JS et Wasm
- [x] `ClimbDto` / `ClimbPartDto` traversent correctement les deux frontières
- [x] `.d.ts` régénérés
- [x] Panneau Cols dans la démo, avec tableau et navigation
- [x] Coloration des portions sur le profil et sur la carte
- [x] Tests jsTest et wasmJsTest verts
- [x] `:demo:assemble`, `typecheck` et `lint` verts
- [x] Validation manuelle effectuée sur `stelvio.gpx` et `sample.gpx`

## Résultat

**Tableaux imbriqués en Wasm : ça passe.** C'était le point à vérifier en premier. Un
`JsArray<ClimbPartDto>` **à l'intérieur** d'un `ClimbDto` lui-même dans un `JsArray` traverse la
frontière sans rien tordre — l'objet est construit entièrement côté JS par le builder `@JsFun`,
Kotlin n'en détient qu'une référence opaque. Ni aplatissement, ni seconde fonction
`climbParts(index)`. Un test par cible lit effectivement à travers le DTO imbriqué, sinon
l'assertion serait creuse.

**Défaut de performance trouvé — et c'est moi qui l'ai introduit.** Après câblage dans la démo,
`sample.gpx` gelait l'onglet. Attribué proprement en reconstruisant la démo **sans** les
changements g12 : la baseline enhance sans problème, donc la régression venait bien de g12.
Mesuré dans le navigateur :

```
stelvio brut      259 points -> 104 ms
stelvio enhancé   621 points -> 345 ms
```

Soit un coût quadratique — c'est l'algorithme de la référence, `O(n²)` sur le nombre de points.
gpx2web l'applique à des traces simplifiées ; vcyclist lui donnait la sortie du pipeline, et les
options JS par défaut **désactivent** `simplifyPath` et `computeOnePointPerSecond`. Pour
`sample.gpx` (140 km densifié à 1–2 m) cela fait ~25 000 points, soit ~9 minutes par
extrapolation quadratique. D'où le gel.

**Correctif : `ClimbOptions.maxAnalysisPoints` (défaut 3000)**, dans `:engine` plutôt que dans la
démo — le problème touche tous les consommateurs, pas seulement celle-ci. Au-delà du seuil, le
profil est décimé uniformément **pour l'analyse seulement** ; les index rapportés référencent
toujours le path d'origine. Aucune perte de précision réelle : les portions sont déjà
simplifiées à 10–50 m près, donc résoudre le départ d'un col au point le plus proche à ~50 m est
dans le bruit. **En dessous du seuil rien ne change**, donc la validation croisée contre le Java
de g11 reste valable telle quelle — le cas 16 le verrouille.

Au passage, le profil est extrait une fois dans des `DoubleArray` plats au lieu d'appeler les
accesseurs de `Path` dans la boucle interne : gain net sur Kotlin/JS où chaque accès coûte cher.

**Second correctif, plus modeste :** le tracé des portions sur la carte faisait
`parts × longueur` appels à travers la frontière JS/Kotlin (~20 000 sur `sample.gpx`). Réécrit
en une seule passe : les distances et positions sont lues une fois, puis un curseur unique
parcourt le col.

**Conséquence de g10 rencontrée ici.** La démo ne compilait plus : le bundle `:engine` importe
désormais `@garmin/fitsdk` (parce que g10 a mis la façade FIT dans `:engine`), et Vite ne le
résolvait pas — le bundle vit hors du répertoire de la démo, donc hors de son `node_modules`.
Corrigé par un alias dans `vite.config.ts` plus la dépendance déclarée dans `demo/package.json`.
**Coût mesuré : ~976 ko de source `@garmin/fitsdk` entrent dans les entrées de bundle de la
démo**, uniquement parce que le paquet engine réexporte FIT. À arbitrer (cf. Notes).

**Validation manuelle** (les deux cas exigés) :

| Cas | Résultat |
|---|---|
| `stelvio.gpx` + Enhance | 1 col : 1,49 km, +124 m, 8,4 % — la bande orange du graphe couvre exactement la partie montante du profil, le tracé orange suit les lacets sur la carte |
| `sample.gpx` + Enhance | 6 cols : +589 m, +906 m, +1029 m, +380 m, +38 m, +1309 m ; pentes 4,0 % à 7,4 %. Un col de 17,7 km à 7,4 % pour 1309 m de D+ est un hors-catégorie crédible. Bandes alignées visuellement sur les montées du profil. |
| Clic sur une ligne | Carte recentrée sur le col, graphe zoomé sur sa plage de distance, ligne surlignée |
| Couleurs | Barre « Profile » par portion, légende des 4 bandes, cohérence graphe / carte / tableau |

Les valeurs avant et après Enhance concordent (589 contre 592, 906 contre 897, 1029 contre 1020,
1309 contre 1305), ce qui est un contrôle de cohérence gratuit.

**Validation :** `./gradlew check` + `ktlintCheck` verts, `:demo:assemble`, `typecheck` et `lint`
verts. 4 tests jsTest, 3 wasmJsTest, 16 `ClimbDetectorTest` (dont 2 nouveaux sur la décimation).

## Notes

- **Tableaux imbriqués en Wasm** : `Array<ClimbDto>` où `ClimbDto` contient lui-même un
  `Array<ClimbPartDto>` est le point à vérifier en premier. Si ça ne passe pas, exposer deux
  fonctions (`detectClimbs` puis `climbParts(climbIndex)`) plutôt que de tordre le modèle.
- **`stelvio.gpx` est le cas de démonstration** : un col unique, long, régulier, avec un
  dénivelé connu. C'est la fixture qui rend le résultat vérifiable à l'œil.
- **Ne pas recalculer à chaque rendu** : la détection tourne sur un path de ~1000 points après
  simplification, donc c'est rapide, mais un recalcul par frame reste un défaut de conception.
- Cette tâche n'a de sens que si g11 est terminée et validée : ne pas la commencer en parallèle.
- **À arbitrer, hérité de g10 :** `@glandais/vcyclist-engine` dépend de `@garmin/fitsdk` parce que
  la façade `pathToFit` vit dans `:engine`. Mesuré : ~976 ko de source entrent dans les entrées de
  bundle de la démo, qui n'exporte aucun FIT. Trois sorties possibles — laisser tel quel, publier
  `:gpx` en npm pour que `@glandais/vcyclist-fit` puisse être bundlé séparément avec des `Path`
  compatibles, ou charger le SDK à la demande (`import()` dynamique) pour que les bundlers le
  découpent. Décision produit, pas technique.
