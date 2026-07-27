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

- [ ] `detectClimbs` + `detectClimbsWithOptions` exportés en JS et Wasm
- [ ] `ClimbDto` / `ClimbPartDto` traversent correctement les deux frontières
- [ ] `.d.ts` régénérés
- [ ] Onglet Cols dans la démo, avec tableau et navigation
- [ ] Coloration des portions sur le profil et sur la carte
- [ ] Tests jsTest et wasmJsTest verts
- [ ] `:demo:assemble`, `typecheck` et `lint` verts
- [ ] Validation manuelle effectuée sur `stelvio.gpx` et `sample.gpx`

## Notes

- **Tableaux imbriqués en Wasm** : `Array<ClimbDto>` où `ClimbDto` contient lui-même un
  `Array<ClimbPartDto>` est le point à vérifier en premier. Si ça ne passe pas, exposer deux
  fonctions (`detectClimbs` puis `climbParts(climbIndex)`) plutôt que de tordre le modèle.
- **`stelvio.gpx` est le cas de démonstration** : un col unique, long, régulier, avec un
  dénivelé connu. C'est la fixture qui rend le résultat vérifiable à l'œil.
- **Ne pas recalculer à chaque rendu** : la détection tourne sur un path de ~1000 points après
  simplification, donc c'est rapide, mais un recalcul par frame reste un défaut de conception.
- Cette tâche n'a de sens que si g11 est terminée et validée : ne pas la commencer en parallèle.
