package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Incrément 9.3, CA1 (partie Compte) : garde de rôle de la suppression d'un bénévole (RG1).
 * Signature attendue : {@code void Compte.verifierSuppressionBenevole()} qui lève {@link CompteNonGerableException}
 * si le rôle n'est pas BENEVOLE ; l'état actif n'intervient pas.
 */
class CompteSuppressionBenevoleTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");

    private static Compte compte(Role role, boolean actif) {
        return Compte.reconstituer(UUID.fromString("00000000-0000-0000-0000-0000000000b1"), new Pseudo("Cible"),
                "empreinte", role, CREE_LE, actif);
    }

    @ParameterizedTest(name = "CA1 - un compte {0} n''est pas supprimable comme bénévole")
    @EnumSource(value = Role.class, names = {"COUREUR", "ADMIN", "ADMIN_MASTER"})
    @DisplayName("CA1 - la garde refuse tout rôle autre que BENEVOLE")
    void doit_refuser_la_suppression_d_un_compte_qui_n_est_pas_benevole(Role role) {
        Compte compte = compte(role, true);

        assertThatThrownBy(compte::verifierSuppressionBenevole).isInstanceOf(CompteNonGerableException.class);
    }

    @Test
    @DisplayName("CA1 - la garde laisse passer un bénévole actif")
    void doit_accepter_la_suppression_d_un_benevole_actif() {
        Compte benevole = compte(Role.BENEVOLE, true);

        assertThatCode(benevole::verifierSuppressionBenevole).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA1 - la garde laisse passer un bénévole désactivé")
    void doit_accepter_la_suppression_d_un_benevole_desactive() {
        Compte benevole = compte(Role.BENEVOLE, false);

        assertThatCode(benevole::verifierSuppressionBenevole).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "CA1 - un compte {0} désactivé n''est pas non plus supprimable comme bénévole")
    @EnumSource(value = Role.class, names = {"COUREUR", "ADMIN"})
    @DisplayName("CA1 - l'état désactivé ne change pas le refus d'un rôle non géré")
    void doit_refuser_aussi_un_compte_non_benevole_desactive(Role role) {
        Compte compte = compte(role, false);

        assertThatThrownBy(compte::verifierSuppressionBenevole).isInstanceOf(CompteNonGerableException.class);
    }
}
