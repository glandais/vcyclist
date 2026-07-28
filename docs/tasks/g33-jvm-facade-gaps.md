# g33 — Les quatre trous que g27 a laissés, trouvés en migrant

## Goal

g27 a posé les façades `…Jvm` sur « toute l'API publique ». La migration du backend Quarkus l'a
mise à l'épreuve bout en bout, et le résultat se mesure : une fois la migration terminée, le
backend nommait encore un type Kotlin à **six endroits**.

```
GpxProcessingService:181   GpxToPathKt.tracksAsPaths(doc, EnumSet.allOf(GpxPathKind.class))
GpxProcessingService:345   kotlin.time.Instant.Companion.fromEpochMilliseconds(...)
GpxProcessingService:368   Path.Companion.fromCoordinates(...) + computeDerivedData()
WindEstimator:36           PathWindKt.dominantHeadwindDirection(paths)
RouterService:115          Path.Companion.fromCoordinates(...) + computeDerivedData()
ThumbnailService:170       Path.Companion.fromCoordinates(...) + computeDerivedData()
```

Aucun de ces six n'est un trou fonctionnel : tout était appelable, et le backend a été livré avec.
C'est le même motif que les cinq fiches d'appelabilité de la phase I — l'API est juste, elle
oblige juste le consommateur à écrire quelque chose qu'il n'a aucune raison de savoir.

Le critère de sortie est le même que celui que le plan de migration s'était donné pour les
coroutines : **le backend ne nomme aucun type `kotlin.*`**, et n'écrit aucun nom de classe généré
par le compilateur.

Une cinquième entrée est un vrai correctif et non un confort d'appel : `Path.withoutTime()`.

## Depends on

- `g27` (livrée) — dont cette fiche est la suite directe : mêmes conventions de façade
  (`@file:JvmName("…Jvm")`, `@JvmOverloads`, fonctions de premier niveau qui délèguent).
- `g24` (livrée) — `GpxPathKind` et le défaut `ALL_KINDS`, que la façade GPX doit pouvoir nommer.
- `g25` (livrée) — le contrat de timestamp FIT, dont la précondition de monotonie motive
  `withoutTime()`.
- `g26` (livrée) — `dominantHeadwindDirection`, portée sans façade JVM.

## Inputs

Les six lignes ci-dessus, relevées sur le backend après migration. Aucune n'est une supposition :
ce sont des lignes livrées, pas des cas imaginés.

## Steps

1. **`PathWindJvm`** (`:engine`, `jvmMain`) — les quatre formes de `dominantHeadwindDirection` /
   `dominantHeadwindAzimuthDeg`. Rien n'était inatteignable : `PathWindKt.…` marche. Mais ce nom
   est celui du **fichier**, pas de l'API — renommer `PathWind.kt` casserait tous les appelants
   Java au link, sans que rien au point d'appel n'ait jamais suggéré la fragilité.

2. **`GpxToPathJvm`** (`:gpx`, `jvmMain`) — `tracksAsPaths` / `segmentsAsPaths` avec le défaut
   `kinds`, plus `firstTrackAsPath` et les deux `toPath`. C'est le plus sérieux des quatre :
   `ALL_KINDS` était **`private`**, donc un appelant Java ne pouvait pas exprimer « les paths de ce
   fichier » du tout. Il devait nommer un ensemble, et le choix évident —
   `EnumSet.of(GpxPathKind.TRACK)` — réinstaure silencieusement le comportement d'avant g24 qui
   ignore tous les `<rte>`. Retrouver le défaut supposait de savoir qu'il valait `entries.toSet()`.
   `ALL_KINDS` passe `internal` pour que la façade nomme le même défaut plutôt que d'en créer un
   second.

3. **`PathToFitJvm`** — surcharges prenant le départ en **millisecondes epoch**. Le fichier
   défendait déjà cet argument pour `interPathGap`, au motif qu'une value class traverse en `long`
   d'unité indéterminée. `Instant` n'a pas ce défaut, mais il oblige à écrire
   `kotlin.time.Instant.Companion.fromEpochMilliseconds(t)` — le seul `kotlin.*` du backend. Et la
   valeur vient toujours de `path.time(0)`, déjà des millisecondes epoch : l'aller-retour par
   `Instant` ne lui apporte rien.

4. **`Path.fromCoordinates` appelle `computeDerivedData()`**. Ce n'est pas une façade mais une
   correction à la racine, et le seul changement de comportement de la fiche. La fabrique rendait
   un `Path` dont `distance(i)` valait 0 : `PointPerDistance` trouve alors tous les points à
   distance 0 du départ et effondre la trace. Les **trois** appelants du backend enchaînaient
   `computeDerivedData()` juste après, dont deux avec un commentaire d'avertissement écrit pendant
   la migration. La fabrique est le seul moyen de construire un `Path` depuis l'extérieur : elle
   doit rendre un objet utilisable. Aucun appelant de production dans la bibliothèque, donc aucun
   risque de double calcul.

   Une façade `PathJvm.fromCoordinates` l'accompagne. `Path.Companion.fromCoordinates` marche
   déjà et, contrairement à `PathWindKt`, ce nom est stable — `Companion` est de l'API, pas un
   artefact du compilateur. C'est simplement l'appel le plus bruyant de la bibliothèque vu de
   Java, et celui que tout consommateur Java fait, puisque c'est le seul moyen de construire un
   `Path` depuis l'extérieur. `@JvmStatic` sur la fonction du companion dirait exactement ça,
   mais ne se résout pas depuis `commonMain` — d'où la façade.

5. **`Path.withoutTime()`** — le correctif. `toFitSegment` exige des temps monotones, ce qu'un
   enregistrement réel n'a pas toujours : un compteur qui resynchronise son horloge en cours de
   route recule. Le consommateur n'avait que de mauvaises options : refuser l'import, ou
   reconstruire le path via `fromCoordinates` et perdre tout ce qui n'est pas une coordonnée. Le
   backend a d'abord fait la seconde. Ici la géométrie est conservée, l'horloge tombe, et le
   résultat s'encode comme un parcours — ce que produit déjà une route sans temps.

## Outputs

- `engine/src/jvmMain/kotlin/io/github/glandais/engine/path/PathWindJvm.kt`
- `gpx/src/jvmMain/kotlin/io/github/glandais/engine/gpx/GpxToPathJvm.kt`
- `fit/src/jvmMain/kotlin/io/github/glandais/fit/PathToFitJvm.kt` — 4 surcharges ajoutées
- `gpx/src/jvmMain/kotlin/io/github/glandais/engine/path/PathJvm.kt`
- `gpx/src/commonMain/kotlin/io/github/glandais/engine/path/Path.kt` — `withoutTime()` +
  `fromCoordinates` qui dérive
- `gpx/src/commonMain/kotlin/io/github/glandais/engine/gpx/GpxToPath.kt` — `ALL_KINDS` internal
- `engine/src/jvmTest/java/io/github/glandais/engine/path/PathWindJavaTest.java` (nouveau)
- `gpx/src/jvmTest/java/io/github/glandais/engine/JavaInteropTest.java` — 4 cas
- `fit/src/jvmTest/java/io/github/glandais/fit/FitJavaInteropTest.java` — 2 cas

Les tests sont **en Java**, par la convention de g22 : depuis Kotlin, la même source compile que
les ponts JVM existent ou non. C'est le seul endroit où la propriété testée est visible.

## Validation

- `./gradlew check` vert sur toutes les cibles (JVM, JS Node, JS browser) — `fromCoordinates` est
  du code commun, la modification traverse.
- `./gradlew ktlintCheck`.
- Côté backend, le critère de sortie : `grep -rn "kotlin\.\|Kt\.\|\.Companion\." backend/src/main`
  ne remonte plus rien.

## Done when

- [x] Les cinq façades / corrections livrées
- [x] Tests Java de callabilité sur chacune
- [x] `./gradlew check` vert
- [x] Le backend Quarkus ne nomme plus aucun type Kotlin

## Notes

**Rien n'est poussé en amont.** La fiche est livrée en local, republiée en **3.0.0** sans bump,
tant que la migration du backend n'est pas validée bout en bout — sinon le consommateur court
après un numéro de version pendant qu'on le débugue. Le contenu de la release publique se décidera
d'un coup une fois la validation faite.

Attention à la republication : `gradle.properties` porte `version=2.0.0` (la dernière **release**,
semantic-release ne l'ayant pas encore réécrit), donc `./gradlew publishToMavenLocal` publierait
une 2.0.0 et laisserait la 3.0.0 du backend inchangée. Il faut
`./gradlew -Pversion=3.0.0 publishToMavenLocal`.

`dominantHeadwindAzimuthDeg` garde ses deux conventions d'absence — `null` pour la direction,
`NaN` pour l'azimut — et la façade se contente de le documenter du point de vue Java
(`Double.isNaN`, jamais `== 0.0`, puisque `0.0` est plein nord). Les uniformiser serait une
rupture pour la façade JS, qui a choisi `NaN` précisément pour éviter `number | null`.
