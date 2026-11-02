package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.AdminMasterNonSupprimableException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DernierAdminActifNonSupprimableException;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.RegistreTentativesConnexion;
import fr.backyard.tracker.comptes.domaine.SuppressionAdminRefuseeException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppression définitive d'un Compte ADMIN (actif ou désactivé) par l'admin master, dans une seule transaction : tous
 * les Comptes ADMIN relus sous verrou, garde du domaine, suppression du Compte, remise à zéro du compteur d'échecs de
 * son pseudo, puis fermeture de toutes ses sessions après validation. Un admin n'a ni affectation ni Inscription
 * (hypothèse à revoir au 9.7). Journal sans pseudo ni mot de passe.
 */
@Service
public class SupprimerAdmin {

    private static final Logger JOURNAL = System.getLogger(SupprimerAdmin.class.getName());

    private final DepotComptes depotComptes;
    private final InvalidationAutresSessions invalidationSessions;
    private final LimitationTentatives limitation;

    public SupprimerAdmin(DepotComptes depotComptes, InvalidationAutresSessions invalidationSessions,
                          RegistreTentativesConnexion registreTentatives, PolitiqueBlocage politiqueBlocage) {
        this.depotComptes = depotComptes;
        this.invalidationSessions = invalidationSessions;
        this.limitation = new LimitationTentatives(registreTentatives, politiqueBlocage);
    }

    /**
     * @throws CompteNonGerableException                aucun Compte ADMIN ni ADMIN_MASTER pour cet identifiant
     * @throws AdminMasterNonSupprimableException       la cible est l'admin master
     * @throws DernierAdminActifNonSupprimableException la cible est le dernier admin actif
     */
    @Transactional
    public void executer(UUID appelantId, UUID cibleId) {
        AdminsVerrouilles verrouilles = AdminsVerrouilles.charger(depotComptes, cibleId);
        Compte cible = verrouilles.cible();
        verifier(cible, verrouilles);
        depotComptes.supprimer(cibleId);
        limitation.oublier(cible.pseudoNormalise());
        JOURNAL.log(Level.INFO, "Admin supprimé (compte {0} par {1})", cibleId, appelantId);
        Desactivation.fermerSessionsApresValidation(invalidationSessions, cibleId);
    }

    /** Refus du domaine journalisé (motif seul) puis propagé tel quel. */
    private static void verifier(Compte cible, AdminsVerrouilles verrouilles) {
        try {
            cible.verifierSuppressionAdmin(verrouilles.admins());
        } catch (SuppressionAdminRefuseeException refus) {
            JOURNAL.log(Level.INFO, "Suppression refusée (compte {0}, motif {1})", cible.id(), refus.motif());
            throw refus;
        }
    }
}
