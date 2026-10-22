# Increment 7.4 : Publication de la version 0.5.0 (DEP-03, DEP-07, CVE)

**Dépendance : 7.1 à 7.3 livrés.** Branche `increment/7.4-release-0.5.0`, issue de `increment/7.3-transport-erreurs-connexion`. Source : `docs/roadmap.md` (7.4) et `docs/audits/audit-2026-10-21-pre-release.md` (DEP-03, DEP-07, chapitre « CVE et dépendances »).

Numérotation : RG et CA repartent à 1. Incrément sans code métier, sans endpoint, sans écran nouveau : versions, documentation, outillage de build, un script d'exploitation.

## 1. Périmètre

**Inclus**
- Passage des sources en `0.5.0` : `backend/pom.xml`, `frontend/package.json`, `e2e/package.json` et leurs `package-lock.json`, `VERSION` d'exemple de `.env.example` (RG1, RG2).
- Section `## [0.5.0]` de `CHANGELOG.md` (RG3).
- Mise à jour de Vite et d'`@angular/build` (outillage de build) et audit npm propre (RG4, RG5) ; contrôle des dépendances Maven et des images Docker, documenté (RG6, RG7).
- Script de sauvegarde planifiable `scripts/sauvegarder.sh`, exemple `cron`, restauration testée (RG8 à RG10).
- Procédure de répétition générale `-rc.1` dans `docs/deploiement.md` (RG11).

**Exclu**
- Poser un tag, pousser, créer la GitHub Release, déployer sur le VPS : actions manuelles de l'utilisateur, décrites dans « Tester à la main ». Le développeur ne les exécute jamais.
- Plugin `dependency-check` dans le build Maven (clé NVD et temps de build) : contrôle manuel documenté, pas d'automatisation (point ouvert 2).
- Sauvegarde automatique pilotée par l'application ou service `compose` dédié : exclu, le `cron` de l'hôte suffit.
- Copie hors VPS automatisée (rclone, S3) : la copie reste manuelle ou à la charge de l'utilisateur (point ouvert 3).
- Toute évolution de code applicatif, de schéma Liquibase ou de contrat d'API.

**Taille estimée (hors tests) : ~120 lignes** (script ~60, doc ~50, CHANGELOG ~40 hors lignes de lock). Pas de découpage.

## 2. Règles de gestion

### Version (DEP-03)

- **RG1 : version unique 0.5.0.** `backend/pom.xml` (`<version>` du projet, sans `-SNAPSHOT`), `frontend/package.json`, `e2e/package.json` et les entrées racine de leurs `package-lock.json` (`version` et `packages[""].version`) valent exactement `0.5.0`. Aucune autre occurrence de `0.4.0` ne subsiste hors `CHANGELOG.md` (section 0.4.0 et lien) et hors exemples historiques de `docs/deploiement.md` explicitement datés. `/api/version` et `/actuator/info` renvoient `0.5.0` pour un build local sans argument.
- **RG2 : `.env.example`.** `VERSION=0.5.0`, et le commentaire d'exemple cite `0.5.0` et `0.5.0-rc.1`. `docs/deploiement.md` (tableau des variables, premier déploiement) cite les mêmes exemples.
- **RG3 : CHANGELOG 0.5.0.** Section `## [0.5.0] - <date de la rédaction, AAAA-MM-JJ>` placée au-dessus de la 0.4.0, au même format, avec un lien de comparaison `[0.5.0]: https://github.com/quenaultthomas/backyard-ultra-tracker/releases/tag/v0.5.0`. Contenu obligatoire :
  - **Ajouté**, par jalon : jalon 5 (scan hors ligne : file locale, nouvelles tentatives, idempotence sur l'identifiant de scan, compteur « en attente »), jalon 6 (application installable PWA, mise à jour proposée par bandeau, écran de suivi public projetable avec polling, accueil public des courses), jalon 7 (délai de grâce de 3 minutes avant de trancher une Boucle, suivi public sous charge : micro-cache, limite de débit par IP, relance étalée ; planificateur allégé ; fin des requêtes N+1).
  - **Sécurité** : HSTS en HTTPS, cookies `Secure` indépendants de `X-Forwarded-Proto`, plafond de hachage par IP, gestionnaire d'erreurs couvrant tous les contrôleurs, mise à jour de l'outillage de build (RG4). Retirer de la 0.5.0 la mention « pas d'en-tête HSTS » de la 0.4.0 : la section 0.5.0 indique que cette limite est levée.
  - **Variables d'environnement nouvelles depuis la 0.4.0**, avec défaut et caractère facultatif : `BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE` (défaut 1200, `0` désactive), `COOKIES_SECURE` (défaut `false` en dev et E2E, imposée `true` en production), `HACHAGE_CONCURRENCE_MAX_PAR_IP` (défaut 1), `BACKYARD_PROXY_DE_CONFIANCE` (fixée à `web` par `docker-compose.yml`, non réglable). La liste est comparée à `.env.example` : toute variable de `.env.example` absente de la 0.4.0 doit y figurer.
  - **Changements à l'exploitation** : sauvegarde planifiable (RG8), session et blocages perdus à chaque redémarrage de `api` (rappel), ne pas mettre à jour pendant une course.
  - **Limites connues** restantes, reprises de `deploiement.md` (blocage de connexion par pseudo, limite de débit partagée derrière une IP commune, correction impossible sur course `TERMINEE` après délai de grâce).
- La vérification du workflow `Release` (tag = versions des sources = section du CHANGELOG, suffixe `-rc.N` ignoré) doit passer pour `v0.5.0-rc.1` et `v0.5.0` sans modification du workflow.

### CVE et dépendances

- **RG4 : outillage de build à jour.** `vite` et `@angular/build` sont montés à la plus petite version corrigeant les alertes Dependabot ouvertes sur ces paquets, sans changement de version majeure d'Angular. Si `vite` est une dépendance transitive de `@angular/build`, la mise à jour passe par `@angular/build` (ou, à défaut, un `overrides` documenté dans `package.json`, retiré dès que le paquet amont le corrige). Le build (`ng build`), le service worker (PWA) et les tests E2E donnent les mêmes résultats qu'avant. Aucun paquet d'exécution (`dependencies`) n'est modifié, sauf si `npm audit --omit=dev` l'exige.
- **RG5 : audit npm propre.** Après mise à jour, `npm audit --omit=dev` et `npm audit` sont à `found 0 vulnerabilities` dans `frontend/` et dans `e2e/`. Si une alerte ne peut être corrigée (pas de version corrigée disponible), elle est consignée dans `docs/deploiement.md` (section « Contrôle des vulnérabilités ») avec paquet, gravité, raison de l'impossibilité et exposition réelle (outillage de build : non embarqué dans l'image d'exécution) ; aucune alerte `high` ou `critical` ne peut être laissée ouverte sur une dépendance d'exécution.
- **RG6 : dépendances Maven.** Le résultat de `./mvnw org.owasp:dependency-check-maven:check` (ou, sans clé NVD, de `./mvnw versions:display-dependency-updates` et des alertes Dependabot Maven) est consigné dans la section « Contrôle des vulnérabilités » : date, commande, résultat. Toute vulnérabilité de gravité `HIGH` ou `CRITICAL` sur une dépendance d'exécution est corrigée par montée de version (gérée par le parent Spring Boot quand c'est possible) dans cet incrément ; sinon elle est listée avec sa justification dans les points restants. Ne modifie pas `pom.xml` autrement que pour la version du projet et les montées nécessaires.
- **RG7 : images Docker.** La section « Contrôle des vulnérabilités » donne la commande de scan à lancer sur `ghcr.io/.../backyard-ultra-tracker-api:<version>`, `...-web:<version>` et `postgres:17.11-alpine` (outil épinglé par version, jamais `latest`, par exemple `docker run --rm aquasec/trivy:<version> image --severity HIGH,CRITICAL <image>`), les résultats datés de la répétition `-rc.1` (RG11 y renvoie), et la règle : une vulnérabilité `HIGH`/`CRITICAL` corrigeable par changement de version de l'image de base est corrigée avant `0.5.0` (nouvelle `-rc.N`) ; sinon elle est acceptée par écrit avec son exposition. Le scan des images publiées est fait par l'utilisateur lors de la répétition (les images n'existent qu'après le tag).

### Sauvegarde du jour de course (DEP-07)

- **RG8 : script `scripts/sauvegarder.sh`.** Script `sh`/`bash` exécutable, lancé depuis la racine du dépôt sur le VPS, qui produit `sauvegarde-AAAAMMJJ-HHMM.dump` (format personnalisé `pg_dump -Fc`, exécuté dans le conteneur `base`, utilisateur et base lus depuis le conteneur comme dans `deploiement.md`) dans un dossier de destination. Configuration par variables d'environnement, toutes facultatives : `SAUVEGARDE_DOSSIER` (défaut `./sauvegardes`, créé s'il manque, droits `700`), `SAUVEGARDE_CONSERVER` (nombre de fichiers gardés, défaut `14`, entier >= 1), `COMPOSE_FICHIERS` (défaut `-f docker-compose.yml -f docker-compose.prod.yml`). Le script :
  1. écrit d'abord dans un fichier temporaire, jamais dans le fichier final ;
  2. refuse un fichier vide ou illisible : `pg_restore --list` doit réussir sur le dump, sinon le fichier temporaire est supprimé, le message d'erreur est écrit sur la sortie d'erreur et le code de sortie est non nul (aucune sauvegarde valide existante n'est supprimée) ;
  3. applique la rotation uniquement après succès : ne garde que les `SAUVEGARDE_CONSERVER` plus récents fichiers `sauvegarde-*.dump` du dossier, sans toucher aux autres fichiers ;
  4. affiche en dernière ligne, en cas de succès, le chemin du fichier et sa taille, code de sortie 0 ;
  5. n'affiche jamais de mot de passe ni le contenu de `.env`.
  Une valeur invalide de `SAUVEGARDE_CONSERVER` (0, négatif, non numérique) fait échouer le script avant toute écriture, code 2, message citant la variable.
- **RG9 : exemple de planification.** `docs/deploiement.md` (section Sauvegarde) fournit : (a) la consigne du jour de course : une sauvegarde manuelle juste avant le départ de la première Boucle et une juste après l'annonce du Vainqueur, copiées hors du VPS (commande `scp`/`rsync` d'exemple) ; (b) un exemple `crontab` toutes les heures pendant la course (`0 * * * *`) avec sortie journalisée dans un fichier et un exemple quotidien hors course ; (c) la précision que `cron` s'exécute sur l'hôte, que la sauvegarde `pg_dump` ne bloque ni les scans ni le suivi, et qu'une sauvegarde laissée sur le même disque ne protège pas d'une panne du disque. La phrase « La sauvegarde est manuelle (aucune tâche planifiée fournie) » est supprimée.
- **RG10 : restauration testée.** Le script accepte l'option `--verifier-restauration` : après la sauvegarde, il restaure le dump dans une base temporaire `restauration_verif` créée dans le conteneur `base` (jamais dans la base de production), compare le nombre de lignes des tables `course`, `inscription` et `passage` (noms réels de la base, à confirmer par le développeur dans les changelogs Liquibase) avec la base source, supprime la base temporaire dans tous les cas (succès ou échec), et sort en code non nul avec un message explicite si les nombres diffèrent. La restauration réelle (`pg_restore --clean --if-exists --no-owner` sur la base de production, `api` et `web` arrêtés) reste la procédure documentée de `deploiement.md` et est dérouler en répétition `-rc.1` (RG11) ; sa description est complétée par une étape de contrôle (`$PROD ps`, `curl /api/version`, connexion, liste des courses).

### Répétition générale (roadmap 7.4)

- **RG11 : procédure `-rc.1` dans `docs/deploiement.md`.** Nouvelle section « Répétition générale d'une version » qui décrit, dans l'ordre : (1) poser et pousser `v0.5.0-rc.1` sur la tête de la branche de release, suivre le workflow `Release`, vérifier que la Release GitHub est marquée pré-version ; (2) rendre les packages publics si c'est la première publication (renvoi vers « Accès du VPS aux images ») ; (3) sur le VPS, `VERSION=0.5.0-rc.1`, `$PROD pull`, `$PROD up -d`, `curl https://<domaine>/api/version` doit répondre `0.5.0-rc.1`... (voir point ouvert 1 sur l'affichage du suffixe) ; (4) vérifications de sécurité visibles : en-tête `Strict-Transport-Security`, cookies `Secure`, pas d'`/api/test/horloge` ; (5) une sauvegarde avec `--verifier-restauration`, puis restauration réelle sur le VPS ; (6) une mini-course de bout en bout (Course de 2 minutes par Boucle, 2 ou 3 coureurs, un bénévole affecté, un scan hors ligne, un abandon automatique, un Vainqueur, suivi public ouvert sur un second appareil, PWA installée) ; (7) scan de vulnérabilités des images (RG7) ; (8) critères de passage à la `0.5.0` : tout vert, sinon correction et `-rc.2` ; (9) pose du tag `v0.5.0` sur `main` après merge, déploiement par la procédure « Mise à jour », mise à jour des consignes de retour arrière. Un tag publié n'est jamais déplacé.

## 3. Cas limites

- Tag `-rc.1` posé alors que les sources valent `0.5.0` : accepté par la vérification du workflow (suffixe ignoré). Tag `v0.5.0` alors que `CHANGELOG.md` n'a pas de section `[0.5.0]` : le workflow échoue, aucune image publiée.
- `package-lock.json` non régénéré après changement de version (les deux champs racine divergent de `package.json`) : `npm ci` ou la CI échoue ; RG1 impose la cohérence.
- `npm audit` signale une alerte sans correctif : traitée par RG5 (consignée), pas de contournement par `--force` qui monterait Angular d'une version majeure.
- Mise à jour de `vite` qui casse le service worker ou le build PWA : revenir à une version corrigée compatible ou retarder la montée d'`@angular/build` ; le test E2E de l'installation PWA détecte la régression.
- Sauvegarde lancée alors que le conteneur `base` est arrêté ou non sain : échec explicite (code non nul), aucun fichier final créé.
- Sauvegarde lancée pendant une course : sans effet sur les scans (lecture cohérente de `pg_dump`) ; la rotation ne supprime jamais le fichier qui vient d'être créé.
- Disque plein : `pg_dump` échoue, fichier temporaire supprimé, anciennes sauvegardes préservées (RG8, point 2 avant 3).
- Deux sauvegardes dans la même minute : noms distincts (le nom contient les secondes ou un suffixe) pour ne jamais écraser un fichier existant.
- Dossier de destination contenant d'autres fichiers : jamais supprimés par la rotation (motif `sauvegarde-*.dump` seulement).
- `--verifier-restauration` alors que `restauration_verif` existe déjà (exécution précédente interrompue) : elle est supprimée puis recréée.
- Restauration sur un VPS dont le mot de passe de base diffère de celui de la sauvegarde : sans objet (le dump ne contient pas les rôles, `--no-owner`) ; piège du volume existant déjà documenté.
- Sauvegarde du jour de course hors VPS : si le VPS tombe, les `.dump` locaux sont perdus ; seule la copie distante protège (RG9).

## 4. Contrat d'API

Aucun endpoint créé, modifié ni supprimé. Seul `GET /api/version` (public, existant) change de valeur renvoyée : `0.5.0` (200, JSON `{ "version": string }` inchangé, valeur issue du pom ou de l'argument de build `VERSION`). Erreurs et `ProblemDetail` inchangés. Aucun changement de DTO pour le front.

## 5. Écrans

Aucun écran nouveau ni modifié. Le pied de page (existant, version discrète) affiche la version renvoyée par `/api/version` ; la couverture E2E existante des écrans reste inchangée et doit rester verte après la montée de Vite (CA8).

## 6. Critères d'acceptation

| CA | Étant donné / Quand / Alors | Niveau | RG |
|---|---|---|---|
| CA1 | Étant donné le dépôt à la fin de l'incrément, quand on lit `backend/pom.xml`, `frontend/package.json`, `e2e/package.json` et la racine des deux `package-lock.json`, alors la version est `0.5.0` partout et `grep -rn "0\.4\.0"` ne renvoie que `CHANGELOG.md` et les mentions datées de `deploiement.md`. | intégration (contrôle du dépôt par la CI : test d'intégration lisant les fichiers) | RG1 |
| CA2 | Étant donné l'api construite sans argument `VERSION`, quand `GET /api/version` et `GET /actuator/info`, alors la version renvoyée est `0.5.0` (200). | intégration | RG1 |
| CA3 | Étant donné `.env.example`, quand on lit la variable `VERSION`, alors elle vaut `0.5.0` et le commentaire cite `0.5.0` et `0.5.0-rc.1` ; `docs/deploiement.md` cite les mêmes exemples. | intégration (lecture de fichiers) | RG2 |
| CA4 | Étant donné `CHANGELOG.md`, quand on lit la section `[0.5.0]`, alors elle précède la 0.4.0, porte une date `AAAA-MM-JJ`, cite les jalons 5, 6 et 7, les quatre variables `BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE`, `COOKIES_SECURE`, `HACHAGE_CONCURRENCE_MAX_PAR_IP`, `BACKYARD_PROXY_DE_CONFIANCE` et se termine par le lien `[0.5.0]`. | intégration (lecture de fichiers) | RG3 |
| CA5 | Étant donné la logique de vérification du workflow `Release` (tag = versions des sources = section du CHANGELOG), quand on la rejoue en local avec les tags `v0.5.0-rc.1` et `v0.5.0`, alors elle réussit pour les deux ; avec `v0.5.1` elle échoue. | intégration (script de vérification extrait ou rejoué) | RG1, RG3 |
| CA6 | Étant donné `frontend/` et `e2e/` après mise à jour, quand `npm audit --omit=dev` puis `npm audit` sont lancés, alors chacun renvoie `found 0 vulnerabilities`, ou chaque alerte restante est consignée dans la section « Contrôle des vulnérabilités ». | intégration (commande lancée par le testeur) | RG4, RG5 |
| CA7 | Étant donné `frontend/package-lock.json`, quand on lit les versions résolues de `vite` et `@angular/build`, alors elles sont supérieures ou égales aux versions corrigées des alertes Dependabot ouvertes avant l'incrément, et `@angular/core` reste dans la même version majeure qu'avant. | intégration (lecture de fichiers) | RG4 |
| CA8 | Étant donné la stack lancée par `docker compose up -d --build` avec le nouveau Vite, quand on rejoue la suite E2E complète (dont l'installation PWA et le service worker), alors tous les scénarios existants restent verts et l'API répond `200` sur `/api/version` avec `0.5.0`. | E2E | RG1, RG4 |
| CA9 | Étant donné `docs/deploiement.md`, quand on lit la section « Contrôle des vulnérabilités », alors elle contient la date, la commande et le résultat pour npm, Maven et le scan d'images (commande épinglée, règles de décision), et ne contient aucune vulnérabilité `HIGH`/`CRITICAL` d'une dépendance d'exécution sans justification. | intégration (lecture de fichiers) | RG5, RG6, RG7 |
| CA10 | Étant donné la stack lancée avec des données de démo et `SAUVEGARDE_DOSSIER` dans un dossier vide, quand on lance `scripts/sauvegarder.sh`, alors le code de sortie est 0, un fichier `sauvegarde-*.dump` non vide existe, `pg_restore --list` sur ce fichier réussit et la dernière ligne affichée donne son chemin et sa taille. | intégration (le testeur exécute le script sur une stack réelle) | RG8 |
| CA11 | Étant donné un dossier contenant 3 fichiers `sauvegarde-*.dump` plus un fichier `notes.txt` et `SAUVEGARDE_CONSERVER=3`, quand le script réussit, alors il reste exactement 3 fichiers `sauvegarde-*.dump` dont le plus récent vient d'être créé, et `notes.txt` est intact. | intégration | RG8 |
| CA12 | Étant donné le conteneur `base` arrêté, quand on lance le script, alors le code de sortie est non nul, un message est écrit sur la sortie d'erreur, aucun fichier `sauvegarde-*.dump` n'est créé ni supprimé. | intégration | RG8 |
| CA13 | Étant donné `SAUVEGARDE_CONSERVER=0` (puis `abc`), quand on lance le script, alors le code de sortie est 2, le message cite `SAUVEGARDE_CONSERVER` et aucun fichier n'est écrit. | intégration | RG8 |
| CA14 | Étant donné une base contenant 1 course, 5 inscriptions et 12 passages, quand on lance `scripts/sauvegarder.sh --verifier-restauration`, alors le script affiche les trois comptages identiques (1, 5, 12), supprime la base `restauration_verif`, laisse la base de production intacte et sort en code 0. | intégration | RG10 |
| CA15 | Étant donné une base `restauration_verif` déjà existante, quand on lance `--verifier-restauration`, alors elle est recréée et la vérification réussit ; si un comptage diffère (dump altéré), le code de sortie est non nul et la base temporaire est quand même supprimée. | intégration | RG10 |
| CA16 | Étant donné `docs/deploiement.md`, quand on lit les sections Sauvegarde, Restauration et « Répétition générale d'une version », alors on y trouve : la consigne avant départ / après Vainqueur, l'exemple `crontab` horaire et quotidien, la copie hors VPS, les neuf étapes de la répétition `-rc.1`, et la phrase « aucune tâche planifiée fournie » n'existe plus. | intégration (lecture de fichiers) | RG9, RG11 |
| CA17 | Étant donné une sauvegarde faite par le script sur une stack de test locale (`.env.prod`, voir « Tester la production en local »), quand on la restaure avec la procédure de `deploiement.md` après avoir supprimé une Course, alors la Course supprimée réapparaît, `api` redevient `healthy` et l'écran de suivi public de la Course affiche ses coureurs. | E2E (parcours de restauration sur la stack complète) | RG10 |

Couverture : RG1 (CA1, CA2, CA5, CA8), RG2 (CA3), RG3 (CA4, CA5), RG4 (CA6, CA7, CA8), RG5 (CA6, CA9), RG6 (CA9), RG7 (CA9), RG8 (CA10 à CA13), RG9 (CA16), RG10 (CA14, CA15, CA17), RG11 (CA16). Aucun écran nouveau : l'exigence « chaque écran en E2E » est tenue par CA8 (suite complète) et CA17.

## 7. Tester à la main

Prérequis : Docker, accès au dépôt GitHub (droit de pousser des tags) et, pour la répétition, un VPS déployé avec la 0.4.0 ou vierge.

**A. En local, sans tag**
1. `git switch increment/7.4-release-0.5.0 && docker compose up -d --build`, attendre `docker compose ps` : `base`, `api`, `web` `healthy`.
2. `curl http://localhost/api/version` -> `0.5.0`. Pied de page de http://localhost -> « 0.5.0 ».
3. Se connecter avec l'admin master (`.env`), lancer `./scripts/donnees-demo.sh` : une Course avec inscrits apparaît.
4. `cd frontend && npm audit --omit=dev && npm audit` -> `found 0 vulnerabilities` (idem dans `e2e/`).
5. `./scripts/sauvegarder.sh` (avec `COMPOSE_FICHIERS="-f docker-compose.yml"` en dev) -> un `.dump` non vide dans `./sauvegardes`, dernière ligne = chemin et taille.
6. `./scripts/sauvegarder.sh --verifier-restauration` -> trois comptages identiques, base temporaire supprimée.
7. `SAUVEGARDE_CONSERVER=2` : relancer 3 fois -> il ne reste que 2 fichiers `.dump`.
8. Arrêter `base` (`docker compose stop base`), relancer le script -> erreur claire, code de sortie non nul ; `docker compose start base`.
9. Ouvrir `CHANGELOG.md` : section 0.5.0 au-dessus de la 0.4.0, quatre variables listées. Lire la section « Contrôle des vulnérabilités » de `docs/deploiement.md`.

**B. Répétition générale `-rc.1` (manuelle, par l'utilisateur ; l'agent ne pose aucun tag)**
1. Ouvrir la pull request de la branche, attendre la CI verte.
2. Sur la tête de la branche : `git tag v0.5.0-rc.1 && git push origin v0.5.0-rc.1`. Suivre `gh run watch` : le workflow `Release` réussit et crée une pré-version.
3. Sur le VPS, suivre la section « Répétition générale d'une version » de `deploiement.md` : `VERSION=0.5.0-rc.1` dans `.env`, `$PROD pull && $PROD up -d`, `curl https://<domaine>/api/version`, en-tête `Strict-Transport-Security` présent (`curl -sI https://<domaine>`), cookies `Secure` dans les outils du navigateur.
4. Poser un `crontab` d'exemple, vérifier qu'un `.dump` apparaît, le copier hors du VPS.
5. Restaurer ce dump (procédure Restauration), vérifier la connexion et la liste des Courses.
6. Dérouler une mini-course (2 min par Boucle, 3 coureurs, un scan hors ligne, un abandon automatique, un Vainqueur) avec le suivi public sur un second appareil.
7. Scanner les images (commande de la section CVE) et noter le résultat.
8. Si tout est bon : merger sur `main`, puis `git switch main && git pull && git tag v0.5.0 && git push origin v0.5.0`. Sinon corriger et poser `v0.5.0-rc.2` (jamais déplacer un tag publié).

## 8. Points ouverts

1. **Suffixe `-rc.N` affiché par `/api/version`.** Les sources valent `0.5.0` pour un tag `-rc.1` ; les images reçoivent l'argument de build `VERSION` (supposé `0.5.0-rc.1` par la CI). À confirmer en lisant `.github/workflows/release.yml` ; si l'argument est `0.5.0`, l'étape (3) de RG11 attend `0.5.0` et le tag d'image distingue seul le `-rc.1`. Tranché au plus simple : le développeur aligne la procédure sur le comportement réel du workflow, sans le modifier.
2. **Contrôle Maven automatisé ou manuel.** Tranché au plus simple : manuel et documenté (RG6), pas de plugin dans le build. À rouvrir si l'utilisateur veut un échec de CI sur CVE.
3. **Copie hors VPS.** Tranché au plus simple : consigne et commande `scp`/`rsync` d'exemple, pas d'outil ni de stockage distant imposés. Où l'utilisateur veut-il stocker les sauvegardes ?
4. **Noms réels des tables comptées par `--verifier-restauration`.** À confirmer par le développeur dans les changelogs Liquibase ; si différents de `course`, `inscription`, `passage`, la spec est mise à jour par le `fonctionnel`.
5. **Date de la section 0.5.0.** Posée à la date de rédaction ; à corriger au jour réel du tag si l'utilisateur le souhaite (modification du CHANGELOG avant le tag de la `0.5.0`, jamais après).
6. **Niveau des CA de lecture de fichiers et de script.** Classés « intégration » faute de niveau dédié ; le testeur-it peut les réaliser en test JUnit lisant le dépôt ou en script de CI. Le script de sauvegarde n'étant pas du code Java, il est hors du calcul de couverture JaCoCo (85 %).
