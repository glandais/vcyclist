# 09 — Elevation : tests d'intégration HTTP réels (tuiles mapterhorn)

## Goal

Valider sur des données **réelles** que la stack `ElevationProvider → TileManager → fetchAndDecodeTile → mapterhorn.com` produit des altitudes cohérentes avec la réalité géographique.

Le smoke test JVM existant (`TileFetcherJvmTest`, tâche 06) prouve que le pipeline fonctionne avec un serveur local fournissant des tuiles PNG/WebP synthétiques. Cette tâche ajoute un test **opt-in** (gated `INTEGRATION=1`) qui frappe `tiles.mapterhorn.com` et vérifie :

- altitude Mont Blanc (~4805 m) à ±50 m,
- altitude Mer Morte (~-430 m) à ±50 m (cas négatif),
- altitude Death Valley (~-86 m) à ±50 m,
- cache LRU effectif : un appel répété ne refait pas de fetch HTTP,
- attribution reste celle de mapterhorn par défaut,
- `getElevationsAlong` sur un mini-path alpin (3-5 waypoints) retourne un profil avec ≥ N points.

Le test est **JVM uniquement** dans un premier temps. Les variants Wasm/Node sont mentionnés en notes pour de futures tâches.

## Depends on

- `08-elevation-provider-batch` (`ElevationProvider` + `getElevation` + `getElevationsAlong` opérationnels)

## Inputs

- `/home/glandais/code/perso/vcyclist-all/elevation/test/ElevationProvider.integration.test.ts` — référence (utile pour la structure, mais le contenu actuel est un mock-test cache eviction, pas un fetch réel).
- `vcyclist/elevation/src/jvmTest/kotlin/io/github/glandais/elevation/TileFetcherJvmTest.kt` — pattern de test JVM existant.
- Coordonnées de référence (sources publiques) :
  - **Mont Blanc** : 45.8326°N, 6.8652°E → 4805 m
  - **Mer Morte (rive)** : 31.5°N, 35.5°E → -430 m (sous niveau de la mer)
  - **Death Valley (Badwater Basin)** : 36.250°N, -116.832°W → -85 m
  - **Mont Saint-Michel** (mer) : 48.636°N, -1.511°W → ~0 m (référence niveau de la mer)

## Steps

### 1. Gating par variable d'environnement

Pattern Kotlin Test (compatible 3 targets) :

```kotlin
private fun integrationEnabled(): Boolean =
    System.getenv("INTEGRATION") == "1" || System.getProperty("integration") == "true"
```

`System.getenv` n'est dispo qu'en JVM ; ce test reste **JVM-only** (fichier dans `jvmTest/`). Pas besoin de wrapping `expect/actual`.

Chaque `@Test` commence par :
```kotlin
@Test fun `Mont Blanc altitude is close to 4805 m`() = runTest {
    if (!integrationEnabled()) return@runTest  // Skip when offline
    ...
}
```

**Note** : `kotlin.test.Test` ne supporte pas le `@Ignore` conditionnel cross-platform. Le skip silencieux via `if (...) return` est le pattern le plus portable. On peut ajouter un `println("[skipped: INTEGRATION!=1]")` pour visibilité.

### 2. `ElevationProviderIntegrationTest.kt`

`elevation/src/jvmTest/kotlin/io/github/glandais/elevation/ElevationProviderIntegrationTest.kt` :

```kotlin
package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertTrue

class ElevationProviderIntegrationTest {

    private fun integrationEnabled(): Boolean =
        System.getenv("INTEGRATION") == "1" || System.getProperty("integration") == "true"

    private fun skipIfOffline(): Boolean {
        if (!integrationEnabled()) {
            println("[skipped: set INTEGRATION=1 to run live HTTP integration tests]")
            return true
        }
        return false
    }

    private fun newProvider(cacheSize: Int = 16): ElevationProvider =
        ElevationProvider(ElevationProviderConfig(cacheSize = cacheSize))

    @Test fun `Mont Blanc altitude is ~4805 m`() = runTest {
        if (skipIfOffline()) return@runTest
        val provider = newProvider()
        val ele = provider.getElevation(45.8326, 6.8652)
        assertTrue(
            (ele - 4805.0).absoluteValue < 50.0,
            "Mont Blanc elevation got $ele, expected within ±50 m of 4805",
        )
    }

    @Test fun `Dead Sea shore altitude is ~-430 m`() = runTest {
        if (skipIfOffline()) return@runTest
        val provider = newProvider()
        val ele = provider.getElevation(31.5, 35.5)
        assertTrue(
            (ele - (-430.0)).absoluteValue < 50.0,
            "Dead Sea elevation got $ele, expected within ±50 m of -430",
        )
    }

    @Test fun `Death Valley Badwater Basin altitude is ~-85 m`() = runTest {
        if (skipIfOffline()) return@runTest
        val provider = newProvider()
        val ele = provider.getElevation(36.250, -116.832)
        assertTrue(
            (ele - (-85.0)).absoluteValue < 50.0,
            "Death Valley elevation got $ele, expected within ±50 m of -85",
        )
    }

    @Test fun `second call to same coords is served from cache (HTTP not re-issued)`() = runTest {
        if (skipIfOffline()) return@runTest

        // Wrap the real fetcher to count HTTP hits
        var httpCalls = 0
        val countingFetcher: suspend (String) -> RawTile = { url ->
            httpCalls++
            fetchAndDecodeTile(url)
        }
        val provider = ElevationProvider(
            config = ElevationProviderConfig(cacheSize = 8),
            fetcher = countingFetcher,
        )

        val ele1 = provider.getElevation(45.8326, 6.8652)
        val callsAfterFirst = httpCalls

        val ele2 = provider.getElevation(45.8326, 6.8652)

        assertTrue(callsAfterFirst >= 1, "first call must trigger at least one HTTP fetch")
        // The 4 neighbour pixels for bilinear interpolation may live in the same tile (1 fetch)
        // or span up to 4 neighbouring tiles. Either way, the second call must add zero HTTP calls.
        kotlin.test.assertEquals(
            callsAfterFirst, httpCalls,
            "second call must be entirely served from cache (no extra HTTP)",
        )
        assertTrue(
            (ele1 - ele2).absoluteValue < 1e-9,
            "deterministic re-query must return the exact same elevation",
        )
    }

    @Test fun `default attribution targets mapterhorn`() {
        if (skipIfOffline()) return
        val provider = newProvider()
        val attr = provider.attribution
        assertTrue("mapterhorn" in attr.text.lowercase(), "attribution text: ${attr.text}")
        assertTrue(attr.url?.contains("mapterhorn") == true, "attribution url: ${attr.url}")
    }

    @Test fun `getElevationsAlong on a small Alpine path returns a densified profile`() = runTest {
        if (skipIfOffline()) return@runTest
        val provider = newProvider()

        // 4 waypoints along ~6 km in the Mont Blanc range
        val path = listOf(
            LatLon(45.8350, 6.8500),
            LatLon(45.8400, 6.8700),
            LatLon(45.8500, 6.8800),
            LatLon(45.8550, 6.8900),
        )
        val profile = provider.getElevationsAlong(
            path = path,
            step = 100.0,         // 1 point per 100 m
            minDistance = 10.0,
            interpolation = true,
        )

        // sanity: enough densification
        assertTrue(profile.size >= 10, "profile size: ${profile.size}")

        // every point has an elevation, mostly between 1500 m and 5000 m in this zone
        val outliers = profile.count { it.elevation < 1000.0 || it.elevation > 5500.0 }
        assertTrue(outliers == 0, "found $outliers elevation outliers in profile")

        // first and last points correspond to inputs (lat/lon preserved within float tolerance)
        assertTrue(
            (profile.first().latitude - path.first().latitude).absoluteValue < 1e-6,
            "first lat mismatch",
        )
        assertTrue(
            (profile.last().latitude - path.last().latitude).absoluteValue < 1e-6,
            "last lat mismatch",
        )
    }
}
```

### 3. Pas de changement de build

Aucune dépendance nouvelle. Le test utilise `kotlinx-coroutines-test` (déjà présent depuis tâche 06) et la stdlib JVM (`System.getenv`).

Recommandation `build.gradle.kts` (optionnelle) : exposer un raccourci pour lancer ces tests :

```kotlin
tasks.withType<Test>().configureEach {
    // Forward INTEGRATION env var if set in the parent shell
    environment("INTEGRATION", providers.environmentVariable("INTEGRATION").orElse("").get())
}
```

Pas indispensable — Gradle propage `environment` par défaut.

### 4. Documentation

Ajouter une note courte dans `docs/ARCHITECTURE.md` (ou un nouveau `docs/elevation-integration.md`) :

```markdown
# Tests d'intégration `:elevation`

Les tests `ElevationProviderIntegrationTest` font de **vrais appels HTTP** à
`tiles.mapterhorn.com`. Ils sont skippés sauf si `INTEGRATION=1` est défini.

## Lancer localement

```bash
INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*Integration*'
```

## Coût

~6 tuiles × ~30 ko chacune = ~180 ko de bande passante par exécution complète.
Le cache HTTP du JDK est désactivé pour ces tests, donc chaque run paie l'aller-retour.

## Pourquoi ne pas l'exécuter en CI

- Dépendance à un service tiers (mapterhorn.com) → fragile.
- Attribution à respecter — pas d'usage automatisé excessif.
- Performance variable selon la latence réseau du runner.

Le test est verrouillé par `INTEGRATION=1` pour permettre un check manuel régulier
(avant chaque release du module ou après une refonte du pipeline).
```

## Outputs (fichiers attendus)

Créés :

- `vcyclist/elevation/src/jvmTest/kotlin/io/github/glandais/elevation/ElevationProviderIntegrationTest.kt`
- `vcyclist/docs/elevation-integration.md` (court guide d'usage)

Aucune modification de `build.gradle.kts` requise (sauf le snippet optionnel ci-dessus).

## Validation

```bash
# Mode CI offline : aucun test "Integration" exécuté
./gradlew :elevation:jvmTest                    # tous les tests passent, intégration skippée silencieusement
./gradlew :elevation:allTests                   # idem

# Mode intégration : exécution réelle
INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*ElevationProviderIntegrationTest*' --rerun-tasks
```

Critères :

- En mode normal (sans `INTEGRATION=1`) : la classe `ElevationProviderIntegrationTest` apparaît dans le rapport mais chaque test est skippé (via `return@runTest` + ligne `[skipped]` dans la sortie console).
- En mode `INTEGRATION=1` : **6 tests verts** :
  - Mont Blanc : 4755 ≤ ele ≤ 4855
  - Mer Morte : -480 ≤ ele ≤ -380
  - Death Valley : -135 ≤ ele ≤ -35
  - Cache : 2e appel sur mêmes coords → `httpCalls` inchangé
  - Attribution : `text` et `url` contiennent "mapterhorn"
  - Profile alpin : ≥ 10 points, pas d'outliers
- Aucun test ne plante l'ensemble du build si offline.
- Non-régression : `./gradlew :engine:allTests` toujours vert.

## Done when

- [x] `ElevationProviderIntegrationTest.kt` créé dans `jvmTest/`
- [x] Helper `integrationEnabled()` lit `INTEGRATION` env var et `integration` system property
- [x] 6 cas de test : 3 altitudes ponctuelles, 1 cache HTTP, 1 attribution, 1 profile path
- [x] `./gradlew :elevation:jvmTest` passe sans `INTEGRATION=1` (tests skippés silencieusement)
- [x] `INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*Integration*'` exécute et passe les 6 tests sur une connexion réseau standard (vérifié manuellement le 2026-05-10 : 6 tests verts en 2.48s contre `tiles.mapterhorn.com`)
- [x] `docs/elevation-integration.md` créé avec instructions de run
- [x] `:engine:allTests` toujours vert
- [x] `ktlintCheck` vert
- [x] Toutes les checkboxes cochées

## Notes

- **Tolérance ±50 m** : DEM Terrarium @ z=12 a une résolution horizontale ~30 m → l'erreur verticale typique sur des reliefs est de 10-30 m. ±50 m laisse une marge pour les imprécisions de coordonnées de référence (sommet exact non documenté en lat/lon décimal officiel).
- **Pas de `runBlocking`** : on garde `runTest` pour la cohérence et la testabilité (annulation propre des coroutines en cas d'échec).
- **Cache HTTP JDK** : par défaut, `java.net.http.HttpClient` n'utilise pas de cache (différent de l'ancienne `HttpURLConnection`). On peut donc tester la couche cache applicative sans interférence.
- **Coût bandwidth** : un test complet télécharge ~5-6 tuiles WebP de 30-50 ko = environ 200 ko. Acceptable pour un check manuel.
- **Attribution mapterhorn** : par convention de la licence (https://mapterhorn.com/attribution/), tout usage public doit afficher l'attribution. Ces tests n'exposent rien à un utilisateur final ; conformes.
- **Variants Wasm/Node** :
  - **Wasm** : test browser via Karma exigerait un certificat CORS pour mapterhorn (vérifier `Access-Control-Allow-Origin`). À traiter dans une tâche dédiée si nécessaire.
  - **Node** : la target Node de `:elevation` est en stub `NotImplementedError` (tâche 06) → pas pertinent tant que `sharp` n'est pas branché.
- **Si mapterhorn devient indisponible** : revoir l'`urlTemplate` par défaut (basculer sur AWS S3 Terrarium par exemple) ; la fonction `ElevationProviderConfig` permet déjà l'override.
- **Pas de retry intentionnel** : si la requête échoue (offline, 503, etc.), le test échoue et l'utilisateur sait pourquoi. Un retry masquerait les vrais problèmes.
- **Pourquoi cette tâche est petite** : tout le pipeline est déjà construit et testé en isolation (tâches 06-08). L'objectif ici est purement de **prouver la connexion à un service externe réel**, pas de valider de nouvelles unités de code.
- **Évolution future** : on pourrait ajouter un test de bench (latence p50/p95) ou un test « 100 tuiles aléatoires en parallèle » pour stresser `Flux.forEachParallel`. À traiter dans une tâche perf dédiée si profilage montre un besoin.
