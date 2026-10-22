# Changelog

Toutes les évolutions notables de Backyard Ultra Tracker sont consignées ici.
Le format suit [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) et le projet suit le [versionnement sémantique](https://semver.org/lang/fr/).

## [0.5.0] - 2026-10-22

Version du jour de course : scan hors ligne, application installable, écran de suivi public projetable et accueil public, avec une décision de Boucle tolérante aux scans tardifs et un suivi qui tient la charge des spectateurs.

### Ajouté

**Jalon 5 : scan hors ligne**
- File de scan locale côté bénévole : chaque scan est d'abord enregistré sur l'appareil, puis envoyé avec nouvelles tentatives automatiques.
- Idempotence sur l'identifiant de scan généré par l'appareil : un rejeu renvoie le même résultat sans doublon ; un autre scan pour le même coureur et la même Boucle reste refusé.
- Compteur « en attente » des scans non encore transmis, scans à vérifier et confirmation avant une déconnexion qui perdrait des scans.

**Jalon 6 : application installable et suivi public**
- Application installable (PWA) avec coque en cache ; mise à jour proposée par un bandeau, jamais appliquée toute seule.
- Écran de suivi public d'une Course, pensé pour être projeté : logo, Boucle en cours et compte à rebours, durée écoulée, distance et dénivelé cumulés, coureurs en course, abandons (Boucle et motif), Vainqueur, statistiques et mode projection ; rafraîchissement par polling.
- Accueil public listant les Courses en cours, à venir et terminées.

**Jalon 7 : fiabilité du jour de course**
- Délai de grâce de 3 minutes (plafonné au quart de la durée de Boucle) avant de trancher une Boucle : un scan fait pendant la Boucle et transmis en retard est encore accepté.
- Suivi public sous charge : micro-cache d'une seconde par Course, limite de débit par IP sur les routes publiques (429 `TROP_DE_REQUETES`), relance étalée et espacée des écrans en cas d'erreur.
- Planificateur allégé (une Course n'est relue qu'à l'échéance d'une Boucle) et fin des requêtes N+1 sur les listes de Courses et les Inscriptions du coureur.

### Sécurité
- En-tête HSTS servi en HTTPS : la limite « pas d'en-tête HSTS » de la 0.4.0 est levée.
- Drapeau `Secure` des cookies de session et CSRF fixé par configuration, indépendant de `X-Forwarded-Proto`.
- Plafond des calculs Argon2id par adresse IP, en plus du plafond global.
- Gestionnaire d'erreurs `ProblemDetail` couvrant tous les contrôleurs.
- Outillage de build à jour : `vite` 8.3.3 (alertes Dependabot de `vite` 8.3.0 à 8.3.2), imposé par un `overrides` dans `frontend/package.json` tant qu'aucune version stable d'`@angular/build` ne l'embarque. `npm audit` sans vulnérabilité dans `frontend/` et `e2e/`.
- Correctifs de dépendances d'exécution au-dessus des versions de Spring Boot 4.1.1 : Tomcat 11.0.25 (trois CVE critiques de 11.0.24) et Jackson 3.1.7 (cinq CVE hautes de 3.1.5). Vulnérabilités restantes des images `postgres` et `caddy`, sans image corrigée disponible : voir « Contrôle des vulnérabilités » dans `docs/deploiement.md`.

### Variables d'environnement nouvelles depuis la 0.4.0
Toutes facultatives (voir `.env.example` et `docs/deploiement.md`) :
- `BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE` : requêtes publiques admises par IP et par minute, défaut `1200`, `0` désactive la limite.
- `COOKIES_SECURE` : drapeau `Secure` des cookies, défaut `false` en dev et E2E, imposée à `true` en production par `docker-compose.prod.yml`.
- `HACHAGE_CONCURRENCE_MAX_PAR_IP` : calculs Argon2id simultanés par adresse cliente, défaut `1`.
- `BACKYARD_PROXY_DE_CONFIANCE` : proxy dont `X-Forwarded-For` est cru, fixée à `web` par `docker-compose.yml`, non réglable.

### Changements à l'exploitation
- Sauvegarde planifiable : `scripts/sauvegarder.sh` (rotation, contrôle du dump, option `--verifier-restauration`), exemples `cron` et consignes du jour de course dans `docs/deploiement.md`.
- Rappel : sessions et blocages de connexion sont en mémoire et perdus à chaque redémarrage de `api` ; ne pas mettre à jour pendant une course.

### Limites connues
- Le blocage de connexion porte sur le pseudo : un tiers peut bloquer temporairement un compte, l'admin master compris.
- La limite de débit publique est partagée par les spectateurs derrière une même IP (wifi d'un stade) : 1200 admet environ 60 écrans.
- Sur une Course `TERMINEE`, après le délai de grâce, aucune correction n'est possible dans l'application.

## [0.4.0] - 2026-10-16

Première version déployable : une course de bout en bout, de la déclaration à la désignation du vainqueur, avec scan des passages par les bénévoles. Le scan hors ligne, l'installation en application (PWA) et l'écran de suivi public ne sont pas inclus (jalons 5 et 6).

### Ajouté

**Comptes et connexion**
- Création libre d'un compte coureur (pseudo et mot de passe de 12 caractères minimum), connexion et déconnexion par session serveur.
- Blocage temporaire après plusieurs échecs de connexion, avec un message identique que le pseudo existe ou non.
- Admin master créé au premier démarrage à partir des variables d'environnement ; il crée les admins, les admins créent les bénévoles.
- Écran « Mon compte » : changement de mot de passe pour tous les rôles, suppression du compte coureur (anonymisation, résultats conservés).

**Courses**
- Déclaration et modification d'une course tant qu'elle est en préparation : nom, date, distance, durée et dénivelé d'une boucle, nombre maximum de participants et de boucles.
- Logo de course (PNG, JPEG ou WebP, 2 Mo maximum).
- Affectation des bénévoles à une course ; seuls les bénévoles affectés peuvent scanner.
- Suppression d'une course en préparation, réservée à l'admin master.
- Données de démonstration : `scripts/donnees-demo.sh`.

**Inscriptions**
- Inscription aux courses ouvertes avec dossard attribué automatiquement, refus si la course est complète ou n'est plus en préparation.
- « Mes inscriptions » : dossard et QR code personnel, désinscription tant que la course est en préparation.
- Liste des inscrits d'une course pour les admins, places restantes, dossard imprimable avec QR code.

**Course en direct**
- Démarrage d'une course avec confirmation ; les paramètres de boucle sont alors figés.
- Écran de pilotage : boucle courante, compte à rebours, durée écoulée, coureurs en course, qui a bouclé la boucle courante.
- Scan des QR codes à la caméra (ou saisie du jeton) avec retour visuel immédiat et refus explicites : doublon, coureur en abandon, course non démarrée ou terminée, bénévole non affecté, jeton inconnu, scan trop rapproché.
- Abandon automatique au départ de la boucle suivante pour tout coureur sans passage sur la boucle terminée.
- Fin de course : vainqueur unique, vainqueurs partagés si personne ne termine la boucle, ou à la dernière boucle autorisée.
- Abandon manuel avec motif et confirmation ; réintégration d'un coureur (passages corrigés, badge « corrigé »).

**Interface**
- Thème graphique backyard, menu burger, liste des courses en cartes.
- Version de l'application affichée discrètement en pied de page.

**Exploitation**
- `/actuator/info` et `/api/version` indiquent la version déployée.
- Images Docker `api` et `web` versionnées, publiées sur GHCR par la CI à chaque tag (voir `docs/deploiement.md`).

### Sécurité
- Mots de passe hachés en Argon2id, sessions serveur avec cookie `HttpOnly`, `SameSite=Lax` et protection CSRF, contrôle des rôles sur chaque route.
- Audit du 2026-10-16 (`docs/audits/`) : le hachage Argon2id est sorti des transactions et plafonné en concurrence (réponse 503 `SERVICE_SATURE` en cas de saturation), pour qu'une rafale de connexions ne bloque pas les scans ; journaux Docker bornés ; plafonds mémoire des conteneurs et JVM qui s'arrête proprement sur manque de mémoire.
- Limites connues : le blocage de connexion porte sur le pseudo (un tiers peut bloquer temporairement un compte, l'admin master compris), pas de limite de débit par adresse IP, pas d'en-tête HSTS. Voir `docs/deploiement.md` et le jalon A de la roadmap.

[0.5.0]: https://github.com/quenaultthomas/backyard-ultra-tracker/releases/tag/v0.5.0
[0.4.0]: https://github.com/quenaultthomas/backyard-ultra-tracker/releases/tag/v0.4.0
