# w10 — Documentation utilisateur de l'ABI WASI

## Goal

`docs/kotlin-wasm-wasi.md` documente *comment le POC a été fait* — c'est une note d'ingénierie,
utile pour maintenir le projet. Il manque le pendant destiné à **quelqu'un qui veut juste
exécuter le `.wasm`** : quels imports fournir, quels exports appeler, dans quel ordre, avec
quels codes d'erreur.

## Depends on

- `w04` (surface stabilisée), `w05` (imports définitifs), `w07` (où l'on télécharge le binaire),
  `w09` (le harnais à référencer).

## Inputs

- `docs/kotlin-js-jvm-webp.md` — le modèle de guide d'interop du dépôt : ce qu'on vise en ton
  et en niveau de détail.
- `README.md` — section « runtime / CLI » à compléter.
- `docs/publishing.md`, `docs/gpx2web-coverage.md` — style des tables de correspondance.
- La table `EngineJsApi` → `EngineWasiApi` produite en w04.

## Steps

### 1. `docs/wasm-wasi-abi.md`

Le document de référence, structuré pour être lu par un implémenteur d'hôte :

1. **Démarrage rapide** — télécharger le binaire, l'instancier, appeler `vcAbiVersion`, sous
   wasmtime-py (renvoi vers `tools/wasi/host.py` pour le code complet).
2. **Forme du module** — réacteur WASI Preview 1, section `start`, pas de `_start` ni de
   `_initialize` à appeler ; `memory` exportée ; proposals requis : `function-references`, `gc`,
   `exceptions`.
3. **Imports à fournir** — les trois, avec signature, sémantique et valeurs de retour. Dire
   explicitement qu'ils sont **obligatoires** même si l'hôte n'utilise pas l'élévation.
4. **Protocole de transfert** — `read_input` / `write_output`, la règle de non-réentrance, la
   durée de vie des pointeurs.
5. **Handles et cycle de vie** — `vcRelease`, `vcReleaseAll`, ce qui fuit si on oublie.
6. **Codes d'erreur** — la table des sentinelles + `vcLastError`.
7. **Référence des exports** — un tableau : nom, signature, sémantique, erreurs possibles.
8. **Schémas JSON** — options d'`enhance`, de `detectClimbs`, format des sorties.
9. **Limites connues** — pas de FIT (w12), décodage de tuiles à la charge de l'hôte (w11), pas
   de Component Model.

### 2. Table de correspondance JS ↔ WASI

Publier la table de w04 dans ce document : c'est ce qu'un utilisateur de `@glandais/vcyclist-engine`
regardera pour savoir s'il peut migrer.

### 3. README

Une section courte : ce qu'est le `.wasm`, où le télécharger, un exemple de 10 lignes, un lien
vers `wasm-wasi-abi.md`. Mettre à jour le diagramme d'architecture s'il liste les cibles.

### 4. Renvois croisés

- `CLAUDE.md` : ajouter les lignes « Où trouver quoi » (`Comment un hôte WASI appelle le
  moteur ? → docs/wasm-wasi-abi.md`), et mentionner la cible `wasmWasi` dans la description des
  modules et l'invariant des cibles.
- `docs/kotlin-wasm-wasi.md` : ajouter en tête un renvoi vers le nouveau doc (note d'ingénierie
  vs guide utilisateur).
- `docs/publishing.md` : lien vers l'ABI depuis la section des artefacts WASI.

## Outputs

- `docs/wasm-wasi-abi.md` (nouveau).
- `README.md`, `CLAUDE.md`, `docs/kotlin-wasm-wasi.md`, `docs/publishing.md` mis à jour.

## Validation

- [x] Un lecteur qui ne connaît pas le projet peut instancier le module et calculer une distance
      en suivant le seul « Démarrage rapide » — le vérifier en le faisant depuis un dossier vide.
- [x] Tous les exports de `EngineWasiApi` figurent dans la référence, aucun de trop.
- [x] Les codes d'erreur documentés correspondent aux constantes Kotlin (les comparer, pas les
      recopier de mémoire).
- [x] Pas de lien mort.

## Done when

Un tiers peut écrire son propre hôte (Go, Rust, JVM…) sans lire le code Kotlin.

## Notes

Ne pas recopier de longs blocs Python dans le markdown : pointer `tools/wasi/host.py`, qui est
exécuté par la CI et ne peut donc pas mentir.

### Ce qui s'est passé

`docs/wasm-wasi-abi.md`, douze sections, **en anglais** : le lectorat visé est extérieur au
projet, comme celui du `README.md` et de `publishing.md`, qui sont les deux autres documents
tournés vers l'extérieur. Les notes d'ingénierie et les plans restent en français.

La fiche dépend de `w07` pour « où télécharger le binaire ». `w07` est bloquée par `w08`, elle-même
en attente de Kotlin 2.4.20 (toujours pas publiée : Maven Central s'arrête à `2.4.20-Beta2`, pas
même une RC). Le §1 donne donc la commande Gradle et signale d'une ligne que le binaire publié
arrive avec w07 — le reste du document n'en dépend pas.

### Les quatre validations, faites plutôt que supposées

1. **Démarrage rapide depuis un dossier vide.** Le bloc Python a été extrait *verbatim* du
   markdown par un script, copié dans un répertoire ne contenant que le `.wasm` et un GPX, puis
   exécuté : `1`, `259 points`, `3573.8048648177737 m`. Un exemple qu'on n'a jamais lancé tel
   qu'il est écrit est un exemple faux.
2. **Exhaustivité des exports.** Comparaison programmatique entre les `^fun vc…` de
   `EngineWasiApi.kt` et les identifiants cités dans le document : **32 exports, aucun manquant,
   aucun en trop**.
3. **Codes d'erreur.** Les quatre `const val ERR_*` de `WasiAbi.kt` relus dans le source et
   confrontés aux lignes du tableau, ainsi que `VERSION`. Aucun recopié de mémoire.
4. **Liens.** Tous les liens relatifs des six documents touchés résolus sur le disque. Trois faux
   positifs subsistent au scan, tous du Python `](store, …)` dans un bloc de code.

### Renvois croisés

- `README.md` : la colonne « Targets » des quatre modules du cœur mentionne WASI, et une section
  « Running it without a JVM or a JavaScript host » donne la commande, dix lignes d'exemple et les
  deux liens.
- `CLAUDE.md` : une ligne « How does a WASI host call the engine ? » dans *Where to find things*.
- `kotlin-wasm-wasi.md` : renvoi en tête, avec la distinction explicite — celui-ci dit *pourquoi*
  l'ABI a cette forme, l'autre dit *comment* s'en servir.
- `publishing.md` : les variantes `-wasm-wasi` sont des **klibs** ; le `.wasm` exécutable est un
  artefact distinct, à attacher en w07.

### Deux choses écrites noir sur blanc parce qu'elles surprennent

- **Les trois imports sont obligatoires**, y compris `fetch_tile` pour un hôte qui ne corrigera
  jamais d'altitude : les imports Wasm sont résolus à l'instanciation. `return 0` suffit.
- **`startTimeEpochMs` sur un chemin fraîchement parsé date la sortie des décennies dans le
  futur** — le piège trouvé en w09. Le §9 le dit, avec la conduite à tenir (simuler d'abord).
  C'est un pansement documentaire ; la vraie question — rebaser comme `g25` l'a fait pour FIT —
  reste ouverte.
