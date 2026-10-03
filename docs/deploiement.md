# Déploiement

Toute l'application tourne dans Docker : aucune installation de Java, Node ou PostgreSQL n'est nécessaire sur la machine hôte.

| Service | Rôle | Exposition en production |
|---|---|---|
| `base` | PostgreSQL, volume nommé `donnees-postgres` | aucune |
| `api` | Spring Boot, migrations Liquibase appliquées au démarrage | aucune (réseau interne uniquement) |
| `web` | Caddy : front Angular, relais `/api` vers `api`, HTTPS automatique | 80/tcp, 443/tcp, 443/udp |

- Dev / E2E : `docker compose up -d --build` (HTTP sur http://localhost, API aussi publiée sur le port 8080).
- Production : `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build`.

`docker-compose.prod.yml` ne contient que des surcharges : seul `web` publie des ports, `restart: unless-stopped` sur les trois services, HTTPS par Caddy, volumes des certificats `donnees-caddy` et `config-caddy`, variables `BASE_MOT_DE_PASSE`, `DOMAINE`, `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE` obligatoires.

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
| `CONNEXION_ECHECS_MAX` | facultative (défaut `5`, entier ≥ 1) | échecs de connexion consécutifs sur un même pseudo avant blocage temporaire |
| `CONNEXION_BLOCAGE_SECONDES` | facultative (défaut `900`, entier ≥ 1) | durée du blocage, et délai au-delà duquel des échecs partiels sont oubliés |
| `ADMIN_MASTER_PSEUDO` | **obligatoire** (facultative en dev) | pseudo de l'admin master, créé au démarrage s'il n'existe pas (3 à 30 caractères) |
| `ADMIN_MASTER_MOT_DE_PASSE` | **obligatoire** (facultative en dev) | mot de passe initial de l'admin master (12 à 128 caractères) |

Si `BASE_MOT_DE_PASSE`, `DOMAINE`, `ADMIN_MASTER_PSEUDO` ou `ADMIN_MASTER_MOT_DE_PASSE` est absente ou vide, Compose échoue avant tout démarrage avec un message citant la variable. Compose ne peut pas refuser une valeur faible : utiliser impérativement un mot de passe généré (`openssl rand -hex 24`), jamais `backyard`.

Une valeur de `CONNEXION_ECHECS_MAX` ou `CONNEXION_BLOCAGE_SECONDES` non entière ou inférieure à 1 empêche `api` de démarrer (jamais `healthy`) ; `$PROD logs api` cite la propriété (`backyard.connexion.echecs-max` ou `backyard.connexion.blocage-secondes`) et la borne. Pour un test à la main, `CONNEXION_BLOCAGE_SECONDES=30` évite d'attendre 15 minutes ; ne pas garder une valeur aussi basse en production.

### Admin master

L'admin master est l'unique compte de rôle `ADMIN_MASTER`. Il n'est jamais créable depuis l'interface : `api` le crée à son démarrage, après les migrations, à partir de `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE`.

- **Création** : uniquement s'il n'existe pas encore. `$PROD logs api` contient alors « Admin master créé (compte <identifiant>) ». Il se connecte ensuite comme tout compte (pseudo insensible à la casse).
- **Variables ignorées ensuite** : dès qu'un admin master existe (« Admin master déjà présent » dans les logs), les deux variables ne sont plus lues ; les modifier ne change ni son pseudo ni son mot de passe. Elles restent néanmoins exigées par `docker-compose.prod.yml` à chaque démarrage.
- **Échec du démarrage** (`api` jamais `healthy`, cause dans `$PROD logs api`, sans aucune valeur) : une seule des deux variables renseignée ; pseudo invalide (3 à 30 caractères, lettres, chiffres, `.`, `_`, `-`) ; mot de passe de moins de 12 ou de plus de 128 caractères ; pseudo déjà utilisé par un autre compte (ce compte n'est jamais promu : choisir un autre pseudo).
- **Dev** : les deux variables vides, `api` démarre sans admin master avec l'avertissement « Aucun admin master n'existe et ADMIN_MASTER_PSEUDO / ADMIN_MASTER_MOT_DE_PASSE ne sont pas renseignées ».
- **Changement du mot de passe** : par l'écran « Mon compte » (http(s)://<domaine>/mon-compte), mot de passe actuel exigé. À faire après le premier déploiement : la valeur de `ADMIN_MASTER_MOT_DE_PASSE` n'est plus relue ensuite, c'est le nouveau mot de passe qui reste valable après redémarrage. Les autres sessions de l'admin master sont fermées.
- **Mot de passe** : générer avec `openssl rand -base64 18`. Compose interprète `$` dans `.env` : éviter ce caractère ou le doubler (`$$`). Le mot de passe n'apparaît jamais dans les logs, mais reste visible par `docker inspect` et `printenv` sur le VPS : protéger l'accès au VPS et au fichier `.env` (`chmod 600`).

**Mot de passe de l'admin master perdu** (l'écran « Mon compte » exige le mot de passe actuel) : supprimer le compte puis redémarrer `api` avec de nouvelles valeurs dans `.env` ; il est recréé.

```sh
# Remplacer backyard par BASE_UTILISATEUR et BASE_NOM s'ils ont été changés.
$PROD exec base psql -U backyard -d backyard -c "delete from compte where role = 'ADMIN_MASTER'"
$PROD up -d
$PROD logs api | grep "Admin master"
```

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
   - `BASE_MOT_DE_PASSE=` la sortie de `openssl rand -hex 24` ;
   - `ADMIN_MASTER_PSEUDO=` le pseudo de l'admin master (ex. `Patron`) ;
   - `ADMIN_MASTER_MOT_DE_PASSE=` la sortie de `openssl rand -base64 18` (à conserver dans un gestionnaire de mots de passe).
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
8. Vérifier l'admin master : `$PROD logs api | grep "Admin master"` affiche « Admin master créé (compte <identifiant>) », puis se connecter sur `https://<domaine>/connexion` avec ses identifiants : arrivée sur l'espace d'administration.

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
3. Comparer `.env.example` avec `.env` et ajouter les nouvelles variables éventuelles (depuis 1.4 : `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE`, obligatoires ; ignorées si l'admin master existe déjà).
4. Reconstruire et relancer (les migrations Liquibase s'appliquent au démarrage de `api`) :
   ```sh
   $PROD up -d --build
   $PROD ps
   ```
5. Facultatif, libérer l'espace des anciennes images : `docker image prune -f`.

Ne jamais utiliser `down -v` en production : cette option supprime les volumes, donc la base et les certificats.

Les sessions de connexion sont conservées en mémoire par `api` (expiration après 12 h d'inactivité) : toute mise à jour ou tout redémarrage de `api` déconnecte tous les utilisateurs, qui doivent se reconnecter. Éviter de mettre à jour pendant une course.

Les compteurs d'échecs de connexion et les blocages temporaires sont aussi en mémoire : un redémarrage de `api` les remet à zéro (c'est aussi le moyen de débloquer un pseudo avant la fin du délai). L'application est prévue pour une seule instance de `api`.

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

Le nom du volume est préfixé par le nom du projet Compose (`backyard-ultra-tracker_` sur le VPS, `backyard-prod_` pour le test local décrit plus bas) : vérifier avec `docker volume ls`.

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
# dans .env.prod : DOMAINE=localhost, BASE_MOT_DE_PASSE=<sortie de openssl rand -hex 24>,
#                  ADMIN_MASTER_PSEUDO=Patron et ADMIN_MASTER_MOT_DE_PASSE=<sortie de openssl rand -base64 18>
P="docker compose -p backyard-prod --env-file .env.prod -f docker-compose.yml -f docker-compose.prod.yml"
$P up -d --build
$P ps                           # 3 services healthy, ports publiés uniquement sur web
curl -k https://localhost/api/sante   # {"status":"UP"}
curl -sI http://localhost/            # 308, Location: https://localhost/
curl --max-time 5 http://localhost:8080/actuator/health   # échec attendu (connexion refusée)
```

Redémarrage automatique : `docker compose kill` et `docker compose stop` sont des arrêts manuels, que `restart: unless-stopped` ne relance volontairement pas. Pour simuler un plantage : `$P exec api kill 1` (arrêt du processus Java), puis `$P ps` : `api` redémarre seul et redevient `healthy` (`docker inspect -f '{{.RestartCount}}' backyard-prod-api-1` vaut 1 juste après ; le compteur repart à 0 si le conteneur est recréé par `down` puis `up`).

**Navigateur : utiliser une fenêtre de navigation privée.** En production, `http://localhost` répond par une redirection 308 permanente vers `https://localhost`, que le navigateur garde en cache. De retour sur la stack de dev (HTTP seul, rien sur le port 443), http://localhost devient alors inaccessible dans ce navigateur. Si c'est déjà arrivé : vider le cache du navigateur pour localhost (F12, clic droit sur le bouton recharger, « Vider le cache et effectuer une actualisation forcée »).

Avec `DOMAINE=localhost`, Caddy émet un certificat de son autorité interne : le navigateur affiche un avertissement (attendu). Pour l'éviter avec curl : `$P cp web:/data/caddy/pki/authorities/local/root.crt ./caddy-root.crt` puis `curl --cacert ./caddy-root.crt https://localhost/api/sante` (ne pas commiter ce fichier).

Pour finir : `$P down -v` (supprime uniquement les volumes du projet `backyard-prod`), puis supprimer `.env.prod` et les éventuels fichiers de sauvegarde. Les étapes détaillées sont dans la section « Tester à la main » de `docs/specs/increment-0.4.md`.

## Notes développeur

- `./mvnw verify` (dans `backend/`) nécessite un **démon Docker accessible** : les tests d'intégration lancent PostgreSQL par Testcontainers. Sous WSL, activer l'intégration WSL de Docker Desktop (Settings > Resources > WSL integration) ou lancer le démon Docker dans la distribution.
- Tests E2E (`e2e/`) : si Chromium de Playwright refuse de démarrer faute de bibliothèques système (WSL, Linux) :
  ```sh
  sudo npx playwright install-deps chromium
  ```
  Sous nvm, `sudo` ne voit pas `npx` : `sudo env "PATH=$PATH" npx playwright install-deps chromium`.
- Les tests E2E tournent sur la stack de dev (`docker compose up -d --build`, `BASE_URL=http://localhost`). Depuis 1.4, ils supposent un admin master : renseigner `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE` dans `.env` avant le démarrage, et les mêmes valeurs dans `E2E_ADMIN_MASTER_PSEUDO` et `E2E_ADMIN_MASTER_MOT_DE_PASSE` pour Playwright.
