# Roadmap de développement

Découpage en mini-incréments. Chacun est livré, relu et validé avant de passer au suivant.

## Règles du découpage

- **Un incrément = un comportement visible.** Une règle métier, une action ou un écran, pas plus.
- **Tranche verticale.** Chaque incrément touche ce qu'il faut de base, de back et de front pour être utilisable dans l'application. Pas d'incrément « back seul » invisible à l'écran, sauf pour le socle.
- **Relisible en 15 à 20 minutes.** Cible : environ 400 lignes de code de production modifiées, hors tests. Si la spec dépasse, le `fonctionnel` propose un découpage avant tout code.
- **Testable en déploiement local.** Tout se vérifie avec `docker compose up -d --build` sur http://localhost, en suivant la section « Tester à la main » de la spec.
- **Données de démo.** À partir du jalon 2, `scripts/donnees-demo.sh` crée par l'API les comptes, courses et inscriptions nécessaires au test manuel. Il est enrichi à chaque incrément qui en a besoin, pour ne jamais avoir à tout ressaisir à la main.
- **Un incrément = une branche = une pull request**, nommée `increment/X.Y-slug` (par exemple `increment/1.2-connexion`). Spec dans `docs/specs/increment-X.Y.md`.
- **Pas d'incrément suivant sans ta validation.**

## Vue d'ensemble

| Jalon | Contenu | Incréments | À la fin du jalon, tu peux… |
|---|---|---|---|
| 0 | Socle | 0.1 → 0.4 | lancer la stack et voir la page d'accueil |
| 1 | Comptes et sécurité | 1.1 → 1.6 | créer des comptes, te connecter avec chaque rôle |
| 2 | Courses | 2.1 → 2.5 | déclarer une course complète avec logo et bénévoles |
| 3 | Inscriptions | 3.1 → 3.6 | inscrire des coureurs et voir leurs dossards et QR |
| 4 | Course en direct | 4.1 → 4.12 | faire tourner une course de bout en bout |
| 5 | Scan hors ligne | 5.1 → 5.2 | scanner sans réseau sans perdre de passage |
| 6 | Suivi public | 6.1 → 6.4 | projeter le suivi d'une course |
| 7 | Préparation de la release | 7.1 → 7.4 | lancer un test grandeur nature avec une version publiée |
| 8 | Durcissement issu du pentest | 8.1 → 8.5 | désactiver un compte privilégié, voir des sessions, connexions et créations de compte plafonnées |
| 9 | Gestion des comptes, notification d'inscription et navigation d'administration | 9.1 → 9.9 | naviguer dans l'administration par un menu, supprimer des bénévoles et des admins, promouvoir un coureur, recevoir un mail à chaque inscription |

---

## Jalon 0 : Socle

### 0.1 Squelette back et base
- **Livré** : projet Spring Boot 4.1.1 / Java 25 dans `backend/`, `Dockerfile` multi-étapes, `docker-compose.yml` avec `base` et `api`, changelog Liquibase initial vide, `/actuator/health`.
- **Tu testes** : `docker compose up -d --build`, les deux services passent `healthy` (`docker compose ps`), `curl http://localhost:8080/actuator/health` répond `UP`.

### 0.2 Squelette front
- **Livré** : application Angular dans `frontend/`, `Dockerfile` (build Node + Caddy), `Caddyfile`, service `web` dans le compose, page d'accueil qui appelle l'API et affiche son état.
- **Tu testes** : http://localhost affiche « Backyard Ultra Tracker » et « API : disponible ». Arrêter `api` fait passer l'indicateur à « indisponible ».

### 0.3 Outillage qualité
- **Livré** : JaCoCo avec seuil bloquant à 85 %, règles ArchUnit des quatre couches, un test d'intégration Testcontainers de démonstration, projet Playwright dans `e2e/` avec un test de la page d'accueil sur la stack compose.
- **Tu testes** : `./mvnw verify` dans `backend/` passe et produit le rapport de couverture ; `npx playwright test` dans `e2e/` passe. Ajouter volontairement un import Spring dans le domaine fait échouer ArchUnit.

### 0.4 Configuration de production
- **Livré** : `docker-compose.prod.yml` (seul `web` exposé, `restart: unless-stopped`, HTTPS Caddy), `.env.example`, `docs/deploiement.md` (premier déploiement, mise à jour, sauvegarde et restauration).
- **Tu testes** : lancer la stack de production en local avec `DOMAINE=localhost` : https://localhost répond (certificat local Caddy), le port 8080 n'est plus accessible.

---

## Jalon 1 : Comptes et sécurité

### 1.1 Créer un compte coureur
- **Livré** : écran de création de compte (pseudo + mot de passe + confirmation), API, table des comptes, mot de passe haché en Argon2id.
- **Tu testes** : créer un compte → message de confirmation. Pseudo déjà pris → refusé. Mot de passe de moins de 12 caractères → refusé. Le mot de passe n'apparaît en clair ni en base ni dans les logs.

### 1.2 Se connecter et se déconnecter
- **Livré** : écran de connexion, session serveur, cookie sécurisé, CSRF, pseudo affiché dans l'en-tête, déconnexion, lien vers la création de compte.
- **Tu testes** : connexion avec le compte de 1.1, rechargement de la page → toujours connecté, déconnexion → retour à l'écran de connexion. Mauvais mot de passe → message générique.

### 1.3 Limiter les tentatives de connexion
- **Livré** : blocage temporaire après plusieurs échecs, message identique que le pseudo existe ou non.
- **Tu testes** : enchaîner les échecs sur un pseudo → blocage ; même message avec un pseudo inexistant ; reconnexion possible après le délai.

### 1.4 Admin master et espace d'administration
- **Livré** : création de l'admin master au démarrage depuis les variables d'environnement, écran d'administration vide réservé aux rôles admin, redirection selon le rôle après connexion.
- **Tu testes** : connexion avec l'admin master du `.env` → espace d'administration. Un coureur qui tape l'URL d'administration → accès refusé. Redémarrer la stack ne crée pas de second admin master.

### 1.5 Créer des comptes admin
- **Livré** : l'admin master crée et liste les admins.
- **Tu testes** : créer un admin, s'y connecter. Un admin (non master) ne voit pas cette fonction et l'API lui répond 403.

### 1.6 Créer des comptes bénévoles et changer son mot de passe
- **Livré** : les admins (et l'admin master) créent et listent les bénévoles ; écran « Mon compte » avec changement de mot de passe pour tous les rôles.
- **Tu testes** : créer un bénévole, s'y connecter (écran d'accueil bénévole vide). Changer son mot de passe, se reconnecter avec le nouveau.

---

## Jalon 2 : Courses

### 2.1 Déclarer une course
- **Livré** : formulaire admin (nom, date, distance d'une boucle en m, durée en min, dénivelé en m, nombre max de participants, nombre max de boucles), liste des courses avec statut `EN_PREPARATION`. Démarrage de `scripts/donnees-demo.sh`.
- **Tu testes** : créer une course, la retrouver dans la liste. Valeurs nulles ou négatives refusées.

### 2.2 Modifier une course
- **Livré** : modification de tous les champs tant que la course est `EN_PREPARATION`.
- **Tu testes** : modifier la durée de boucle, vérifier dans la liste.

### 2.3 Logo de course
- **Livré** : envoi d'un logo (formats et taille limités), stockage sur le volume `logos`, affichage dans la liste et la fiche de la course.
- **Tu testes** : envoyer un PNG → affiché. Fichier trop lourd ou non image → refusé. `docker compose down` puis `up` → le logo est toujours là.

### 2.4 Affecter des bénévoles
- **Livré** : sur la fiche d'une course, choix des bénévoles parmi les comptes `BENEVOLE` ; le bénévole voit la liste de ses courses à son accueil.
- **Tu testes** : affecter un bénévole, se connecter avec → la course apparaît. Le retirer → elle disparaît.

### 2.5 Supprimer une course
- **Livré** : suppression d'une course `EN_PREPARATION` par l'admin master uniquement, avec confirmation ; son logo et ses affectations de bénévoles sont supprimés.
- **Tu testes** : en admin master, supprimer une course → elle disparaît de la liste et de l'accueil des bénévoles. En admin simple, le bouton n'existe pas et l'API répond 403. Une course démarrée ne peut pas être supprimée.

---

## Jalon 3 : Inscriptions

### 3.1 S'inscrire à une course
- **Livré** : liste des courses ouvertes côté coureur, inscription, dossard attribué automatiquement.
- **Tu testes** : s'inscrire avec un coureur → dossard affiché. Deux coureurs → deux dossards différents.

### 3.2 Inscriptions refusées
- **Livré** : refus si la course est complète, si le coureur est déjà inscrit, si la course n'est plus `EN_PREPARATION`.
- **Tu testes** : course à 2 participants max, 3ᵉ inscription → message « course complète ». Double inscription → refusée.

### 3.3 Mes inscriptions et QR code
- **Livré** : écran « Mes inscriptions » avec course, dossard et QR code (jeton aléatoire, distinct du dossard).
- **Tu testes** : le QR s'affiche et se lit avec un téléphone ; il contient un jeton, pas le dossard.

### 3.4 Se désinscrire
- **Livré** : désinscription avec confirmation tant que la course est `EN_PREPARATION`.
- **Tu testes** : se désinscrire → l'inscription disparaît et la place se libère (une course complète redevient ouverte).

### 3.5 Inscrits d'une course (admin)
- **Livré** : sur la fiche admin d'une course, liste des inscrits (pseudo, dossard) et nombre de places restantes.
- **Tu testes** : avec le script de démo, la liste correspond aux inscriptions faites.

### 3.6 Supprimer son compte coureur
- **Livré** : suppression du compte avec confirmation et saisie du mot de passe. Les inscriptions aux courses `EN_PREPARATION` sont annulées. Le compte est anonymisé : pseudo remplacé par « Coureur anonyme », mot de passe effacé, connexion impossible ; ses inscriptions aux courses `EN_COURS` ou `TERMINEE` et leurs passages sont conservés pour ne pas fausser les résultats.
- **Tu testes** : supprimer un compte inscrit à une course en préparation → plus de connexion possible, il disparaît des inscrits. Avec le script de démo, supprimer un compte ayant couru une course terminée → les résultats affichent « Coureur anonyme » avec son dossard et ses boucles. Le pseudo libéré peut être repris par un nouveau compte.

---

## Jalon 4 : Course en direct

### 4.1 Démarrer une course
- **Livré** : bouton « Démarrer » avec confirmation, statut `EN_COURS`, heure de départ enregistrée, paramètres de boucle et nombre max de boucles figés, inscriptions et désinscriptions fermées.
- **Tu testes** : démarrer → statut changé, formulaire de modification verrouillé, inscription impossible côté coureur.

### 4.2 Écran de pilotage
- **Livré** : écran admin de la course en cours : boucle courante, compte à rebours jusqu'au prochain départ, durée écoulée, liste des inscrits `EN_COURSE`. Tout est calculé, rien n'est stocké.
- **Tu testes** : course à boucles de 1 min → la boucle courante change à chaque minute, le compte à rebours repart.

### 4.3 Horloge pilotable en test
- **Livré** : dans un profil Spring `e2e` uniquement, une horloge pilotable et un endpoint `/api/test/horloge` pour avancer le temps ; le planificateur d'abandon s'appuie sur cette horloge. Hors profil `e2e`, l'endpoint n'existe pas (vérifié par un test d'intégration) et l'horloge est l'horloge système. Un fichier `docker-compose.e2e.yml` active le profil pour les tests Playwright.
- **Tu testes** : lancer la stack avec le profil `e2e`, démarrer une course de 60 min, avancer l'horloge de 60 min → l'écran de pilotage passe à la boucle 2. Sur la stack normale, `/api/test/horloge` répond 404.

### 4.4 Scanner un passage (saisie du jeton)
- **Livré** : écran de scan bénévole avec choix de la course, saisie du jeton au clavier, enregistrement du passage pour la boucle courante, retour visuel vert avec dossard et pseudo.
- **Tu testes** : copier le jeton d'un coureur, le saisir → passage accepté, visible côté pilotage.

### 4.5 Scanner à la caméra
- **Livré** : lecture du QR code à la caméra sur l'écran de scan.
- **Tu testes** : sur ton ordinateur avec webcam (http://localhost est accepté par le navigateur), présenter le QR affiché sur ton téléphone. Pour un test sur téléphone, passer par la configuration HTTPS de 0.4.

### 4.6 Scans refusés
- **Livré** : refus avec retour visuel rouge et motif : passage déjà enregistré pour cette boucle, coureur en abandon, course non démarrée, bénévole non affecté, jeton inconnu.
- **Tu testes** : scanner deux fois le même coureur sur la même boucle → « déjà passé ». Scanner pour une course où le bénévole n'est pas affecté → refusé.

### 4.7 Passages sur l'écran de pilotage
- **Livré** : pour la boucle courante, qui a bouclé et qui est encore dehors ; nombre de boucles terminées par coureur.
- **Tu testes** : scanner deux coureurs sur trois → le troisième apparaît « en boucle ».

### 4.8 Abandon automatique
- **Livré** : au départ de chaque boucle, les inscriptions sans passage sur la boucle précédente passent en `ABANDON` (`HORS_DELAI`). Planificateur côté serveur, indépendant par course.
- **Tu testes** : course à 1 min, scanner un coureur sur deux → au départ suivant, l'autre passe en abandon avec la boucle d'abandon.

### 4.9 Vainqueur unique
- **Livré** : un seul coureur termine la boucle → `VAINQUEUR`, course `TERMINEE`, affichage sur le pilotage.
- **Tu testes** : trois coureurs, ne scanner qu'un seul → au départ suivant il est vainqueur, la course est terminée.

### 4.10 Vainqueurs partagés et nombre max de boucles
- **Livré** : personne ne termine la boucle → tous les coureurs encore en course au départ de cette boucle sont vainqueurs. Dernière boucle autorisée → tous ceux qui l'ont terminée sont vainqueurs.
- **Tu testes** : ne scanner personne → vainqueurs partagés. Course à 2 boucles max, scanner tout le monde deux fois → vainqueurs partagés à la fin de la boucle 2.

### 4.11 Abandon manuel
- **Livré** : depuis le pilotage, mettre un coureur en abandon avec un motif (`VOLONTAIRE`, `MANUEL`, `AUTRE`) et une confirmation.
- **Tu testes** : abandonner un coureur → il passe dans les abandons, son QR est ensuite refusé au scan.

### 4.12 Réintégration
- **Livré** : depuis le pilotage, réintégrer un coureur en abandon : passages `CORRECTION` recréés pour les boucles manquées, statut `EN_COURSE`, badge « corrigé ».
- **Tu testes** : laisser un coureur passer en abandon automatique, le réintégrer → il est de nouveau en course avec ses boucles comptées et le badge visible.

---

## Jalon 5 : Scan hors ligne

### 5.1 File de scan hors ligne
- **Livré** : chaque scan est enregistré localement puis envoyé, avec nouvelles tentatives ; compteur de scans en attente ; API idempotente sur (inscription, boucle).
- **Tu testes** : dans les outils de développement du navigateur, passer hors ligne, scanner 3 coureurs → « 3 en attente ». Repasser en ligne → envoyés, visibles au pilotage, sans doublon.

### 5.2 Application installable
- **Livré** : manifeste PWA et service worker : l'écran de scan s'installe et s'ouvre sans réseau. Icône de l'application : visuel source SVG issu de la charte (documenté dans `docs/design.md`, remplace le favicon Angular), icônes PNG 192 et 512 px (`any`), 512 px `maskable` (motif dans la zone de sécurité), `apple-touch-icon` 180 px sans transparence pour iOS ; nom, nom court, couleurs de thème et de fond issues des jetons de la charte. Manifeste servi en `application/manifest+json`, compatible avec la CSP du `Caddyfile`.
- **Tu testes** : installer l'application depuis le navigateur, couper le réseau, la rouvrir → l'écran de scan s'affiche. Sur un téléphone Android et un iPhone (stack HTTPS de 0.4), l'icône de l'application apparaît sur l'écran d'accueil et au lancement (désinstaller toute ancienne installation et vider les données du site avant). Chaque icône déclarée répond en `image/png` (une icône absente renverrait sinon `index.html` sans erreur visible) ; l'onglet Application de Chrome n'affiche aucun avertissement sur le manifeste.

---

## Jalon 6 : Suivi public

### 6.1 Écran de suivi : l'essentiel
- **Livré** : page publique sans connexion : nom et logo de la course, boucle en cours, compte à rebours, durée écoulée, rafraîchissement automatique toutes les 2 à 3 secondes.
- **Tu testes** : ouvrir le suivi dans une fenêtre privée pendant qu'une course tourne → les informations avancent seules.

### 6.2 Coureurs en course, abandons et vainqueurs
- **Livré** : liste des coureurs en course, liste des abandons avec boucle et motif, mise en avant du ou des vainqueurs.
- **Tu testes** : provoquer un abandon et une fin de course → le suivi se met à jour sans recharger.

### 6.3 Statistiques et mode projection
- **Livré** : nombre de boucles, distance et dénivelé cumulés, allure (hors passages `CORRECTION`) ; affichage plein écran lisible à distance.
- **Tu testes** : les cumuls correspondent aux paramètres de la course ; passer en plein écran sur un grand écran ou un projecteur.

### 6.4 Accueil public
- **Livré** : la page d'accueil liste les courses à venir, en cours et terminées, avec un lien vers leur suivi.
- **Tu testes** : chaque course de démo apparaît dans la bonne rubrique et mène à son suivi.

---

## Jalon 7 : Préparation de la release

Issu de `docs/audits/audit-2026-10-21-pre-release.md`. Objectif : corriger ce qui fausserait ou bloquerait un test grandeur nature. Le 7.1 est bloquant et dépend d'une décision à trancher (voir « Décisions en attente »). Le jalon A du 2026-10-16 (A.1 à A.3) ayant disparu de cette roadmap au commit `ddd0383`, ses points encore ouverts sont repris ici (7.2 et 7.3).

### 7.1 Scan tardif et fin de course (MET-02, bloquant)
- **Livré** : un scan fait dans les temps mais reçu après le départ de la boucle suivante ne fausse plus la fin de course, grâce à un délai de grâce dans le domaine avant de trancher la boucle (décision prise). L'aide « demandez une réintégration » n'est plus affichée quand la réintégration est impossible. Procédure terrain ajoutée à `docs/deploiement.md`.
- **Tu testes** : avec l'horloge de la démo, scanner un coureur juste après le départ de la boucle suivante → le résultat final est correct (ou corrigeable) ; le message affiché au bénévole est exact.

### 7.2 Suivi public sous charge (PER-04)
- **Livré** : micro-cache de 1 à 2 s de la réponse de suivi par course ; limite de débit par IP sur les routes publiques `/api/courses/*` ; décalage aléatoire de la relance au zéro du compte à rebours et espacement progressif sur erreur 5xx côté front ; planificateur d'abandon allégé (verrou et relectures seulement à l'échéance d'une boucle) ; fin des requêtes N+1 des listes de courses.
- **Tu testes** : une course de démo avec une trentaine de coureurs et plusieurs dizaines d'écrans de suivi ouverts (script de charge fourni) : le suivi reste fluide et les scans ne ralentissent pas au départ d'une boucle.

### 7.3 Transport, erreurs et connexion (SEC-04, SEC-06, ARC-03, ARC-07)
- **Livré** : en-tête HSTS dans le `Caddyfile`, cookie `Secure` indépendant de `X-Forwarded-Proto` ; le plafond global de hachage ne permet plus à un anonyme de refuser toutes les connexions légitimes ; la borne de 24 h de l'âge d'un scan passe dans le domaine (supprimée du DTO et du front) ; le gestionnaire d'erreurs couvre tous les contrôleurs, y compris les publics.
- **Tu testes** : en-têtes de réponse en HTTPS ; des tentatives de connexion anonymes en rafale ne bloquent pas la connexion de l'admin master ; un scan trop ancien est refusé avec le même message qu'avant.

### 7.4 Publication de la version 0.5.0 (DEP-03, DEP-07, CVE)
- **Livré** : branche de release avec versions du pom et des `package.json`, section 0.5.0 du `CHANGELOG.md` et `VERSION` d'exemple à jour ; mise à jour de Vite et d'`@angular/build` pour les alertes Dependabot (outillage de build uniquement) ; sauvegarde `pg_dump` planifiée documentée (et utilisable) pour le jour de course ; tag `0.5.0-rc.1` pour une répétition générale sur le VPS, puis `0.5.0`.
- **Tu testes** : déployer le tag `-rc.1` sur le VPS en suivant `docs/deploiement.md`, y restaurer une sauvegarde, puis dérouler une mini-course de bout en bout.

## Jalon 8 : Durcissement issu du pentest (audit-2026-10-26-pentest)

### 8.1 Désactivation et réactivation des comptes ADMIN et BENEVOLE (PEN-01, SEC-13)
- **Livré** : état `actif` sur le Compte (nouveau changeset) ; désactivation et réactivation d'un admin (admin master) ou d'un bénévole (admin, admin master) ; refus pour l'admin master, soi-même et le dernier admin actif ; bénévole désactivé désaffecté des courses `EN_PREPARATION` (passages conservés) ; sessions du compte fermées ; compte désactivé non connectable (réponse identique à des identifiants invalides) ; boutons dans l'écran de gestion des comptes.
- **Tu testes** : désactiver un bénévole puis tenter de se connecter avec, le réactiver ; essayer de désactiver l'admin master, ton propre compte, le dernier admin.

### 8.2 Durée et inactivité de session des rôles privilégiés (PEN-05, SEC-16)
- **Livré** : délai d'inactivité de session par rôle, posé à la connexion (admin et admin master 2 h, coureur et bénévole 12 h), règle dans le domaine, réglable par `SESSION_INACTIVITE_ADMIN_SECONDES` et `SESSION_INACTIVITE_COUREUR_BENEVOLE_SECONDES` et validé au démarrage ; message « session expirée » sur la connexion après une navigation dans l'application.
- **Tu testes** : réduire le délai admin à 60 s, rester inactif puis cliquer dans l'application (message de session expirée) ; un bénévole ou un coureur reste connecté.
### 8.3 Exposition de /api/version (PEN-03, SEC-17)
- **Livré** : `/api/version` ne renvoie plus que la version applicative déployée, `{"app":{"version":"…"}}` ; plus de groupe, d'artefact, de nom ni de date de build.
- **Tu testes** : `curl /api/version` ne montre que `app.version` ; `/api/actuator/info` répond 401.
### 8.4 Plafond d'échecs de connexion par IP (PEN-02, SEC-14)
- **Livré** : compteur d'échecs de connexion par adresse en fenêtre glissante (20 échecs par 60 s par défaut) en plus du blocage par pseudo ; même 429 `TENTATIVES_EXCESSIVES` + `Retry-After`, contrôle avant tout hachage ; variables `CONNEXION_ECHECS_PAR_IP_MAX` et `CONNEXION_ECHECS_PAR_IP_FENETRE_SECONDES`.
- **Tu testes** : 21 tentatives de connexion échouées de suite depuis la même adresse (la 21e est refusée), puis la connexion normale après une minute.
### 8.5 Création de comptes coureur limitée par IP (PEN-04, SEC-15)
- **Livré** : plafond de créations de compte par adresse (10 par 60 s par défaut) sur `POST /api/comptes`, 429 `TROP_DE_REQUETES`, contrôle avant tout hachage ; message dédié sur l'écran de création ; variables `CREATION_COMPTE_PAR_IP_MAX` et `CREATION_COMPTE_PAR_IP_FENETRE_SECONDES` ; scripts de démo qui réessaient sur 429.
- **Tu testes** : créer 11 comptes de suite (le 11e est refusé avec un message), puis réessayer après une minute.

---

## Jalon 9 : Gestion des comptes, notification d'inscription et navigation d'administration

Cinq fonctionnalités demandées après la livraison du jalon 8. Elles ne rejoignent pas un jalon existant : le jalon 1 est livré et validé (un « 1.7 » fausserait son « À la fin du jalon, tu peux… ») et les jalons 7 et 8 sont clos par l'audit qui les a motivés. Les numéros existants ne changent pas.

Le **9.3 revient sur une exclusion du 8.1**, qui avait écarté la suppression d'un compte `ADMIN` ou `BENEVOLE` au profit de la désactivation réversible. La désactivation reste en place et se combine avec la suppression (voir « Décisions en attente », question 1).

Ordre : le menu et les formulaires à la demande (9.1, 9.2) réorganisent des écrans existants et accueillent les actions suivantes ; les suppressions (9.3, 9.4) précèdent le changement de rôle (9.5 à 9.7), qui réutilise leur invalidation de session ; le mail (9.8, 9.9) est indépendant des comptes et vient en dernier (9.8 modifie le formulaire de course une fois rendu « à la demande » par le 9.2).

### 9.1 Menu d'administration
- **Dépend de** : 1.4, 1.5, 1.6, 2.1 (écrans existants).
- **Livré** : l'espace d'administration a un menu de navigation (Courses, Admins, Bénévoles) dont les entrées dépendent du rôle : l'admin master voit les trois, un admin ne voit pas Admins. L'entrée de la page courante est mise en évidence ; le menu reste utilisable au clavier et sur téléphone. Front seul : aucun changement d'API, de route ni de garde (le menu ne remplace pas le contrôle des rôles côté serveur et les gardes de routes).
- **Tu testes** : connecté en admin master, trois entrées et navigation entre les trois pages ; connecté en admin, deux entrées, et l'URL `/administration/admins` saisie à la main reste refusée. Le menu est utilisable sur un écran de téléphone.
- **Données de démo** : rien.

### 9.2 Formulaires de création à la demande
- **Dépend de** : 9.1.
- **Livré** : sur les pages Courses, Admins et Bénévoles, la liste reste visible et le formulaire de création est masqué. Un bouton (« Nouvelle course », « Nouvel admin », « Nouveau bénévole ») l'ouvre ; il se referme après une création réussie (la nouvelle ligne apparaît dans la liste) ou sur « Annuler » (champs et erreurs effacés). Une saisie refusée laisse le formulaire ouvert avec son message. Front seul, aucun changement d'API. Les scénarios E2E existants qui remplissent ces formulaires ouvrent d'abord le formulaire : leurs vérifications ne sont ni supprimées ni affaiblies.
- **Tu testes** : sur chaque page, le formulaire n'est pas affiché à l'arrivée ; ouvrir, annuler, rouvrir (champs vides) ; créer un élément → formulaire refermé et élément dans la liste ; saisie invalide → formulaire toujours ouvert avec l'erreur.
- **Données de démo** : rien.

### 9.3 Supprimer un bénévole
- **Dépend de** : 1.6, 2.4, 8.1.
- **Livré** : bouton « Supprimer » avec confirmation sur la liste des bénévoles (admins et admin master). Le compte est supprimé et son pseudo libéré, ses affectations aux courses sont retirées, ses sessions actives sont fermées. Refus 409 s'il est affecté à une course `EN_COURS`, avec message explicite. Les passages qu'il a scannés restent intacts (ils ne le référencent pas). Un bénévole désactivé (8.1) peut être supprimé. Le port et l'adaptateur de désaffectation du 8.1 sont réutilisés.
- **Tu testes** : supprimer un bénévole sans affectation → il disparaît, sa connexion échoue et le pseudo est réutilisable pour un nouveau bénévole. Supprimer un bénévole affecté à une course en préparation → l'affectation disparaît de la fiche de la course. Supprimer un bénévole affecté à une course démarrée → refus avec message.
- **Données de démo** : à ajouter plus tard à `scripts/donnees-demo.sh` : un bénévole jetable sans affectation (les bénévoles Léo et Marc servent aux autres scénarios). Léo, affecté à une course en cours, illustre le refus.

### 9.4 Supprimer un admin
- **Dépend de** : 1.5, 8.1, 9.3.
- **Livré** : bouton « Supprimer » avec confirmation sur la liste des admins, pour l'admin master uniquement (403 pour un admin). L'admin master n'est jamais supprimable (409). Le compte supprimé ne peut plus se connecter, ses sessions sont fermées, son pseudo est libéré. Refus éventuel sur le dernier admin actif : voir « Décisions en attente », question 2.
- **Tu testes** : en admin master, supprimer un admin → il disparaît, sa connexion échoue, une session ouverte avec ce compte est refusée au clic suivant. Tenter de supprimer l'admin master → impossible. En admin simple, la page Admins est inaccessible et l'API répond 403 sur la suppression d'un admin.
- **Données de démo** : à ajouter plus tard : un admin jetable en plus de Nadia.

### 9.5 Rechercher un coureur par pseudo
- **Dépend de** : 1.1, 9.2.
- **Livré** : `GET /api/administration/coureurs?q=…` (admins et admin master) : recherche partielle, insensible à la casse, 3 caractères minimum (400 sinon), résultats limités (10), comptes `COUREUR` connectables uniquement (les comptes anonymisés par le 3.6 et les autres rôles sont absents). Dans les pages Bénévoles et Admins, un bouton « Promouvoir un coureur » ouvre un panneau de recherche à la demande (même principe que le 9.2) qui liste les pseudos trouvés. Aucune action de promotion à ce stade.
- **Tu testes** : saisir 2 caractères → aucune recherche ; saisir 3 caractères d'un pseudo de coureur → il apparaît ; un admin, un bénévole et un compte supprimé n'apparaissent jamais ; plus de 10 correspondances → 10 résultats.
- **Données de démo** : à ajouter plus tard : une dizaine de coureurs aux pseudos proches (`promo01`…`promo12`) pour la limite des résultats.

### 9.6a Anonymisation à la suppression d'un bénévole ou d'un admin
- **Dépend de** : 9.3, 9.4, 3.6.
- **Livré** : back seul. La suppression d'un bénévole (9.3) ou d'un admin (9.4) qui a des inscriptions anonymise le compte comme au 3.6 (pseudo remplacé, mot de passe effacé, rôle ramené à `COUREUR`, résultats conservés, pseudo libéré) ; sans inscription, suppression physique inchangée. Port `ConsultationInscriptions` côté `comptes`, adaptateur dans `assemblage`. Spec : `docs/specs/increment-9.6.md` (RG1 à RG10, CA1 à CA11). Découpé du 9.6 (dépassement de la cible de 400 lignes).
- **Tu testes** : voir la section « Tester à la main » de la spec (une ligne SQL fabrique un bénévole ex-coureur).
- **Données de démo** : rien.

### 9.6b Passer un coureur en bénévole
- **Dépend de** : 9.3, 9.5, 2.4, 3.6.
- **Livré** : depuis les résultats de recherche de la page Bénévoles, « Passer en bénévole » avec confirmation (admins et admin master). Le compte devient `BENEVOLE`, sa session est fermée pour que le nouveau rôle s'applique à la prochaine connexion. Refus 409 si le coureur a une inscription à une course `EN_PREPARATION` ou `EN_COURS`, avec message explicite ; ses inscriptions aux courses `TERMINEE` sont conservées. Après le changement, il apparaît dans la liste des bénévoles et est proposé à l'affectation. Terme « changement de rôle » ajouté au langage ubiquitaire. La suppression d'un bénévole (et d'un admin) qui a des inscriptions est désormais une anonymisation, sans changement visible pour l'admin (décision prise).
- **Tu testes** : promouvoir un coureur sans inscription en cours → il se reconnecte en bénévole et voit l'espace bénévole. Promouvoir un coureur inscrit à une course en préparation → refus avec message ; se désinscrire puis réessayer → accepté. Promouvoir un coureur ayant couru une course terminée → accepté, ses résultats restent affichés.
- **Données de démo** : à ajouter plus tard : un coureur sans inscription, un inscrit à une course en préparation, un inscrit à une course en cours, un ayant couru une course terminée.

### 9.7 Passer un coureur en admin
- **Dépend de** : 9.6.
- **Livré** : depuis la page Admins, « Passer en admin » avec confirmation, pour l'admin master uniquement (403 pour un admin). Mêmes refus, mêmes effets de session et même conservation des inscriptions terminées que le 9.6.
- **Tu testes** : en admin master, promouvoir un coureur en admin → il se reconnecte et voit l'administration. En admin simple, la page Admins est inaccessible et l'API répond 403.
- **Données de démo** : les coureurs du 9.6 suffisent.

### 9.8 Adresse de notification d'une course
- **Dépend de** : 2.1, 2.2, 9.2.
- **Livré** : champ facultatif « adresse mail de notification » sur la course, saisi à la création et à la modification (format validé, 400 sinon ; modifiable tant que la course est `EN_PREPARATION`, vidable). Affiché sur la fiche admin de la course. Migration Liquibase pour la colonne. Aucun mail n'est encore envoyé.
- **Tu testes** : créer une course avec une adresse valide → affichée sur la fiche ; adresse mal formée → refusée ; modifier puis vider le champ → course enregistrée sans adresse.
- **Données de démo** : à ajouter plus tard : une course de démo avec une adresse de notification (celle de Mailpit) et une sans.

### 9.9 Mail à chaque inscription
- **Dépend de** : 3.1, 3.2, 9.8.
- **Livré** : à chaque inscription validée, mail envoyé à l'adresse de notification de la course ; rien si le champ est vide. Contenu : course, pseudo, dossard, nombre de places restantes, aucune autre donnée personnelle. Envoi asynchrone après validation de l'inscription ; un échec SMTP est journalisé (sans secret) et ne bloque ni n'annule jamais l'inscription. Port sortant `NotificationInscription` dans le domaine ou l'application, adaptateur SMTP dans l'infrastructure. Configuration SMTP par variables d'environnement, ajoutées à `.env.example`. Service Mailpit (interface web, API pour Playwright) dans un fichier de surcharge `docker-compose.mail.yml`, utilisé en local et en E2E, absent en production (voir « Décisions prises »). `docs/deploiement.md` : configuration SMTP en production.
- **Tu testes** : lancer la stack avec la surcharge Mailpit, s'inscrire à la course de démo avec adresse de notification → un mail apparaît dans l'interface Mailpit avec course, pseudo, dossard et places restantes. S'inscrire à une course sans adresse → aucun mail. Arrêter Mailpit puis s'inscrire → l'inscription réussit, l'échec est dans les logs de `api`. En production, aucun service Mailpit (`docker compose -f docker-compose.yml -f docker-compose.prod.yml config`).
- **Données de démo** : voir 9.8.
- **Taille** : peut dépasser la cible de 400 lignes (port, adaptateur, asynchrone, compose, documentation) ; le `fonctionnel` propose alors un découpage 9.9a (port, adaptateur SMTP, envoi) / 9.9b (Mailpit, E2E, documentation de production), comme pour le 8.1.

---

## Décisions prises

| Sujet | Décision | Incrément |
|---|---|---|
| Maîtrise du temps pour les tests E2E | Horloge pilotable activée uniquement dans le profil `e2e`. Les vrais essais en temps réel se font en recette sur le terrain. | 4.3 |
| Suppression d'un compte ayant couru | Anonymisation : le compte ne peut plus se connecter, son pseudo est remplacé, ses résultats sont conservés. | 3.6 |
| Suppression d'une course | Par l'admin master uniquement, tant que la course est `EN_PREPARATION`. | 2.5 |
| Scan reçu après le départ de la boucle suivante (MET-02) | Délai de grâce dans le domaine : la boucle N n'est tranchée (abandon automatique, fin de course) qu'à départ(N+1) + délai. Le scan reste attribué à N grâce à son âge. Durée du délai à fixer dans la spec 7.1. | 7.1 |
| Suppression d'un bénévole | Par les admins et l'admin master, avec confirmation. Affectations retirées, refus (409) s'il est affecté à une course `EN_COURS`, session invalidée, passages scannés conservés (ils ne le référencent pas). | 9.3 |
| Suppression d'un admin | Par l'admin master uniquement (403 pour un admin), avec confirmation. L'admin master n'est jamais supprimable. Connexion impossible et session invalidée. | 9.4 |
| Changement de rôle d'un coureur | Recherche par pseudo (partielle, 3 caractères minimum, résultats limités, comptes `COUREUR` uniquement). L'admin master passe un coureur en `ADMIN`, les admins et l'admin master le passent en `BENEVOLE`. Refus (409) s'il a une inscription à une course `EN_PREPARATION` ou `EN_COURS` ; inscriptions `TERMINEE` conservées ; session invalidée. | 9.5, 9.6, 9.7 |
| Mail d'inscription | Adresse de notification facultative sur la course (format validé) ; aucun mail si vide. Contenu : course, pseudo, dossard, places restantes. Envoi asynchrone après validation, un échec SMTP est journalisé et ne bloque ni n'annule jamais l'inscription. Port dans le domaine ou l'application, adaptateur SMTP dans l'infrastructure, configuration par variables d'environnement. | 9.8, 9.9 |
| Menu d'administration | Menu de navigation (Courses, Admins, Bénévoles selon le rôle) ; chaque liste reste visible, le formulaire de création s'ouvre à la demande puis se referme après création ou annulation. Aucun changement d'API. | 9.1, 9.2 |
| Suppression et désactivation | Les deux coexistent ; la suppression est possible aussi sur un compte désactivé. Suppression physique, pseudo libéré (pas d'anonymisation : aucun résultat ne référence un admin ou un bénévole). | 9.3, 9.4 |
| Dernier admin | La règle du 8.1 s'applique aussi à la suppression : refus s'il n'existe aucun autre admin actif (l'admin master n'est pas compté). | 9.4 |
| Affectations d'un bénévole supprimé | Toutes les affectations sont retirées, y compris sur les courses `TERMINEE` ; seuls les passages font foi pour l'historique. | 9.3 |
| Suppression d'un bénévole ou d'un admin issu d'un coureur | Tranché : le compte est anonymisé comme au 3.6 (pseudo remplacé, mot de passe effacé, résultats conservés, pseudo libérable) s'il a des inscriptions ; sinon suppression physique comme aux 9.3 et 9.4. Livré avec le 9.6. | 9.6, 9.7 |
| Rétrogradation | Le changement de rôle est à sens unique ; pas de retour en coureur dans ce jalon (désactivation ou suppression suffisent). | 9.6, 9.7 |
| Emplacement de la promotion | Bouton « Promouvoir un coureur » sur les pages Bénévoles (vers `BENEVOLE`) et Admins (vers `ADMIN`) ; résultats de recherche limités à 10. | 9.5 |
| Mailpit | Fichier de surcharge `docker-compose.mail.yml`, combiné au fichier de base en local et à `docker-compose.e2e.yml` en E2E, jamais à `docker-compose.prod.yml`. Le local par défaut n'envoie pas de mail. | 9.9 |
| Données personnelles dans le mail | Pseudo envoyé à l'adresse de notification : accepté, avec TLS exigé vers le fournisseur et une phrase d'information sur l'écran d'inscription. | 9.9 |

## Décisions en attente

1. **Fournisseur SMTP en production (9.9)** : budget quasi nul et port 25 souvent bloqué sur les VPS. Offre gratuite d'un service transactionnel (port 587/465 avec authentification) ou relais SMTP de la messagerie existante ? Domaine d'expédition, SPF et DKIM à prévoir. À trancher avant le déploiement du 9.9 ; le développement et les tests n'en dépendent pas.

