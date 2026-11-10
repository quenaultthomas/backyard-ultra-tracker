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
 * Lecture publique d'une photo de profil par son jeton (10.1 RG7), en deux étapes (correctif audit jalon 10 RG3) :
 * {@link #resoudre} contrôle la forme du jeton avant tout accès, puis trouve le Compte par son jeton (la base fait foi)
 * sans toucher au stockage ; {@link #lire} lit ensuite le fichier, seulement si l'appelant en a besoin (pas pour un
 * 304). Tout échec donne la même {@link PhotoIntrouvableException} ; un fichier manquant alors que la base porte le
 * jeton est journalisé en ERROR.
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

    /** Résolution puis lecture, en un appel. */
    public PhotoLue executer(String jeton) {
        return lire(resoudre(jeton));
    }

    /**
     * Jeton et format de la photo portée par un Compte : une recherche en base au plus, aucun accès au stockage.
     *
     * @throws PhotoIntrouvableException jeton mal formé (sans accès à la base), inconnu ou photo retirée
     */
    @Transactional(readOnly = true)
    public ReferencePhoto resoudre(String jeton) {
        if (!JetonPhoto.estValide(jeton)) {
            throw new PhotoIntrouvableException();
        }
        Compte compte = depotComptes.trouverParJetonPhoto(jeton).orElseThrow(PhotoIntrouvableException::new);
        return compte.photo();
    }

    /**
     * Octets de la photo résolue : une lecture du stockage.
     *
     * @throws PhotoIntrouvableException fichier manquant (journalisé en ERROR avec l'identifiant du Compte, jamais le
     *                                    jeton : seul ce cas d'anomalie relit le Compte)
     */
    public PhotoLue lire(ReferencePhoto photo) {
        byte[] octets = stockage.lire(photo.jeton(), photo.format()).orElseThrow(() -> fichierManquant(photo));
        return new PhotoLue(photo.jeton(), photo.format(), octets);
    }

    private PhotoIntrouvableException fichierManquant(ReferencePhoto photo) {
        depotComptes.trouverParJetonPhoto(photo.jeton()).map(Compte::id).ifPresentOrElse(
                id -> JOURNAL.log(Level.ERROR, "Fichier de photo introuvable (compte {0})", id),
                () -> JOURNAL.log(Level.ERROR, "Fichier de photo introuvable"));
        return new PhotoIntrouvableException();
    }
}
