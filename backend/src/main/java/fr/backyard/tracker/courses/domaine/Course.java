package fr.backyard.tracker.courses.domaine;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

/**
 * Racine d'agrégat : une édition de backyard. Une Course ne se crée que par {@link #declarer}, qui applique
 * toutes les règles de saisie : aucune Course invalide n'existe en mémoire.
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
        String nomSaisi = nom == null ? "" : nom.trim();
        new DeclarationCourse(aujourdhui)
                .verifierNom(nomSaisi)
                .verifierDate(date)
                .verifierParametresBoucle(distanceBoucleMetres, dureeBoucleMinutes, denivelePositifBoucleMetres)
                .verifierLimites(nombreMaxParticipants, nombreMaxBoucles)
                .rejeterSiInvalide();
        return new Course(UUID.randomUUID(), nomSaisi, date, StatutCourse.EN_PREPARATION,
                new ParametresBoucle(distanceBoucleMetres, dureeBoucleMinutes, denivelePositifBoucleMetres),
                nombreMaxParticipants, nombreMaxBoucles);
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
