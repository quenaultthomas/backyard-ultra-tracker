package fr.backyard.tracker.courses.domaine;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.stream.LongStream;

/**
 * Racine d'agrégat : une édition de backyard. Une Course ne se crée que par {@link #declarer} et ne change que par
 * {@link #modifier}, qui appliquent les mêmes règles de saisie : aucune Course invalide n'existe en mémoire.
 * Les bénévoles affectés (identifiants de Comptes) ne changent que par {@link #affecterBenevoles}. Le passage à
 * EN_COURS et l'heure de départ ne viennent que de {@link #demarrer}.
 */
public final class Course {

    /** Ordre de la liste des Courses : date décroissante, puis nom croissant sans tenir compte de la casse, puis id. */
    public static final Comparator<Course> ORDRE_DE_LISTE = Comparator.comparing(Course::date).reversed()
            .thenComparing(Course::nom, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(course -> course.id().toString());

    /** Délai minimal entre deux Passages d'une même Inscription, avant plafonnement par la durée de Boucle. */
    public static final Duration DELAI_MINIMAL_ENTRE_PASSAGES = Duration.ofMinutes(2);

    private final UUID id;
    private final String nom;
    private final LocalDate date;
    private final StatutCourse statut;
    private final ParametresBoucle parametresBoucle;
    private final int nombreMaxParticipants;
    private final int nombreMaxBoucles;
    private final Instant demarreeLe;
    private Set<UUID> benevolesAffectes;

    private Course(UUID id, String nom, LocalDate date, StatutCourse statut, ParametresBoucle parametresBoucle,
                   int nombreMaxParticipants, int nombreMaxBoucles, Collection<UUID> benevolesAffectes,
                   Instant demarreeLe) {
        this.id = id;
        this.nom = nom;
        this.date = date;
        this.statut = statut;
        this.parametresBoucle = parametresBoucle;
        this.nombreMaxParticipants = nombreMaxParticipants;
        this.nombreMaxBoucles = nombreMaxBoucles;
        this.benevolesAffectes = ensembleNonModifiable(benevolesAffectes);
        this.demarreeLe = demarreeLe;
    }

    /**
     * Déclare une nouvelle Course EN_PREPARATION, d'identifiant généré.
     *
     * @param aujourdhui date du jour, calculée par l'appelant à partir de l'horloge
     * @throws DonneesCourseInvalidesException toutes les violations, au plus une par champ, dans l'ordre des champs
     */
    public static Course declarer(String nom, LocalDate date, Integer distanceBoucleMetres, Integer dureeBoucleMinutes,
                                  Integer denivelePositifBoucleMetres, Integer nombreMaxParticipants,
                                  Integer nombreMaxBoucles, LocalDate aujourdhui) {
        String nomSaisi = sansEspacesAutour(nom);
        DeclarationCourse declaration = new DeclarationCourse(aujourdhui).verifierNom(nomSaisi).verifierDate(date);
        verifierParametresEtLimites(declaration, distanceBoucleMetres, dureeBoucleMinutes,
                denivelePositifBoucleMetres, nombreMaxParticipants, nombreMaxBoucles);
        return new Course(UUID.randomUUID(), nomSaisi, date, StatutCourse.EN_PREPARATION,
                new ParametresBoucle(distanceBoucleMetres, dureeBoucleMinutes, denivelePositifBoucleMetres),
                nombreMaxParticipants, nombreMaxBoucles, Set.of(), null);
    }

    /**
     * Remplace tous les champs modifiables d'une Course EN_PREPARATION, avec les règles de saisie de la déclaration,
     * sauf qu'une date inchangée est toujours acceptée. La Course renvoyée garde l'identifiant, le statut et les
     * bénévoles affectés ; celle-ci n'est pas affectée.
     *
     * @param aujourdhui date du jour, calculée par l'appelant à partir de l'horloge
     * @throws CourseNonModifiableException la Course n'est plus EN_PREPARATION (contrôlé avant la saisie)
     * @throws DonneesCourseInvalidesException toutes les violations, au plus une par champ, dans l'ordre des champs
     */
    public Course modifier(String nom, LocalDate date, Integer distanceBoucleMetres, Integer dureeBoucleMinutes,
                           Integer denivelePositifBoucleMetres, Integer nombreMaxParticipants,
                           Integer nombreMaxBoucles, LocalDate aujourdhui) {
        autoriserModification();
        String nomSaisi = sansEspacesAutour(nom);
        DeclarationCourse declaration = new DeclarationCourse(aujourdhui).verifierNom(nomSaisi)
                .verifierNouvelleDate(date, this.date);
        verifierParametresEtLimites(declaration, distanceBoucleMetres, dureeBoucleMinutes,
                denivelePositifBoucleMetres, nombreMaxParticipants, nombreMaxBoucles);
        return new Course(id, nomSaisi, date, statut,
                new ParametresBoucle(distanceBoucleMetres, dureeBoucleMinutes, denivelePositifBoucleMetres),
                nombreMaxParticipants, nombreMaxBoucles, benevolesAffectes, demarreeLe);
    }

    /**
     * Démarre une Course EN_PREPARATION le jour de sa date et avec au moins une Inscription : la Course renvoyée est
     * EN_COURS, partie à {@code maintenant} tronqué à la seconde (départ de la Boucle 1) ; celle-ci n'est pas
     * affectée. Contrôles dans l'ordre : statut, date, inscrits. Unique définition de cette règle.
     *
     * @param maintenant instant du démarrage, lu une seule fois sur l'horloge par l'appelant
     * @param aujourdhui date du jour des Courses, calculée par l'appelant à partir du même instant
     * @param nombreInscriptions nombre d'Inscriptions de la Course, tous statuts confondus
     * @throws CourseNonDemarrableException la Course n'est plus EN_PREPARATION
     * @throws CourseHorsDateException la date de la Course n'est pas celle du jour
     * @throws CourseSansInscritException la Course n'a aucune Inscription
     */
    public Course demarrer(Instant maintenant, LocalDate aujourdhui, int nombreInscriptions) {
        if (statut != StatutCourse.EN_PREPARATION) {
            throw new CourseNonDemarrableException(id);
        }
        if (!date.equals(aujourdhui)) {
            throw new CourseHorsDateException(id);
        }
        if (nombreInscriptions <= 0) {
            throw new CourseSansInscritException(id);
        }
        return new Course(id, nom, date, StatutCourse.EN_COURS, parametresBoucle, nombreMaxParticipants,
                nombreMaxBoucles, benevolesAffectes, maintenant.truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * Termine une Course EN_COURS : la Course renvoyée est TERMINEE, heure de départ et autres données conservées ;
     * celle-ci n'est pas affectée. Aucun instant de fin n'est stocké.
     *
     * @throws CourseNonTerminableException la Course n'est pas EN_COURS
     */
    public Course terminer() {
        if (statut != StatutCourse.EN_COURS) {
            throw new CourseNonTerminableException(id);
        }
        return new Course(id, nom, date, StatutCourse.TERMINEE, parametresBoucle, nombreMaxParticipants,
                nombreMaxBoucles, benevolesAffectes, demarreeLe);
    }

    /**
     * Seule une Course EN_COURS, avec ou sans heure de départ, accepte un abandon manuel. Unique contrôle de cette
     * règle.
     *
     * @throws CourseTermineePourAbandonException la Course est TERMINEE
     * @throws CourseNonDemarreeException la Course est EN_PREPARATION
     */
    public void autoriserAbandonManuel() {
        if (statut == StatutCourse.TERMINEE) {
            throw new CourseTermineePourAbandonException(id);
        }
        if (statut == StatutCourse.EN_PREPARATION) {
            throw new CourseNonDemarreeException(id);
        }
    }

    /**
     * Seule une Course EN_COURS, avec une heure de départ et dont le temps de la dernière Boucle n'est pas écoulé,
     * accepte une réintégration. Une Course TERMINEE n'est jamais rouverte. Unique contrôle de cette règle.
     *
     * @param maintenant instant lu une seule fois sur l'horloge par l'appelant
     * @throws CourseTermineePourReintegrationException la Course est TERMINEE
     * @throws CourseNonDemarreeException la Course est EN_PREPARATION
     * @throws HeureDeDepartInconnueException la Course est EN_COURS sans heure de départ
     * @throws TempsDeCourseEcouleException le temps de la dernière Boucle autorisée est écoulé
     */
    public void autoriserReintegration(Instant maintenant) {
        if (statut == StatutCourse.TERMINEE) {
            throw new CourseTermineePourReintegrationException(id);
        }
        if (statut == StatutCourse.EN_PREPARATION) {
            throw new CourseNonDemarreeException(id);
        }
        if (demarreeLe == null) {
            throw new HeureDeDepartInconnueException(id);
        }
        if (chronometrage(maintenant).orElseThrow().boucleMaxTerminee()) {
            throw new TempsDeCourseEcouleException();
        }
    }

    /**
     * Après un abandon manuel : une Course sans aucune Inscription EN_COURSE est terminée sans vainqueur (la Course
     * renvoyée est TERMINEE), sinon elle est renvoyée inchangée. Celle-ci n'est pas affectée. Unique définition.
     *
     * @param nombreInscriptionsEnCourse Inscriptions encore EN_COURSE dans cette Course après l'abandon
     * @throws CourseNonTerminableException la Course n'est pas EN_COURS alors qu'elle devrait se terminer
     */
    public Course terminerSiPlusAucunCoureurEnCourse(int nombreInscriptionsEnCourse) {
        return nombreInscriptionsEnCourse > 0 ? this : terminer();
    }

    /**
     * Remplace l'ensemble des bénévoles affectés par celui donné (doublons ignorés, ensemble vide permis).
     *
     * @throws CourseTermineeException la Course est TERMINEE ; l'ensemble précédent est conservé
     */
    public void affecterBenevoles(Collection<UUID> benevoles) {
        autoriserAffectation();
        this.benevolesAffectes = ensembleNonModifiable(benevoles);
    }

    /**
     * Les bénévoles d'une Course EN_PREPARATION ou EN_COURS sont modifiables. Unique contrôle de cette règle.
     *
     * @throws CourseTermineeException la Course est TERMINEE
     */
    public void autoriserAffectation() {
        if (statut == StatutCourse.TERMINEE) {
            throw new CourseTermineeException(id);
        }
    }

    /**
     * Seule une Course EN_PREPARATION est modifiable (champs de la Course comme logo). Unique contrôle de ce statut.
     *
     * @throws CourseNonModifiableException la Course n'est plus EN_PREPARATION
     */
    public void autoriserModification() {
        if (statut != StatutCourse.EN_PREPARATION) {
            throw new CourseNonModifiableException(id);
        }
    }

    /**
     * Seule une Course EN_PREPARATION peut être supprimée. Unique contrôle de cette règle.
     *
     * @throws CourseNonSupprimableException la Course est EN_COURS ou TERMINEE
     */
    public void verifierSuppressible() {
        if (statut != StatutCourse.EN_PREPARATION) {
            throw new CourseNonSupprimableException(id);
        }
    }

    /**
     * Seule une Course démarrée (EN_COURS ou TERMINEE) se pilote. Unique contrôle de cette règle.
     *
     * @throws CourseNonDemarreeException la Course est EN_PREPARATION
     */
    public void autoriserPilotage() {
        if (statut == StatutCourse.EN_PREPARATION) {
            throw new CourseNonDemarreeException(id);
        }
    }

    /**
     * Chronométrage de la Course à {@code maintenant}, à la milliseconde. La Boucle N commence à
     * {@code demarreeLe + (N - 1) x durée} ; à une frontière exacte, la nouvelle Boucle est courante. La Boucle
     * courante est plafonnée au nombre maximum de Boucles ; un instant antérieur au départ compte comme le départ.
     * Unique définition de ces calculs.
     *
     * @param maintenant instant lu une seule fois sur l'horloge par l'appelant
     * @return vide pour une Course qui n'est pas EN_COURS ou sans heure de départ
     */
    public Optional<Chronometrage> chronometrage(Instant maintenant) {
        if (statut != StatutCourse.EN_COURS || demarreeLe == null) {
            return Optional.empty();
        }
        Duration dureeBoucle = dureeBoucle();
        Instant instantCompte = instantCompte(maintenant);
        Duration dureeEcoulee = Duration.between(demarreeLe, instantCompte);
        int boucleCourante = (int) Math.min(dureeEcoulee.dividedBy(dureeBoucle) + 1, nombreMaxBoucles);
        Instant debutBoucle = debutBoucle(boucleCourante);
        Instant finBoucle = debutBoucle.plus(dureeBoucle);
        Duration tempsAvantFin = finBoucle.isAfter(instantCompte) ? Duration.between(instantCompte, finBoucle)
                : Duration.ZERO;
        boolean boucleMaxTerminee = dureeEcoulee.compareTo(dureeBoucle.multipliedBy(nombreMaxBoucles)) >= 0;
        return Optional.of(new Chronometrage(boucleCourante, boucleCourante == nombreMaxBoucles, boucleMaxTerminee,
                debutBoucle, finBoucle, tempsAvantFin, dureeEcoulee));
    }

    /**
     * Nombre de Boucles terminées à {@code maintenant}, à la milliseconde : la Boucle courante moins un, ou le nombre
     * maximum de Boucles une fois le temps de la dernière écoulé, d'après {@link #chronometrage}. Zéro pour une
     * Course qui n'est pas EN_COURS, sans heure de départ ou avant son départ. Jamais stocké.
     */
    public int bouclesTerminees(Instant maintenant) {
        return chronometrage(maintenant)
                .map(chrono -> chrono.boucleMaxTerminee() ? nombreMaxBoucles : chrono.boucleCourante() - 1)
                .orElse(0);
    }

    /**
     * Statistiques de la Course à {@code maintenant}. Boucles terminées : {@link #bouclesTerminees} hors TERMINEE ;
     * pour une Course TERMINEE, le plus grand numéro de Boucle des Passages (0 sans Passage), indépendant de
     * l'instant. Cumuls : Boucles terminées x paramètres de Boucle. Durée finale (TERMINEE seulement) : Boucles
     * terminées x durée de Boucle. Allure : moyenne pondérée des Passages SCAN (voir allureMoyenne). Unique définition
     * de ces calculs.
     *
     * @param maintenant instant lu une seule fois sur l'horloge par l'appelant
     * @param passages Passages (toute origine) de toutes les Inscriptions de la Course
     */
    public StatistiquesCourse statistiques(Instant maintenant, Collection<Passage> passages) {
        boolean terminee = statut == StatutCourse.TERMINEE;
        int boucles = terminee ? plusGrandeBoucle(passages) : bouclesTerminees(maintenant);
        OptionalLong dureeFinale = terminee ? OptionalLong.of(dureeBoucle().multipliedBy(boucles).toMillis())
                : OptionalLong.empty();
        return StatistiquesCourse.cumulees(boucles, parametresBoucle, allureMoyenne(passages), dureeFinale);
    }

    private static int plusGrandeBoucle(Collection<Passage> passages) {
        return passages.stream().mapToInt(Passage::numeroBoucle).max().orElse(0);
    }

    /**
     * Allure moyenne en secondes par kilomètre, pondérée par Passage : somme des temps mis par chaque Passage SCAN
     * depuis le départ de sa Boucle, divisée par (nombre de ces Passages x distance d'une Boucle), arrondie au
     * demi supérieur. Les Passages CORRECTION et les écarts négatifs sont exclus. Vide sans heure de départ, hors
     * Course démarrée ou sans Passage retenu.
     */
    private OptionalLong allureMoyenne(Collection<Passage> passages) {
        if (statut == StatutCourse.EN_PREPARATION || demarreeLe == null) {
            return OptionalLong.empty();
        }
        long[] ecarts = passages.stream().filter(passage -> passage.origine() == OriginePassage.SCAN)
                .mapToLong(this::ecartDepuisDebutDeBoucle).filter(ecart -> ecart >= 0).toArray();
        if (ecarts.length == 0) {
            return OptionalLong.empty();
        }
        long totalMillisecondes = LongStream.of(ecarts).sum();
        long metres = (long) ecarts.length * parametresBoucle.distanceMetres();
        return OptionalLong.of((2 * totalMillisecondes + metres) / (2 * metres));
    }

    private long ecartDepuisDebutDeBoucle(Passage passage) {
        Instant scanneLe = passage.scanneLe().orElseThrow();
        return Duration.between(debutBoucle(passage.numeroBoucle()), scanneLe).toMillis();
    }

    /** Départ de la Boucle {@code numeroBoucle} : {@code demarreeLe + (numeroBoucle - 1) x durée}. */
    private Instant debutBoucle(int numeroBoucle) {
        return demarreeLe.plus(dureeBoucle().multipliedBy(numeroBoucle - 1L));
    }

    private Duration dureeBoucle() {
        return Duration.ofMinutes(parametresBoucle.dureeMinutes());
    }

    /**
     * Seuls les bénévoles affectés à la Course y scannent des Passages ; l'affectation est relue à chaque scan.
     *
     * @throws BenevoleNonAffecteException le Compte n'est pas un bénévole affecté à cette Course
     */
    public void autoriserScanPar(UUID benevoleId) {
        if (!benevolesAffectes.contains(benevoleId)) {
            throw new BenevoleNonAffecteException();
        }
    }

    /**
     * Seule une Course EN_COURS avec une heure de départ accepte un Passage. Unique contrôle de cette règle.
     *
     * @throws CourseTermineePourPassageException la Course est TERMINEE
     * @throws CourseNonDemarreeException la Course est EN_PREPARATION
     * @throws HeureDeDepartInconnueException la Course est EN_COURS sans heure de départ
     */
    public void autoriserPassage() {
        if (statut == StatutCourse.TERMINEE) {
            throw new CourseTermineePourPassageException(id);
        }
        if (statut == StatutCourse.EN_PREPARATION) {
            throw new CourseNonDemarreeException(id);
        }
        if (demarreeLe == null) {
            throw new HeureDeDepartInconnueException(id);
        }
    }

    /**
     * Boucle d'un Passage scanné à {@code instantDuScan} : la Boucle courante à cet instant (le coureur qui franchit
     * la ligne pendant la Boucle N termine la Boucle N), d'après {@link #chronometrage}. Une fois le temps de la
     * dernière Boucle écoulé, aucune Boucle n'est calculable. Unique définition de cette règle.
     *
     * @throws ScanAnterieurAuDepartException le scan précède le départ de la Course
     * @throws TempsDeCourseEcouleException le temps de la dernière Boucle autorisée est écoulé
     * @see #autoriserPassage() pour les autres refus, contrôlés en premier
     */
    public int boucleDePassage(Instant instantDuScan) {
        autoriserPassage();
        Instant scan = instantDuScan.truncatedTo(ChronoUnit.MILLIS);
        if (scan.isBefore(demarreeLe)) {
            throw new ScanAnterieurAuDepartException(id);
        }
        Chronometrage chrono = chronometrage(scan).orElseThrow();
        if (chrono.boucleMaxTerminee()) {
            throw new TempsDeCourseEcouleException();
        }
        return chrono.boucleCourante();
    }

    /**
     * Délai minimal entre deux Passages d'une même Inscription : {@link #DELAI_MINIMAL_ENTRE_PASSAGES}, plafonné au
     * quart de la durée de Boucle pour ne jamais bloquer l'enchaînement des Boucles courtes. Unique définition.
     */
    public Duration delaiMinimalEntrePassages() {
        Duration quartDeBoucle = Duration.ofMinutes(parametresBoucle.dureeMinutes()).dividedBy(4);
        return quartDeBoucle.compareTo(DELAI_MINIMAL_ENTRE_PASSAGES) < 0 ? quartDeBoucle
                : DELAI_MINIMAL_ENTRE_PASSAGES;
    }

    /**
     * Anti-doublon : un scan de la Boucle N > 1 est refusé s'il suit de moins que {@link #delaiMinimalEntrePassages()}
     * le Passage scanné de la Boucle N-1 (comparaison stricte, à la milliseconde). Aucun contrôle en Boucle 1, sans
     * Passage précédent ou si celui-ci est une correction (sans instant). Unique définition de cette règle.
     *
     * @param passageDeLaBoucle Passage de l'Inscription sur une Boucle donnée, consulté pour la seule Boucle N-1
     * @throws PassageTropRapprocheException le délai minimal n'est pas écoulé
     */
    public void verifierDelaiEntrePassages(int numeroBoucle, Instant scanneLe,
                                           IntFunction<Optional<Passage>> passageDeLaBoucle) {
        if (numeroBoucle <= 1) {
            return;
        }
        passageDeLaBoucle.apply(numeroBoucle - 1)
                .flatMap(Passage::scanneLe)
                .ifPresent(precedent -> verifierEcart(numeroBoucle, Duration.between(precedent, scanneLe)));
    }

    private void verifierEcart(int numeroBoucle, Duration ecart) {
        Duration delai = delaiMinimalEntrePassages();
        if (ecart.compareTo(delai) < 0) {
            long millisRestantes = delai.minus(ecart).toMillis();
            throw new PassageTropRapprocheException(numeroBoucle, delai, Math.ceilDiv(millisRestantes, 1000L));
        }
    }

    /** Instant à la milliseconde, ramené au départ s'il le précède (anomalie d'horloge) : jamais de durée négative. */
    private Instant instantCompte(Instant maintenant) {
        Instant aLaMilliseconde = maintenant.truncatedTo(ChronoUnit.MILLIS);
        return aLaMilliseconde.isBefore(demarreeLe) ? demarreeLe : aLaMilliseconde;
    }

    /**
     * Une Course est ouverte aux inscriptions tant qu'elle est EN_PREPARATION, quelle que soit sa date. Unique
     * définition de cette règle.
     */
    public boolean estOuverte() {
        return statut == StatutCourse.EN_PREPARATION;
    }

    /**
     * Seule une Course ouverte ({@link #estOuverte()}) accepte une Inscription. Unique contrôle de cette règle.
     *
     * @throws CourseNonOuverteException la Course est EN_COURS ou TERMINEE
     */
    public void autoriserInscription() {
        if (!estOuverte()) {
            throw new CourseNonOuverteException();
        }
    }

    /**
     * Les coureurs d'une Course (dossard, pseudo, statut) ne sont publics qu'une fois la Course démarrée : jamais tant
     * qu'elle est EN_PREPARATION. Unique définition de cette règle.
     */
    public boolean exposeSesCoureursAuPublic() {
        return statut != StatutCourse.EN_PREPARATION;
    }

    /**
     * Une Inscription ne peut être supprimée (désinscription, annulation à la suppression du Compte) que tant que la
     * Course est EN_PREPARATION. Unique définition de cette règle.
     */
    public boolean permetDesinscription() {
        return statut == StatutCourse.EN_PREPARATION;
    }

    /**
     * Contrôle de {@link #permetDesinscription()} pour la désinscription par le coureur.
     *
     * @throws DesinscriptionImpossibleException la Course est EN_COURS ou TERMINEE
     */
    public void autoriserDesinscription() {
        if (!permetDesinscription()) {
            throw new DesinscriptionImpossibleException();
        }
    }

    /**
     * Une Course est complète quand son nombre d'Inscriptions, tous statuts confondus, atteint le nombre maximum de
     * participants. Unique définition de cette règle.
     */
    public boolean estComplete(int nombreInscriptions) {
        return nombreInscriptions >= nombreMaxParticipants;
    }

    /**
     * Places encore disponibles pour ce nombre d'Inscriptions, tous statuts confondus ; jamais négatif, même si la
     * Course compte plus d'Inscriptions que son maximum. Unique définition de ce calcul.
     */
    public int placesRestantes(int nombreInscriptions) {
        return Math.max(0, nombreMaxParticipants - nombreInscriptions);
    }

    /**
     * @throws CourseCompleteException la Course est complète pour ce nombre d'Inscriptions
     */
    public void verifierPlaceDisponible(int nombreInscriptions) {
        if (estComplete(nombreInscriptions)) {
            throw new CourseCompleteException();
        }
    }

    private static String sansEspacesAutour(String nom) {
        return nom == null ? "" : nom.trim();
    }

    private static void verifierParametresEtLimites(DeclarationCourse declaration, Integer distanceBoucleMetres,
                                                    Integer dureeBoucleMinutes, Integer denivelePositifBoucleMetres,
                                                    Integer nombreMaxParticipants, Integer nombreMaxBoucles) {
        declaration
                .verifierParametresBoucle(distanceBoucleMetres, dureeBoucleMinutes, denivelePositifBoucleMetres)
                .verifierLimites(nombreMaxParticipants, nombreMaxBoucles)
                .rejeterSiInvalide();
    }

    private static Set<UUID> ensembleNonModifiable(Collection<UUID> benevoles) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(benevoles));
    }

    /** Reconstitue une Course déjà enregistrée sans bénévole affecté, sans revalider la saisie. */
    public static Course reconstituer(UUID id, String nom, LocalDate date, StatutCourse statut,
                                      ParametresBoucle parametresBoucle, int nombreMaxParticipants,
                                      int nombreMaxBoucles) {
        return reconstituer(id, nom, date, statut, parametresBoucle, nombreMaxParticipants, nombreMaxBoucles,
                Set.of());
    }

    /** Reconstitue une Course déjà enregistrée avec ses bénévoles affectés, sans revalider la saisie. */
    public static Course reconstituer(UUID id, String nom, LocalDate date, StatutCourse statut,
                                      ParametresBoucle parametresBoucle, int nombreMaxParticipants,
                                      int nombreMaxBoucles, Collection<UUID> benevolesAffectes) {
        return reconstituer(id, nom, date, statut, parametresBoucle, nombreMaxParticipants, nombreMaxBoucles,
                benevolesAffectes, null);
    }

    /**
     * Reconstitue une Course déjà enregistrée avec ses bénévoles affectés et son heure de départ, sans revalider la
     * saisie.
     *
     * @param demarreeLe heure de départ, null si la Course n'a pas démarré ou n'a pas d'heure enregistrée
     */
    public static Course reconstituer(UUID id, String nom, LocalDate date, StatutCourse statut,
                                      ParametresBoucle parametresBoucle, int nombreMaxParticipants,
                                      int nombreMaxBoucles, Collection<UUID> benevolesAffectes,
                                      Instant demarreeLe) {
        return new Course(id, nom, date, statut, parametresBoucle, nombreMaxParticipants, nombreMaxBoucles,
                benevolesAffectes, demarreeLe);
    }

    public UUID id() {
        return id;
    }

    public String nom() {
        return nom;
    }

    public LocalDate date() {
        return date;
    }

    public StatutCourse statut() {
        return statut;
    }

    public ParametresBoucle parametresBoucle() {
        return parametresBoucle;
    }

    public int nombreMaxParticipants() {
        return nombreMaxParticipants;
    }

    public int nombreMaxBoucles() {
        return nombreMaxBoucles;
    }

    /** Heure de départ de la Boucle 1, à la seconde ; vide si la Course n'a pas démarré ou n'a pas d'heure. */
    public Optional<Instant> demarreeLe() {
        return Optional.ofNullable(demarreeLe);
    }

    /** Identifiants des Comptes bénévoles affectés, sans doublon ni ordre significatif ; non modifiable. */
    public Set<UUID> benevolesAffectes() {
        return benevolesAffectes;
    }
}
