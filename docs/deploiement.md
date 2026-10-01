# Déploiement

Toute l'application tourne dans Docker : aucune installation de Java, Node ou PostgreSQL n'est nécessaire sur la machine hôte.

| Service | Rôle | Exposition en production |
|---|---|---|
| `base` | PostgreSQL, volume nommé `donnees-postgres` | aucune |
| `api` | Spring Boot, migrations Liquibase appliquées au démarrage | aucune (réseau interne uniquement) |
| `web` | Caddy : front Angular, relais `/api` vers `api`, HTTPS automatique | 80/tcp, 443/tcp, 443/udp |

- Dev / E2E : `docker compose up -d --build` (HTTP sur http://localhost, API aussi publiée sur le port 8080).
- Production : `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build`.

`docker-compose.prod.yml` ne contient que des surcharges : seul `web` publie des ports, `restart: unless-stopped` sur les trois services, HTTPS par Caddy, volumes des certificats `donnees-caddy` et `config-caddy`, variables `BASE_MOT_DE_PASSE` et `DOMAINE` obligatoires.

Dans la suite, sur le VPS :

```sh
PROD="docker compose -f docker-compose.yml -f docker-compose.prod.yml"
```

## Prérequis

- Un VPS Linux avec **Docker Engine** et **Docker Compose >= 2.24** (`docker compose version`) : la surcharge de production utilise la balise `!reset`, inconnue des versions antérieures.
- **2 Go de RAM** recommandés pour la construction des images (Maven et Angular).
- Un **enregistrement DNS** (A, et AAAA si IPv6) du nom de domaine pointant vers l'adresse IP du VPS.
- Pare-feu : ports **80/tcp** et **443/tcp** ouverts depuis Internet (obtention et renouvellement du certificat Let's Encrypt, puis trafic HTTPS), **443/udp** ouvert pour HTTP/3 (facultatif : pour s'en passer, retirer la ligne `443:443/udp` de `docker-compose.prod.yml`).
- Ports 80 et 443 libres sur l'hôte (aucun autre serveur web).
- `git` et `openssl` pour récupérer le code et générer le mot de passe.

## Configuration

Toutes les variables sont décrites dans `.env.example`. Le fichier `.env` n'est jamais commité.

| Variable | Production | Rôle |
|---|---|---|
| `BASE_NOM` | facultative (défaut `backyard`) | nom de la base |
| `BASE_UTILISATEUR` | facultative (défaut `backyard`) | utilisateur de la base |
| `BASE_MOT_DE_PASSE` | **obligatoire**, non vide | mot de passe de la base |
| `DOMAINE` | **obligatoire** | nom de domaine servi en HTTPS |

Si `BASE_MOT_DE_PASSE` ou `DOMAINE` est absente ou vide, Compose échoue avant tout démarrage avec un message citant la variable. Compose ne peut pas refuser une valeur faible : utiliser impérativement un mot de passe généré (`openssl rand -hex 24`), jamais `backyard`.

Identifiants de l'admin master : à venir en 1.4.

## Premier déploiement

1. Récupérer le code :
   ```sh
   git clone <url-du-depot> backyard-ultra-tracker
   cd backyard-ultra-tracker
   ```
2. Créer le fichier de variables et le protéger :
   ```sh
   cp .env.example .env
   chmod 600 .env
   ```
3. Renseigner dans `.env` :
   - `DOMAINE=` le nom de domaine (ex. `backyard.example.org`) ;
   - `BASE_MOT_DE_PASSE=` la sortie de `openssl rand -hex 24`.
4. Vérifier la configuration (échoue si une variable obligatoire manque) :
   ```sh
   $PROD config --quiet
   ```
5. Construire et lancer :
   ```sh
   $PROD up -d --build
   ```
6. Attendre environ 90 s puis vérifier que les trois services sont `healthy` :
   ```sh
   $PROD ps
   ```
7. Vérifier le site : `curl https://<domaine>/api/sante` répond `{"status":"UP"}`, et `http://<domaine>/` redirige (308) vers HTTPS.

Le certificat Let's Encrypt est obtenu au premier démarrage puis renouvelé automatiquement par Caddy. En cas d'échec (DNS non propagé, port 80 ou 443 fermé), la cause figure dans `$PROD logs web` ; le site reste inaccessible en HTTPS mais `web` reste `healthy` (sa santé ne dépend pas du certificat). Les certificats sont conservés dans le volume `donnees-caddy` : ne pas le supprimer sans raison, Let's Encrypt limite le nombre d'émissions.

### Piège du volume de base existant

`POSTGRES_PASSWORD` n'est appliqué par PostgreSQL **qu'à la création** du volume `donnees-postgres`. Changer `BASE_MOT_DE_PASSE` ensuite, ou passer d'une stack de dev (mot de passe `backyard`) à la production sur le même volume, fait échouer `api` (authentification refusée, `api` jamais `healthy`).

- En local, repartir d'un volume vide : `docker compose down -v` (**supprime les données**).
- Sur le VPS, ne jamais supprimer le volume : restaurer une sauvegarde dans une base créée avec le bon mot de passe (voir Restauration), ou changer le mot de passe dans PostgreSQL (`ALTER USER ... PASSWORD ...`) avant de modifier `.env`.

## Mise à jour

1. Faire une sauvegarde (voir ci-dessous).
2. Récupérer la nouvelle version :
   ```sh
   git pull
   ```
3. Comparer `.env.example` avec `.env` et ajouter les nouvelles variables éventuelles.
4. Reconstruire et relancer (les migrations Liquibase s'appliquent au démarrage de `api`) :
   ```sh
   $PROD up -d --build
   $PROD ps
   ```
5. Facultatif, libérer l'espace des anciennes images : `docker image prune -f`.

Ne jamais utiliser `down -v` en production : cette option supprime les volumes, donc la base et les certificats.

## Sauvegarde

Sauvegarde de la base au format personnalisé de `pg_dump`, lancée dans le conteneur `base` (l'utilisateur et la base sont lus depuis le conteneur, pas depuis l'hôte) :

```sh
$PROD exec -T base sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > sauvegarde-$(date +%Y%m%d-%H%M).dump
```

Vérifier que le fichier n'est pas vide, puis le copier hors du VPS. La sauvegarde est manuelle (aucune tâche planifiée fournie).

Facultatif, sauvegarde des certificats Caddy (évite une nouvelle émission après une réinstallation) :

```sh
docker run --rm -v backyard-ultra-tracker_donnees-caddy:/data -v "$PWD":/sauvegarde alpine:3.22 \
  tar czf /sauvegarde/donnees-caddy.tar.gz -C /data .
```

Volume des logos de course : à venir (2.3), sa sauvegarde sera documentée à ce moment-là.

## Restauration

La restauration se fait avec `api` et `web` arrêtés, `base` en marche :

```sh
$PROD stop api web
$PROD exec -T base sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner' < sauvegarde.dump
$PROD start api web
$PROD ps
```

`--clean --if-exists` supprime les objets existants avant de les recréer ; `--no-owner` attribue les objets à l'utilisateur courant. Sur un nouveau VPS : faire le premier déploiement (base vide créée avec le mot de passe de `.env`), puis restaurer.

Restauration facultative des certificats Caddy (`web` arrêté ; `tar` exécuté en root conserve les propriétaires d'origine, l'utilisateur non root `web`) :

```sh
$PROD stop web
docker run --rm -v backyard-ultra-tracker_donnees-caddy:/data -v "$PWD":/sauvegarde alpine:3.22 \
  tar xzf /sauvegarde/donnees-caddy.tar.gz -C /data
$PROD start web
```

## Tester la production en local

Pour ne toucher ni aux données ni au `.env` de la stack de dev, la production se teste dans un projet Compose isolé avec son propre fichier de variables `.env.prod` (ignoré par git) :

```sh
docker compose down            # libère les ports 80 et 8080, sans -v : données de dev conservées
cp .env.example .env.prod
# dans .env.prod : DOMAINE=localhost et BASE_MOT_DE_PASSE=<sortie de openssl rand -hex 24>
P="docker compose -p backyard-prod --env-file .env.prod -f docker-compose.yml -f docker-compose.prod.yml"
$P up -d --build
$P ps                           # 3 services healthy, ports publiés uniquement sur web
curl -k https://localhost/api/sante   # {"status":"UP"}
curl -sI http://localhost/            # 308, Location: https://localhost/
curl --max-time 5 http://localhost:8080/actuator/health   # échec attendu (connexion refusée)
```

Redémarrage automatique : `docker compose kill` et `docker compose stop` sont des arrêts manuels, que `restart: unless-stopped` ne relance volontairement pas. Pour simuler un plantage : `$P exec api kill 1` (arrêt du processus Java), puis `$P ps` : `api` redémarre seul et redevient `healthy` (`docker inspect -f '{{.RestartCount}}' backyard-prod-api-1` vaut 1).

Avec `DOMAINE=localhost`, Caddy émet un certificat de son autorité interne : le navigateur affiche un avertissement (attendu). Pour l'éviter avec curl : `$P cp web:/data/caddy/pki/authorities/local/root.crt ./caddy-root.crt` puis `curl --cacert ./caddy-root.crt https://localhost/api/sante` (ne pas commiter ce fichier).

Pour finir : `$P down -v` (supprime uniquement les volumes du projet `backyard-prod`), puis supprimer `.env.prod` et les éventuels fichiers de sauvegarde. Les étapes détaillées sont dans la section « Tester à la main » de `docs/specs/increment-0.4.md`.

## Notes développeur

- `./mvnw verify` (dans `backend/`) nécessite un **démon Docker accessible** : les tests d'intégration lancent PostgreSQL par Testcontainers. Sous WSL, activer l'intégration WSL de Docker Desktop (Settings > Resources > WSL integration) ou lancer le démon Docker dans la distribution.
- Tests E2E (`e2e/`) : si Chromium de Playwright refuse de démarrer faute de bibliothèques système (WSL, Linux) :
  ```sh
  sudo npx playwright install-deps chromium
  ```
  Sous nvm, `sudo` ne voit pas `npx` : `sudo env "PATH=$PATH" npx playwright install-deps chromium`.
- Les tests E2E tournent sur la stack de dev (`docker compose up -d --build`, `BASE_URL=http://localhost`).
