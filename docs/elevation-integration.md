# Tests d'intégration `:elevation`

Les tests `ElevationProviderIntegrationTest` font de **vrais appels HTTP** à
`tiles.mapterhorn.com`. Ils sont skippés sauf si `INTEGRATION=1` est défini
dans l'environnement (ou `-Dintegration=true` en system property).

## Lancer localement

```bash
INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*ElevationProviderIntegrationTest*' --rerun-tasks
```

Les 6 tests couvrent :

- altitude Mont Blanc (~4805 m) à ±50 m
- altitude Mer Morte (~-430 m, sous niveau de la mer) à ±50 m
- altitude Death Valley / Badwater Basin (~-85 m) à ±50 m
- cache LRU effectif : un 2e appel sur les mêmes coordonnées ne refait pas de fetch HTTP
- attribution par défaut pointe vers mapterhorn
- `getElevationsAlong` sur 4 waypoints alpins → profil densifié ≥ 10 points sans outliers

## Coût

~6 tuiles WebP × ~30–50 ko = ~200 ko de bande passante par exécution complète.
Le cache HTTP du JDK n'est pas utilisé par `java.net.http.HttpClient`, chaque run
paie l'aller-retour réseau.

## Pourquoi ne pas l'exécuter en CI

- Dépendance à un service tiers (mapterhorn.com) — fragile.
- Attribution à respecter ([mapterhorn.com/attribution](https://mapterhorn.com/attribution/)) — pas d'usage automatisé excessif.
- Performance variable selon la latence réseau du runner.

Le test reste verrouillé par `INTEGRATION=1` pour permettre un check manuel régulier
(avant chaque release du module ou après une refonte du pipeline).
