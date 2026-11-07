package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.AnnulationInscriptions;
import fr.backyard.tracker.comptes.domaine.BenevoleAffecteCourseEnCoursException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.ConsultationInscriptions;
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
 * Suppression d'un Compte BENEVOLE (actif ou désactivé) par un admin ou l'admin master, dans une seule transaction :
 * Compte relu sous verrou, garde de rôle du domaine, annulation de ses Inscriptions aux Courses non démarrées, retrait
 * de toutes ses affectations (Courses verrouillées après le Compte, refus si l'une est EN_COURS), puis anonymisation
 * s'il lui reste des Inscriptions (9.6a RG1), suppression définitive sinon (photo effacée après validation dans les deux cas, 10.1 RG9), remise à zéro du compteur d'échecs de son
 * pseudo et fermeture de toutes ses sessions après validation. Journal sans pseudo, mot de passe ni nom de Course.
 */
@Service
public class SupprimerBenevole {

    private static final Logger JOURNAL = System.getLogger(SupprimerBenevole.class.getName());

    private final DepotComptes depotComptes;
    private final DesaffectationBenevole desaffectation;
    private final InvalidationAutresSessions invalidationSessions;
    private final LimitationTentatives limitation;
    private final AnnulationInscriptions annulationInscriptions;
    private final ConsultationInscriptions consultationInscriptions;
    private final EffacementPhoto effacementPhoto;

    public SupprimerBenevole(DepotComptes depotComptes, DesaffectationBenevole desaffectation,
                             InvalidationAutresSessions invalidationSessions,
                             RegistreTentativesConnexion registreTentatives, PolitiqueBlocage politiqueBlocage,
                             AnnulationInscriptions annulationInscriptions,
                             ConsultationInscriptions consultationInscriptions,
                             EffacementPhoto effacementPhoto) {
        this.depotComptes = depotComptes;
        this.desaffectation = desaffectation;
        this.invalidationSessions = invalidationSessions;
        this.limitation = new LimitationTentatives(registreTentatives, politiqueBlocage);
        this.annulationInscriptions = annulationInscriptions;
        this.consultationInscriptions = consultationInscriptions;
        this.effacementPhoto = effacementPhoto;
    }

    /**
     * @throws CompteNonGerableException              aucun Compte BENEVOLE pour cet identifiant : rien n'est modifié
     * @throws BenevoleAffecteCourseEnCoursException le bénévole est affecté à une Course EN_COURS : rien n'est modifié
     */
    @Transactional
    public void executer(UUID appelantId, UUID cibleId) {
        Compte cible = ComptesGeres.BENEVOLES.cible(depotComptes.trouverParIdPourModification(cibleId));
        cible.verifierSuppressionBenevole();
        annulationInscriptions.annulerPour(cibleId);
        int retirees = retirerDeToutesSesCourses(cibleId);
        if (consultationInscriptions.aDesInscriptions(cibleId)) {
            depotComptes.mettreAJour(cible.anonymiserBenevoleOuAdmin());
            JOURNAL.log(Level.INFO, "Bénévole anonymisé (compte {0} par {1}, {2} affectation(s) retirée(s))",
                    cibleId, appelantId, retirees);
        } else {
            depotComptes.supprimer(cibleId);
            JOURNAL.log(Level.INFO, "Bénévole supprimé (compte {0} par {1}, {2} affectation(s) retirée(s))",
                    cibleId, appelantId, retirees);
        }
        effacementPhoto.effacerApresValidation(cible);
        limitation.oublier(cible.pseudoNormalise());
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
