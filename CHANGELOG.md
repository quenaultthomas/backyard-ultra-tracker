# Changelog

Toutes les évolutions notables de Backyard Ultra Tracker sont consignées ici.
Le format suit [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) et le projet suit le [versionnement sémantique](https://semver.org/lang/fr/).

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

[0.4.0]: https://github.com/quenaultthomas/backyard-ultra-tracker/releases/tag/v0.4.0
