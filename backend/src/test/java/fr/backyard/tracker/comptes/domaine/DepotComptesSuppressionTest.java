package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 9.3, CA4 : {@code void DepotComptes.supprimer(UUID id)} a un défaut qui lève
 * {@link UnsupportedOperationException}, comme les autres opérations facultatives du port, pour que les dépôts
 * réduits des tests existants restent valides.
 */
class DepotComptesSuppressionTest {

    @Test
    @DisplayName("CA4 - un double de DepotComptes qui ne redéfinit pas supprimer lève UnsupportedOperationException")
    void doit_lever_unsupported_operation_par_defaut_pour_supprimer() {
        DepotComptes depotReduit = new DepotComptes() {
            @Override
            public boolean existeParPseudoNormalise(String pseudoNormalise) {
                return false;
            }

            @Override
            public void enregistrer(Compte compte) {
            }
        };

        assertThatThrownBy(() -> depotReduit.supprimer(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
