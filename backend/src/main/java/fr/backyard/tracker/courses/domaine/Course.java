package fr.backyard.tracker.courses.domaine;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

/**
 * Racine d'agrégat : une édition de backyard. Une Course ne se crée que par {@link #declarer} et ne change que par
 * {@link #modifier}, qui appliquent les mêmes règles de saisie : aucune Course invalide n'existe en mémoire.
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

    private Course(UUID id, String nom, LocalDate date, StatutCourse statut, ParametresBoucle parametresBoucle,
                   int nombreMaxParticipants, int nombreMaxBoucles) {
        this.id = id;
        this.nom = nom;
        this.date = date;
        this.statut = statut;
        this.parametresBoucle = parametresBoucle;
        this.nombreMaxParticipants = nombreMaxParticipants;
        this.nombreMaxBoucles = nombreMaxBoucles;
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
                nombreMaxParticipants, nombreMaxBoucles);
    }

    /**
     * Remplace tous les champs modifiables d'une Course EN_PREPARATION, avec les règles de saisie de la déclaration,
     * sauf qu'une date inchangée est toujours acceptée. La Course renvoyée garde l'identifiant et le statut ;
     * celle-ci n'est pas affectée.
     *
     * @param aujourdhui date du jour, calculée par l'appelant à partir de l'horloge
     * @throws CourseNonModifiableException la Course n'est plus EN_PREPARATION (contrôlé avant la saisie)
     * @throws DonneesCourseInvalidesException toutes les violations, au plus une par champ, dans l'ordre des champs
     */
    public Course modifier(String nom, LocalDate date, Integer distanceBoucleMetres, Integer dureeBoucleMinutes,
                           Integer denivelePositifBoucleMetres, Integer nombreMaxParticipants,
                           Integer nombreMaxBoucles, LocalDate aujourdhui) {
        if (statut != StatutCourse.EN_PREPARATION) {
            throw new CourseNonModifiableException(id);
        }
        String nomSaisi = sansEspacesAutour(nom);
        DeclarationCourse declaration = new DeclarationCourse(aujourdhui).verifierNom(nomSaisi)
                .verifierNouvelleDate(date, this.date);
        verifierParametresEtLimites(declaration, distanceBoucleMetres, dureeBoucleMinutes,
                denivelePositifBoucleMetres, nombreMaxParticipants, nombreMaxBoucles);
        return new Course(id, nomSaisi, date, statut,
                new ParametresBoucle(distanceBoucleMetres, dureeBoucleMinutes, denivelePositifBoucleMetres),
                nombreMaxParticipants, nombreMaxBoucles);
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

    /** Reconstitue une Course déjà enregistrée, sans revalider la saisie. */
    public static Course reconstituer(UUID id, String nom, LocalDate date, StatutCourse statut,
                                      ParametresBoucle parametresBoucle, int nombreMaxParticipants,
                                      int nombreMaxBoucles) {
        return new Course(id, nom, date, statut, parametresBoucle, nombreMaxParticipants, nombreMaxBoucles);
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
}
