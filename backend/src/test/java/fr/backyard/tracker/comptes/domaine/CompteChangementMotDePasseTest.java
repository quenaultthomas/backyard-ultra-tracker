package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Changement d'empreinte d'un Compte (incrément 1.6b, CA1). */
class CompteChangementMotDePasseTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-02T09:00:00Z");

    private Compte compte(Role role) {
        return Compte.reconstituer(UUID.randomUUID(), new Pseudo("Alice"), "empreinte:ancienne", role, CREE_LE);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Role.class)
    @DisplayName("CA1 - changerMotDePasse remplace l'empreinte et laisse id, pseudo, rôle et date de création inchangés")
    void doit_remplacer_seulement_l_empreinte(Role role) {
        Compte avant = compte(role);

        Compte apres = avant.changerMotDePasse("empreinte:nouvelle");

        assertThat(apres.empreinteMotDePasse()).isEqualTo("empreinte:nouvelle");
        assertThat(apres.id()).isEqualTo(avant.id());
        assertThat(apres.pseudo()).isEqualTo(avant.pseudo());
        assertThat(apres.pseudoNormalise()).isEqualTo(avant.pseudoNormalise());
        assertThat(apres.role()).isEqualTo(role);
        assertThat(apres.creeLe()).isEqualTo(CREE_LE);
        assertThat(apres.peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA1 - le compte d'origine n'est pas modifié (le domaine reste immuable)")
    void doit_laisser_le_compte_d_origine_intact() {
        Compte avant = compte(Role.COUREUR);

        avant.changerMotDePasse("empreinte:nouvelle");

        assertThat(avant.empreinteMotDePasse()).isEqualTo("empreinte:ancienne");
    }

    @Test
    @DisplayName("CA1 - une empreinte absente est refusée : un changement ne peut pas rendre un compte inutilisable")
    void doit_refuser_une_empreinte_absente() {
        Compte avant = compte(Role.COUREUR);

        assertThatThrownBy(() -> avant.changerMotDePasse(null)).isInstanceOf(NullPointerException.class);
    }
}
