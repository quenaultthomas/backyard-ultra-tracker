package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.BenevoleAffecteCourseEnCoursException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DesaffectationBenevole;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.RegistreTentativesConnexion;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppression définitive d'un Compte BENEVOLE (actif ou désactivé) par un admin ou l'admin master, dans une seule
 * transaction : Compte relu sous verrou, garde de rôle du domaine, retrait de toutes ses affectations (Courses
 * verrouillées après le Compte, refus si l'une est EN_COURS), suppression du Compte, remise à zéro du compteur
 * d'échecs de son pseudo, puis fermeture de toutes ses sessions après validation. Journal sans pseudo, mot de passe ni
 * nom de Course.
 */
@Service
public class SupprimerBenevole {

    private static final Logger JOURNAL = System.getLogger(SupprimerBenevole.class.getName());

    private final DepotComptes depotComptes;
    private final DesaffectationBenevole desaffectation;
    private final InvalidationAutresSessions invalidationSessions;
    private final LimitationTentatives limitation;

    public SupprimerBenevole(DepotComptes depotComptes, DesaffectationBenevole desaffectation,
                             InvalidationAutresSessions invalidationSessions,
                             RegistreTentativesConnexion registreTentatives, PolitiqueBlocage politiqueBlocage) {
        this.depotComptes = depotComptes;
        this.desaffectation = desaffectation;
        this.invalidationSessions = invalidationSessions;
        this.limitation = new LimitationTentatives(registreTentatives, politiqueBlocage);
    }

    /**
     * @throws CompteNonGerableException              aucun Compte BENEVOLE pour cet identifiant : rien n'est modifié
     * @throws BenevoleAffecteCourseEnCoursException le bénévole est affecté à une Course EN_COURS : rien n'est modifié
     */
    @Transactional
    public void executer(UUID appelantId, UUID cibleId) {
        Compte cible = ComptesGeres.BENEVOLES.cible(depotComptes.trouverParIdPourModification(cibleId));
        cible.verifierSuppressionBenevole();
        int retirees = retirerDeToutesSesCourses(cibleId);
        depotComptes.supprimer(cibleId);
        limitation.oublier(cible.pseudoNormalise());
        JOURNAL.log(Level.INFO, "Bénévole supprimé (compte {0} par {1}, {2} affectation(s) retirée(s))", cibleId,
                appelantId, retirees);
        Desactivation.fermerSessionsApresValidation(invalidationSessions, cibleId);
    }

    private int retirerDeToutesSesCourses(UUID cibleId) {
        try {
            return desaffectation.desaffecterDeToutesLesCourses(cibleId);
        } catch (BenevoleAffecteCourseEnCoursException refus) {
            JOURNAL.log(Level.INFO,
                    "Suppression refusée (compte {0}, motif BENEVOLE_AFFECTE_COURSE_EN_COURS, {1} course(s))",
                    cibleId, refus.courses().size());
            throw refus;
        }
    }
}
