package fr.backyard.tracker.courses.domaine;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Port sortant : pseudos des Comptes, que le contexte courses ne connaît que par leur identifiant. Lecture seule, en
 * une seule recherche pour tous les identifiants demandés.
 */
public interface AnnuairePseudos {

    /**
     * Pseudo tel que saisi de chaque Compte trouvé. Un identifiant inconnu est absent de la Map (jamais d'exception) ;
     * une collection vide donne une Map vide.
     */
    Map<UUID, String> pseudosDe(Collection<UUID> idsComptes);
}
