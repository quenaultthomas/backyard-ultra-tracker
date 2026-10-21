package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ConsulterPilotage;
import fr.backyard.tracker.courses.application.ListerCourses;
import fr.backyard.tracker.courses.domaine.Chronometrage;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;

/**
 * Instantané de pilotage d'une Course pour l'administration. Les champs de chronométrage sont null (et les booléens
 * faux) sans chronométrage ; les nombres de bouclage et l'indicateur de bouclage de chaque inscrit sont alors null.
 * {@code heureServeur} est l'instant UTC de calcul, toujours aux millisecondes ; {@code boucleEnAttenteDeDecision}
 * est la Boucle terminée dont le délai de grâce court à cet instant, null sinon. Ni jeton QR, ni identifiant de
 * Compte, ni détail de Passage.
 */
public record PilotageCourseReponse(UUID courseId, String nom, String logoUrl, StatutCourse statut,
                                    String heureServeur, Instant demarreeLe, int dureeBoucleMinutes,
                                    int nombreMaxBoucles, Integer boucleCourante, boolean derniereBoucle,
                                    boolean boucleMaxTerminee, Integer boucleEnAttenteDeDecision,
                                    Instant debutBoucleCourante,
                                    Instant finBoucleCourante, Long tempsAvantFinBoucleMillisecondes,
                                    Long dureeEcouleeMillisecondes, int nombreInscrits, Integer nombreAyantBoucle,
                                    Integer nombreEnBoucle, List<InscritPilotageReponse> inscritsEnCourse,
                                    int nombreAbandons, List<AbandonPilotageReponse> inscritsEnAbandon,
                                    int nombreVainqueurs, List<VainqueurPilotageReponse> inscritsVainqueurs) {

    /**
     * Ligne d'un inscrit en course ; {@code boucleCouranteTerminee} est null sans chronométrage. Dans les trois
     * lignes, {@code nombreBouclesCorrigees} est le nombre de Passages CORRECTION (badge « corrigé »).
     */
    public record InscritPilotageReponse(UUID inscriptionId, int dossard, String pseudo, StatutInscription statut,
                                         int nombreBouclesTerminees, Boolean boucleCouranteTerminee,
                                         int nombreBouclesCorrigees) {

        /** Identifiant et dossard uniquement : jamais de pseudo dans le journal. */
        @Override
        public String toString() {
            return "InscritPilotageReponse[inscriptionId=" + inscriptionId + ", dossard=" + dossard + "]";
        }

        static InscritPilotageReponse depuis(ConsulterPilotage.InscritEnCourse inscrit) {
            return new InscritPilotageReponse(inscrit.inscriptionId(), inscrit.dossard(), inscrit.pseudo(),
                    inscrit.statut(), inscrit.nombreBouclesTerminees(), inscrit.boucleCouranteTerminee().orElse(null),
                    inscrit.nombreBouclesCorrigees());
        }
    }

    /** Ligne d'un inscrit en abandon ; Boucle et motif null pour un abandon enregistré sans eux. */
    public record AbandonPilotageReponse(UUID inscriptionId, int dossard, String pseudo, StatutInscription statut,
                                         int nombreBouclesTerminees, Integer boucleAbandon,
                                         MotifAbandon motifAbandon, int nombreBouclesCorrigees) {

        /** Identifiant et dossard uniquement : jamais de pseudo dans le journal. */
        @Override
        public String toString() {
            return "AbandonPilotageReponse[inscriptionId=" + inscriptionId + ", dossard=" + dossard + "]";
        }

        static AbandonPilotageReponse depuis(ConsulterPilotage.InscritEnAbandon abandon) {
            return new AbandonPilotageReponse(abandon.inscriptionId(), abandon.dossard(), abandon.pseudo(),
                    abandon.statut(), abandon.nombreBouclesTerminees(), abandon.boucleAbandon().orElse(null),
                    abandon.motifAbandon().orElse(null), abandon.nombreBouclesCorrigees());
        }
    }

    /** Ligne d'un vainqueur. */
    public record VainqueurPilotageReponse(UUID inscriptionId, int dossard, String pseudo, StatutInscription statut,
                                           int nombreBouclesTerminees, int nombreBouclesCorrigees) {

        /** Identifiant et dossard uniquement : jamais de pseudo dans le journal. */
        @Override
        public String toString() {
            return "VainqueurPilotageReponse[inscriptionId=" + inscriptionId + ", dossard=" + dossard + "]";
        }

        static VainqueurPilotageReponse depuis(ConsulterPilotage.InscritVainqueur vainqueur) {
            return new VainqueurPilotageReponse(vainqueur.inscriptionId(), vainqueur.dossard(), vainqueur.pseudo(),
                    vainqueur.statut(), vainqueur.nombreBouclesTerminees(), vainqueur.nombreBouclesCorrigees());
        }
    }

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
                enAttente(course.boucleEnAttenteDeDecision(pilotage.heureServeur())),
                valeur(chrono, Chronometrage::debutBoucleCourante), valeur(chrono, Chronometrage::finBoucleCourante),
                valeur(chrono, c -> millisecondes(c.tempsAvantFinBoucle())),
                valeur(chrono, c -> millisecondes(c.dureeEcoulee())), pilotage.nombreInscrits(),
                pilotage.nombreAyantBoucle().orElse(null), pilotage.nombreEnBoucle().orElse(null),
                pilotage.inscritsEnCourse().stream().map(InscritPilotageReponse::depuis).toList(),
                pilotage.nombreAbandons(),
                pilotage.inscritsEnAbandon().stream().map(AbandonPilotageReponse::depuis).toList(),
                pilotage.nombreVainqueurs(),
                pilotage.inscritsVainqueurs().stream().map(VainqueurPilotageReponse::depuis).toList());
    }

    private static Integer enAttente(OptionalInt boucle) {
        return boucle.isPresent() ? boucle.getAsInt() : null;
    }

    private static <T> T valeur(Optional<Chronometrage> chronometrage, Function<Chronometrage, T> champ) {
        return chronometrage.map(champ).orElse(null);
    }

    private static Long millisecondes(Duration duree) {
        return duree.toMillis();
    }
}
