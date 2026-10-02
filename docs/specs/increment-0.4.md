# Incrément 0.4 : Configuration de production

Branche : `increment/0.4-configuration-production`. Dernier incrément du jalon 0 (Socle).

## 1. Périmètre

### Inclus
- `docker-compose.prod.yml` : surcharge de `docker-compose.yml` (seul `web` exposé, `restart: unless-stopped`, HTTPS Caddy, volumes des certificats, variables obligatoires).
- Adaptation de `frontend/Caddyfile` : l'adresse du site devient pilotable par variable d'environnement (défaut `:80`, donc dev et E2E inchangés).
- `.env.example` à la racine.
- `docs/deploiement.md` : prérequis VPS, premier déploiement, mise à jour, sauvegarde et restauration de la base, notes développeur (Docker pour `./mvnw verify`, astuce WSL pour Playwright).
- Mise à jour de commentaires obsolètes dans `docker-compose.yml` (aucun changement de comportement).

### Exclu
- Admin master et variables associées : 1.4 (le `.env.example` les mentionne « à venir »).
- Cookie `Secure`, `X-Forwarded-Proto` côté Spring, HSTS, en-têtes de sécurité applicatifs, CSRF : 1.x.
- Volume `logos` : 2.3 (sauvegarde du volume des logos : à documenter à ce moment-là).
- `docker-compose.e2e.yml`, horloge pilotable, E2E en HTTPS : 4.3.
- CI / déploiement automatique : non planifié.
- Aucun changement de code Java ni Angular. Aucun nouvel écran, aucun nouvel endpoint.

## 2. Règles de gestion

Mécanisme du Caddyfile (RG4) : le site est déclaré `{$ADRESSE_SITE::80}` (syntaxe Caddy `{$VARIABLE:défaut}`, le défaut est `:80`). Le compose de dev ne définit pas `ADRESSE_SITE` : le site reste `:80` en HTTP. La surcharge prod définit `ADRESSE_SITE: ${DOMAINE}`. La directive globale `auto_https off` est supprimée : un site `:80` sans nom d'hôte n'est jamais éligible à HTTPS automatique, donc le comportement dev est identique. Aucun Caddyfile dédié, aucune image différente.

- **RG1 Usage** : la production se lance par `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build`. `docker-compose.prod.yml` ne contient que des surcharges, aucun service nouveau.
- **RG2 Ports** : en production, seul `web` publie des ports : `80:80`, `443:443` et `443:443/udp` (HTTP/3, facultatif, désactivable en retirant la ligne UDP). `api` n'en publie aucun : `ports: !reset []` dans la surcharge (les listes `ports` étant fusionnées par Compose, un simple remplacement ne suffit pas ; `!reset` requiert Compose v2.24+, prérequis documenté). `base` n'en publie aucun (déjà le cas).
- **RG3 Redémarrage** : `restart: unless-stopped` sur `base`, `api` et `web`.
- **RG4 Adresse du site** : voir ci-dessus. En production, `web` reçoit `ADRESSE_SITE=${DOMAINE}`.
- **RG5 Redirection** : en production, tout appel HTTP (port 80) est redirigé vers HTTPS (redirection automatique de Caddy, code 308, même chemin).
- **RG6 Certificats** : `DOMAINE=localhost` : Caddy émet un certificat de son autorité interne (non reconnu par les navigateurs : avertissement attendu). Autre domaine : certificat Let's Encrypt obtenu et renouvelé automatiquement ; il suppose un enregistrement DNS pointant vers le VPS et les ports 80 et 443 joignables depuis Internet.
- **RG7 Persistance des certificats** : volumes nommés `donnees-caddy` (`/data`) et `config-caddy` (`/config`), déclarés dans la surcharge prod. Ils survivent à `down` / `up` (pas de nouvelle émission : limites de Let's Encrypt). Le conteneur reste exécuté en utilisateur non root (`web`, déjà propriétaire de `/data` et `/config` dans l'image).
- **RG8 Variables obligatoires en production** : la surcharge utilise `${BASE_MOT_DE_PASSE:?...}` (sur `POSTGRES_PASSWORD` de `base` et `SPRING_DATASOURCE_PASSWORD` de `api`) et `${DOMAINE:?...}`, avec un message explicite. Variable absente ou vide : Compose échoue avant tout démarrage. En dev (sans surcharge), les valeurs par défaut `backyard` restent. Compose ne peut pas interdire la valeur `backyard` : la documentation impose un mot de passe généré (`openssl rand -hex 24`).
- **RG9 Healthcheck de `web` en production** : le test de dev (`wget http://localhost/`) échouerait (redirection vers HTTPS). Le Caddyfile ajoute un site interne `http://127.0.0.1:8081` (`bind 127.0.0.1`, sert uniquement la page d'accueil, non publié) ; la surcharge prod remplace le healthcheck de `web` pour interroger `http://127.0.0.1:8081/`. Le healthcheck de dev reste sur `http://localhost/` (inchangé). La santé reste celle de la page d'accueil, pas de l'API.
- **RG10 Dev et E2E inchangés** : `docker compose up -d --build` seul : mêmes services, mêmes ports (80, 8080), même HTTP ; `BASE_URL=http://localhost` des tests Playwright inchangé.
- **RG11 `.env.example`** : liste toutes les variables lues par les compose : `BASE_NOM`, `BASE_UTILISATEUR`, `BASE_MOT_DE_PASSE`, `DOMAINE`. Chacune est commentée (rôle, obligatoire ou non en production, valeur par défaut en dev). Aucune valeur secrète : `BASE_MOT_DE_PASSE=` vide, `DOMAINE=localhost`. Un commentaire annonce les identifiants de l'admin master « ajoutés en 1.4 ». `.env` reste ignoré par git (déjà le cas).
- **RG12 `docs/deploiement.md`** (en français) contient les sections : prérequis (Docker Engine et Compose >= 2.24, DNS, ports 80/443 TCP et 443 UDP ouverts au pare-feu, 2 Go de RAM recommandés pour la construction), premier déploiement, mise à jour, sauvegarde, restauration, notes développeur, et mentionne le volume des logos « à venir en 2.3 ».
- **RG13 Sauvegarde / restauration de la base** : par `docker compose exec -T base` avec `pg_dump -Fc` et `pg_restore --clean --if-exists --no-owner`, en lisant `POSTGRES_USER` et `POSTGRES_DB` depuis le conteneur (pas depuis l'hôte). La restauration se fait `api` et `web` arrêtés. La documentation indique de sauvegarder aussi, facultativement, `donnees-caddy`.
- **RG14 Piège du volume existant** : `POSTGRES_PASSWORD` n'est appliqué qu'à la création du volume `donnees-postgres`. Passer de dev à prod sur un volume existant fait échouer `api` (authentification refusée) : la documentation et « Tester à la main » imposent `down -v` (local) ou la restauration d'une sauvegarde.
- **RG15 Notes développeur** (dans `docs/deploiement.md`) : `./mvnw verify` nécessite un démon Docker accessible (Testcontainers) ; sous WSL, activer l'intégration WSL de Docker Desktop ou lancer le démon ; si Chromium de Playwright manque de bibliothèques : `sudo npx playwright install-deps chromium` (sous nvm : `sudo env "PATH=$PATH" npx playwright install-deps chromium`).

## 3. Cas limites

- `DOMAINE` non défini ou vide en prod : échec de Compose (RG8). En dev : sans effet.
- `BASE_MOT_DE_PASSE` vide en prod : échec ; en dev : `backyard`.
- Port 80 ou 443 déjà occupé (ex. stack de dev encore lancée) : échec du démarrage de `web` ; arrêter l'autre stack d'abord.
- Domaine ne pointant pas vers le VPS : Caddy échoue à obtenir le certificat (logs de `web`), le site reste inaccessible en HTTPS ; la santé de `web` (RG9) reste saine car indépendante du certificat.
- Redémarrage de l'hôte : les trois services redémarrent seuls (RG3) ; `api` attend `base` saine.
- `docker compose stop` manuel : `unless-stopped` ne redémarre pas le service arrêté.
- Passage dev vers prod sur un volume existant : voir RG14.
- Exposition de `api:8080` à `web` : inchangée, via le réseau interne Compose.
- Sans objet à ce stade : coureurs, boucles, rôles (aucune règle métier dans cet incrément).

## 4. Contrat d'API

Aucun endpoint créé ou modifié. `GET /api/sante` conserve le contrat de 0.2 (200 `{"status":"UP"}` quand l'API est saine, `Cache-Control: no-store`) et devient joignable en HTTPS sur le domaine. Aucun DTO.

Contrat d'environnement (consommé par les compose) :

| Variable | Dev | Production | Rôle |
|---|---|---|---|
| `BASE_NOM` | défaut `backyard` | facultative | nom de la base |
| `BASE_UTILISATEUR` | défaut `backyard` | facultative | utilisateur de la base |
| `BASE_MOT_DE_PASSE` | défaut `backyard` | obligatoire, non vide | mot de passe de la base |
| `DOMAINE` | ignorée | obligatoire | nom de domaine servi en HTTPS (`localhost` pour un essai local) |
| `ADRESSE_SITE` | non définie (Caddy : `:80`) | positionnée par la surcharge à `${DOMAINE}` | interne, non destinée au `.env` |

## 5. Écrans

Aucun écran créé ou modifié. La page d'accueil (« Backyard Ultra Tracker », « API : disponible ») est servie à l'identique, en HTTP en dev, en HTTPS en production. Aucun message d'erreur applicatif ; les messages d'erreur d'outillage sont ceux de Compose (RG8).

## 6. Critères d'acceptation

Niveaux : `outillage` = vérifiable par commande sur la stack lancée (sans code de test applicatif) ; `E2E` = test Playwright.

| N° | RG | Niveau | Étant donné / quand / alors |
|---|---|---|---|
| CA1 | RG1, RG2 | outillage | Étant donné `DOMAINE=x` et `BASE_MOT_DE_PASSE=x`, quand `docker compose -f docker-compose.yml -f docker-compose.prod.yml config` est exécuté, alors `api` n'a aucune section `ports`, `web` publie exactement 80/tcp, 443/tcp et 443/udp, `base` n'en publie aucun. |
| CA2 | RG2 | outillage | Étant donné la stack prod lancée, quand `curl --max-time 5 http://localhost:8080/actuator/health` est exécuté, alors la connexion est refusée (code curl 7) ; `docker compose ps` ne montre aucun port publié pour `api` et `base`. |
| CA3 | RG4, RG6 | outillage | Étant donné la stack prod avec `DOMAINE=localhost`, quand `curl -k https://localhost/api/sante` est exécuté, alors code 200 et corps `{"status":"UP"}` ; `curl -k https://localhost/` renvoie le HTML contenant « Backyard Ultra Tracker ». |
| CA4 | RG5 | outillage | Même stack, quand `curl -sI http://localhost/api/sante` est exécuté, alors code 308 et `Location: https://localhost/api/sante`. |
| CA5 | RG3 | outillage | Même stack, quand `docker inspect -f '{{.HostConfig.RestartPolicy.Name}}'` est lancé sur les 3 conteneurs, alors `unless-stopped` ; après un plantage simulé du processus (`docker compose exec api kill 1` ; un `docker compose kill` compte comme un arrêt manuel et ne déclenche pas `unless-stopped`), `api` redémarre seul et redevient `healthy` en moins de 2 minutes. |
| CA6 | RG8 | outillage | Étant donné `BASE_MOT_DE_PASSE` absent (et `.env` sans valeur), quand la commande `config` ou `up` de production est lancée, alors code de sortie non nul et message citant `BASE_MOT_DE_PASSE`, aucun conteneur créé. |
| CA7 | RG8 | outillage | Même test avec `DOMAINE` absent : échec citant `DOMAINE`. |
| CA8 | RG9 | outillage | Étant donné la stack prod lancée, quand `docker compose ps` est exécuté après 90 s, alors `base`, `api` et `web` sont `healthy` ; `docker compose exec web wget -q -O /dev/null http://127.0.0.1:8081/` réussit ; l'interface 8081 n'est pas publiée sur l'hôte. |
| CA9 | RG7 | outillage | Étant donné la stack prod lancée, quand l'empreinte de `/data/caddy/pki/authorities/local/root.crt` est relevée, puis `down` (sans `-v`) et `up -d` exécutés, alors l'empreinte est identique ; `docker volume ls` contient `donnees-caddy` et `config-caddy`. |
| CA10 | RG10 | outillage | Étant donné aucune surcharge, quand `docker compose up -d --build` est lancé, alors les 3 services sont `healthy`, http://localhost répond 200, `curl http://localhost:8080/actuator/health` répond `UP`, et `docker compose config` ne contient ni `restart`, ni 443, ni volumes Caddy. |
| CA11 | RG10 | E2E | Étant donné la stack de dev lancée, quand `npx playwright test` est exécuté dans `e2e/` (`BASE_URL` par défaut), alors les 2 tests de 0.3 passent sans modification, dont la page d'accueil qui affiche « API : disponible » en moins de 5 s. |
| CA12 | RG13, RG14 | outillage | Étant donné la stack prod, une table `test_sauvegarde` contenant 1 ligne, quand la sauvegarde `pg_dump` est faite, la table supprimée puis la restauration `pg_restore` exécutée (`api` et `web` arrêtés puis relancés), alors la table contient de nouveau 1 ligne et `api` redevient `healthy`. |
| CA13 | RG11 | outillage | Quand `.env.example` est lu, alors chaque `${VAR}` de `docker-compose.yml` et `docker-compose.prod.yml` (hors `ADRESSE_SITE`) y figure avec un commentaire, `BASE_MOT_DE_PASSE=` est vide, et `git check-ignore .env` renvoie `.env`. |
| CA14 | RG12, RG15 | outillage | Quand `docs/deploiement.md` est relu, alors il contient les sections de RG12, la mention du volume des logos « à venir (2.3) », la dépendance Docker de `./mvnw verify` et la commande `sudo npx playwright install-deps chromium`. |
| CA15 | RG4 | outillage | Étant donné `DOMAINE=localhost`, quand `docker compose logs web` est lu, alors aucune erreur de configuration Caddy et une ligne indiquant l'émission du certificat pour `localhost`. |

Couverture : RG1 (CA1), RG2 (CA1, CA2), RG3 (CA5), RG4 (CA3, CA15), RG5 (CA4), RG6 (CA3), RG7 (CA9), RG8 (CA6, CA7), RG9 (CA8), RG10 (CA10, CA11), RG11 (CA13), RG12 (CA14), RG13 (CA12), RG14 (CA12), RG15 (CA14). Aucun test d'intégration ni unitaire : pas de code back. Aucun écran modifié ; la non-régression de l'écran existant est couverte par CA11.

## 7. Tester à la main

Prérequis : Docker avec Compose >= 2.24 (installé : v5.5.1), ports 80, 443 et 8080 libres. Depuis la racine du dépôt.

Pour ne toucher ni aux données ni au `.env` de la stack de dev, la production est testée en local dans un projet compose isolé, avec son propre fichier de variables. Dans toutes les étapes suivantes : `P="docker compose -p backyard-prod --env-file .env.prod -f docker-compose.yml -f docker-compose.prod.yml"`. Sur le VPS, la commande reste celle de `docs/deploiement.md` (fichier `.env`, nom de projet par défaut).

1. Libérer les ports : `docker compose down` (sans `-v`, les données de dev sont conservées).
2. Vérifier la non-régression dev : `docker compose up -d --build`, `docker compose ps` (3 `healthy`), http://localhost affiche « API : disponible », `curl http://localhost:8080/actuator/health` répond `UP`. Puis `docker compose down` (sans `-v`).
3. Créer `.env.prod` (ignoré par git) : `cp .env.example .env.prod`, puis y renseigner `DOMAINE=localhost` et `BASE_MOT_DE_PASSE=` suivi d'une valeur aléatoire (`openssl rand -hex 24`).
4. Contrôle des variables obligatoires : vider temporairement `BASE_MOT_DE_PASSE` dans `.env.prod`, lancer `$P config` : échec avec message citant `BASE_MOT_DE_PASSE`. Remettre la valeur.
5. Lancer la production : `$P up -d --build`, attendre 90 s, `$P ps` : 3 services `healthy`, ports publiés uniquement sur `web` (80, 443 TCP et 443 UDP).
6. HTTPS : `curl -k https://localhost/api/sante` renvoie `{"status":"UP"}`. Dans une fenêtre de **navigation privée** (sinon la redirection 308 reste en cache et casse ensuite http://localhost en dev), https://localhost : avertissement de certificat (attendu, autorité locale Caddy), puis la page « Backyard Ultra Tracker » avec « API : disponible ». Facultatif, pour éviter l'avertissement avec curl : `$P cp web:/data/caddy/pki/authorities/local/root.crt ./caddy-root.crt` puis `curl --cacert ./caddy-root.crt https://localhost/api/sante` (supprimer le fichier ensuite, ne pas le commiter).
7. Redirection : `curl -sI http://localhost/` renvoie `308` et `Location: https://localhost/`.
8. Port 8080 fermé : `curl --max-time 5 http://localhost:8080/actuator/health` échoue (« Connection refused », code 7).
9. Redémarrage automatique : simuler un plantage avec `$P exec api kill 1` (et non `$P kill api`, que Docker traite comme un arrêt manuel), puis `$P ps` : `api` redémarre seul et redevient `healthy` ; `docker inspect -f '{{.Name}} {{.HostConfig.RestartPolicy.Name}}' $($P ps -q)` affiche `unless-stopped` trois fois.
10. Sauvegarde et restauration :
    1. `$P exec -T base sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "create table test_sauvegarde(id int); insert into test_sauvegarde values (1);"'`
    2. `$P exec -T base sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > sauvegarde.dump` (fichier non vide, à supprimer ensuite).
    3. `$P exec -T base sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "drop table test_sauvegarde;"'`
    4. `$P stop api web`, puis `$P exec -T base sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner' < sauvegarde.dump`, puis `$P start api web`.
    5. `$P exec -T base sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select * from test_sauvegarde;"'` affiche 1 ligne. Nettoyer : `drop table test_sauvegarde;`.
11. Persistance des certificats : `$P down` (sans `-v`), `$P up -d`, https://localhost répond encore ; `docker volume ls` contient `backyard-prod_donnees-caddy` et `backyard-prod_config-caddy`.
12. Fin : `$P down -v` (supprime uniquement les volumes du projet `backyard-prod`), supprimer `sauvegarde.dump` et `.env.prod`. Ne pas laisser le port 80 occupé si l'on repasse en dev.
13. Relire `.env.example` et `docs/deploiement.md` (sections de RG12).

## 8. Points ouverts

Bloquants : aucun.

Non bloquants (position par défaut retenue) :
1. **HSTS** : volontairement absent en 0.4. Un HSTS sur `localhost` serait mémorisé par le navigateur et casserait le http://localhost de dev ; à traiter en 1.x pour les domaines réels uniquement.
2. **Proxy et Spring** : pour que le cookie `Secure` et les URL fonctionnent derrière Caddy, Spring devra honorer `X-Forwarded-*` (`server.forward-headers-strategy`) : à traiter en 1.2.
3. **Sécurité de la valeur du mot de passe** : Compose n'interdit que l'absence ; `backyard` reste possible en production. Contrôle renforcé possible côté `api` (refus de démarrer avec le mot de passe par défaut en profil de production) : à décider, hors 0.4.
4. **HTTP/3** : publié par défaut (443/udp) ; à retirer si le pare-feu du VPS bloque l'UDP.
5. **`{$ADRESSE_SITE::80}`** : syntaxe de valeur par défaut Caddy contenant un `:` ; le développeur le valide par CA3 et CA10 et ajuste si l'image Caddy fixée se comporte autrement (alternative : Caddyfile dédié monté en prod).
6. **E2E en HTTPS** : non fait (certificat interne) ; à étudier en 4.3 avec le profil `e2e`.
7. **Sauvegarde automatique** (cron) : non incluse, procédure manuelle uniquement.
8. **Compose minimal** : l'exigence >= 2.24 pour `!reset` est documentée ; pas de contournement pour les anciennes versions.
