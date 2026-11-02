package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 9.4, CA3 : exceptions de refus de suppression d'un admin (RG12, RG13).
 * Signatures attendues : {@code new AdminMasterNonSupprimableException()} et
 * {@code new DernierAdminActifNonSupprimableException()}, sans argument, {@code RuntimeException} avec
 * {@code String motif()}.
 */
class SuppressionAdminRefusExceptionsTest {

    @Test
    @DisplayName("CA3 - le refus admin master porte un motif stable et un message sans donnée personnelle")
    void doit_exposer_le_motif_et_le_message_du_refus_admin_master() {
        AdminMasterNonSupprimableException refus = new AdminMasterNonSupprimableException();

        assertThat(refus.motif()).isEqualTo("ADMIN_MASTER_NON_SUPPRIMABLE");
        assertThat(refus.getMessage()).isEqualTo("L'administrateur principal ne peut pas être supprimé.");
        assertThat(refus.toString()).doesNotContainIgnoringCase("patron").doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}");
    }

    @Test
    @DisplayName("CA3 - le refus dernier admin actif porte un motif stable et un message propre à la suppression")
    void doit_exposer_le_motif_et_le_message_du_refus_dernier_admin_actif() {
        DernierAdminActifNonSupprimableException refus = new DernierAdminActifNonSupprimableException();

        assertThat(refus.motif()).isEqualTo("DERNIER_ADMIN_ACTIF");
        assertThat(refus.getMessage()).isEqualTo("Impossible de supprimer le dernier administrateur actif.");
        assertThat(refus.toString()).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}");
    }

    @Test
    @DisplayName("CA3 - la désactivation du dernier admin actif garde son message \"désactiver\"")
    void doit_conserver_le_message_de_desactivation_du_dernier_admin_actif() {
        DernierAdminActifException desactivation = new DernierAdminActifException();

        assertThat(desactivation.getMessage()).isEqualTo("Impossible de désactiver le dernier administrateur actif.");
        assertThat(desactivation.motif()).isEqualTo("DERNIER_ADMIN_ACTIF");
    }

    @Test
    @DisplayName("CA3 - les deux refus de suppression sont distincts de ceux de la désactivation")
    void doit_distinguer_les_refus_de_suppression_de_ceux_de_desactivation() {
        assertThat(new DernierAdminActifNonSupprimableException()).isNotInstanceOf(DernierAdminActifException.class);
        assertThat(new AdminMasterNonSupprimableException()).isNotInstanceOf(AdminMasterNonDesactivableException.class);
    }
}
