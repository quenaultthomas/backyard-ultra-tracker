package fr.backyard.tracker.comptes.domaine;

import fr.backyard.tracker.partage.domaine.FormatImage;
import java.util.Optional;

/**
 * Port sortant du stockage des fichiers de photo, désignés uniquement par leur jeton et leur format (jamais par un nom
 * ou un chemin venu d'un client). La base reste la source de vérité : un fichier sans jeton en base n'est jamais servi.
 */
public interface StockagePhotos {

    /** Écrit le fichier de façon atomique : un fichier partiellement écrit n'est jamais lisible. */
    void enregistrer(String jeton, FormatImage format, byte[] octets);

    /** Octets du fichier, vide s'il n'existe pas. */
    Optional<byte[]> lire(String jeton, FormatImage format);

    /** Efface le fichier ; un fichier déjà absent n'est pas une erreur. */
    void effacer(String jeton, FormatImage format);
}
