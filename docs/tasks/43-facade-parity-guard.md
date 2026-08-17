# 43 — Garde-fou : empêcher la façade JS et la démo de décrocher du cœur

## Goal

Trois fois de suite, le même trou :

| Constat | Fiche | Portée |
|---|---|---|
| g23, g24, g25 livrées sans toucher `EngineJsApi` | `g29` | 3 tâches |
| g26, g27 (partiel) | `g31`, `g33` | 2 tâches |
| R9, R15, R16, R18, R19 livrées sans toucher `EngineJsApi` | `41` | 5 tâches |
| La démo cassée par le renommage R17 pendant neuf entrées du ledger | `40` | — |

`g29` avait déjà écrit la conclusion dans ses Notes : « toute fiche qui ajoute un paramètre à une
API de `commonMain` devrait se demander si la façade JS le relaie. Envisager une ligne à ce sujet
dans `CLAUDE.md` ». La ligne n'a pas été écrite, et le trou s'est reproduit — en pire, puisque
cette fois-ci un renommage a **cassé** un consommateur au lieu de simplement ne pas l'atteindre.

Une note dans `CLAUDE.md` n'a pas suffi à empêcher la récidive de `g29`, et n'aurait rien attrapé
de R17. Il faut quelque chose qui échoue au build.

## Depends on

- `41` (la façade doit d'abord être à jour ; un garde-fou sur une surface en retard s'installerait
  rouge)

## Inputs

- `cli/src/test/kotlin/…/MixinParsingTest.kt` — le modèle à suivre : ses cas 03, 06 et 11
  vérifient champ par champ que les défauts du CLI **sont** ceux d'`EngineConstants`, au lieu de
  les recopier. C'est exactement la forme de garde-fou qui manque côté JS.
- `engine/src/commonMain/…/physics/` — les cinq `CyclistPowerProvider` concrets et les deux
  décorateurs
- `engine/src/jsMain/…/EngineJsApi.kt` — `toCyclistPowerProvider`, `toEnhanceOptions`
- `demo/src/engine-shim.ts` — la troisième copie de la même surface, écrite à la main
- `CLAUDE.md` — § *Codebase touchpoints*
- `docs/tasks/g29-js-facade-catchup.md` — le constat d'origine

## Steps

### 1. Fermer la hiérarchie des providers de puissance

`CyclistPowerProvider` est une `fun interface` ouverte : rien ne permet d'énumérer ses
implémentations, donc rien ne peut constater qu'une nouvelle n'est pas relayée. La rendre
`sealed` (avec les décorateurs comme sous-type distinct) donne au compilateur la liste, et un
`when` exhaustif sur cette liste devient le point de rupture.

À évaluer honnêtement avant de le faire : `sealed` interdit l'implémentation hors module. C'est
un **changement d'API publique** — un consommateur Kotlin qui écrit son propre provider ne le peut
plus. Si ce coût est jugé trop élevé, se rabattre sur l'étape 2 seule, qui l'évite au prix d'une
liste tenue à la main.

### 2. Un test de parité côté `jsTest`

Le test énumère les modèles que la façade prétend accepter et vérifie que chacun construit bien le
provider attendu — puis qu'aucun autre provider concret du cœur n'est resté sans nom :

- pour chaque `type` accepté, `toCyclistPowerProvider` rend une instance du type attendu ;
- pour chaque décorateur, la composition est celle du CLI (`base → pacing → slew`) ;
- un `type` inconnu lève ;
- la liste des `type` est comparée à la liste (scellée ou tenue à la main) des providers du cœur,
  et l'écart est **le message d'échec** : « `PowerProviderXxx` existe dans `:engine` et n'a pas de
  `type` JS ».

Le dernier point est le seul qui attrape une fiche future. Les trois premiers ne font que
verrouiller l'existant.

### 3. Le même test, côté CLI vs façade

`MixinParsingTest` prouve que le CLI est complet vis-à-vis du cœur. Rien ne prouve que la façade
est complète vis-à-vis du CLI, et c'est précisément l'écart qui s'est creusé : R9/R16/R18/R19 ont
toutes atterri dans `CyclistMixin` et nulle part ailleurs.

Le CLI est JVM-only et la façade jsMain — aucun test ne voit les deux. Donc pas de test croisé
possible : à la place, une **table dans `docs/gpx2web-coverage.md`** (ou une table sœur) à une
ligne par capacité, trois colonnes cœur / CLI / JS. Une table se relit en dix secondes à la
revue ; c'est faible comme garantie mais c'est ce que la structure du projet permet.

### 4. Le shim de la démo

`demo/src/engine-shim.ts` est une **quatrième** copie, écrite à la main, des DTO. C'est elle qui a
laissé passer R17 : le `.d.ts` généré par Kotlin/JS disait `durability`, le shim disait
`constant_tiring`, et rien ne les compare.

**Mesuré pendant `41`, et cela ferme une piste** : Kotlin/JS **n'émet aucun corps** pour un
`external interface`. Dans le `.d.ts` généré, `CyclistDto`, `BikeDto`, `PowerProviderDto` et
`EnhanceOptionsDto` n'apparaissent que par leur *nom*, dans la signature de `enhanceWithCourse` ;
les seules interfaces avec un corps (`FitDecodeError`…) viennent d'une dépendance tierce
`@JsExport`ée. Faire dériver le shim du `.d.ts`, ou même y assigner un littéral pour le typecheck,
est donc **impossible** — il n'y a rien à quoi se comparer. C'est aussi la vraie raison pour
laquelle ce fichier est écrit à la main, et pourquoi il dérive.

Il reste deux pistes, à trancher :

- exporter les DTO en `data class` `@JsExport`ée plutôt qu'en `external interface`, ce qui leur
  donnerait un corps dans le `.d.ts` — mais change la façon dont un appelant JS les construit
  (littéral d'objet aujourd'hui), donc c'est une rupture d'API, pas un ajout ;
- garder le shim manuel et le couvrir par un test qui envoie **chaque** valeur de `type` et chaque
  champ au vrai moteur, à la façon du smoke Node de `40`. Ça ne compare pas des types, mais ça
  attrape le renommage, qui est le cas qui a fait mal.

Attention au piège de `g29` : le `.d.ts` de `build/dist/js/productionLibrary/` n'est pas
régénéré par `jsBrowserDistribution` ; le frais est celui de `compileSync/js/main/…`.

### 5. La ligne dans `CLAUDE.md`

Sous § *Codebase touchpoints*, une entrée « Ajouter une capacité au moteur » : cœur → CLI → façade
JS → shim démo → ledger. C'est la documentation du garde-fou, pas le garde-fou.

## Outputs

Modifiés :

- `engine/src/commonMain/…/physics/CyclistPowerProvider.kt` (si `sealed` est retenu)
- `CLAUDE.md`
- `docs/gpx2web-coverage.md` ou une table sœur
- `demo/src/engine-shim.ts` (si la dérivation depuis le `.d.ts` est retenue)

Créés :

- `engine/src/jsTest/…/EngineJsApiParityTest.kt`

## Validation

```bash
./gradlew :engine:jsNodeTest :engine:jsBrowserTest :cli:test
./gradlew check ktlintCheck
cd demo && npm run typecheck
```

Le test qui compte est **négatif** : ajouter localement un `PowerProviderFake` dans `:engine`,
vérifier que la suite passe au rouge avec un message qui nomme le provider et la surface
manquante, puis le retirer. Un garde-fou qu'on n'a pas vu échouer n'est pas un garde-fou — c'est
la même erreur que le test « par point » de R11, qui serait passé par chance.

## Done when

- [ ] Test de parité en `jsTest`, échec vérifié en ajoutant un provider bidon
- [ ] Décision tranchée et **motivée** sur `sealed` (le coût API est réel, le refuser est une
      réponse acceptable si la liste manuelle est testée)
- [ ] Table de couverture cœur / CLI / JS
- [ ] Position tranchée sur le shim de la démo (dériver du `.d.ts` est exclu — voir étape 4)
- [ ] Ligne dans `CLAUDE.md`
- [ ] `./gradlew check` + `ktlintCheck` + typecheck démo verts

## Notes

- **Pourquoi maintenant et pas après `g29`.** Parce que `g29` a conclu « envisager une ligne dans
  `CLAUDE.md` » et s'est arrêtée là. La récidive est la preuve que le niveau de contrainte était
  trop faible. Cette fiche vise le build, pas la documentation.
- **Ce que le garde-fou n'attrapera pas** : une capacité qui n'est pas un provider de puissance —
  R9 vit sur `Cyclist`, R15 sur `EnhanceOptions`. L'étape 2 ne couvre que la famille où la dérive
  a été la plus coûteuse ; les étapes 3 et 5 couvrent le reste, moins solidement. Le dire
  explicitement vaut mieux que laisser croire à une couverture totale.
- **Le ledger est aussi une surface de suivi qui a menti** : il porte R9/R16/R18/R19 en ✅ alors
  que « livré » y voulait dire « livré dans le cœur et le CLI ». `41` corrige les entrées ; la
  leçon est que ✅ doit nommer les surfaces atteintes.
