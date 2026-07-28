# g26 — Port de `GPXDataComputer.getWind`

## Goal

`docs/gpx2web-coverage.md:198` classe `GPXDataComputer` en « non porté », motif « aucun
consommateur identifié hors de la webapp `gpx-web` ». Le consommateur existe : il est apparu à
la migration. La décision est donc à réviser — ce qui implique de corriger **la ligne du tableau
et l'entrée correspondante** de la section « Explicitement non porté » de `PLAN-GPX2WEB.md`,
qui se veut normative.

**Ce que calcule `getWind`** (`GPXDataComputer.java:83-104`) : la moyenne des vecteurs unitaires
départ → chaque point, projetée en 2D, **normalisée puis inversée**. Autrement dit la direction
d'un vent qui serait *défavorable en moyenne* sur la trace — soit l'orientation dominante du
parcours, retournée. Vecteur nul si la trace fait 3 points ou moins.

C'est un outil de réglage : il répond à « quel vent constant dois-je simuler pour que ce
parcours soit le plus dur possible ». Le nom `getWind` ne le dit pas.

## Depends on

Rien. Indépendant des six autres fiches de la série.

## Inputs

- `../gpx2web/…/util/GPXDataComputer.java` (`getWind`, `getWindUnscaled`, `vector`, `isCrossing`)
- `../gpx2web/…/util/Vector.java` et `data/Point.java` (`project()`)
- `gpx/src/commonMain/…/path/Path.kt` (`latitudeDeg`, `coordinatesAt`)
- `elevation/src/commonMain/…/{Vector3D,EcefConverter}.kt`
- `engine/src/commonMain/…/physics/wind/` — les `WindProvider` existants
- `docs/gpx2web-coverage.md:198`, `docs/PLAN-GPX2WEB.md` § « Explicitement non porté »

## Steps

### 1. Trancher le sort d'`isCrossing` — **au démarrage, par écrit**

`GPXDataComputer` porte deux fonctions sans rapport : `getWind` et `isCrossing` (détection
d'auto-intersection de la trace, sur un path simplifié à 50 m, en O(n²)).

Seul `getWind` a été demandé. Deux issues, à choisir explicitement :

- **`isCrossing` inclus** si le même appelant en a besoin — le porter dans le même fichier, avec
  la simplification Douglas-Peucker à 50 m que fait gpx2web (sans elle, le O(n²) sur un path à
  1 Hz est inutilisable).
- **`isCrossing` reste non porté** sinon, et la ligne du tableau de couverture est mise à jour en
  conséquence (`GPXDataComputer` devient « partiellement porté »).

Ne pas le porter « au cas où » : du code sans appelant est du code non testé en conditions
réelles.

### 2. API

Dans `engine/src/commonMain/…/path/PathWind.kt` :

```kotlin
/**
 * Direction du vent « le plus défavorable en moyenne » pour ce path : l'opposé de son
 * orientation dominante. Vecteur unitaire 2D (composante z nulle), ou `null` si le path
 * compte moins de 4 points.
 *
 * Porté de `GPXDataComputer.getWind(GPXPath)` (gpx2web).
 */
fun Path.dominantHeadwindDirection(): Vector3D?

/** Même calcul agrégé sur plusieurs paths, comme `getWind(GPX)`. */
fun List<Path>.dominantHeadwindDirection(): Vector3D?
```

Deux décisions à acter dans le KDoc :

- **`null` plutôt que le vecteur nul** pour le cas dégénéré. gpx2web renvoie
  `new Vector(0,0,0)` prétendument normalisé : un vecteur nul qui se présente comme unitaire est
  un piège silencieux. Nous refusons de répondre plutôt que de répondre faux.
- **Le nom.** `getWind` ne décrit pas ce que la fonction calcule (ce n'est pas une météo). Le nom
  porté doit être explicite ; le KDoc cite la source Java pour la traçabilité.

### 3. Projection

gpx2web utilise `Point.project()` (Mercator). `:elevation` fournit déjà `EcefConverter`.

Utiliser le projeté **déjà disponible** plutôt que d'introduire une seconde projection dans le
projet — **à condition** de vérifier sur une trace de test que l'écart d'azimut avec la référence
Java reste sous 1°. Aux latitudes moyennes et sur des traces de quelques dizaines de kilomètres,
il devrait être très inférieur. Si ce n'est pas le cas, porter la projection de gpx2web à
l'identique et écrire pourquoi dans les notes.

Mesure de l'écart : rejouer le calcul Java sur une trace de `commonTestFixtures`, comparer les
azimuts, consigner le chiffre dans la section « Résultat » de cette fiche.

### 4. Ne pas câbler sur `WindProvider`

Le résultat est une aide au réglage d'un `WindProviderConstant`, **pas** un provider. Le
documenter explicitement dans le KDoc pour couper court à la question — et montrer en une ligne
d'exemple comment passer du vecteur au `WindProviderConstant`, ce qui est le vrai usage.

### 5. Façade JS

Optionnelle, à évaluer selon le besoin de la démo. Si retenue :
`@JsExport fun dominantHeadwind(path: Path): DoubleArray?` sur `jsMain` — voir
[`docs/kotlin-js-jvm-webp.md`](../kotlin-js-jvm-webp.md). À défaut de
besoin identifié, s'abstenir : une façade non utilisée est une surface à maintenir.

## Outputs

Créés :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/path/PathWind.kt`
- `engine/src/commonTest/…/path/PathWindTest.kt`

Modifiés :

- `docs/gpx2web-coverage.md` (ligne 198)
- `docs/PLAN-GPX2WEB.md` (§ « Explicitement non porté » — l'entrée `util/GPXDataComputer`)

## Validation

```bash
./gradlew :engine:allTests
./gradlew ktlintCheck
```

| # | Cas | Attendu |
|---|---|---|
| 1 | Trace rectiligne plein nord | vecteur unitaire plein sud, ±1° |
| 2 | Trace rectiligne plein est | vecteur unitaire plein ouest, ±1° |
| 3 | Aller-retour parfait | norme ~0 avant normalisation → `null` ou documenté |
| 4 | Boucle fermée (départ = arrivée) | direction dominée par le lobe le plus éloigné, non nulle |
| 5 | Path de 3 points | `null` |
| 6 | Path de 4 points | valeur définie |
| 7 | Path vide | `null`, pas de crash |
| 8 | Composante z | exactement 0.0 |
| 9 | Norme du résultat | 1.0 à 1e-9 près |
| 10 | Agrégat sur 2 paths opposés | ~`null` ou vecteur de norme négligeable, documenté |
| 11 | Écart d'azimut vs référence Java sur une fixture réelle | < 1° |
| 12 | Le même calcul sur les 3 cibles | écart < 1e-9 (tolérance trig, cf. `CLAUDE.md`) |

## Done when

- [ ] Sort d'`isCrossing` tranché **par écrit** avant de commencer
- [ ] `dominantHeadwindDirection()` sur `Path` et `List<Path>`, en commonMain
- [ ] Choix de projection justifié par une mesure d'écart chiffrée
- [ ] `null` en cas dégénéré, documenté
- [ ] KDoc citant la source Java + l'exemple `WindProviderConstant`
- [ ] `gpx2web-coverage.md` et `PLAN-GPX2WEB.md` corrigés
- [ ] `./gradlew check` + `ktlintCheck` verts

## Notes

- **Le tableau de couverture est un contrat, pas un journal.** La section « Explicitement non
  porté » de `PLAN-GPX2WEB.md` se déclare normative : « si l'un d'eux redevient nécessaire, il
  faut une nouvelle tâche ». C'est cette tâche. La mettre à jour fait partie du travail, pas de
  la documentation qui suit.
- **Le motif du refus initial était bon.** « Aucun consommateur identifié » est un critère sain ;
  il s'est simplement révélé faux à l'usage. Rien à corriger dans la méthode.
- **`getWindUnscaled` n'est pas exposé.** C'est un intermédiaire de calcul chez gpx2web
  (la version non normalisée, utile uniquement pour agréger plusieurs paths). Il reste privé ici.
