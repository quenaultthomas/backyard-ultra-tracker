# Backyard Ultra Tracker

Application de gestion de courses de backyard ultra en mode amateur : organisation des courses, inscriptions des coureurs, scan des passages par les bénévoles et écran de suivi en direct. Le projet vise un budget quasi nul et un déploiement Docker simple sur VPS.

## Présentation

Backyard Ultra Tracker permet de gérer des courses de backyard ultra avec :

- création et pilotage de plusieurs courses en parallèle ;
- inscriptions de coureurs avec dossard et QR code ;
- validation des passages par les bénévoles ;
- calcul automatique des abandons et des fins de course ;
- suivi public en direct d'une course avec boucle en cours, temps, distance et classement ;
- administration des comptes, des bénévoles et des réintégrations.

La logique métier est centralisée dans le domaine, et les règles sont dérivées et jamais stockées en base.

## Contexte métier

- Une boucle (yard) démarre à heure fixe : tous les coureurs encore en course partent ensemble et doivent boucler avant le départ de la boucle suivante.
- Pas de passage valide sur la boucle courante = abandon (DNF).
- La course s'arrête quand un seul coureur termine une boucle que personne d'autre ne termine : il est vainqueur, tous les autres sont en abandon.
- Plusieurs courses sont gérées en parallèle, chacune avec ses propres paramètres et sa propre page d'inscription.

## Terminologie du domaine

Ces termes sont la référence pour le code, les specs, l'API et l'interface :

| Terme | Définition |
|---|---|
| Course | Une édition de backyard : nom, date, logo, paramètres de boucle, limites, bénévoles affectés, statut |
| Boucle | Un yard : distance (m), durée (min), dénivelé positif (m), identiques pour toute la course |
| Compte | Identité d'une personne qui se connecte (pseudo, mot de passe, rôle) |
| Inscription | Participation d'un compte coureur à une course : dossard, jeton QR, statut, motif et boucle d'abandon |
| Passage | Enregistrement brut du franchissement de la ligne pour une boucle donnée |
| Abandon | Sortie de course d'un coureur (DNF), avec un motif |
| Réintégration | Correction d'un abandon enregistré à tort |
| Bénévole | Compte chargé de scanner les passages |

Statuts et énumérations :

- Course : `EN_PREPARATION`, `EN_COURS`, `TERMINEE`
- Inscription : `EN_COURSE`, `ABANDON`, `VAINQUEUR`
- Motif d'abandon : `VOLONTAIRE`, `HORS_DELAI`, `MANUEL`, `AUTRE`
- Origine d'un passage : `SCAN`, `CORRECTION`
- Rôle : `ADMIN_MASTER`, `ADMIN`, `BENEVOLE`, `COUREUR`

## Fonctionnalités par rôle

### Administration (`ADMIN_MASTER`, `ADMIN`)

- déclaration des courses : nom, date, distance, durée, dénivelé, logo, nombre max de participants et de boucles ;
- pilotage d'une course : démarrage, suivi des coureurs, abandon manuel, réintégration ;
- gestion des comptes : création des admins et des bénévoles ;
- contrôle strict des permissions côté serveur.

### Bénévole (`BENEVOLE`)

- scan des QR codes des coureurs à la caméra ;
- retour visuel immédiat du passage ;
- file hors ligne avec tentative de reprise automatique.

### Coureur (`COUREUR`)

- inscription aux courses ouvertes ;
- consultation de ses inscriptions, dossard et QR code ;
- désinscription tant que la course est en préparation ;
- gestion de son propre compte.

### Suivi public

- écran soigné, pensé pour être projeté ;
- boucle en cours et compte à rebours ;
- durée écoulée, nombre de boucles, distance et dénivelé cumulés ;
- coureurs en course, abandons et gagnants ;
- rafraîchissement automatique.

## Règles métier clés

- Abandon automatique : au départ de la boucle N+1, toute inscription `EN_COURSE` sans passage valide sur la boucle N passe en `ABANDON` avec motif `HORS_DELAI`.
- Fin de course : contrôlée au départ de la boucle N+1, avec abandon automatique et calcul du vainqueur ou des vainqueurs partagés.
- Nombre max de participants : une inscription est refusée quand la course est complète.
- Paramètres figés : distance, durée, dénivelé et nombre max de boucles ne sont plus modifiables une fois la course `EN_COURS`.
- Bénévoles affectés : seuls les bénévoles affectés à une course peuvent scanner des passages pour cette course.
- Réintégration : action admin qui recrée les passages manquants et remet l'inscription `EN_COURSE`.
- Scan : un passage est refusé s'il existe déjà pour cette inscription et cette boucle, ou si l'inscription n'est pas `EN_COURSE`.
- Filet réseau : les scans sont enregistrés localement puis envoyés à l'API de manière idempotente.
- Désinscription : possible uniquement tant que la course est en préparation.
- Suppression d'une course : uniquement par l'admin master et uniquement tant que la course est en préparation.

## Stack technique

- Java 25
- Spring Boot 4.1.1
- Spring Web, Spring Data JPA, Spring Security, Actuator
- PostgreSQL
- Liquibase
- Angular (PWA)
- Docker + Docker Compose

## Architecture

Le projet est construit selon une Clean Architecture + DDD :

1. domaine : agrégats, entités, value objects, règles métier et ports sortants ;
2. application : cas d'usage métier ;
3. infrastructure : persistance JPA, sécurité, stockage du logo, adaptateurs ;
4. exposition : contrôleurs REST et DTO.

Les règles métier vivent au cœur du domaine, et les dépendances vont uniquement vers l'intérieur.

## Structure du dépôt

```text
backend/                 application Spring Boot + tests unitaires et d'intégration + Dockerfile
frontend/                application Angular (PWA) + Dockerfile (build Angular servi par Caddy)
e2e/                     tests Playwright
docs/                    roadmap, specs, audits, déploiement
scripts/                 scripts utilitaires (données de démo)
CLAUDE.md                conventions d'architecture et workflow
action de l'agent
.env.example             variables d'environnement de référence
docker-compose.yml       stack commune pour le développement et la production
docker-compose.prod.yml  surcharges production (domaine, HTTPS, redémarrage)
docker-compose.e2e.yml  surcharges pour les tests E2E (profil e2e)
README.md                documentation projet
```

## Déploiement

L'application se lance entièrement avec Docker, sans installation locale de Java, Node ou PostgreSQL.

### Local

```bash
docker compose up -d --build
```

Puis ouvrir :

```text
http://localhost
```

### Production

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

Le service `web` sert le front et relaie `/api` vers le backend. Le service `api` attend que PostgreSQL soit sain avant de démarrer ; le frontend attend que l'API soit saine.

## Variables d'environnement

Le fichier `.env.example` référence les variables nécessaires à l'application :

- connexion PostgreSQL ;
- identifiants de l'admin master ;
- paramètres de sécurité et de blocage des tentatives de connexion ;
- configuration du déploiement.

Les secrets ne doivent jamais être commités.

## Tests et qualité

Le projet impose :

- tests unitaires du domaine et des cas d'usage ;
- tests d'intégration sur l'API et la persistance ;
- tests E2E Playwright ;
- tests d'architecture ArchUnit ;
- couverture minimale du back de 85 % ;
- validation de la définition de « fini » avant validation d'un incrément.

## Documentation

- `docs/roadmap.md` : roadmap des incréments
- `docs/specs/` : spécifications détaillées par incrément
- `docs/deploiement.md` : procédure de déploiement, sauvegarde et mise à jour
- `docs/audits/` : référentiel et rapports d'audit

## Contribution

Le projet suit un workflow par incréments : chaque incrément correspond à une branche et à une spec dédiée. Les règles métier et le contrat utilisateur sont validés avant de passer à l'incrément suivant.

## Licence

À compléter selon la licence choisie pour le projet.
