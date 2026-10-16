package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonDemarreeException;
import fr.backyard.tracker.courses.domaine.CourseTermineePourReintegrationException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.DepotPassages;
import fr.backyard.tracker.courses.domaine.HeureDeDepartInconnueException;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionIntrouvableException;
import fr.backyard.tracker.courses.domaine.InscriptionNonEnAbandonException;
import fr.backyard.tracker.courses.domaine.Passage;
import fr.backyard.tracker.courses.domaine.Reintegration;
import fr.backyard.tracker.courses.domaine.TempsDeCourseEcouleException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Réintégration d'un coureur en abandon par un admin (le rôle est contrôlé avant ce cas d'usage). La Course est
 * chargée sous le verrou partagé avec le scan, l'abandon automatique et l'abandon manuel ; l'horloge est lue une
 * seule fois ; l'Inscription et ses Passages sont relus sous ce verrou. Les Passages CORRECTION puis l'Inscription
 * sont écrits dans la même transaction ; la Course n'est jamais modifiée. Journal : identifiants, motif d'origine et
 * nombre de Boucles corrigées uniquement.
 */
@Service
public class ReintegrerCoureur {

    private static final Logger JOURNAL = System.getLogger(ReintegrerCoureur.class.getName());

    /** Inscription réintégrée et numéros croissants des Boucles pour lesquelles un Passage CORRECTION a été créé. */
    public record Resultat(Inscription inscription, List<Integer> bouclesCorrigees) {
    }

    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;
    private final DepotPassages depotPassages;
    private final Clock horloge;

    public ReintegrerCoureur(DepotCourses depotCourses, DepotInscriptions depotInscriptions,
                             DepotPassages depotPassages, Clock horloge) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
        this.depotPassages = depotPassages;
        this.horloge = horloge;
    }

    /**
     * @throws CourseIntrouvableException aucune Course pour cet identifiant
     * @throws CourseNonDemarreeException la Course est EN_PREPARATION (avant toute lecture de l'Inscription)
     * @throws CourseTermineePourReintegrationException la Course est TERMINEE (avant toute lecture de l'Inscription)
     * @throws HeureDeDepartInconnueException la Course est EN_COURS sans heure de départ
     * @throws TempsDeCourseEcouleException le temps de la dernière Boucle est écoulé
     * @throws InscriptionIntrouvableException Inscription inconnue ou d'une autre Course
     * @throws InscriptionNonEnAbandonException l'Inscription est EN_COURSE ou VAINQUEUR
     */
    @Transactional
    public Resultat executer(UUID courseId, UUID inscriptionId) {
        Instant maintenant = horloge.instant();
        Course course = depotCourses.parIdPourModification(courseId).orElseThrow(CourseIntrouvableException::new);
        course.autoriserReintegration(maintenant);
        Inscription abandon = depotInscriptions.parIdEtCourse(inscriptionId, courseId)
                .orElseThrow(InscriptionIntrouvableException::new);
        List<Integer> numerosDejaPasses = depotPassages.parInscriptions(List.of(inscriptionId)).stream()
                .map(Passage::numeroBoucle)
                .toList();
        Inscription reintegree = abandon.reintegrer();
        List<Integer> bouclesCorrigees = Reintegration.bouclesACorriger(abandon.boucleAbandon(),
                course.bouclesTerminees(maintenant), numerosDejaPasses);
        bouclesCorrigees.forEach(boucle -> depotPassages.enregistrer(
                Passage.corriger(UUID.randomUUID(), reintegree, boucle)));
        depotInscriptions.mettreAJour(reintegree);
        JOURNAL.log(Level.INFO, "Réintégration : course {0}, inscription {1}, motif d''origine {2}, "
                        + "{3} boucle(s) corrigée(s).", courseId, inscriptionId,
                abandon.motifAbandon().map(Enum::name).orElse("inconnu"), String.valueOf(bouclesCorrigees.size()));
        return new Resultat(reintegree, bouclesCorrigees);
    }
}
