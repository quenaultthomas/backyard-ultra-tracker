# Increment 5.1b : Scans à vérifier et déconnexion

**Dépendance : 5.1a (`increment-5.1a.md`) doit être livrée et validée.** 5.1b s'appuie sur la file locale IndexedDB, le service d'envoi, la classification des réponses (5.1a RG5), le compteur « N en attente » et l'isolation par Compte de 5.1a. Elle n'ajoute aucun envoi ni aucune règle d'envoi.

Elle rend **visibles et durables** les refus définitifs des scans envoyés en différé, et protège le bénévole d'une déconnexion qui laisserait des scans en attente :
- une liste persistante « Scans à vérifier » (bouton « Compris », aide vers la réintégration) ;
- les totaux « en attente » et « à vérifier » sur l'accueil bénévole ;
- une confirmation de déconnexion quand des scans sont en attente.

**Aucun changement d'API, de domaine ni de base.** Le contrat de `POST /api/benevole/courses/{courseId}/passages` est celui de 4.4a/4.6, inchangé et déjà consommé par 5.1a.

Numérotation : RG et CA repartent à 1. « 5.1a RGn » désigne la spec 5.1a ; « 4.4a », « 4.6 », « 4.12 » les specs correspondantes.

## Ce que 5.1a livre avant 5.1b (et que 5.1b remplace)

- Un refus définitif reçu **en différé** (ou un scan trop ancien) s'affiche dans la **zone rouge** `scan-refus` de l'écran de sa Course (le dernier remplace le précédent), **sans liste ni persistance** : perdu au rechargement ou si l'écran n'est pas ouvert (5.1a RG10). Les entrées concernées sont supprimées de la file (état `EN_ATTENTE` / `EN_ENVOI` seulement). Pas d'aide vers la réintégration.
- Le compteur « N en attente » n'existe que sur l'écran de scan ; aucun total sur l'accueil bénévole.
- « Se déconnecter » est immédiat, sans confirmation ; les scans en attente restent dans IndexedDB.
- Purge à 24 h au démarrage : entrées de plus de 24 h supprimées (jeton effacé), sans affichage.
- Un refus **en direct** (première tentative, jamais en attente) s'affiche en zone rouge : inchangé par 5.1b.

## Ce qui est déjà livré, donc non respécifié

- **API** : `POST /api/benevole/courses/{courseId}/passages`, tous les refus et leurs extensions (`dossard`, `pseudo`, `numeroBoucle`, `statut`…) : 4.4a §4 et 4.6 §4. Libellés de motif par `code` : 4.6 RG8 (réutilisés tels quels). Pas de dossard pour `JETON_INCONNU` (4.6 RG9).
- **Front (5.1a)** : service de file (stockage IndexedDB avec repli mémoire, `compteId` par entrée, cycle d'envoi sur toute page, signal `enAttente(courseId)`), classification des réponses, jeton supprimé à la réponse, âge > 24 h non envoyé, purge au démarrage.
- **Front (existant)** : `SessionService.deconnecter()`, bouton « Se déconnecter » de l'en-tête, accueil bénévole `/benevole`, `.message--avertissement`, `--cible-min`.
- **Réintégration** : action admin livrée en 4.12 (aucun changement).
- E2E : `aide-scan.ts` (`preparerCourseDeScan`, `ouvrirEcranDeScan`, `scannerParEntree`, `lireJetonQrEnBase`, `placerStatutInscriptionEnBase`, `placerStatutCourseEnBase`), `aide-horloge.ts`, projets `chromium` et `horloge`.

**Taille estimée (production hors tests) : ~100 lignes, tout en front** (état `A_VERIFIER` et persistance ~20, liste et lignes ~40, totaux accueil ~15, confirmation de déconnexion ~30), moins le code provisoire de 5.1a RG10 qu'il remplace.

## 1. Périmètre

**Inclus**
- Liste persistante « Scans à vérifier » par Course, bouton « Compris », aide vers la réintégration (RG1).
- Complément « dont M à vérifier » du message de fin d'envoi (RG2).
- Totaux « en attente » et « à vérifier » sur l'accueil bénévole (RG3).
- Confirmation de déconnexion avec scans en attente (RG4).
- Persistance et isolation par Compte de la liste, purge à 24 h devenue « à vérifier » (RG5).
- Charte des nouveaux éléments (RG6).

**Exclu**
- Tout ce qui est dans 5.1a : file, envoi, tentatives, compteur par Course, 401/403, stockage indisponible, etc.
- Service worker, manifeste, ouverture hors ligne : **5.2**.
- Tolérance serveur pour un scan fait dans les temps mais reçu après l'abandon automatique ou la fin de la Course (décision de l'utilisateur : refus définitif, rattrapage par la réintégration admin 4.12).
- Réintégration depuis l'écran de scan : l'aide renvoie seulement vers un administrateur ; aucun lien ni action.
- Son ou vibration, export de la liste, rejeu manuel d'un scan refusé : exclus.

## 2. Règles de gestion

- **RG1** : liste « Scans à vérifier » (remplace 5.1a RG10). Quand une entrée qui a été `EN_ATTENTE` reçoit un refus définitif (5.1a RG5), ou est trop ancienne (âge > 86 400 000 ms, 5.1a RG3), elle passe à l'état `A_VERIFIER` (jamais retentée), **son jeton est supprimé**, et elle reste en IndexedDB avec : `code`, `motif`, `detail`, `dossard`, `pseudo`, `numeroBoucle` (selon ce que l'API a fourni ; jamais de jeton). La zone rouge `scan-refus` n'est **plus** utilisée pour un refus différé (elle reste pour les refus en direct). La liste `scan-a-verifier` (de la Course de l'écran ; absente si vide) affiche `scan-a-verifier-compteur` « N à vérifier » et une ligne `scan-a-verifier-ligne` par entrée `A_VERIFIER` :
  - `scan-a-verifier-motif` : libellé par `code` (table de 4.6 RG8 réutilisée) ; « Scan trop ancien » pour l'âge > 24 h ; « Scan refusé » pour un 4xx générique ;
  - « Dossard N » et pseudo si l'API les a fournis (jamais pour `JETON_INCONNU`) ; « Boucle B » si fournie ;
  - le `detail` de l'API tel quel ou, pour « Scan trop ancien », « Ce scan date de plus de 24 heures et n'a pas été envoyé. » ;
  - bouton `scan-a-verifier-compris` « Compris » qui supprime la ligne de la liste et de IndexedDB ;
  - pour `INSCRIPTION_NON_EN_COURSE`, `COURSE_TERMINEE` et `TEMPS_DE_COURSE_ECOULE`, `scan-a-verifier-aide` : « Si ce scan a été fait dans les temps, demandez à un administrateur de réintégrer le coureur. » (la réintégration est l'action admin de 4.12 ; le serveur ne rouvre jamais une Boucle terminée).

  La liste est **persistante** : elle survit au rechargement et à la déconnexion jusqu'à « Compris ». Un refus obtenu **en direct** et affiché dans la zone de retour n'est **pas** dupliqué dans la liste. La zone rouge du dernier scan de 5.1a n'est donc plus remplacée par un refus différé, car ceux-ci vont désormais dans la liste « Scans à vérifier ». Si le refus différé concerne le dernier scan de l'écran, la zone de retour est vidée (au lieu de rester sur « Scan gardé sur l'appareil »). Une liste ne contient que les entrées du Compte connecté et de la Course de l'écran.
- **RG2** : message de fin d'envoi (complète 5.1a RG9). Quand la file passe de N >= 1 à 0 et qu'au moins un refus différé est survenu dans le cycle, `scan-message-envoyes` devient « N scan(s) envoyé(s), dont M à vérifier » (N = succès, M = entrées passées `A_VERIFIER` pendant le cycle). Si N = 0 et M >= 1 : « M scan(s) à vérifier ». Sans refus, inchangé (« N scan(s) envoyé(s) »). Accords tels que livrés : « 1 scan envoyé, dont 1 à vérifier », « 2 scans envoyés, dont 1 à vérifier » ; « 1 scan à vérifier » et « 2 scans à vérifier » quand rien n'est parti (« scan » et « envoyé » prennent le pluriel dès 2 ; « à vérifier » est invariable).
- **RG3** : totaux sur l'accueil bénévole (`/benevole`). `benevole-en-attente` « N scan(s) en attente » et `benevole-a-verifier` « N à vérifier » : totaux du **Compte connecté**, toutes Courses confondues, chacun **absent à 0**, en `.message--avertissement`. Ils se relisent dans la base locale à chaque cycle d'envoi de 5.1a. Le reste de l'accueil est inchangé.
- **RG4** : confirmation de déconnexion. Si le Compte a au moins une entrée `EN_ATTENTE`, « Se déconnecter » (en-tête) ne déconnecte pas tout de suite : `entete-deconnexion-confirmation` (bloc `role="alert"`) « N scan(s) ne sont pas encore envoyés. Si vous vous déconnectez, ils resteront sur cet appareil et ne partiront qu'à votre prochaine connexion avec ce compte. » avec `entete-deconnexion-annuler` « Rester connecté » (focus initial) et `entete-deconnexion-confirmer` « Me déconnecter quand même ». Au singulier (N = 1) : « 1 scan n'est pas encore envoyé. Si vous vous déconnectez, il restera sur cet appareil et ne partira qu'à votre prochaine connexion avec ce compte. » Sans entrée `EN_ATTENTE`, la déconnexion est inchangée (immédiate). `A_VERIFIER` ne bloque pas la déconnexion. Après confirmation, la file n'est **pas purgée** (5.1a RG12).
- **RG5** : persistance, isolation, purge. La liste `A_VERIFIER` est dans IndexedDB, isolée par `compteId` (un autre Compte sur le même appareil ne voit rien et ne peut pas la modifier) et survit à la déconnexion. Au démarrage de l'application (complète 5.1a RG12), toute entrée `EN_ATTENTE` de plus de 24 h est traitée comme « trop ancienne » : jeton supprimé ; pour le Compte **courant** elle devient `A_VERIFIER` (« Scan trop ancien »), pour un **autre** Compte elle est supprimée. Une entrée d'un Compte absent au démarrage et déjà passée `A_VERIFIER` est conservée telle quelle jusqu'à « Compris ». En stockage indisponible (repli mémoire, 5.1a RG13), la liste existe en mémoire avec les mêmes règles et est perdue au rechargement.
- **RG6** : charte (`docs/design.md` §11 « Scan » et accueil bénévole, à compléter ainsi que `docs/design-apercu.html`). Compteur et liste en `--taille-moyenne`, mots coupés (pseudo de 30 caractères sans débordement), pas de défilement horizontal à 320 px (texte à 200 % compris), cibles de 44 px (`--cible-min`) pour « Compris », « Rester connecté » et « Me déconnecter quand même », focus visible, libellé + pictogramme CSS (jamais la couleur seule), aucune couleur en dur, aucune animation.

## 3. Cas limites

- **Scan fait dans les temps, reçu après l'abandon automatique, la fin de la Course ou la dernière Boucle** : refus définitif (`INSCRIPTION_NON_EN_COURSE`, `COURSE_TERMINEE`, `TEMPS_DE_COURSE_ECOULE`), ligne « à vérifier » avec l'aide de réintégration (RG1). Aucun Passage n'est créé ; coureur resté en `ABANDON` ou Course `TERMINEE` ; rattrapage par l'admin (4.12). Décision de l'utilisateur, aucune tolérance serveur.
- **Scénario d'abandon automatique : au moins trois coureurs** : avec un seul coureur et aucun Passage valide sur la Boucle 1, la règle des vainqueurs partagés (4.10) le déclare `VAINQUEUR` et la Course `TERMINEE` ; il ne passe pas en `ABANDON`. Tout scénario où un scan arrive après l'abandon automatique d'un coureur doit donc compter au moins trois coureurs, dont deux ont un Passage valide sur la Boucle 1 (la Course continue), comme 5.1a CA9.
- **Dernière Boucle autorisée, course complète, aucun passage sur une Boucle (vainqueurs partagés)** : décisions côté serveur (4.8 à 4.10) ; un scan en file qui arrive après la fin devient une ligne « à vérifier ».
- **Coureur réintégré** (4.12) : un scan en file de la Boucle dont il a un Passage `CORRECTION` est refusé `PASSAGE_DEJA_ENREGISTRE` ; ligne « à vérifier », sans gravité.
- **Course non démarrée** (`COURSE_NON_DEMARREE`) et **bénévole retiré de la Course** (`BENEVOLE_NON_AFFECTE`) : lignes « à vérifier », sans aide de réintégration.
- **Inscription sans aucun Passage** : scan de Boucle 2 sans Boucle 1 : `PASSAGE_PRECEDENT_MANQUANT`, ligne « à vérifier ».
- **Passage `CORRECTION` vs `SCAN`** : la file ne crée que des `SCAN` ; seul le refus d'un `SCAN` entre dans la liste.
- **Plusieurs Courses en parallèle** : la liste d'un écran ne montre que sa Course ; l'accueil totalise toutes les Courses ; un refus d'une Course dont aucun écran n'est ouvert est quand même gardé.
- **Plusieurs refus dans un même cycle** : une ligne chacun ; un refus n'interrompt pas la file (5.1a RG4).
- **Même Compte, deux onglets** : la liste est lue dans IndexedDB ; « Compris » dans un onglet la retire dans l'autre au prochain rafraîchissement local (cycle ou rechargement).
- **Déconnexion avec seulement des lignes « à vérifier »** : pas de confirmation. **Session expirée (401)** avec scans en attente : file en pause (5.1a RG11) ; la confirmation de déconnexion ne s'applique qu'à l'action « Se déconnecter ».
- **Rôle insuffisant / bénévole non affecté** : redirection avant tout scan (4.4b) ; l'accueil et l'en-tête ne montrent des totaux qu'au Compte `BENEVOLE` connecté.

## 4. Contrat d'API

**Aucun nouvel endpoint, aucun changement de contrat.** Source de vérité : `increment-4.4a.md` §4 et `increment-4.6.md` §4, déjà consommés par 5.1a. Éléments utiles à 5.1b :

### POST /api/benevole/courses/{courseId}/passages (inchangé)
- Rôle `BENEVOLE` affecté ; CSRF ; réponses `Cache-Control: no-store`.
- Corps `PassageRequete` : `jetonQr`, `identifiantScan` (UUID, clé d'idempotence), `ageDuScanMillisecondes` (entier 0 à 86 400 000).
- Refus `ProblemDetail` stockés dans la ligne « à vérifier » : `code`, `detail` et extensions `dossard` (entier), `pseudo` (texte), `numeroBoucle` (entier) lorsqu'ils sont présents. Codes concernés (refus définitifs) : 400 `VALIDATION_ECHOUEE`, `CORPS_ILLISIBLE` ; 403 `BENEVOLE_NON_AFFECTE` ; 404 `JETON_INCONNU` ; 409 `IDENTIFIANT_SCAN_DEJA_UTILISE`, `COURSE_NON_DEMARREE`, `COURSE_TERMINEE`, `HEURE_DE_DEPART_INCONNUE`, `INSCRIPTION_NON_EN_COURSE`, `SCAN_ANTERIEUR_AU_DEPART`, `TEMPS_DE_COURSE_ECOULE`, `PASSAGE_DEJA_ENREGISTRE`, `PASSAGE_PRECEDENT_MANQUANT`, `PASSAGE_TROP_RAPPROCHE` ; autre 4xx générique. Pannes, 401 et 403 `ACCES_REFUSE` / `CSRF_INVALIDE` : traitées par 5.1a, jamais dans la liste.

### Endpoints existants
`GET /api/benevole/courses`, `GET /api/comptes/moi`, `POST` de déconnexion : inchangés. Aucune variable d'environnement, aucun changement de `docker-compose*.yml`, de `.env.example`, de migration.

### Front (indicatif)
Le service de file de 5.1a gagne l'état `A_VERIFIER` et les signaux `aVerifier(courseId)`, `totalEnAttente()`, `totalAVerifier()` ; l'en-tête interroge `totalEnAttente()` avant de déconnecter. Noms libres, comportement fixé par RG1 à RG6.

## 5. Écrans

### Scan d'un passage (`/benevole/courses/:courseId/scan`, modifié)
- **Affiché en plus** : liste `scan-a-verifier` (`scan-a-verifier-compteur`, lignes `scan-a-verifier-ligne` avec `scan-a-verifier-motif`, dossard, pseudo, Boucle, détail, `scan-a-verifier-aide`, `scan-a-verifier-compris`) ; `scan-message-envoyes` complété « dont M à vérifier ». Le refus différé n'utilise plus la zone rouge ; s'il concerne le dernier scan de l'écran, la zone de retour est vidée.
- **Actions** : « Compris ».
- **Messages** : « N à vérifier » ; « Scan trop ancien » ; « Ce scan date de plus de 24 heures et n'a pas été envoyé. » ; « Si ce scan a été fait dans les temps, demandez à un administrateur de réintégrer le coureur. » ; « N scan(s) envoyé(s), dont M à vérifier » ; motifs et `detail` de 4.4a/4.6.

### Accueil bénévole (`/benevole`, modifié)
- `benevole-en-attente` « N scan(s) en attente » et `benevole-a-verifier` « N à vérifier » (totaux du Compte, chacun absent à 0), en `.message--avertissement`. Reste inchangé.

### En-tête (modifié)
- Confirmation de déconnexion `entete-deconnexion-confirmation` / `-annuler` / `-confirmer` (RG4), seulement pour un Compte avec des scans en attente.
- **Messages** : « N scan(s) ne sont pas encore envoyés. Si vous vous déconnectez, ils resteront sur cet appareil et ne partiront qu'à votre prochaine connexion avec ce compte. » (singulier : « 1 scan n'est pas encore envoyé. Si vous vous déconnectez, il restera sur cet appareil et ne partira qu'à votre prochaine connexion avec ce compte. ») ; « Rester connecté » ; « Me déconnecter quand même ».

## 6. Critères d'acceptation

Valeurs de référence : comptes de 3.1 (`Léo` `BENEVOLE` affecté, `Marc` `BENEVOLE`, `Nadia` `ADMIN`) ; `Course E2E <suffixe>` = X (**60 min**, M = 5, datée du jour, coureurs de dossards 1, 2, 3, Léo affecté, démarrée par l'API) ; jetons `j1`, `j2`, `j3` lus par `lireJetonQrEnBase`. Réseau coupé par `context.setOffline(true)` ou `page.route` ; attentes par `page.clock`. Pas de tests unitaires front ; l'API ne change pas, donc aucun test d'intégration (le contrat est verrouillé par 5.1a CA1).

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné `context.setOffline(true)` et Léo qui scanne `j1`, puis de nouveau `j1` (deux `identifiantScan`), puis `j2` ; puis retour en ligne : `j1` (premier) -> 201, `j1` (second) -> 409 `PASSAGE_DEJA_ENREGISTRE` **non rejoué** (une seule requête pour cette entrée, observé sur 10 s d'horloge avancée), `j2` -> 201 ; `scan-a-verifier` visible avec **une** ligne : `scan-a-verifier-motif` « Déjà passé », « Dossard 1 », pseudo, « Boucle 1 », `detail` « Ce coureur a déjà un passage sur la boucle 1. », pas d'aide de réintégration ; `scan-a-verifier-compteur` « 1 à vérifier » ; la zone rouge `scan-refus` **n'est pas** affichée pour ce refus ; `scan-message-envoyes` « 2 scans envoyés, dont 1 à vérifier » ; compteur d'attente absent ; deux lignes `passage` en base ; après rechargement la ligne est toujours là ; « Compris » la supprime (et elle ne revient pas au rechargement) ; l'entrée refusée dans IndexedDB ne contient plus de jeton ; un refus **en direct** (`j1` scanné en ligne de nouveau) s'affiche en `scan-refus` « Déjà passé » **sans** nouvelle ligne dans la liste (RG1, RG2, RG5) | E2E |
| CA2 | Étant donné un scan de `j1` mis en file hors ligne puis : (a) l'Inscription passée `ABANDON` en base (`placerStatutInscriptionEnBase`) ; (b) la Course passée `TERMINEE` en base ; (c) un jeton inconnu `abc` scanné hors ligne ; (d) la Course repassée `EN_PREPARATION`; puis retour en ligne, alors chaque cas donne une ligne « à vérifier » avec le motif et le `detail` de l'API ((a) « Coureur en abandon » / « Ce coureur n'est plus en course. » ; (b) « Course terminée » ; (c) « Jeton inconnu » **sans** dossard ni pseudo ni Boucle ; (d) « Course non démarrée »), l'aide `scan-a-verifier-aide` « Si ce scan a été fait dans les temps, demandez à un administrateur de réintégrer le coureur. » présente pour (a) et (b) seulement, aucun Passage créé, aucune relance (une requête chacun), jeton effacé de l'entrée (RG1, §3) | E2E |
| CA3 | Étant donné la stack `e2e` (projet `horloge`), Course de **1 min**, M = 5, **trois coureurs** (dossards 1, 2, 3 : avec un seul coureur, la règle des vainqueurs partagés de 4.10 le déclarerait `VAINQUEUR` et terminerait la Course) et Léo affecté, démarrée à `t0` : en ligne, Léo scanne les coureurs 1 et 2 sur la Boucle 1 (201) ; hors ligne, il scanne le coureur 3 à `t0 + 20 s` ; `avancerHorloge(PT90S)` : au départ de la Boucle 2 le coureur 3, sans Passage reçu sur la Boucle 1, passe `ABANDON` (`HORS_DELAI`, 4.8) tandis que les coureurs 1 et 2 restent `EN_COURSE` et que la Course reste `EN_COURS` ; retour en ligne : refus 409 `INSCRIPTION_NON_EN_COURSE`, ligne « à vérifier » « Coureur en abandon » **avec** l'aide de réintégration, aucun Passage créé pour le coureur 3. **Puis** `Nadia` réintègre le coureur 3 depuis le pilotage (4.12) : il est `EN_COURSE` avec ses Passages `CORRECTION` ; la ligne reste dans la liste jusqu'à « Compris » (RG1, §3) | E2E |
| CA4 | Étant donné un scan de `j1` mis en file hors ligne et `page.clock` avancé de **25 h** (âge calculé > 86 400 000), retour en ligne sans rechargement : **aucune requête** pour cette entrée ; ligne « Scan trop ancien » avec « Ce scan date de plus de 24 heures et n'a pas été envoyé. », sans dossard ni aide de réintégration ; jeton supprimé ; au rechargement après 25 h, une entrée `EN_ATTENTE` de plus de 24 h d'un **autre** Compte est supprimée de la base, celle de Léo devient une ligne « à vérifier » (RG1, RG5) | E2E |
| CA5 | Étant donné deux Courses X et W affectées à Léo : hors ligne, un scan sur X et deux sur W ; `/benevole` affiche `benevole-en-attente` « 3 scans en attente » et `benevole-a-verifier` absent ; retour en ligne depuis `/benevole` : `benevole-en-attente` disparaît ; un jeton de W scanné hors ligne dans X, puis retour en ligne : `benevole-a-verifier` « 1 à vérifier » sur `/benevole`, la ligne apparaît sur l'écran de X seulement (pas sur celui de W) ; « Compris » sur X : `benevole-a-verifier` disparaît ; connecté en `Marc`, aucun des deux totaux (RG1, RG3, RG5) | E2E |
| CA6 | Étant donné deux scans en attente, quand Léo clique « Se déconnecter » dans l'en-tête, alors `entete-deconnexion-confirmation` « 2 scans ne sont pas encore envoyés. Si vous vous déconnectez, ils resteront sur cet appareil et ne partiront qu'à votre prochaine connexion avec ce compte. » apparaît avec le focus sur « Rester connecté » ; « Rester connecté » ferme le bloc, Léo reste connecté et les scans restent en file ; « Me déconnecter quand même » déconnecte (écran de connexion), les entrées restent dans IndexedDB ; reconnecté, Léo retrouve « 2 en attente » et ils partent ; avec **un** scan en attente le texte est au singulier : « 1 scan n'est pas encore envoyé. Si vous vous déconnectez, il restera sur cet appareil et ne partira qu'à votre prochaine connexion avec ce compte. » ; sans scan en attente, « Se déconnecter » déconnecte immédiatement ; avec seulement des lignes « à vérifier », aucune confirmation (RG4) | E2E |
| CA7 | Étant donné une ligne « à vérifier » de Léo, quand Léo se déconnecte puis `Marc` se connecte sur le même appareil, alors Marc ne voit ni liste ni total ; Léo reconnecté retrouve la ligne sur l'écran de sa Course et le total sur `/benevole` ; IndexedDB indisponible (`addInitScript`) : un refus différé crée la ligne en mémoire, perdue au rechargement, et `scan-avertissement-stockage` reste affiché (RG5) | E2E |
| CA8 | Étant donné l'écran de scan avec une ligne « à vérifier » (pseudo de 30 caractères) et `/benevole` avec les deux totaux, à 375 px puis 320 px (texte normal puis 200 %) : aucun défilement horizontal ; `scan-a-verifier-compris`, « Rester connecté » et « Me déconnecter quand même » de 44 px au moins, atteignables et visibles au clavier ; pseudo coupé sans débordement ; `entete-deconnexion-confirmation` en `role="alert"` ; libellé + pictogramme (aucun état par la seule couleur) ; contrastes AA ; `prefers-reduced-motion` : aucune animation ; pas de couleur en dur (RG6) | E2E |
| CA9 | Étant donné le « Tu testes » de la roadmap : Léo scanne `j1` en ligne (succès), puis hors ligne scanne `j1` de nouveau, puis repasse en ligne -> le refus apparaît dans « Scans à vérifier » ; hors ligne, il scanne `j2` puis clique « Se déconnecter » -> confirmation affichée (RG1, RG4) | E2E |

Répartition : unitaire 0 (pas de tests unitaires front, aucun changement de domaine), intégration 0 (aucun changement d'API ; contrat verrouillé par 5.1a CA1), E2E 9 (CA1 à CA9). Total 9.

Couverture des RG : RG1 CA1/CA2/CA3/CA4/CA5/CA9, RG2 CA1, RG3 CA5, RG4 CA6/CA9, RG5 CA1/CA4/CA5/CA7, RG6 CA8. Écrans : « Scan d'un passage » CA1 à CA4, CA7, CA8 ; « Accueil bénévole » CA5, CA8 ; « En-tête » CA6, CA9 (tous en E2E).

### Stratégie E2E
- Réutiliser les helpers de 5.1a (courses créées, affectées, démarrées par l'API ; jetons par `lireJetonQrEnBase` ; statuts par `placerStatutInscriptionEnBase` / `placerStatutCourseEnBase`). Lecture d'IndexedDB par `page.evaluate`.
- Projet `chromium`, Courses de **60 min** sauf CA3 (projet `horloge`, un seul worker, **trois coureurs**). `page.clock` pour l'âge de 25 h ; aucune attente réelle longue.
- **Ne pas recharger la page hors ligne** (avant 5.2) : recharger en ligne.

## 7. Tester à la main

Prérequis : 5.1a livrée et validée. `docker compose up -d --build` (services sains), http://localhost. **Base neuve** de préférence (`docker compose down -v`), puis `./scripts/donnees-demo.sh` (inchangé). Comptes : `Léo` et `Marc` (`mot-de-passe-benevole-1`), `Nadia` (`mot-de-passe-admin-1`), `Karim`, `Sophie`, `Tom` (`un-mot-de-passe-12`). « Backyard du jour » : boucles de **1 min**, 5 Boucles max, Karim 1, Sophie 2, Tom 3, Léo affecté ; délai anti-doublon 15 s. Jetons : `docker compose exec base psql -U backyard -d backyard -c "select i.dossard, i.jeton_qr from inscription i join course c on c.id = i.course_id where c.nom = 'Backyard du jour' order by i.dossard"` (à copier dans un fichier texte). Les Boucles sont courtes : préparer les jetons avant de démarrer. **Outils de développement** (F12) > Réseau > « Sans limitation » > « Hors connexion » pour couper et rétablir le réseau ; ne **pas** recharger la page hors ligne.

1. **Démarrer** : en `Nadia`, fiche de « Backyard du jour » > « Démarrer la course » > « Démarrer maintenant ». Garder le pilotage dans un autre onglet.
2. **Doublon hors ligne** (Boucle 2, au moins 1 min après le démarrage, après avoir fait passer Karim en Boucle 2) : en `Léo` > « Scanner », couper le réseau, coller le jeton de Karim **deux fois** : « 2 en attente » ; rétablir : « 1 scan envoyé, dont 1 à vérifier » ; la liste « Scans à vérifier » montre « 1 à vérifier », « Déjà passé », « Dossard 1 », « Karim », « Boucle 2 ». Aucun bloc rouge. Recharger la page : la ligne est toujours là ; « Compris » la retire, et elle ne revient pas au rechargement.
3. **Scan trop tardif** (comportement attendu, pas un bogue) : la Course doit garder au moins deux autres coureurs en course (avec un seul coureur sans Passage, la règle des vainqueurs partagés le déclarerait vainqueur et terminerait la Course). Pendant la Boucle 1, scanner Karim et Tom **en ligne** ; puis couper le réseau, scanner Sophie, **attendre plus de 70 secondes** hors ligne (Sophie passe en abandon au départ de la Boucle 2, Karim et Tom restent en course), rétablir : ligne « Coureur en abandon » avec « Si ce scan a été fait dans les temps, demandez à un administrateur de réintégrer le coureur. ». Aucun Passage n'est créé. En `Nadia`, réintégrer Sophie depuis le pilotage (4.12) : elle est de nouveau en course.
4. **Totaux de l'accueil** : couper le réseau, scanner Tom, aller sur l'accueil bénévole (`/benevole`, par le lien de l'application sans recharger) : « 1 scan en attente » et, si une ligne « à vérifier » existe, « N à vérifier ». Rétablir le réseau : le total « en attente » disparaît.
5. **Confirmation de déconnexion** : couper le réseau, scanner, cliquer « Se déconnecter » : « 1 scan n'est pas encore envoyé… » avec « Rester connecté » (focus) ; « Rester connecté » annule ; « Me déconnecter quand même » déconnecte. Se connecter en `Marc` (réseau rétabli) : ni liste ni total ; se reconnecter en `Léo` : le scan en attente repart et la liste « à vérifier » éventuelle est toujours là. Sans scan en attente, « Se déconnecter » est immédiat.
6. **Téléphone** (facultatif) : largeur 375 px : liste, totaux et boutons occupent la largeur, cibles de 44 px.

## 8. Points ouverts

À remonter à l'utilisateur ; chaque point a une position par défaut qui permet de commencer.

1. **Dépendance et ordre de livraison** : 5.1b ne peut pas être livrée sans 5.1a. Entre les deux, les refus différés sont fragiles (5.1a Points ouverts n°2). Position par défaut : enchaîner 5.1b immédiatement après la validation de 5.1a.
2. **Scan fait dans les temps mais reçu après l'abandon automatique ou la fin de la Course** : **tranché par l'utilisateur** : refus définitif, aide « demandez à un administrateur de réintégrer le coureur », rattrapage par la réintégration admin (4.12), sans tolérance serveur.
3. **Conservation d'une ligne « à vérifier »** : sans limite de durée, jusqu'à « Compris » (pas de purge automatique, pas de plafond de nombre). Position par défaut : retenu ; à revoir si des listes très longues apparaissent en recette terrain.
4. **Confirmation de déconnexion et session expirée** : sur un 401, le bénévole est redirigé vers la connexion sans confirmation (la session n'existe déjà plus) ; ses scans restent en file (5.1a RG11). Position par défaut : retenu.
5. **Aide de réintégration** : texte seul, sans lien vers le pilotage, car le bénévole n'a pas accès à l'action admin. Position par défaut : retenu.
