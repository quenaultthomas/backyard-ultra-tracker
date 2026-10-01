# Frontend Backyard Ultra Tracker

Application Angular (standalone, signals) servie par Caddy dans l'image Docker `web`.

Aucune installation locale n'est nécessaire : `docker compose up -d --build` à la racine du dépôt construit et sert l'application sur http://localhost.

Pour développer sans Docker (Node 24) :

```bash
npm ci
npm run build   # build de production dans dist/frontend/browser
npm start       # serveur de développement sur http://localhost:4200
```

Pas de tests unitaires front : les écrans sont couverts par les tests E2E Playwright (`e2e/`).
