# 39 — Démo Vue/Vite : déploiement GitHub Pages (optionnel)

## Goal

Publier automatiquement la démo sur GitHub Pages à chaque push sur `develop`, de sorte que `https://glandais.github.io/vcyclist/` (ou équivalent selon la config du repo) affiche la dernière version de la démo Vue/Vite consommant le moteur Kotlin/JS.

**Cette tâche est explicitement stretch/optionnelle.** Elle n'est requise ni par le critère de fin de phase 9, ni par le workflow de release (semantic-release publie déjà engine/elevation sur npm + Maven Central via `develop`).

## Depends on

- Task 38 (`./gradlew :demo:assemble` produit un site statique servable)

## Inputs

- `.github/workflows/release.yml` (ou nom équivalent) — référence pour la convention CI du repo.
- `docs/publishing.md` — pour ajouter une section "Démo GitHub Pages".
- `demo/vite.config.ts` — sera ajusté pour le `base` path.

## Steps

### 1. Adapter `vite.config.ts` au sous-chemin Pages

GitHub Pages pour un repo `glandais/vcyclist` sert sous `https://glandais.github.io/vcyclist/`. Vite doit générer des URLs relatives au sous-chemin :

```ts
// vite.config.ts
export default defineConfig({
    // …
    base: process.env.DEPLOY_TARGET === 'gh-pages' ? '/vcyclist/' : './',
    // …
});
```

Garder `./` pour `npm run dev` et `npm run build` standard (offrant la possibilité de servir derrière n'importe quel sous-chemin). Le workflow CI passe `DEPLOY_TARGET=gh-pages` pour le build de production Pages.

### 2. Workflow `.github/workflows/gh-pages.yml`

```yaml
name: Deploy demo to GitHub Pages

on:
  push:
    branches: [develop]
    paths:
      - 'demo/**'
      - 'engine/**'
      - 'elevation/**'
      - '.github/workflows/gh-pages.yml'
  workflow_dispatch:

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: gh-pages
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - name: Build demo
        env:
          DEPLOY_TARGET: gh-pages
        run: ./gradlew :demo:assemble --no-daemon
      - uses: actions/upload-pages-artifact@v3
        with:
          path: demo/dist

  deploy:
    needs: build
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - id: deployment
        uses: actions/deploy-pages@v4
```

⚠ Si Vite est configuré pour écrire dans `demo/build/dist/` (au lieu de `demo/dist/`, cf. task 38 notes), adapter le `path:` de `upload-pages-artifact`.

### 3. Activer GitHub Pages dans les settings du repo

Manuel (à faire par le mainteneur) : `Settings → Pages → Source: GitHub Actions`. Documenter dans `docs/publishing.md`.

### 4. `docs/publishing.md`

Ajouter une section :

```markdown
## Démo GitHub Pages

Le module `:demo` (Vue/Vite, consomme Kotlin/JS engine) est publié
automatiquement sur GitHub Pages à chaque push sur `develop` :

- Workflow : `.github/workflows/gh-pages.yml`
- URL : `https://glandais.github.io/vcyclist/`
- Trigger : push sur `develop` qui touche `demo/`, `engine/`, ou `elevation/`.
- Configuration manuelle requise une fois : `Settings → Pages → Source:
  GitHub Actions`.

Pour tester une PR avant merge, déclencher manuellement le workflow via
`workflow_dispatch` (onglet Actions → "Deploy demo to GitHub Pages" → Run
workflow on branch …).
```

### 5. Smoke (limité — pas reproductible localement)

```bash
# build local avec base path Pages pour vérifier que les assets sont relatifs
cd vcyclist
DEPLOY_TARGET=gh-pages ./gradlew :demo:assemble
grep -E 'src=|href=' demo/dist/index.html
# Doit montrer des paths `/vcyclist/assets/...` (pas `/assets/...` ou `./assets/...`)
```

Puis sur GitHub :

1. Merger la PR avec la task 39.
2. Aller dans Settings → Pages → activer "GitHub Actions" comme source.
3. Vérifier que le workflow `Deploy demo to GitHub Pages` se déclenche sur le prochain push develop.
4. Suivre l'URL exposée par le job `deploy` (visible dans l'environnement github-pages).
5. La démo doit charger Stelvio par défaut et fonctionner identiquement au `npm run dev` local.

## Outputs

Créés :

- `.github/workflows/gh-pages.yml`

Modifiés :

- `demo/vite.config.ts` (ajout du `base` conditionnel)
- `docs/publishing.md` (section "Démo GitHub Pages")

## Validation

- `DEPLOY_TARGET=gh-pages ./gradlew :demo:assemble` produit un `index.html` avec des paths `/vcyclist/...`.
- Le workflow YAML est syntaxiquement valide (CI doit l'accepter au premier push).
- Aucune régression sur `:demo:check` ou `:demo:assemble` standard.

## Done when

- [x] `.github/workflows/gh-pages.yml` créé et valide
- [x] `vite.config.ts` accepte `DEPLOY_TARGET=gh-pages` pour basculer sur `base: '/vcyclist/'`
- [x] `docs/publishing.md` documente le flow GitHub Pages
- [x] Build local avec `DEPLOY_TARGET=gh-pages` produit des assets sous `/vcyclist/...`
- [ ] Workflow se déclenche sur push develop (vérifié post-merge)
- [ ] La page publiée sur `https://<user>.github.io/vcyclist/` charge correctement Stelvio + Enhance fonctionne
- [ ] Toutes les checkboxes cochées

## Notes

- **Pourquoi optionnel** : la valeur "shelf-life" d'une démo en ligne est haute (recruteurs, blog posts, partages Twitter), mais le repo fonctionne très bien sans elle (engine est publié sur npm + Maven Central). Cette tâche peut être reportée si autre chose est plus prioritaire.
- **Concurrent à semantic-release** : `release.yml` (semantic-release) écrit déjà sur develop des commits `chore(release)`. Ces commits sont taggés `[skip ci]`, donc le workflow Pages ne sera pas déclenché par eux — pas de boucle infinie. Vérifier la condition `paths:` est suffisamment précise.
- **Caching Gradle** : le job CI tire le wrapper Gradle + cache Maven local + cache `~/.gradle`. Le download Node initial (via plugin `node-gradle`) ajoute ~30 s ; cachable via `actions/cache` sur `~/.gradle/nodejs/`. Optimisation à faire après le premier run mesuré.
- **Permissions** : le workflow exige `pages: write` et `id-token: write`. Le repo doit avoir ces permissions activées (par défaut OK pour les repos publics).
- **Custom domain** : si on veut servir sous `demo.vcyclist.example.com`, ajouter un fichier `demo/public/CNAME` avec le domaine, et basculer le `base` à `./`. Hors scope de cette tâche.
- **Preview deploys par PR** : non couvert ici. Si désiré, adopter un service tiers (Netlify/Vercel/Cloudflare Pages) au lieu de GitHub Pages — sortie de scope.
