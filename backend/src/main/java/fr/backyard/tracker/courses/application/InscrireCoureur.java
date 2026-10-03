package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.GenerateurJetonQr;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionDejaExistanteException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inscription d'un Compte coureur (identifié par la session, le rôle est contrôlé avant ce cas d'usage) à une Course.
 * La Course est relue sous verrou : les inscriptions simultanées à une même Course sont sérialisées, ce qui rend
 * l'attribution du dossard et le contrôle du doublon sûrs en concurrence.
 */
@Service
public class InscrireCoureur {

    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;
    private final GenerateurJetonQr generateurJetonQr;

    public InscrireCoureur(DepotCourses depotCourses, DepotInscriptions depotInscriptions,
                           GenerateurJetonQr generateurJetonQr) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
        this.generateurJetonQr = generateurJetonQr;
    }

    /**
     * @throws CourseIntrouvableException aucune Course pour cet identifiant
     * @throws InscriptionDejaExistanteException le Compte est déjà inscrit à cette Course ; rien n'est créé
     */
    @Transactional
    public Inscription executer(UUID compteId, UUID courseId) {
        Course course = depotCourses.parIdPourModification(courseId).orElseThrow(CourseIntrouvableException::new);
        if (depotInscriptions.existePour(course.id(), compteId)) {
            throw new InscriptionDejaExistanteException();
        }
        Inscription inscription = Inscription.creer(course, compteId,
                depotInscriptions.plusGrandDossard(course.id()), generateurJetonQr.generer());
        depotInscriptions.enregistrer(inscription);
        return inscription;
    }
}
