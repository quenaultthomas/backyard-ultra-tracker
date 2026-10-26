package fr.backyard.tracker.comptes.domaine;

import java.util.Optional;

/** Port sortant : adresse cliente de la requête en cours, vide hors requête HTTP. */
public interface AdresseDeLaRequete {

    Optional<String> courante();
}
