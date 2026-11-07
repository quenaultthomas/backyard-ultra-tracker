package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteIntrouvableOuInutilisableException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.JetonPhoto;
import fr.backyard.tracker.comptes.domaine.Photo;
import fr.backyard.tracker.comptes.domaine.PhotoInvalideException;
import fr.backyard.tracker.comptes.domaine.PhotoNonAutoriseeException;
import fr.backyard.tracker.comptes.domaine.StockagePhotos;
import fr.backyard.tracker.partage.domaine.FormatImage;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ajout ou remplacement de la photo de profil du Compte de la session (10.1 RG5), Compte relu sous verrou : contrôle du
 * rôle puis du contenu, écriture du fichier sous un jeton neuf, mise à jour du Compte, puis effacement de l'ancien
 * fichier après validation. Tout échec après l'écriture, ou l'annulation de la transaction, efface le fichier neuf :
 * l'ancienne photo reste intacte. Journal sans jeton, pseudo, nom ni octets.
 */
@Service
public class EnregistrerPhoto {

    private static final Logger JOURNAL = System.getLogger(EnregistrerPhoto.class.getName());

    private final DepotComptes depotComptes;
    private final StockagePhotos stockage;
    private final EffacementPhoto effacement;

    public EnregistrerPhoto(DepotComptes depotComptes, StockagePhotos stockage, EffacementPhoto effacement) {
        this.depotComptes = depotComptes;
        this.stockage = stockage;
        this.effacement = effacement;
    }

    /**
     * @return le jeton de la nouvelle photo
     * @throws CompteIntrouvableOuInutilisableException Compte disparu ou ne pouvant plus se connecter
     * @throws PhotoNonAutoriseeException              le Compte n'est pas un Compte coureur
     * @throws PhotoInvalideException                  contenu absent, trop volumineux ou de format inconnu
     */
    @Transactional
    public String executer(UUID compteId, byte[] octets) {
        Compte compte = depotComptes.trouverParIdPourModification(compteId)
                .filter(Compte::peutSeConnecter)
                .orElseThrow(CompteIntrouvableOuInutilisableException::new);
        compte.verifierPhotoModifiable();
        Photo photo = Photo.depuis(octets);
        String jeton = JetonPhoto.generer();
        stockage.enregistrer(jeton, photo.format(), photo.octets());
        mettreAJour(compte.avecPhoto(jeton, photo.format()));
        ApresTransaction.apresAnnulation(() -> effacerFichierNeuf(compteId, jeton, photo.format()));
        effacement.effacerAnciennePhotoApresValidation(compte);
        JOURNAL.log(Level.INFO, "Photo de profil enregistrée (compte {0})", compteId);
        return jeton;
    }

    private void mettreAJour(Compte avecPhoto) {
        try {
            depotComptes.mettreAJour(avecPhoto);
        } catch (RuntimeException echec) {
            effacerFichierNeuf(avecPhoto.id(), avecPhoto.photo().jeton(), avecPhoto.photo().format());
            throw echec;
        }
    }

    private void effacerFichierNeuf(UUID compteId, String jeton, FormatImage format) {
        try {
            stockage.effacer(jeton, format);
        } catch (RuntimeException echec) {
            JOURNAL.log(Level.WARNING, "Effacement de la photo impossible (compte {0})", compteId);
        }
    }
}
