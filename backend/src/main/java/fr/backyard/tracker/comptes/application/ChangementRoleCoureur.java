package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.ChangementRoleRefuseException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.ConsultationInscriptions;
import fr.backyard.tracker.comptes.domaine.CourseNonTerminee;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Déroulé unique d'un changement de rôle d'un coureur (9.6b, 9.7 RG11), appelé dans la transaction du cas d'usage :
 * Compte relu sous verrou, transformation du domaine (garde « coureur connectable »), refus s'il est inscrit à une
 * Course non TERMINEE, mise à jour du rôle, journal de succès, puis fermeture de toutes ses sessions après
 * validation. Le compteur d'échecs de connexion n'est pas touché (même Compte, même pseudo). Journal sans pseudo, mot
 * de passe ni nom de Course. Seuls la transformation et le libellé de succès varient selon le rôle cible.
 */
final class ChangementRoleCoureur {

    private static final Logger JOURNAL = System.getLogger(ChangementRoleCoureur.class.getName());

    private final DepotComptes depotComptes;
    private final InvalidationAutresSessions invalidationSessions;
    private final ConsultationInscriptions consultationInscriptions;

    ChangementRoleCoureur(DepotComptes depotComptes, InvalidationAutresSessions invalidationSessions,
                          ConsultationInscriptions consultationInscriptions) {
        this.depotComptes = depotComptes;
        this.invalidationSessions = invalidationSessions;
        this.consultationInscriptions = consultationInscriptions;
    }

    /**
     * @param transformation méthode de domaine qui donne le Compte au nouveau rôle (garde comprise)
     * @param journalSucces  ligne INFO de succès, paramètres {0} = Compte cible, {1} = appelant
     * @return le Compte au nouveau rôle
     * @throws CompteNonGerableException      aucun coureur connectable pour cet identifiant : rien n'est modifié
     * @throws ChangementRoleRefuseException le coureur est inscrit à une Course non TERMINEE : rien n'est modifié
     */
    Compte executer(UUID appelantId, UUID cibleId, UnaryOperator<Compte> transformation, String journalSucces) {
        Compte change = transformation.apply(depotComptes.trouverParIdPourModification(cibleId)
                .orElseThrow(CompteNonGerableException::new));
        verifierAucuneCourseNonTerminee(cibleId);
        depotComptes.mettreAJour(change);
        JOURNAL.log(Level.INFO, journalSucces, cibleId, appelantId);
        Desactivation.fermerSessionsApresValidation(invalidationSessions, cibleId);
        return change;
    }

    private void verifierAucuneCourseNonTerminee(UUID cibleId) {
        try {
            CourseNonTerminee.refuserSiInscrit(consultationInscriptions.coursesNonTerminees(cibleId));
        } catch (ChangementRoleRefuseException refus) {
            JOURNAL.log(Level.INFO, "Changement de rôle refusé (compte {0}, motif {1}, {2} course(s))", cibleId,
                    refus.motif(), refus.courses().size());
            throw refus;
        }
    }
}
