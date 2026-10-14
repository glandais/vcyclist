# elevation-ign-first — IGN LiDAR HD d'abord, Mapterhorn en repli, ponts et tunnels selon la donnée

## Goal

Faire d'`ign-lidar-hd` la source d'altitude par défaut (sauf WASI), une fois qu'elle se comporte
bien partout. Elle doit lire l'IGN où il couvre et Mapterhorn ailleurs, et Mapterhorn aussi quand
l'IGN est en panne. Elle doit poser les tabliers des ponts et aplatir les tunnels **seulement là où
la donnée lue le permet**, et dire dans le rapport d'où vient chaque kilomètre.

Ce qui existe : `ElevationSource.IGN_LIDAR_HD.provider()` (MNT IGN, repli Mapterhorn sur l'absence
de donnée, MNS en `surfaceModel` à z15), les étapes 3b / 3c (`ElevationCorrection.run`) et le
rapport `Path.elevationCorrections`. Ce qui manque est listé en *Notes / écarts*.

## Depends on

- [`docs/archive/tasks/elevation-bridge-decks.md`](../archive/tasks/elevation-bridge-decks.md)
  (E1–E7) : le banc, le réglage des tunnels pour la précision (E4) et la sonde du Dadès.
- 6.5.0 (`ElevationCorrection.fixAndCorrect`, `TileStore`) et 6.5.1 (budget de requêtes par hôte
  pour tout le processus : un prérequis, sans lui MNT + MNS montaient à 40 req/s).

## Inputs

- Décisions du propriétaire (2026-10-14) :
  - tunnels **seulement sur un terrain mesuré** par défaut ;
  - IGN devient le défaut **à la fin**, en `feat` (version mineure), démo comprise, WASI exclu ;
  - la Finlande reste « non mesurée » pour les tunnels ;
  - pas de `PointField` de provenance dans ce lot.
- `https://download.mapterhorn.com/attribution.json` liste les sources de Mapterhorn (`frhd*`,
  `frrgealti*`, `fi`, `glo30`…) sans emprise : on ne sait pas, point par point, si Mapterhorn sert
  du LiDAR ou du GLO-30 hors de France.

## Steps

1. **Pas d'appel IGN hors de sa couverture** (`fix(elevation)`, **livré** en 6.5.2). Une emprise grossière et
   généreuse (France métropolitaine + Corse, `IgnLidarHdCoverage`) : une tuile IGN qui ne la touche
   pas est une tuile sans donnée, sans requête, pour le MNT comme pour le MNS. Le repli Mapterhorn
   lit ces points comme aujourd'hui. Appliqué par `ElevationSource.provider` et
   `IgnLidarHdLayer.provider`, donc aussi devant un fetcher ou un `TileStore` de l'appelant.
2. **Repli sur erreur, et cache négatif** (`feat(elevation)`). Une tuile IGN illisible après les
   tentatives du pacer (HTTP ≠ 2xx, réseau, corps qui ne décode pas) est marquée indisponible pour
   une durée bornée ; ses points sont lus par le repli. Une annulation se propage ; une erreur du
   dernier niveau échoue comme aujourd'hui ; le MNS garde `fetch-failed`. Avertissement unique
   dans `ElevationCorrectionReport` (nombre de tuiles), et pas de tablier sur une sortie dont le
   terrain mélange les sources. **Livré** : `UnreadableTiles` (une minute), `TileFailureLog` dans le
   contexte de la coroutine (par sortie, pas par provider). Les km relus attendent l'étape 3.
3. **Provenance** (`feat(engine)`). `TerrainOrigin` (`ign-lidar-hd`, `mapterhorn-measured` —
   Mapterhorn dans l'emprise France —, `mapterhorn`, `none`) par station ; un objet
   `terrain` additif dans `ElevationCorrections` (km par origine, km relus après erreur, tuiles IGN
   en échec), qui chiffre aussi l'avertissement de l'étape 2. Garde des tabliers : pas de MNS
   au-dessus d'un terrain qui ne vient pas de l'IGN. **Livré** : cinquième origine `other` (un
   modèle que le catalogue ne connaît pas : hôte WASI, configuration à la main), exposée sur les
   sept portes (JS `terrain`, WASI `terrain`, HTTP `TerrainProvenance`, ligne `terrain:` de la CLI,
   ligne d'état de la démo).
4. **`tunnelScope`** sur les sept portes (`measured-terrain` par défaut, `everywhere`). Les tunnels
   détectés dont une station de *[portail − 100 m, portail + 100 m]* n'est pas d'origine mesurée
   sont écartés, comptés (`tunnelsOutOfScope`) et localisés par km dans un avertissement. Aucun
   seuil du détecteur ne change. **Livré**, plus `ElevationProvider.servingSource` : l'hôte WASI
   déclare la source qu'il sert, sans quoi `measured-terrain` y aurait coupé tous les tunnels.
5. **Bascule du défaut** (`feat`). `ElevationSource.DEFAULT = IGN_LIDAR_HD` pour CLI, Java, JS,
   démo et HTTP ; `SINGLE_LAYER_DEFAULT = MAPTERHORN` pour WASI. Mesures avant / après (volume,
   temps à froid, D+, durée) dans `docs/guides/elevation.md`. Mesurer avant la sérialisation
   `Dem.lock` de l'API HTTP sur une sortie froide.

## Outputs

Code et tests par étape ; `docs/guides/elevation.md`, `wasm-wasi-abi.md` (étape 4), ligne du
registre (sous-ligne de R32 pour `tunnelScope`), `surface-coverage.md` régénéré.

## Validation

- Hors ligne : un fetcher qui compte ses appels (zéro requête IGN hors emprise) ; `FlakyWorld`
  (repli sur erreur, une seule requête par tuile en échec, annulation propagée) ; `BorderWorld`
  (origines, km, tunnel écarté en `measured-terrain` et aplati en `everywhere`) ; garde des tabliers ;
  non-régression bit pour bit d'une route tout IGN.
- `INTEGRATION=1` : `ElevationBenchProbeTest` et `TunnelBenchProbeTest` inchangés (tout le banc est
  en France, la portée ne fait qu'écarter).
- **Aucun seuil n'est réglé** : aucune série tenue à l'écart n'est consommée. Élargir un jour la
  portée des tunnels exige une nouvelle série gelée sur GLO-30 avant toute mesure.

## Done when

`ign-lidar-hd` est le défaut des six portes non WASI, une sortie au Maroc ne fait aucune requête
IGN et n'aplatit aucun tunnel, une panne IGN ne fait plus échouer une sortie, et le rapport dit
d'où vient chaque kilomètre.

## Notes / écarts constatés (conception du 2026-10-14)

- Hors couverture, `ign-lidar-hd` télécharge aujourd'hui chaque tuile z15 (256 kB de `-9999`) et sa
  tuile MNS avant de relire Mapterhorn.
- Le repli ne couvre que l'absence de donnée ; une tuile IGN en erreur fait échouer la sortie, et
  `LruCache` ne garde pas l'échec : chaque point la redemande.
- Aucune provenance : `ElevationCalculator.getElevation` renvoie un `Double`.
- `TunnelStep` lit le terrain à travers toute la chaîne : hors de France il détecte sur GLO-30, où
  la sonde du Dadès a trouvé 3 faux tunnels.
- `BridgeDeckStep` pourrait comparer un MNS IGN à un terrain Mapterhorn au bord de la couverture
  (écart de datum +0,76 m, A19). Aucun cas mesuré.
- Volume z15 : ~52 Mo pour 100 km avec les tabliers, ~30 fois Mapterhorn.
