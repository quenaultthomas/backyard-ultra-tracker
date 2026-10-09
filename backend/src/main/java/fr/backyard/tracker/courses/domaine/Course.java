package fr.backyard.tracker.courses.domaine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
