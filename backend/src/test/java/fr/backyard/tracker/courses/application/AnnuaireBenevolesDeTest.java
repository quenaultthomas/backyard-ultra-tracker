package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.AnnuaireBenevoles;
import java.util.Set;
import java.util.UUID;

/** Double du port AnnuaireBenevoles (2.4) : ne connaît comme bénévoles que les identifiants donnés. */
final class AnnuaireBenevolesDeTest implements AnnuaireBenevoles {

    private final Set<UUID> benevoles;

    AnnuaireBenevolesDeTest(UUID... benevoles) {
        this.benevoles = Set.of(benevoles);
    }

    @Override
    public boolean estBenevole(UUID idCompte) {
        return benevoles.contains(idCompte);
    }
}
