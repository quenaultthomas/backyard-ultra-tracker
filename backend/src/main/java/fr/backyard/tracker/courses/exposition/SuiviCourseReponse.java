package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ConsulterSuivi;
import fr.backyard.tracker.courses.application.ListerCourses;
import fr.backyard.tracker.courses.domaine.Chronometrage;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatistiquesCourse;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;

/**
 * Suivi public d'une Course, liste fermée de 22 champs : jamais de jeton QR, d'identifiant de Compte ou d'Inscription,
 * de statut d'Inscription, de détail ou de nombre de Passages, de bénévole ni de durée de Boucle. Les champs de
 * chronométrage sont null (et les booléens faux) sans chronométrage. {@code heureServeur} est l'instant UTC de calcul,
 * toujours aux millisecondes. Les trois listes, jamais null, gardent l'ordre établi par le cas d'usage. Les
 * statistiques viennent telles quelles du domaine : allure null sans Passage scanné retenu, durée finale null hors
 * TERMINEE. {@code boucleEnAttenteDeDecision} : Boucle terminée dont le délai de grâce court à {@code heureServeur},
 * null sinon.
 */
public record SuiviCourseReponse(UUID courseId, String nom, String logoUrl, StatutCourse statut,
                                 String heureServeur, int nombreMaxBoucles, Integer boucleCourante,
                                 boolean derniereBoucle, boolean boucleMaxTerminee,
                                 Integer boucleEnAttenteDeDecision,
                                 Long tempsAvantFinBoucleMillisecondes, Long dureeEcouleeMillisecondes,
                                 List<CoureurSuiviReponse> coureursEnCourse, List<AbandonSuiviReponse> abandons,
                                 List<CoureurSuiviReponse> vainqueurs, int distanceBoucleMetres,
                                 int denivelePositifBoucleMetres, int bouclesTerminees, long distanceCumuleeMetres,
                                 long denivelePositifCumuleMetres, Long allureSecondesParKilometre,
                                 Long dureeFinaleMillisecondes) {

    /** Coureur en course ou vainqueur : dossard et pseudo uniquement. */
    public record CoureurSuiviReponse(int dossard, String pseudo) {

        /** Dossard uniquement. */
        @Override
        public String toString() {
            return "CoureurSuiviReponse[dossard=" + dossard + "]";
        }

        static List<CoureurSuiviReponse> depuis(List<ConsulterSuivi.CoureurSuivi> coureurs) {
            return coureurs.stream().map(c -> new CoureurSuiviReponse(c.dossard(), c.pseudo())).toList();
        }
    }

    /** Abandon : Boucle et motif null pour un abandon antérieur enregistré sans eux. */
    public record AbandonSuiviReponse(int dossard, String pseudo, Integer boucleAbandon,
                                      MotifAbandon motifAbandon) {

        /** Dossard uniquement. */
        @Override
        public String toString() {
            return "AbandonSuiviReponse[dossard=" + dossard + "]";
        }

        static List<AbandonSuiviReponse> depuis(List<ConsulterSuivi.AbandonSuivi> abandons) {
            return abandons.stream().map(a -> new AbandonSuiviReponse(a.dossard(), a.pseudo(),
                    a.boucleAbandon().orElse(null), a.motifAbandon().orElse(null))).toList();
        }
    }

    private static final DateTimeFormatter INSTANT_AUX_MILLISECONDES =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Identifiant de la Course uniquement. */
    @Override
    public String toString() {
        return "SuiviCourseReponse[courseId=" + courseId + "]";
    }

    static SuiviCourseReponse depuis(ConsulterSuivi.Suivi suivi, ListerCourses.CourseListee courseListee) {
        Course course = suivi.course();
        Optional<Chronometrage> chrono = suivi.chronometrage();
        ParametresBoucle boucle = course.parametresBoucle();
        StatistiquesCourse statistiques = suivi.statistiques();
        return new SuiviCourseReponse(course.id(), course.nom(), CourseReponse.depuis(courseListee).logoUrl(),
                course.statut(), INSTANT_AUX_MILLISECONDES.format(suivi.heureServeur()), course.nombreMaxBoucles(),
                valeur(chrono, Chronometrage::boucleCourante),
                chrono.map(Chronometrage::derniereBoucle).orElse(false),
                chrono.map(Chronometrage::boucleMaxTerminee).orElse(false),
                enAttente(course.boucleEnAttenteDeDecision(suivi.heureServeur())),
                valeur(chrono, c -> c.tempsAvantFinBoucle().toMillis()),
                valeur(chrono, c -> c.dureeEcoulee().toMillis()),
                CoureurSuiviReponse.depuis(suivi.coureursEnCourse()), AbandonSuiviReponse.depuis(suivi.abandons()),
                CoureurSuiviReponse.depuis(suivi.vainqueurs()), boucle.distanceMetres(),
                boucle.denivelePositifMetres(), statistiques.bouclesTerminees(), statistiques.distanceCumuleeMetres(),
                statistiques.denivelePositifCumuleMetres(), valeur(statistiques.allureSecondesParKilometre()),
                valeur(statistiques.dureeFinaleMillisecondes()));
    }

    private static Long valeur(OptionalLong optionnel) {
        return optionnel.isPresent() ? optionnel.getAsLong() : null;
    }

    private static Integer enAttente(OptionalInt boucle) {
        return boucle.isPresent() ? boucle.getAsInt() : null;
    }

    private static <T> T valeur(Optional<Chronometrage> chronometrage, Function<Chronometrage, T> champ) {
        return chronometrage.map(champ).orElse(null);
    }
}
