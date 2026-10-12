# Tests E2E

Playwright (chromium) contre la stack `docker compose` lancée à part. Node 24 requis.

```
docker compose up -d --build     # à la racine du dépôt
cd e2e
npm ci
npx playwright install chromium  # sous Linux/WSL : --with-deps
export E2E_ADMIN_MASTER_PSEUDO="$(sed -n 's/^ADMIN_MASTER_PSEUDO=//p' ../.env)"
export E2E_ADMIN_MASTER_MOT_DE_PASSE="$(sed -n 's/^ADMIN_MASTER_MOT_DE_PASSE=//p' ../.env)"
npx playwright test
```

`BASE_URL` (défaut `http://localhost`) désigne la stack testée. Playwright ne démarre ni n'arrête aucun conteneur.

`E2E_ADMIN_MASTER_PSEUDO` et `E2E_ADMIN_MASTER_MOT_DE_PASSE` sont obligatoires : mêmes valeurs que `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE` du `.env` de la stack (les deux lignes `export` ci-dessus les y lisent).

Le scénario « base neuve » de `donnees-demo.spec.ts` exige une base vierge : `docker compose down -v` puis `docker compose up -d --build`, sans lancer `scripts/donnees-demo.sh` avant les tests.

Sous macOS avec Homebrew, `node@24` n'est pas lié dans le `PATH` (formule keg-only) : `export PATH="/opt/homebrew/opt/node@24/bin:$PATH"` avant `npm ci`.
