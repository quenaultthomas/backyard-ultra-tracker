package fr.backyard.tracker.assemblage;

import fr.backyard.tracker.comptes.application.LireJetonsPhoto;
import fr.backyard.tracker.comptes.application.LirePseudos;
import fr.backyard.tracker.courses.domaine.AnnuairePhotos;
import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pont entre les contextes : implémente les ports AnnuairePseudos (3.5) et AnnuairePhotos (10.2 RG2) de courses en
 * lisant les Comptes de comptes, une seule recherche par appel. Exception nommée des règles ArchUnit (3.5, RG6), comme
 * {@link AnnuaireBenevolesAdapter} : les deux annuaires publics d'un Compte partagent ce même pont.
 */
@Component
public class AnnuairePseudosAdapter implements AnnuairePseudos, AnnuairePhotos {

    private final LirePseudos lirePseudos;
    private final LireJetonsPhoto lireJetonsPhoto;

    public AnnuairePseudosAdapter(LirePseudos lirePseudos, LireJetonsPhoto lireJetonsPhoto) {
        this.lirePseudos = lirePseudos;
        this.lireJetonsPhoto = lireJetonsPhoto;
    }

    @Override
    public Map<UUID, String> pseudosDe(Collection<UUID> idsComptes) {
        return lirePseudos.executer(idsComptes);
    }

    @Override
    public Map<UUID, String> jetonsPhotoDe(Collection<UUID> idsComptes) {
        return lireJetonsPhoto.executer(idsComptes);
    }
}
