package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.AnnuaireBenevoles;
import fr.backyard.tracker.courses.domaine.BenevoleInconnuException;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseTermineeException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remplacement de l'ensemble des bénévoles affectés à une Course par un admin (le rôle est contrôlé avant ce cas
 * d'usage). Ordre des contrôles : existence de la Course, statut, puis bénévoles ; rien n'est modifié en cas de refus.
 */
@Service
public class AffecterBenevoles {

    /** Course concernée et ensemble complet souhaité (doublons permis, ignorés). */
    public record Commande(UUID idCourse, List<UUID> benevoleIds) {
    }

    private final DepotCourses depotCourses;
    private final AnnuaireBenevoles annuaireBenevoles;

    public AffecterBenevoles(DepotCourses depotCourses, AnnuaireBenevoles annuaireBenevoles) {
        this.depotCourses = depotCourses;
        this.annuaireBenevoles = annuaireBenevoles;
    }

    /**
     * @throws CourseIntrouvableException aucune Course pour cet identifiant
     * @throws CourseTermineeException la Course est TERMINEE
     * @throws BenevoleInconnuException un des identifiants ne désigne pas un bénévole
     */
    @Transactional
    public Course executer(Commande commande) {
        Course course = depotCourses.parIdPourModification(commande.idCourse())
                .orElseThrow(CourseIntrouvableException::new);
        course.autoriserAffectation();
        annuaireBenevoles.verifierBenevoles(commande.benevoleIds());
        course.affecterBenevoles(commande.benevoleIds());
        depotCourses.enregistrer(course);
        return course;
    }
}
