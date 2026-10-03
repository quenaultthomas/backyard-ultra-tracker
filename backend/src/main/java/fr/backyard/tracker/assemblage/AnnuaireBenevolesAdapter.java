package fr.backyard.tracker.assemblage;

import fr.backyard.tracker.comptes.application.VerifierBenevole;
import fr.backyard.tracker.courses.domaine.AnnuaireBenevoles;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Seul pont entre les contextes : implémente le port AnnuaireBenevoles de courses en lisant les Comptes de
 * comptes. Exception nommée des règles ArchUnit (2.4, CA5) ; aucune autre classe ne voit les deux contextes.
 */
@Component
public class AnnuaireBenevolesAdapter implements AnnuaireBenevoles {

    private final VerifierBenevole verifierBenevole;

    public AnnuaireBenevolesAdapter(VerifierBenevole verifierBenevole) {
        this.verifierBenevole = verifierBenevole;
    }

    @Override
    public boolean estBenevole(UUID idCompte) {
        return verifierBenevole.estBenevole(idCompte);
    }
}
