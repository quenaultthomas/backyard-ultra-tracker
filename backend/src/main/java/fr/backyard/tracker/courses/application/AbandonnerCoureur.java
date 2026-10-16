package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonDemarreeException;
import fr.backyard.tracker.courses.domaine.CourseTermineePourAbandonException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.DepotPassages;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionIntrouvableException;
import fr.backyard.tracker.courses.domaine.InscriptionNonEnCourseException;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Abandon manuel d'un coureur par un admin (le rôle est contrôlé avant ce cas d'usage). La Course est chargée sous le
 * verrou partagé avec le scan et l'abandon automatique, puis l'Inscription et ses Passages sont relus : les actions
 * concurrentes sont sérialisées. L'Inscription et, si plus personne n'est en course, la Course terminée sont écrites
 * dans la même transaction. L'horloge n'est pas lue. Journal : identifiants, motif et Boucle uniquement.
 */
@Service
public class AbandonnerCoureur {

    private static final Logger JOURNAL = System.getLogger(AbandonnerCoureur.class.getName());

    /** Inscription mise en abandon et statut de la Course après l'action. */
    public record Resultat(Inscription inscription, StatutCourse statutCourse) {
    }

    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;
    private final DepotPassages depotPassages;

    /** L'horloge, injectée comme pour les autres cas d'usage, n'intervient pas dans l'abandon manuel. */
    public AbandonnerCoureur(DepotCourses depotCourses, DepotInscriptions depotInscriptions,
                             DepotPassages depotPassages, Clock horloge) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
        this.depotPassages = depotPassages;
    }

    /**
     * @throws CourseIntrouvableException aucune Course pour cet identifiant
     * @throws CourseNonDemarreeException la Course est EN_PREPARATION (avant toute lecture de l'Inscription)
     * @throws CourseTermineePourAbandonException la Course est TERMINEE (avant toute lecture de l'Inscription)
     * @throws InscriptionIntrouvableException Inscription inconnue ou d'une autre Course
     * @throws InscriptionNonEnCourseException l'Inscription est déjà en abandon ou vainqueur
     * @throws IllegalArgumentException motif réservé à l'abandon automatique
     */
    @Transactional
    public Resultat executer(UUID courseId, UUID inscriptionId, MotifAbandon motif) {
        Course course = depotCourses.parIdPourModification(courseId).orElseThrow(CourseIntrouvableException::new);
        course.autoriserAbandonManuel();
        Inscription inscription = depotInscriptions.parIdEtCourse(inscriptionId, courseId)
                .orElseThrow(InscriptionIntrouvableException::new);
        int nombrePassages = depotPassages.parInscriptions(List.of(inscriptionId)).size();
        Inscription abandon = inscription.abandonnerManuellement(motif, nombrePassages);
        depotInscriptions.mettreAJour(abandon);
        int boucle = abandon.boucleAbandon().orElseThrow();
        JOURNAL.log(Level.INFO, "Abandon manuel : course {0}, inscription {1}, motif {2}, boucle {3}.",
                courseId, inscriptionId, motif, String.valueOf(boucle));
        Course apres = course.terminerSiPlusAucunCoureurEnCourse(autresInscriptionsEnCourse(courseId, inscriptionId));
        if (apres.statut() == StatutCourse.TERMINEE) {
            depotCourses.enregistrer(apres);
            JOURNAL.log(Level.INFO, "Fin de course : course {0}, boucle {1}, aucun vainqueur.",
                    courseId, String.valueOf(boucle));
        }
        return new Resultat(abandon, apres.statut());
    }

    /** Inscriptions EN_COURSE de la Course, hors celle qui vient d'abandonner (quel que soit l'état relu). */
    private int autresInscriptionsEnCourse(UUID courseId, UUID inscriptionAbandonnee) {
        return (int) depotInscriptions.parCourse(courseId).stream()
                .filter(Inscription::estEnCourse)
                .filter(autre -> !autre.id().equals(inscriptionAbandonnee))
                .count();
    }
}
