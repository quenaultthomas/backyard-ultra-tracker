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
| 1 | Comptes et sécurité | 1.1 → 1.6b | créer des comptes, te connecter avec chaque rôle |
| 2 | Courses | 2.1 → 2.5 | déclarer une course complète avec logo et bénévoles |
| 3 | Inscriptions | 3.1 → 3.6 | inscrire des coureurs et voir leurs dossards et QR |
| 4 | Course en direct | 4.1 → 4.12 | faire tourner une course de bout en bout |
| 5 | Scan hors ligne | 5.1 → 5.2 | scanner sans réseau sans perdre de passage |
| 6 | Suivi public | 6.1 → 6.4 | projeter le suivi d'une course |

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

### 1.6a Créer des comptes bénévoles
- **Livré** : les admins (et l'admin master) créent et listent les bénévoles ; écran d'accueil bénévole (vide) vers lequel un bénévole est redirigé après connexion.
- **Tu testes** : créer un bénévole, s'y connecter (écran d'accueil bénévole vide).

### 1.6b Changer son mot de passe
- **Livré** : écran « Mon compte » avec changement de mot de passe pour tous les rôles ; les autres sessions du compte sont fermées au changement ; les échecs comptent dans le blocage de connexion.
- **Tu testes** : changer son mot de passe, se reconnecter avec le nouveau.
- **Découpage** : l'ancien 1.6 dépassait la cible de 400 lignes, il a été scindé en 1.6a et 1.6b.

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
- **Livré** : manifeste PWA et service worker : l'écran de scan s'installe et s'ouvre sans réseau.
- **Tu testes** : installer l'application depuis le navigateur, couper le réseau, la rouvrir → l'écran de scan s'affiche.

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

## Décisions prises

| Sujet | Décision | Incrément |
|---|---|---|
| Maîtrise du temps pour les tests E2E | Horloge pilotable activée uniquement dans le profil `e2e`. Les vrais essais en temps réel se font en recette sur le terrain. | 4.3 |
| Suppression d'un compte ayant couru | Anonymisation : le compte ne peut plus se connecter, son pseudo est remplacé, ses résultats sont conservés. | 3.6 |
| Suppression d'une course | Par l'admin master uniquement, tant que la course est `EN_PREPARATION`. | 2.5 |

## Décisions en attente

Aucune pour l'instant. Toute nouvelle question est ajoutée ici et tranchée avant l'incrément concerné.
