# Increment 0.3 : Outillage qualité

Met en place les garde-fous de qualité (couverture JaCoCo, règles ArchUnit, Playwright) avant tout code métier. Aucune fonctionnalité métier, aucun changement d'API ni d'écran.

## 1. Périmètre

**Inclus**
- JaCoCo dans `backend/pom.xml` : agrégation des tests unitaires et d'intégration, rapport HTML par `./mvnw verify`, seuil bloquant 85 % lignes et branches.
- Séparation des phases de test : surefire (`*Test`) et failsafe (`*IntegrationTest`).
- ArchUnit : règles des quatre couches et des deux contextes délimités (`comptes`, `courses`), convention de packages.
- Les tests d'intégration Testcontainers existants (0.1 : `SqueletteIntegrationTest`, `BaseArreteeIntegrationTest`) tournent en phase failsafe et comptent dans la couverture. Aucun nouveau test d'intégration (position par défaut, voir point ouvert 1).
- Projet Playwright dans `e2e/` (Node séparé, TypeScript, Node 24, chromium) avec un test de la page d'accueil sur la stack compose lancée à part.

**Exclu**
- `docker-compose.prod.yml`, `.env.example`, `docs/deploiement.md` : 0.4.
- `docker-compose.e2e.yml`, profil `e2e`, horloge pilotable `/api/test/horloge` : 4.3.
- Toute fonctionnalité métier, tout package métier avec du code : 1.x et suivants.
- Intégration continue (CI) : non demandée (point ouvert 4).
- Tests unitaires front : jamais (CLAUDE.md).
- Changement du `Dockerfile` ou du compose : aucun.

## 2. Règles de gestion

**Phases de test et JaCoCo (`backend/`)**
- **RG1** : convention de nommage. Les classes `*Test` sont des tests unitaires (sans Spring ni base, y compris les tests ArchUnit), exécutés par surefire en phase `test`. Les classes `*IntegrationTest` sont des tests d'intégration (`@SpringBootTest` + Testcontainers), exclues de surefire (`**/*IntegrationTest.java`) et exécutées par failsafe (phase `integration-test`, vérifiées en `verify`). Un fichier ne peut pas être exécuté par les deux.
- **RG2** : JaCoCo utilise un seul fichier d'exécution (`target/jacoco.exec`) alimenté par surefire et failsafe (agent attaché aux deux, mode ajout). Le rapport HTML est produit en phase `verify`, après failsafe, dans `backend/target/site/jacoco/index.html`.
- **RG3** : la vérification (`jacoco:check`) est liée à `verify`, après le rapport, sur le bundle entier, avec deux règles : `LINE` ratio couvert >= 0.85 et `BRANCH` ratio couvert >= 0.85. Un seuil non atteint fait échouer `./mvnw verify` (build en échec, pas d'avertissement).
- **RG4** : seuils et exclusions sont des propriétés Maven (`jacoco.seuil.lignes`, `jacoco.seuil.branches`, valeur par défaut `0.85` ; `jacoco.exclusions`) pour pouvoir les surcharger en ligne de commande. Les valeurs par défaut ne se modifient pas à la baisse sans décision de l'utilisateur.
- **RG5** : exclusion unique par défaut : la classe `fr.backyard.tracker.BackyardUltraTrackerApplication` (méthode `main`, non testable sans lancer le processus). Aucune autre exclusion (pas de DTO, d'entités JPA ni de configuration) : toute nouvelle exclusion exige une mise à jour de cette spec ou de la spec de l'incrément concerné.
- **RG6** : cas du code sans branche. JaCoCo ignore une règle dont le compteur total est 0 (ratio non défini) : en 0.3 le code applicatif n'a ni branche ni ligne hors classe exclue, les deux règles restent déclarées et le build passe à vide. Elles s'appliquent dès que du code existe. Aucune règle de contournement (pas de seuil à 0, pas de `haltOnFailure=false`).
- **RG7** : `./mvnw test` n'exécute que les tests unitaires et ne vérifie aucun seuil. Seul `./mvnw verify` applique RG3. La construction de l'image `api` (`./mvnw -DskipTests package`, 0.1) reste inchangée et ne dépend ni de Docker, ni de Testcontainers, ni du seuil.
- **RG8** : `SqueletteIntegrationTest` et `BaseArreteeIntegrationTest` ne sont pas modifiés dans leur contenu ; ils sont exécutés par failsafe (RG1), nécessitent Docker sur la machine qui lance `verify`.

**Architecture (ArchUnit)**
- **RG9** : convention de packages : `fr.backyard.tracker.<contexte>.<couche>` avec `<contexte>` dans `{comptes, courses}` et `<couche>` dans `{domaine, application, infrastructure, exposition}`. La classe `BackyardUltraTrackerApplication` reste dans `fr.backyard.tracker` (racine, hors règles). Pas de noyau partagé (point ouvert 2).
- **RG10** : les règles sont dans une classe de tests unitaires `fr.backyard.tracker.ArchitectureTest` (surefire, donc dès `./mvnw test`). Elles analysent uniquement les classes de production (`DoNotIncludeTests`) du package `fr.backyard.tracker`.
- **RG11** : packages vides. Les packages n'existent pas encore : aucun `package-info.java` n'est créé ; les règles sont configurées pour réussir quand aucune classe ne correspond (`archunit.properties` avec `archRule.failOnEmptyShould=false`). Conséquence assumée : une règle est vide et ne protège rien tant qu'aucune classe n'existe dans les packages visés ; elle s'active dès la première classe.
- **RG12** : règle « domaine pur ». Une classe de `..<contexte>.domaine..` ne dépend que de `java..` et de `..<même contexte>.domaine..`. Cela interdit notamment Spring, `jakarta.*`, Jackson (`com.fasterxml..`, `tools.jackson..`), Hibernate, Liquibase, Lombok.
- **RG13** : règle « application ». Une classe de `..application..` ne dépend que de `java..`, du domaine et de l'application du même contexte, et de `org.springframework.stereotype..` et `org.springframework.transaction..` (stéréotype et `@Transactional`, CLAUDE.md : l'application gère les transactions). Interdits : `jakarta.persistence..`, `org.hibernate..`, `org.springframework.web..`, `org.springframework.data..`, Jackson, et toute classe d'`infrastructure` ou d'`exposition`.
- **RG14** : dépendances uniquement vers l'intérieur (`layeredArchitecture`, couches définies par `fr.backyard.tracker.*.<couche>..`) : le domaine n'est accédé que par application, infrastructure et exposition ; l'application n'est accédée que par infrastructure (ex. planificateur) et exposition ; l'exposition et l'infrastructure ne sont accédées par aucune autre couche. En particulier : l'exposition ne dépend pas de l'infrastructure.
- **RG15** : contextes délimités. Aucune classe de `fr.backyard.tracker.courses..` ne dépend d'une classe de `fr.backyard.tracker.comptes..`, et réciproquement. Un compte est référencé dans `courses` par son identifiant (type simple, ex. `UUID`/`Long`), jamais par une classe de `comptes`.
- **RG16** : placement. Toute classe annotée `@RestController`/`@Controller` est dans une `..exposition..` ; toute classe annotée `@Entity` est dans une `..infrastructure..`. Toute classe sous `fr.backyard.tracker.<contexte>..` est dans l'une des quatre couches (pas de classe directement dans le contexte ni dans une couche inconnue).
- **RG17** : un échec ArchUnit fait échouer le build dès la phase `test` (avant les tests d'intégration), avec le message ArchUnit listant la classe et la dépendance fautives.

**E2E (`e2e/`)**
- **RG18** : `e2e/` est un projet Node autonome (`package.json`, lockfile commité, TypeScript) : Node 24 (`engines` et `.nvmrc`), `@playwright/test` en version exacte (sans `^` ni `~`, choisie par le développeur), un seul projet navigateur : chromium. `node_modules/`, `test-results/` et `playwright-report/` sont ignorés par git.
- **RG19** : les tests ne démarrent pas la stack. Prérequis : `docker compose up -d --build` lancé à la racine, services `healthy`. Un `globalSetup` se limite à vérifier que `GET <BASE_URL>/api/sante` répond 200 ; sinon il échoue avec un message explicite (« Stack injoignable sur <BASE_URL> : lancer `docker compose up -d --build` »), sans démarrer de conteneur.
- **RG20** : URL de base : variable d'environnement `BASE_URL`, défaut `http://localhost`. Aucune autre URL en dur.
- **RG21** : le fichier `e2e/tests/accueil.spec.ts` couvre l'écran Accueil : titre visible (`data-testid="titre"` = « Backyard Ultra Tracker ») et indicateur (`data-testid="etat-api"` = « API : disponible »). Chaque test cite dans son titre le CA de 0.3 et le CA de 0.2 qu'il automatise (convention « [CA8 / 0.2 CA7] »). Timeout d'attente de l'indicateur : 5 s (tolérance par rapport aux 3 s de 0.2 CA7, point ouvert 5).
- **RG22** : les tests E2E sont indépendants et non destructifs : ils ne stoppent aucun service. Les scénarios `docker compose stop api` de 0.2 (CA8, CA9) ne sont pas automatisés en 0.3.

## 3. Cas limites

- **Code sans branche** : règle `BRANCH` déclarée mais sans compteur : build vert (RG6), à vérifier explicitement (CA3).
- **Classe `main` non exclue** : le ratio de lignes passe sous 0.85 et le build échoue : c'est la façon de prouver que le seuil bloque à vide (CA4).
- **Packages vides** : les règles ArchUnit réussissent sans classe (RG11) ; une règle qui échouerait par « should being empty » est une erreur de configuration.
- **Première classe dans un contexte** : elle doit respecter RG16 (dans l'une des quatre couches), sinon échec.
- **Test mal nommé** (`FooIT` ou `FooIntegration`) : ni surefire ni failsafe ne le lancent. Seuls `*Test` et `*IntegrationTest` sont reconnus (RG1).
- **Docker absent** : `./mvnw test` passe sans Docker ; `./mvnw verify` échoue sur les tests d'intégration (Testcontainers). Documenté, pas de contournement.
- **Stack arrêtée** pendant `npx playwright test` : échec explicite du `globalSetup` (RG19), pas de timeouts muets.
- **API arrêtée, stack `web` seule** : `globalSetup` échoue (502) ; l'état « indisponible » n'est pas testé en 0.3 (RG22).
- **Navigateur chromium absent** : `npx playwright install chromium` (sous Linux/WSL `--with-deps`) est un prérequis documenté.
- Rôle, bénévole, course, boucle, passage, inscription : non applicables en 0.3 (aucune donnée métier).

## 4. Contrat d'API

Aucun nouvel endpoint, aucun DTO, aucun changement de code de retour. Le test E2E s'appuie uniquement sur le contrat de 0.2 : `GET /api/sante` (200 `{"status":"UP"}`) et la page `/`.

## 5. Écrans

Aucun nouvel écran. L'écran **Accueil** (0.2, `/`) est le seul couvert : informations, actions et messages inchangés (voir spec 0.2, section 5). Sélecteurs utilisés : `data-testid="titre"`, `data-testid="etat-api"`.

## 6. Critères d'acceptation

Niveaux : `unitaire`, `intégration`, `E2E`, `outillage` (vérifiable par commande, sans test dédié).

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné Docker disponible, quand `./mvnw verify` dans `backend/`, alors BUILD SUCCESS ; `target/surefire-reports` ne contient aucun `*IntegrationTest`, `target/failsafe-reports` contient `SqueletteIntegrationTest` (5 tests) et `BaseArreteeIntegrationTest` (1 test), tous verts (RG1, RG8) | outillage |
| CA2 | Étant donné CA1, alors `target/site/jacoco/index.html` existe et `target/jacoco.exec` est unique ; le log montre l'ordre failsafe puis `jacoco:report` puis `jacoco:check` (RG2, RG3) | outillage |
| CA3 | Étant donné le code actuel (0 branche), quand `./mvnw verify`, alors la règle `BRANCH` ne fait pas échouer le build et la règle `LINE` non plus (compteurs à 0 après exclusion) (RG5, RG6) | outillage |
| CA4 | Étant donné le code actuel, quand `./mvnw verify "-Djacoco.exclusions=**/Inexistant.class"` (exclusion de la classe `main` annulée), alors BUILD FAILURE avec « Rule violated for bundle » sur le ratio de lignes (inférieur à 0.85) (RG3, RG4, RG5) | outillage |
| CA5 | Étant donné le code actuel, quand `./mvnw test` sans Docker, alors BUILD SUCCESS, seul `ArchitectureTest` s'exécute, aucun seuil vérifié (RG1, RG7) | outillage |
| CA6 | Étant donné le code actuel, quand `ArchitectureTest` s'exécute, alors toutes les règles RG12 à RG16 passent malgré des packages vides (RG10, RG11) | unitaire |
| CA7 | Étant donné un fichier `ViolationTemporaire.java` dans `courses/domaine` important `org.springframework.stereotype.Component`, quand `./mvnw test`, alors échec d'`ArchitectureTest` nommant `ViolationTemporaire` et `org.springframework`, avant tout test d'intégration (RG12, RG17) | outillage |
| CA8 | Étant donné une classe `courses/exposition` dépendant d'une classe de `courses/infrastructure`, quand `./mvnw test`, alors échec de la règle RG14 (RG14) | outillage |
| CA9 | Étant donné une classe de `courses/domaine` dépendant d'une classe de `comptes/domaine`, quand `./mvnw test`, alors échec des règles RG12 et RG15 (RG15) | outillage |
| CA10 | Étant donné une classe annotée `@Entity` dans `courses/domaine` ou une classe dans `courses/` hors des quatre couches, quand `./mvnw test`, alors échec de la règle RG16 (RG16) | outillage |
| CA11 | Étant donné une classe de `courses/application` important `jakarta.persistence.Entity`, quand `./mvnw test`, alors échec de la règle RG13 ; avec `org.springframework.transaction.annotation.Transactional` elle passe (RG13) | outillage |
| CA12 | Étant donné `docker compose build api`, quand l'image se construit, alors elle réussit sans Docker-in-Docker ni exécution de tests (`-DskipTests package`) (RG7) | outillage |
| CA13 | Étant donné la stack `healthy`, quand `npx playwright test` dans `e2e/` avec `BASE_URL` non défini, alors 2 tests verts sur chromium (RG18 à RG21) | E2E |
| CA14 | Étant donné la page `/` ouverte, alors `data-testid="titre"` est visible avec le texte exact « Backyard Ultra Tracker » (automatise 0.2 CA7, partie titre) (RG21) | E2E |
| CA15 | Étant donné la page `/` ouverte, alors `data-testid="etat-api"` affiche « API : disponible » en moins de 5 s (automatise 0.2 CA7, partie indicateur) (RG21) | E2E |
| CA16 | Étant donné `docker compose stop` (stack arrêtée), quand `npx playwright test`, alors échec immédiat avec le message de RG19 et aucun conteneur démarré (RG19, RG22) | outillage |
| CA17 | Étant donné `BASE_URL=http://localhost:1` , quand `npx playwright test`, alors échec explicite citant `http://localhost:1` (RG20) | outillage |
| CA18 | Étant donné le dépôt, alors `e2e/package.json` fixe `@playwright/test` sans `^` ni `~`, déclare Node 24, un lockfile est commité et `git status` ne montre ni `node_modules/` ni `test-results/` (RG18) | outillage |
| CA19 | Étant donné `docker compose up -d --build`, alors `base`, `api`, `web` sont `healthy` : 0.1 et 0.2 non régressés (aucun fichier de déploiement modifié) (RG7) | outillage |

Répartition : unitaire 1, intégration 0 (les IT de 0.1 sont des pré-requis non modifiés, vérifiés par CA1), E2E 3, outillage 15. Total 19.

Couverture des RG : RG1 CA1/CA5, RG2 CA2, RG3 CA2/CA4, RG4 CA4, RG5 CA3/CA4, RG6 CA3, RG7 CA5/CA12/CA19, RG8 CA1, RG9 CA6/CA10, RG10 CA6, RG11 CA6, RG12 CA7/CA9, RG13 CA11, RG14 CA8, RG15 CA9, RG16 CA10, RG17 CA7, RG18 CA13/CA18, RG19 CA13/CA16, RG20 CA17, RG21 CA13/CA14/CA15, RG22 CA16. L'écran Accueil est couvert en E2E par CA13, CA14, CA15.

## 7. Tester à la main

Prérequis : Docker, `docker compose`, Node 24 sur la machine pour la partie E2E (`e2e/` est hors compose). Les commandes `./mvnw` s'exécutent dans `backend/`.

**A. Couverture**
1. `cd backend && ./mvnw verify` : BUILD SUCCESS ; les tests d'intégration démarrent un conteneur PostgreSQL. Attendu : `failsafe-reports` (2 classes), pas de violation JaCoCo.
2. Ouvrir `backend/target/site/jacoco/index.html` dans un navigateur : le rapport s'affiche (la couverture est vide ou sans branche, normal en 0.3).
3. `./mvnw verify "-Djacoco.exclusions=**/Inexistant.class"` : BUILD FAILURE, « Rule violated for bundle … lines covered ratio is 0.xx, but expected minimum is 0.85 ». Relancer ensuite `./mvnw verify` : succès.

**B. ArchUnit**
4. `./mvnw test` : succès, seul `ArchitectureTest` passe (sans Docker).
5. Créer le fichier `backend/src/main/java/fr/backyard/tracker/courses/domaine/ViolationTemporaire.java` :
   ```java
   package fr.backyard.tracker.courses.domaine;

   import org.springframework.stereotype.Component;

   @Component
   class ViolationTemporaire {
   }
   ```
6. `./mvnw test` : BUILD FAILURE, `ArchitectureTest` en échec, message citant `ViolationTemporaire` et `org.springframework.stereotype.Component`.
7. Supprimer le fichier (`rm backend/src/main/java/fr/backyard/tracker/courses/domaine/ViolationTemporaire.java`, et le dossier `courses/` s'il est resté vide) puis `./mvnw test` : succès. `git status` propre.

**C. Playwright**
8. Depuis la racine : `docker compose up -d --build`, attendre `docker compose ps` : tout `healthy`.
9. `cd e2e && npm ci && npx playwright install chromium` (premier lancement ; sous Linux/WSL `npx playwright install --with-deps chromium`).
10. `npx playwright test` : 2 tests passés (chromium). Variante : `BASE_URL=http://localhost npx playwright test` : même résultat.
11. `cd .. && docker compose stop` puis `cd e2e && npx playwright test` : échec immédiat avec le message « Stack injoignable sur http://localhost : lancer `docker compose up -d --build` ». Relancer la stack (`docker compose up -d`) pour la suite.
12. Facultatif : `npx playwright test --headed` pour voir la page, et `npx playwright show-report`.

## 8. Points ouverts

Bloquants : aucun.

Non bloquants (position par défaut retenue, à confirmer) :
1. **Test d'intégration de démonstration** : la roadmap en promet un ; les deux IT de 0.1 le remplacent (pas de doublon), à condition qu'ils tournent en failsafe et comptent dans la couverture. À confirmer.
2. **Noyau partagé** : pas de package `partage` / `shared` en 0.3 (le seul lien entre contextes est l'identifiant, RG15). S'il devient nécessaire (ex. événements entre contextes), il faudra une règle dédiée.
3. **Règle application** (RG13) : autorise `org.springframework.stereotype` et `transaction` dans l'application car CLAUDE.md demande qu'elle gère les transactions sans interdire Spring à cette couche. Alternative plus stricte : déléguer la transaction à l'infrastructure.
4. **CI** : aucune intégration continue (GitHub Actions) n'est demandée. Utile à terme pour exécuter `verify` et Playwright à chaque PR ; à décider.
5. **Seuil du test E2E** : 5 s au lieu de 3 s pour absorber le premier polling ; resserrable.
6. **Versions** : ArchUnit (version compatible Java 25), JaCoCo (version compatible Java 25) et `@playwright/test` : choix du développeur, versions fixées.
7. **Dépendance à Docker** pour `./mvnw verify` : inévitable avec Testcontainers ; à documenter dans le README ou `docs/deploiement.md` (0.4).
