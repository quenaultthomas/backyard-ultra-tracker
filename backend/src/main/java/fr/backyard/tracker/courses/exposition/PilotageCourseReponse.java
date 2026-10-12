package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ConsulterPilotage;
import fr.backyard.tracker.courses.application.ListerCourses;
import fr.backyard.tracker.courses.domaine.Chronometrage;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Instantané de pilotage d'une Course pour l'administration. Les champs de chronométrage sont null (et les booléens
 * faux) sans chronométrage ; {@code heureServeur} est l'instant UTC de calcul, toujours aux millisecondes. Ni jeton
 * QR, ni identifiant de Compte.
 */
public record PilotageCourseReponse(UUID courseId, String nom, String logoUrl, StatutCourse statut,
                                    String heureServeur, Instant demarreeLe, int dureeBoucleMinutes,
                                    int nombreMaxBoucles, Integer boucleCourante, boolean derniereBoucle,
                                    boolean boucleMaxTerminee, Instant debutBoucleCourante,
                                    Instant finBoucleCourante, Long tempsAvantFinBoucleMillisecondes,
                                    Long dureeEcouleeMillisecondes, int nombreInscrits,
                                    List<InscritsCourseReponse.InscritReponse> inscritsEnCourse) {

    private static final DateTimeFormatter INSTANT_AUX_MILLISECONDES =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Identifiant de la Course uniquement : ni nom ni pseudo dans le journal. */
    @Override
    public String toString() {
        return "PilotageCourseReponse[courseId=" + courseId + "]";
    }

    static PilotageCourseReponse depuis(ConsulterPilotage.Pilotage pilotage, ListerCourses.CourseListee courseListee) {
        Course course = pilotage.course();
        Optional<Chronometrage> chrono = pilotage.chronometrage();
        return new PilotageCourseReponse(course.id(), course.nom(), CourseReponse.depuis(courseListee).logoUrl(),
                course.statut(), INSTANT_AUX_MILLISECONDES.format(pilotage.heureServeur()),
                course.demarreeLe().orElse(null), course.parametresBoucle().dureeMinutes(),
                course.nombreMaxBoucles(), valeur(chrono, Chronometrage::boucleCourante),
                chrono.map(Chronometrage::derniereBoucle).orElse(false),
                chrono.map(Chronometrage::boucleMaxTerminee).orElse(false),
                valeur(chrono, Chronometrage::debutBoucleCourante), valeur(chrono, Chronometrage::finBoucleCourante),
                valeur(chrono, c -> millisecondes(c.tempsAvantFinBoucle())),
                valeur(chrono, c -> millisecondes(c.dureeEcoulee())), pilotage.nombreInscrits(),
                pilotage.inscritsEnCourse().stream().map(InscritsCourseReponse.InscritReponse::depuis).toList());
    }

    private static <T> T valeur(Optional<Chronometrage> chronometrage, Function<Chronometrage, T> champ) {
        return chronometrage.map(champ).orElse(null);
    }

    private static Long millisecondes(Duration duree) {
        return duree.toMillis();
    }
}
