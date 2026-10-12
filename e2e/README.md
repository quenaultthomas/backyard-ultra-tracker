# Tests E2E

Playwright (chromium) contre la stack `docker compose` **e2e** lancée à part. Node 24 requis.

```
docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build   # à la racine du dépôt
cd e2e
npm ci
npx playwright install chromium  # sous Linux/WSL : --with-deps
export E2E_ADMIN_MASTER_PSEUDO="$(sed -n 's/^ADMIN_MASTER_PSEUDO=//p' ../.env)"
export E2E_ADMIN_MASTER_MOT_DE_PASSE="$(sed -n 's/^ADMIN_MASTER_MOT_DE_PASSE=//p' ../.env)"
npm test
```

## Stack e2e

Toute la suite tourne sur la stack lancée avec `docker-compose.e2e.yml` (profil Spring `e2e`, horloge pilotable via `/api/test/horloge`). `global-setup.ts` échoue avec « Stack e2e requise : lancer `docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build` » sur une stack normale, et remet l'horloge à zéro avant les tests. **Ne jamais utiliser le profil `e2e` en production.**

`BASE_URL` (défaut `http://localhost`) désigne la stack testée. Playwright ne démarre ni n'arrête aucun conteneur.

`E2E_ADMIN_MASTER_PSEUDO` et `E2E_ADMIN_MASTER_MOT_DE_PASSE` sont obligatoires : mêmes valeurs que `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE` du `.env` de la stack (les deux lignes `export` ci-dessus les y lisent).

## Projets Playwright et `npm test`

- `chromium` : tous les tests, en parallèle, sauf les fichiers `*.horloge.spec.ts`. Aucun ne doit avancer l'horloge.
- `horloge` : les fichiers `*.horloge.spec.ts`, qui avancent l'horloge applicative (`aide-horloge.ts`). Le décalage étant **global à toute l'application**, ce projet s'exécute après `chromium`, avec un seul worker et des fichiers en mode `serial` ; une fixture automatique remet l'horloge à zéro avant et après chaque test (échec « horloge non réinitialisée : tests concurrents ? » sinon). Ne jamais lancer deux suites en même temps sur la même stack.

`npm test` lance `chromium` (sans le scénario « base neuve », voir ci-dessous) puis `horloge --workers=1`. Pour un seul projet : `npx playwright test --project=chromium` ou `npx playwright test --project=horloge --no-deps --workers=1`.

## Base neuve

Le scénario « base neuve » de `donnees-demo.spec.ts` exige une base vierge : `docker compose down -v` puis `docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build`, sans lancer `scripts/donnees-demo.sh` avant, puis `npx playwright test tests/donnees-demo.spec.ts`. `npm test` l'exclut (`--grep-invert "base neuve"`) pour tourner sur n'importe quelle base.

Sous macOS avec Homebrew, `node@24` n'est pas lié dans le `PATH` (formule keg-only) : `export PATH="/opt/homebrew/opt/node@24/bin:$PATH"` avant `npm ci`.
