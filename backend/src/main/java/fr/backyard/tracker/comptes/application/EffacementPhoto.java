package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.ReferencePhoto;
import fr.backyard.tracker.comptes.domaine.StockagePhotos;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Unique effacement du fichier d'une photo de profil (10.1 RG9), après la validation de la transaction de l'appelant :
 * la base, qui fait foi, ne porte déjà plus le jeton (Compte anonymisé, supprimé, photo retirée ou remplacée). Un échec
 * d'effacement est journalisé en WARN et n'affecte jamais l'appelant : le fichier orphelin n'est jamais servi. Journal
 * sans jeton, chemin ni détail de l'erreur.
 */
@Component
public class EffacementPhoto {

    private static final Logger JOURNAL = System.getLogger(EffacementPhoto.class.getName());

    private final StockagePhotos stockage;

    public EffacementPhoto(StockagePhotos stockage) {
        this.stockage = stockage;
    }

    /**
     * Suppression ou anonymisation d'un Compte : {@code compteAvant} est le Compte tel qu'il était avant, l'effacement
     * de sa photo éventuelle est journalisé « effacée ».
     */
    public void effacerApresValidation(Compte compteAvant) {
        planifier(compteAvant.id(), compteAvant.photo(), true);
    }

    /** Retrait ou remplacement : l'ancienne photo du Compte est effacée sans ligne « effacée ». */
    void effacerAnciennePhotoApresValidation(Compte compteAvant) {
        planifier(compteAvant.id(), compteAvant.photo(), false);
    }

    private void planifier(UUID compteId, ReferencePhoto photo, boolean journaliser) {
        if (photo != null) {
            ApresTransaction.apresValidation(() -> effacer(compteId, photo, journaliser));
        }
    }

    private void effacer(UUID compteId, ReferencePhoto photo, boolean journaliser) {
        try {
            stockage.effacer(photo.jeton(), photo.format());
        } catch (RuntimeException echec) {
            JOURNAL.log(Level.WARNING, "Effacement de la photo impossible (compte {0})", compteId);
            return;
        }
        if (journaliser) {
            JOURNAL.log(Level.INFO, "Photo de profil effacée (compte {0})", compteId);
        }
    }
}
