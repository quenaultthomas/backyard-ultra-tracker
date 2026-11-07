package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.JetonPhoto;
import fr.backyard.tracker.comptes.domaine.PhotoIntrouvableException;
import fr.backyard.tracker.comptes.domaine.ReferencePhoto;
import fr.backyard.tracker.comptes.domaine.StockagePhotos;
import fr.backyard.tracker.partage.domaine.FormatImage;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecture publique d'une photo de profil par son jeton (10.1 RG7) : forme du jeton contrôlée avant tout accès à la base
 * ou au disque, Compte trouvé par son jeton (la base fait foi), puis fichier lu. Tout échec donne la même
 * {@link PhotoIntrouvableException} ; un fichier manquant alors que la base porte le jeton est journalisé en ERROR.
 */
@Service
public class LirePhoto {

    private static final Logger JOURNAL = System.getLogger(LirePhoto.class.getName());

    /** Photo servie : jeton (pour l'ETag), format reconnu à l'envoi et octets. */
    public record PhotoLue(String jeton, FormatImage format, byte[] octets) {

        @Override
        public byte[] octets() {
            return octets.clone();
        }

        @Override
        public boolean equals(Object autre) {
            return autre instanceof PhotoLue lue && jeton.equals(lue.jeton) && format == lue.format
                    && Arrays.equals(octets, lue.octets);
        }

        @Override
        public int hashCode() {
            return jeton.hashCode();
        }

        @Override
        public String toString() {
            return "PhotoLue[format=" + format + ", taille=" + octets.length + " octets]";
        }
    }

    private final DepotComptes depotComptes;
    private final StockagePhotos stockage;

    public LirePhoto(DepotComptes depotComptes, StockagePhotos stockage) {
        this.depotComptes = depotComptes;
        this.stockage = stockage;
    }

    /** @throws PhotoIntrouvableException jeton mal formé ou inconnu, photo retirée ou fichier manquant */
    @Transactional(readOnly = true)
    public PhotoLue executer(String jeton) {
        if (!JetonPhoto.estValide(jeton)) {
            throw new PhotoIntrouvableException();
        }
        Compte compte = depotComptes.trouverParJetonPhoto(jeton).orElseThrow(PhotoIntrouvableException::new);
        ReferencePhoto photo = compte.photo();
        byte[] octets = stockage.lire(photo.jeton(), photo.format()).orElseThrow(() -> fichierManquant(compte));
        return new PhotoLue(photo.jeton(), photo.format(), octets);
    }

    private static PhotoIntrouvableException fichierManquant(Compte compte) {
        JOURNAL.log(Level.ERROR, "Fichier de photo introuvable (compte {0})", compte.id());
        return new PhotoIntrouvableException();
    }
}
