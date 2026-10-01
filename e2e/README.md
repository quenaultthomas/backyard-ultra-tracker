# Tests E2E

Playwright (chromium) contre la stack `docker compose` lancée à part. Node 24 requis.

```
docker compose up -d --build     # à la racine du dépôt
cd e2e
npm ci
npx playwright install chromium  # sous Linux/WSL : --with-deps
npx playwright test
```

`BASE_URL` (défaut `http://localhost`) désigne la stack testée. Playwright ne démarre ni n'arrête aucun conteneur.
