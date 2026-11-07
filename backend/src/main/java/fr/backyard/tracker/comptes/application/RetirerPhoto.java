package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteIntrouvableOuInutilisableException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retrait de la photo de profil du Compte de la session, quel que soit son rôle (10.1 RG6), Compte relu sous verrou :
 * jeton remis à nul, puis effacement du fichier après validation. Idempotent : sans photo, rien n'est fait ni journalisé.
 */
@Service
public class RetirerPhoto {

    private static final Logger JOURNAL = System.getLogger(RetirerPhoto.class.getName());

    private final DepotComptes depotComptes;
    private final EffacementPhoto effacement;

    public RetirerPhoto(DepotComptes depotComptes, EffacementPhoto effacement) {
        this.depotComptes = depotComptes;
        this.effacement = effacement;
    }

    /** @throws CompteIntrouvableOuInutilisableException Compte disparu */
    @Transactional
    public void executer(UUID compteId) {
        Compte compte = depotComptes.trouverParIdPourModification(compteId)
                .orElseThrow(CompteIntrouvableOuInutilisableException::new);
        if (compte.photo() == null) {
            return;
        }
        depotComptes.mettreAJour(compte.retirerPhoto());
        effacement.effacerAnciennePhotoApresValidation(compte);
        JOURNAL.log(Level.INFO, "Photo de profil retirée (compte {0})", compteId);
    }
}
