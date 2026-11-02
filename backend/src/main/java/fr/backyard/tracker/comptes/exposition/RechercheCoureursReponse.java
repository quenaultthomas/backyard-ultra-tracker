package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.application.RechercherCoureurs;
import fr.backyard.tracker.comptes.domaine.Compte;
import java.util.List;
import java.util.UUID;

/** Résultat de la recherche de coureurs : pour chaque coureur, son identifiant et son pseudo, rien d'autre. */
public record RechercheCoureursReponse(List<CoureurTrouve> coureurs, boolean plusDeResultats) {

    static RechercheCoureursReponse depuis(RechercherCoureurs.Resultat resultat) {
        return new RechercheCoureursReponse(resultat.coureurs().stream().map(CoureurTrouve::depuis).toList(),
                resultat.plusDeResultats());
    }

    /** Pseudo tel qu'enregistré (casse d'origine). */
    public record CoureurTrouve(UUID id, String pseudo) {

        static CoureurTrouve depuis(Compte compte) {
            return new CoureurTrouve(compte.id(), compte.pseudo().valeur());
        }
    }
}
