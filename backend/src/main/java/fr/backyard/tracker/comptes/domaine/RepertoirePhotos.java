package fr.backyard.tracker.comptes.domaine;

import java.util.List;
import java.util.Optional;

/**
 * Port sortant : entrées directes du répertoire des photos, pour le nettoyage des orphelins (correctif audit jalon 10
 * RG4, RG6). Ne crée jamais le répertoire ; ne descend pas dans les sous-répertoires et ne suit pas les liens.
 */
public interface RepertoirePhotos {

    /**
     * Entrées directes du répertoire ; vide si le répertoire n'existe pas.
     *
     * @throws java.io.UncheckedIOException répertoire illisible ou qui n'est pas un répertoire
     */
    Optional<List<FichierPhoto>> lister();

    /**
     * Supprime l'entrée de ce nom, enfant direct du répertoire.
     *
     * @throws java.io.UncheckedIOException suppression impossible, y compris un fichier déjà disparu
     */
    void supprimer(String nom);
}
