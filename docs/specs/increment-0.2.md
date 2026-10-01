# Increment 0.2 : Squelette front

Application Angular minimale servie par Caddy, qui affiche le titre et l'état de l'API. Aucun domaine, aucun changement back.

## 1. Périmètre

**Inclus**
- Application Angular (dernière version stable, 22.2.x) dans `frontend/`, build avec Node 24 LTS.
- `frontend/Dockerfile` multi-étapes : build Node (version fixée) puis exécution Caddy (version fixée, jamais `latest`), utilisateur non root. `frontend/.dockerignore` (`node_modules/`, `dist/`, secrets).
- `frontend/Caddyfile` : sert le build Angular avec fallback SPA vers `index.html`, relaie `/api` vers `api:8080`, et expose `/api/sante` (voir section 4).
- Service `web` dans `docker-compose.yml` : construit depuis `frontend/`, port 80 publié en local, healthcheck sur la page d'accueil, `depends_on: api: condition: service_healthy`.
- Une page d'accueil : titre « Backyard Ultra Tracker » et indicateur « API : disponible » / « API : indisponible », rafraîchi automatiquement.

**Exclu**
- PWA et service worker : 5.2 (aucun `ngsw`, aucun manifeste en 0.2).
- JaCoCo, ArchUnit, Playwright, projet `e2e/` : 0.3. Le test Playwright de la page d'accueil est livré en 0.3.
- `docker-compose.prod.yml`, HTTPS, `.env.example`, `docs/deploiement.md` : 0.4. Le compose garde des valeurs par défaut de développement.
- Connexion, comptes, routage métier, autres écrans : 1.x.
- Tests unitaires front : jamais (règle de CLAUDE.md).
- Changement back : aucun (décision : proxy Caddy, voir RG4).

**Unitaire** : aucun test ni CA `unitaire` (pas de logique métier, pas de test unitaire front). **Intégration** : aucun CA (pas de changement back).

## 2. Règles de gestion

- **RG1** : le front est une application Angular 22.2.x construite avec Node 24 LTS dans le Dockerfile ; aucune installation de Node sur l'hôte n'est requise.
- **RG2** : `frontend/Dockerfile` est multi-étapes, versions d'images fixées (Node et Caddy), image d'exécution en utilisateur non root, sans `node_modules/` ni sources dans l'image finale.
- **RG3** : Caddy sert les fichiers du build Angular ; toute URL qui n'est pas un fichier existant et ne commence pas par `/api` renvoie `index.html` avec 200 (fallback SPA).
- **RG4** : l'état de l'API est lu via `GET /api/sante`. Caddy réécrit ce chemin en `/actuator/health` et le relaie à `api:8080`, sans code back. Le code et le corps de la réponse du back sont transmis tels quels (200 `{"status":"UP"}`, 503 `{"status":"DOWN"}`). Seul `/actuator/health` est joignable de cette façon : `/api/sante` ne donne accès à aucun autre endpoint actuator.
- **RG5** : tout autre chemin `/api/*` est relayé tel quel à `api:8080` (le chemin est conservé, sans réécriture). En 0.2 le back n'expose aucun `/api/*` : la réponse est un 404 du back.
- **RG6** : la page d'accueil affiche toujours le titre « Backyard Ultra Tracker ».
- **RG7** : l'indicateur affiche « API : disponible » uniquement si la réponse est HTTP 200 avec `status` égal à `UP`. Dans tous les autres cas (503, 502/504 de Caddy, autre code, corps illisible, erreur réseau, délai dépassé) il affiche « API : indisponible ». Avant la première réponse, il affiche « API : vérification… ».
- **RG8** : l'indicateur est rafraîchi sans recharger la page : première interrogation au chargement, puis une toutes les 5 s, après la fin de la précédente (pas de requêtes concurrentes). Délai maximal d'une requête : 3 s (au-delà : indisponible). Un retour à la normale de l'API repasse l'indicateur à « disponible » au plus 5 s + 3 s plus tard.
- **RG9** : `web` ne dépend de `api` qu'au démarrage (`service_healthy`). Si `api` s'arrête ensuite, `web` reste démarré, `healthy` (le healthcheck porte sur la page d'accueil servie par Caddy, pas sur l'API) et continue de servir le front ; seul l'indicateur change.
- **RG10** : le front n'appelle l'API que par des chemins relatifs (`/api/...`) : aucune URL absolue, pas de CORS, un seul point d'entrée (port 80 de `web`). La réponse de `/api/sante` n'est pas mise en cache (`Cache-Control: no-store`).
- **RG11** : le port 8080 de `api` reste publié en local (0.1) ; `web` publie le port 80.

## 3. Cas limites

- `api` arrêtée après le démarrage : Caddy répond 502 sur `/api/sante` (ou 504) ; l'indicateur passe à « indisponible » en au plus 8 s ; `web` reste `healthy`.
- `api` redémarrée : l'indicateur revient à « disponible » sans recharger la page (RG8).
- `base` arrêtée, `api` démarrée : le back répond 503 `DOWN` ; l'indicateur affiche « indisponible ».
- Premier `docker compose up` : `web` démarre après `api` saine ; si `api` n'est pas saine, `web` n'est pas lancé.
- Rechargement d'une URL inconnue (ex. `/une/page`) : `index.html` est servi (RG3) ; en 0.2 la page d'accueil s'affiche (pas de page 404 dédiée).
- URL de fichier statique inexistante (ex. `/absent.js`) : fallback SPA (comportement standard `try_files`), accepté en 0.2.
- Requête `/api/sante` en méthode autre que GET : transmise telle quelle ; sans effet côté back (405 ou 404 selon actuator), non couvert.
- Réponse 200 mais corps non JSON ou sans `status: UP` : « indisponible » (RG7).
- Onglet en arrière-plan : le navigateur peut ralentir les minuteurs, comportement toléré.
- Rôle, bénévole, course, boucle : non applicables en 0.2 (aucune donnée métier, aucune authentification).

## 4. Contrat d'API

Aucun nouvel endpoint back. Une route de proxy définie dans Caddy, publique (pas d'authentification en 0.2).

### GET /api/sante (proxy Caddy vers `GET api:8080/actuator/health`)
- Rôle requis : aucun.
- Requête : aucun corps, aucun paramètre. Le front envoie `Accept: application/json`.
- Réponse 200 : `{ "status": "UP" }` (champ `status`, chaîne ; aucun autre champ).
- Réponse 503 : `{ "status": "DOWN" }` (base injoignable côté back).
- Réponse 502 / 504 : générée par Caddy quand `api` est arrêtée ou ne répond pas ; corps non exploité par le front (pas de `ProblemDetail` garanti).
- En-tête de réponse : `Cache-Control: no-store`.
- 400/401/403/404/409 : non applicables.
- Position par défaut retenue : le chemin `/api/sante` est un alias public de la santé ; le futur `/api` métier (1.x) ne devra pas le masquer. Le healthcheck docker de `api` continue d'utiliser `/actuator/health` directement.

### Autres `/api/*`
Relayés sans réécriture vers `api:8080` (RG5). Aucun endpoint métier avant 1.x.

### Schéma Caddy attendu (indicatif, pas du code applicatif)
- `/api/sante` : réécriture vers `/actuator/health` puis `reverse_proxy api:8080`.
- `/api/*` : `reverse_proxy api:8080`.
- Reste : `root` sur le build Angular, `try_files {path} /index.html`, `file_server`.

## 5. Écrans

### Accueil (`/`)
- **Informations affichées** : titre « Backyard Ultra Tracker » ; indicateur d'état de l'API avec trois états : « API : vérification… » (avant la première réponse), « API : disponible », « API : indisponible ». Texte seul suffit ; une pastille de couleur (vert/rouge/gris) est facultative mais ne remplace jamais le texte.
- **Actions** : aucune (pas de navigation, pas de bouton). Le rafraîchissement est automatique (RG8).
- **Messages d'erreur** : « API : indisponible » en cas d'erreur de toute nature (RG7). Aucune pile technique ni code HTTP affiché. Aucun popup.
- Sélecteurs de test conseillés (pour 0.3) : `data-testid="titre"` et `data-testid="etat-api"`.

## 6. Critères d'acceptation

Le niveau `E2E (stack)` désigne une vérification manuelle ou par `curl` sur la stack compose. Playwright n'arrive qu'en 0.3 ; l'écran d'accueil (CA7, CA8, CA9) sera alors couvert par un test Playwright automatisé (niveau `E2E`). Cet incrément ne livre pas ce test.

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné un dépôt propre, quand `docker compose up -d --build`, alors `base`, `api` et `web` passent `healthy` en moins de 180 s, `web` démarrant après `api` (RG1, RG2, RG9) | E2E (stack) |
| CA2 | Étant donné la stack lancée, quand `docker compose exec web id -u`, alors le résultat n'est pas `0` (RG2) | E2E (stack) |
| CA3 | Étant donné la stack lancée, quand `curl -i http://localhost/`, alors 200 et le HTML contient `Backyard Ultra Tracker` et une balise `<app-root` (RG3, RG6) | E2E (stack) |
| CA4 | Étant donné la stack lancée, quand `curl -i http://localhost/une/page/inconnue`, alors 200 et le même `index.html` que `/` (RG3) | E2E (stack) |
| CA5 | Étant donné la stack lancée, quand `curl -i http://localhost/api/sante`, alors 200, corps `{"status":"UP"}` et en-tête `Cache-Control: no-store` (RG4, RG10) | E2E (stack) |
| CA6 | Étant donné la stack lancée, quand `curl -i http://localhost/api/inexistant` puis `curl -i http://localhost/api/sante/../actuator/env`, alors le premier renvoie 404 venant du back, le second 404 (ou 400 selon normalisation), jamais 200, et `/actuator/env` n'est jamais joignable via le port 80 (RG4, RG5) | E2E (stack) |
| CA7 | Étant donné la stack lancée, quand on ouvre http://localhost dans un navigateur, alors le titre « Backyard Ultra Tracker » est visible et l'indicateur affiche « API : disponible » en moins de 3 s (RG6, RG7) | E2E (stack) |
| CA8 | Étant donné la page ouverte avec « API : disponible », quand `docker compose stop api` sans recharger, alors l'indicateur affiche « API : indisponible » en au plus 8 s et le titre reste affiché (RG7, RG8, RG9) | E2E (stack) |
| CA9 | Étant donné `api` arrêtée et la page ouverte, quand `docker compose start api`, alors l'indicateur repasse à « API : disponible » sans rechargement, en moins de 60 s après que `api` est `healthy` (RG8) | E2E (stack) |
| CA10 | Étant donné `api` arrêtée, quand `docker compose ps` et `curl -i http://localhost/`, alors `web` est `running` et `healthy`, la page répond 200, et `curl -i http://localhost/api/sante` répond 502 ou 504 (RG4, RG9) | E2E (stack) |
| CA11 | Étant donné `base` arrêtée, `api` démarrée, quand `curl -i http://localhost/api/sante`, alors 503 et `{"status":"DOWN"}`, et la page affiche « API : indisponible » (RG4, RG7) | E2E (stack) |
| CA12 | Étant donné la stack lancée, quand on inspecte les requêtes réseau de la page pendant 20 s, alors elles ciblent uniquement des chemins relatifs `/api/sante` en séquence, environ toutes les 5 s, sans erreur CORS (RG8, RG10) | E2E (stack) |
| CA13 | Étant donné la stack lancée, quand `curl -i http://localhost:8080/actuator/health`, alors 200 `{"status":"UP"}` (le port de `api` reste publié, 0.1 non régressé) (RG11) | E2E (stack) |

Répartition : unitaire 0, intégration 0, E2E (stack) 13.

Couverture des RG : RG1 CA1, RG2 CA1/CA2, RG3 CA3/CA4, RG4 CA5/CA6/CA10/CA11, RG5 CA6, RG6 CA3/CA7, RG7 CA7/CA8/CA11, RG8 CA8/CA9/CA12, RG9 CA1/CA8/CA10, RG10 CA5/CA12, RG11 CA13. L'écran Accueil est couvert par CA7, CA8, CA9.

## 7. Tester à la main

Prérequis : Docker et `docker compose`. Depuis la racine du dépôt.

1. `docker compose up -d --build` : construit `api` et `web`. Attendre environ 2 min au premier lancement.
2. `docker compose ps` : `base`, `api`, `web` en `healthy`.
3. Ouvrir http://localhost : titre « Backyard Ultra Tracker » et « API : disponible ».
4. `curl -i http://localhost/api/sante` : `200`, `{"status":"UP"}`, `Cache-Control: no-store`.
5. `curl -i http://localhost/une/page/inconnue` : `200`, même contenu que l'accueil.
6. `curl -i http://localhost/api/inexistant` : `404`. `docker compose exec web id -u` : nombre différent de `0`.
7. Laisser la page ouverte, puis `docker compose stop api` : en moins de 8 s l'indicateur passe à « API : indisponible », sans recharger. `docker compose ps` : `web` toujours `healthy`. `curl -i http://localhost/api/sante` : `502` ou `504`.
8. `docker compose start api` : après que `api` redevient `healthy`, l'indicateur repasse à « API : disponible » tout seul.
9. `docker compose stop base` : `curl -i http://localhost/api/sante` finit en `503` `{"status":"DOWN"}` (patienter quelques secondes) et l'indicateur passe à « indisponible ». `docker compose start base` : retour à « disponible ».
10. Nettoyage facultatif : `docker compose down` (ajouter `-v` pour supprimer les données).

## 8. Points ouverts

Bloquants : aucun.

Non bloquants (position par défaut retenue, à confirmer) :
1. **Accès à la santé via Caddy** : par défaut, alias Caddy `/api/sante` vers `/actuator/health`, sans code back. Alternative : endpoint back dédié (`/api/sante`) à introduire avec Spring Security en 1.x. Cet alias n'ayant pas de lien avec le futur `/api` métier, il est à reconsidérer en 1.x si Spring Security change l'exposition d'actuator.
2. **Port de Caddy non root** : écouter sur le port 80 sans root demande la capacité `NET_BIND_SERVICE` (présente sur l'image officielle) ou un port interne élevé mappé en `80:xxxx` ; choix laissé au développeur, le port publié reste 80.
3. **Healthcheck de `web`** : l'image Caddy alpine fournit `wget` ; par défaut `wget --spider http://localhost/`. À ajuster si l'image fixée diffère. La page d'accueil est interrogée en HTTP local (pas d'HTTPS avant 0.4) ; le site Caddy est configuré en HTTP simple en 0.2, la gestion du domaine et de HTTPS arrive en 0.4.
4. **Version exacte de Caddy et de Node 24** : choix du développeur (tag précis, sans `latest`).
5. **Fréquence et délai** : 5 s d'intervalle et 3 s de délai maximal retenus par défaut ; ajustables sans impact. Le polling de 2-3 s de l'écran de suivi (CLAUDE.md) est un autre sujet (4.x).
6. **Fallback SPA sur fichiers statiques absents** : renvoie `index.html` (200) ; acceptable en 0.2.
