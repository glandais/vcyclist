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

- [ ] Un lecteur qui ne connaît pas le projet peut instancier le module et calculer une distance
      en suivant le seul « Démarrage rapide » — le vérifier en le faisant depuis un dossier vide.
- [ ] Tous les exports de `EngineWasiApi` figurent dans la référence, aucun de trop.
- [ ] Les codes d'erreur documentés correspondent aux constantes Kotlin (les comparer, pas les
      recopier de mémoire).
- [ ] Pas de lien mort.

## Done when

Un tiers peut écrire son propre hôte (Go, Rust, JVM…) sans lire le code Kotlin.

## Notes

Ne pas recopier de longs blocs Python dans le markdown : pointer `tools/wasi/host.py`, qui est
exécuté par la CI et ne peut donc pas mentir.
