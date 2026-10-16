package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.application.ListerInscritsCourse.Inscrit;
import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import fr.backyard.tracker.courses.domaine.Bouclage;
import fr.backyard.tracker.courses.domaine.Chronometrage;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonDemarreeException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.DepotPassages;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.Passage;
import fr.backyard.tracker.courses.domaine.ProgressionCoureur;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Instantané de pilotage d'une Course démarrée : chronométrage à l'instant de l'horloge (lu une seule fois) et
 * inscrits encore en course, en abandon et vainqueurs, triés par dossard, avec leur progression dérivée de leurs Passages (lus en une seule
 * lecture groupée) et le bouclage de la Boucle courante. Lecture seule : rien n'est écrit ni stocké, aucune transition n'est
 * déclenchée. Aucun journal en fonctionnement normal (lecture fréquente).
 */
@Service
public class ConsulterPilotage {

    /** Ligne d'un inscrit en course : jamais de jeton QR, d'identifiant de Compte ni de détail de Passage. */
    public record InscritEnCourse(Inscrit inscrit, ProgressionCoureur progression) {

        public UUID inscriptionId() {
            return inscrit.inscriptionId();
        }

        public int dossard() {
            return inscrit.dossard();
        }

        public String pseudo() {
            return inscrit.pseudo();
        }

        public StatutInscription statut() {
            return inscrit.statut();
        }

        public int nombreBouclesTerminees() {
            return progression.nombreBouclesTerminees();
        }

        public Optional<Boolean> boucleCouranteTerminee() {
            return progression.boucleCouranteTerminee();
        }

        public int nombreBouclesCorrigees() {
            return progression.nombreBouclesCorrigees();
        }

        /** Identifiant et dossard uniquement : jamais de pseudo dans le journal. */
        @Override
        public String toString() {
            return "InscritEnCourse[inscriptionId=" + inscrit.inscriptionId() + ", dossard=" + inscrit.dossard() + "]";
        }
    }

    /**
     * Ligne d'un inscrit en abandon : Boucle et motif vides pour un abandon enregistré sans eux (données antérieures).
     * Jamais de jeton QR, d'identifiant de Compte ni de détail de Passage.
     */
    public record InscritEnAbandon(Inscrit inscrit, int nombreBouclesTerminees, int nombreBouclesCorrigees,
                                   Optional<Integer> boucleAbandon, Optional<MotifAbandon> motifAbandon) {

        public UUID inscriptionId() {
            return inscrit.inscriptionId();
        }

        public int dossard() {
            return inscrit.dossard();
        }

        public String pseudo() {
            return inscrit.pseudo();
        }

        public StatutInscription statut() {
            return inscrit.statut();
        }

        /** Identifiant et dossard uniquement : jamais de pseudo dans le journal. */
        @Override
        public String toString() {
            return "InscritEnAbandon[inscriptionId=" + inscrit.inscriptionId() + ", dossard=" + inscrit.dossard() + "]";
        }
    }

    /** Ligne d'un vainqueur. Jamais de jeton QR, d'identifiant de Compte ni de détail de Passage. */
    public record InscritVainqueur(Inscrit inscrit, int nombreBouclesTerminees, int nombreBouclesCorrigees) {

        public UUID inscriptionId() {
            return inscrit.inscriptionId();
        }

        public int dossard() {
            return inscrit.dossard();
        }

        public String pseudo() {
            return inscrit.pseudo();
        }

        public StatutInscription statut() {
            return inscrit.statut();
        }

        /** Identifiant et dossard uniquement : jamais de pseudo dans le journal. */
        @Override
        public String toString() {
            return "InscritVainqueur[inscriptionId=" + inscrit.inscriptionId() + ", dossard=" + inscrit.dossard() + "]";
        }
    }

    /**
     * Instantané : chronométrage et bouclage vides pour une Course TERMINEE ou sans heure de départ. Les abandons
     * et les vainqueurs sont triés par dossard, quel que soit l'état de la Course.
     */
    public record Pilotage(Course course, Instant heureServeur, Optional<Chronometrage> chronometrage,
                           int nombreInscrits, List<InscritEnCourse> inscritsEnCourse, Optional<Bouclage> bouclage,
                           List<InscritEnAbandon> inscritsEnAbandon, List<InscritVainqueur> inscritsVainqueurs) {

        public int nombreAbandons() {
            return inscritsEnAbandon.size();
        }

        public int nombreVainqueurs() {
            return inscritsVainqueurs.size();
        }

        public Optional<Integer> nombreAyantBoucle() {
            return bouclage.map(Bouclage::nombreAyantBoucle);
        }

        public Optional<Integer> nombreEnBoucle() {
            return bouclage.map(Bouclage::nombreEnBoucle);
        }

        /** Identifiant de la Course uniquement : ni nom ni pseudo dans le journal. */
        @Override
        public String toString() {
            return "Pilotage[course=" + course.id() + "]";
        }
    }

    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;
    private final DepotPassages depotPassages;
    private final AnnuairePseudos annuairePseudos;
    private final Clock horloge;

    public ConsulterPilotage(DepotCourses depotCourses, DepotInscriptions depotInscriptions,
                             DepotPassages depotPassages, AnnuairePseudos annuairePseudos, Clock horloge) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
        this.depotPassages = depotPassages;
        this.annuairePseudos = annuairePseudos;
        this.horloge = horloge;
    }

    /**
     * @throws CourseIntrouvableException aucune Course pour cet identifiant
     * @throws CourseNonDemarreeException la Course est EN_PREPARATION
     */
    @Transactional(readOnly = true)
    public Pilotage executer(UUID courseId) {
        Course course = depotCourses.parId(courseId).orElseThrow(CourseIntrouvableException::new);
        course.autoriserPilotage();
        Instant maintenant = horloge.instant();
        Map<UUID, Inscription> inscriptions = depotInscriptions.parCourse(courseId).stream()
                .collect(Collectors.toMap(Inscription::id, Function.identity()));
        Optional<Chronometrage> chronometrage = course.chronometrage(maintenant);
        OptionalInt boucleCourante = chronometrage.map(c -> OptionalInt.of(c.boucleCourante()))
                .orElseGet(OptionalInt::empty);
        Map<UUID, List<Passage>> passages = passagesParInscription(inscriptions.keySet());
        List<Inscrit> parDossard = ListerInscritsCourse.inscritsParDossard(List.copyOf(inscriptions.values()),
                annuairePseudos);
        List<InscritEnCourse> inscrits = parDossard.stream().filter(i -> i.statut() == StatutInscription.EN_COURSE)
                .map(inscrit -> new InscritEnCourse(inscrit, ProgressionCoureur.de(
                        passages.getOrDefault(inscrit.inscriptionId(), List.of()), boucleCourante)))
                .toList();
        List<InscritEnAbandon> abandons = parDossard.stream().filter(i -> i.statut() == StatutInscription.ABANDON)
                .map(inscrit -> enAbandon(inscrit, inscriptions.get(inscrit.inscriptionId()), passages))
                .toList();
        List<InscritVainqueur> vainqueurs = parDossard.stream()
                .filter(i -> i.statut() == StatutInscription.VAINQUEUR)
                .map(inscrit -> enVainqueur(inscrit, passages))
                .toList();
        return new Pilotage(course, maintenant, chronometrage, inscriptions.size(), inscrits,
                Bouclage.de(inscrits.stream().map(InscritEnCourse::progression).toList(), boucleCourante), abandons,
                vainqueurs);
    }

    private static ProgressionCoureur progressionHorsBoucle(Inscrit inscrit, Map<UUID, List<Passage>> passages) {
        return ProgressionCoureur.de(passages.getOrDefault(inscrit.inscriptionId(), List.of()), OptionalInt.empty());
    }

    private static InscritEnAbandon enAbandon(Inscrit inscrit, Inscription inscription,
                                              Map<UUID, List<Passage>> passages) {
        ProgressionCoureur progression = progressionHorsBoucle(inscrit, passages);
        return new InscritEnAbandon(inscrit, progression.nombreBouclesTerminees(),
                progression.nombreBouclesCorrigees(), inscription.boucleAbandon(), inscription.motifAbandon());
    }

    private static InscritVainqueur enVainqueur(Inscrit inscrit, Map<UUID, List<Passage>> passages) {
        ProgressionCoureur progression = progressionHorsBoucle(inscrit, passages);
        return new InscritVainqueur(inscrit, progression.nombreBouclesTerminees(),
                progression.nombreBouclesCorrigees());
    }

    private Map<UUID, List<Passage>> passagesParInscription(Collection<UUID> inscriptionIds) {
        return depotPassages.parInscriptions(List.copyOf(inscriptionIds)).stream()
                .collect(Collectors.groupingBy(Passage::inscriptionId));
    }
}
