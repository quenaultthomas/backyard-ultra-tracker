# Incrément 4 — Choix techniques du développeur

> Complément de `docs/specs/increment4.md`. Ce document ne crée aucune règle métier. Il justifie les points laissés au développeur (PO5), la tenue de la CSP (PO13) et les interprétations faites pendant le développement.

## 1. Bibliothèques QR (PO5)

### Choix : `@nuintun/qrcode` 5.0.3, pour la génération et pour le décodage, avec `BarcodeDetector` quand il existe

| Critère (section 0 de la spec) | Constat |
|---|---|
| Licence compatible | MIT. |
| Maintenance active | Version 5.0.3 publiée en février 2026. Portage TypeScript de ZXing, encodeur et décodeur dans un seul paquet. |
| Embarquée dans le bundle | Dépendance npm compilée dans les fichiers de la PWA. Aucun CDN et aucun téléchargement à l'exécution. Les fichiers sont mis en cache par le service worker (RG45). |
| Compatible avec la CSP de RG55 | Code JavaScript pur, sans `eval`, sans `new Function`, sans WebAssembly et sans worker chargé dynamiquement. L'image générée est une URL `data:image/gif`, autorisée par `img-src 'self' data:`. |
| Pas seulement `BarcodeDetector` | `QrFrameReader` (`frontend/src/app/infra/qr-code.ts`) utilise `BarcodeDetector` seulement s'il existe et s'il sait lire `qr_code`. Sinon, il se replie sur le décodeur embarqué (cas de Safari iOS). |
| Paquet ESM | Aucun avertissement « CommonJS » du build Angular (RG59). |

### Alternatives écartées

- `jsqr` (Apache-2.0) : dernière version en 2021, paquet CommonJS, qui produit un avertissement du build Angular.
- `qr-scanner` (MIT) : non maintenu depuis 2022, charge un worker séparé.
- `zxing-wasm` et le polyfill `barcode-detector` : ils reposent sur WebAssembly. La CSP devrait alors autoriser `'wasm-unsafe-eval'` dans `script-src`, ce qui est exclu. Le polyfill télécharge en plus son binaire depuis un CDN par défaut.
- `@zxing/library` et `@zxing/browser` : maintenus, mais plus lourds, et il faudrait une seconde bibliothèque pour la génération.
- `qrcode` (MIT) : générateur seulement, paquet CommonJS avec des dépendances Node (`pngjs`, `yargs`).

## 2. CSP et styles (PO13) : `style-src 'self'` est tenu, sans dérogation

La CSP servie est exactement celle de RG55, y compris `script-src 'self'` et `style-src 'self'`, sans `'unsafe-inline'` ni nonce.

Mesures prises :

1. **Aucun style de composant.** Aucun composant Angular ne déclare `styles` ni `styleUrl`. Tous les styles sont dans `frontend/src/styles.css`, servi comme un fichier à empreinte (`styles-XXXXXXXX.css`, `<link rel="stylesheet">`). Angular n'injecte donc aucune balise `<style>` à l'exécution.
2. **Pas d'intégration des CSS critiques.** `angular.json` fixe `optimization.styles.inlineCritical: false`. Le `index.html` produit ne contient donc ni `<style>` en ligne ni l'astuce `media="print" onload=...`, qui est un gestionnaire d'événement en ligne et serait bloquée par `script-src 'self'`.
3. **Aucun attribut `style` dans les templates, et aucune animation Angular.** Aucune liaison `[style.*]` n'est utilisée.
4. **Aucun script en ligne.** Le `index.html` produit ne charge que `<script src="main-XXXXXXXX.js" type="module">`.

Vérifications faites :

- relecture du `index.html` produit par le build de production ;
- en-têtes observés sur l'application démarrée (Tomcat réel, profil `test`) ;
- rendu de `/`, `/connexion`, `/courses/999` et `/courses/1/inconnu` dans Chrome sans interface. Le journal de la console ne montre aucune violation de CSP.

Le parcours complet sous CSP dans un vrai navigateur relève des E2E (agent test-e2e-frontend).

## 3. Build et outillage (PO4, RG59)

- Angular 22.2, TypeScript 6.0 en mode `strict: true` et `strictTemplates: true`. Les diagnostics étendus d'Angular sont promus en erreurs. `noUnusedLocals` et `noUnusedParameters` sont activés.
- Node **v24.21.0** est fixé à trois endroits : `backend/pom.xml` (propriété `frontend.node.version`), `frontend/.nvmrc` et `frontend/package.json` (`engines`). Le build Maven installe cette version dans `frontend/node/` (ignoré par git) avec `frontend-maven-plugin` 2.0.2.
- Chaîne exécutée par `mvn -B -f backend/pom.xml clean verify` :
  1. `npm ci` ;
  2. `ng build --configuration production` ;
  3. copie de `frontend/dist/backyard-pwa/browser` vers `target/classes/static` ;
  4. `npm test`, en phase `test`.
- `npm test` fait d'abord `tsc -p tsconfig.spec.json`, qui vérifie les tests en mode strict, puis lance `vitest run --coverage`.
- Tests unitaires de la logique pure :
  - Vitest en environnement Node, sans navigateur ni `TestBed`. L'horloge, la minuterie, le stockage et le transport sont injectés.
  - Périmètre de couverture déclaré dans `frontend/vitest.config.mts` (extension `.mts` : configuration chargée en ESM, sans avertissement du chargeur de Vite) : tout `src/app/core/**/*.ts`, hors tests, doublures de test (`*.spec-support.ts`) et fichiers de types seuls (`*.types.ts`).
  - Seuil bloquant : 80 % de lignes.
- Les scripts d'installation des dépendances natives du build Angular (esbuild, lmdb…) sont approuvés explicitement dans `package.json` (`allowScripts`, npm 11).

## 4. Service des fichiers par Spring Boot (RG53 à RG55)

- La liste fermée des chemins est définie une seule fois, dans `fr.backyard.config.PwaPaths`. `SecurityConfig` l'ouvre en GET et HEAD seulement.
  - Elle a été vérifiée contre la sortie réelle du build : `index.html`, `manifest.webmanifest`, `favicon.ico`, `ngsw-worker.js`, `ngsw.json`, `safety-worker.js`, `worker-basic.min.js`, `icons/*`, `main-*.js`, `chunk-*.js` et `styles-*.css`.
  - Le build ne produit ni `assets/` ni `media/`. Ces chemins restent dans la liste de la spec, sans effet.
  - `3rdpartylicenses.txt` est produit hors de `browser/` : il n'est pas copié dans le jar.
- Le service par défaut de `/**` par Spring Boot est désactivé (`spring.web.resources.add-mappings=false`). Les motifs servis par `PwaWebConfig` sont tous dérivés de `PwaPaths` : un fichier précis devient une variable à expression régulière (`/{file:ngsw-worker\.js}`), plus spécifique que `/*.js`.
- Les routes du front sont redirigées en interne (forward) vers `/index.html`. Un chemin `/api/**` n'est jamais concerné.
- Cache :
  - `no-cache` pour `index.html` et les routes du front, le manifeste, les fichiers du service worker, les icônes et le favicon ;
  - `max-age=31536000, public, immutable` pour tous les `/*.js` et `/*.css` de la racine. Le build Angular (`outputHashing: all`) leur donne tous une empreinte, sauf les trois scripts du service worker, nommés explicitement dans un motif plus spécifique.
  - Les empreintes Angular contiennent des minuscules, `_` et `-` (ex. `chunk-B4CwB24-.js`). Elles ne sont donc pas détectées par une expression régulière.
- Le manifeste est servi par `fr.backyard.web.PwaManifestController` en `application/manifest+json`. Le gestionnaire de ressources statiques de Spring ne connaît pas l'extension `.webmanifest` : il répondait `application/octet-stream`.
- En-têtes de RG55 posés par Spring Security sur toutes les réponses. `Permissions-Policy` passe par un `StaticHeadersWriter`, pour ne pas dépendre d'une API dépréciée.

## 5. E19 et tests de slice existants

`SessionController` injecte `SessionService` avec `@Lazy`. Les tests de slice existants (`@WebMvcTest` sur tous les controllers, avec six services mockés) ne déclarent pas ce nouveau service. Sans `@Lazy`, leur contexte ne démarrerait plus, et la consigne interdit de les modifier (CA2). Le service réel est résolu au premier appel.

Le rôle est dérivé dans `SessionService` (retrait du préfixe `ROLE_`, exactement un rôle exigé), jamais dans le controller.

## 6. Interprétations de la spec

- **RG37, nombre de coureurs** : « longueur de E13, chargée à l'ouverture de la course » est lu comme un nombre affiché sur l'écran de la course (`/admin/courses/{id}`), pas dans la liste des courses. Cela évite un appel E13 par course dans la liste.
- **RG21 et RG25, ordre de la file** : chaque élément porte un rang (`queuePosition`), attribué à la capture dans l'ordre de capture. « Renvoyer » lui donne le rang maximal plus 1 (fin de file). Le format 1 fictif (sans rang) sert à démontrer la migration de CA16.
- **RG20, reprise** : la remise EN_ATTENTE des éléments EN_COURS est faite à chaque ouverture de la file, par tout onglet. C'est sans danger : l'émetteur conserve en mémoire l'élément en cours d'envoi, et l'idempotence (RG23) couvre un renvoi.
- **RG50, vibration « 2 × 50 ms »** : motif `[50, 50, 50]`, soit vibration, pause, vibration.
- **RG3 et RG24, réponse 2xx non JSON** (portail captif, par exemple) : classée TRANSITOIRE et non SUCCÈS, pour ne jamais perdre un scan.
- **RG7, connexion ADMIN** : aucune écriture dans le stockage persistant. En revanche, les identifiants SCANNER éventuellement mémorisés sont **effacés**, car « une connexion remplace la précédente ». CA18 interdit l'écriture, pas l'effacement.
- **RG13, 400 sur l'inscription** (identifiant non numérique) : traité comme « Course introuvable », comme le demande RG11.
