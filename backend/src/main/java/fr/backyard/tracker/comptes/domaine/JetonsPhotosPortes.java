package fr.backyard.tracker.comptes.domaine;

import fr.backyard.tracker.partage.domaine.FormatImage;
import java.util.Collection;
import java.util.Map;

/** Port sortant : jetons de photo portés par un Compte, pour le nettoyage des orphelins (correctif audit jalon 10 RG4). */
public interface JetonsPhotosPortes {

    /**
     * Parmi ces jetons, ceux portés par un Compte, associés au format de sa photo, en une seule recherche. Un jeton
     * porté par aucun Compte est absent de la Map.
     */
    Map<String, FormatImage> portesParmi(Collection<String> jetons);
}
