package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 7.3, CA1 (RG5) : la borne de 24 h de l'âge d'un scan est une règle du domaine. Signature attendue :
 * valeur {@code AgeDuScan} avec fabrique {@code AgeDuScan.de(long millisecondes)} qui lève
 * {@link ScanTropAncienException} (RuntimeException, constructeur sans argument) au-delà de 86 400 000 ms (inclus
 * accepté) et expose {@code long millisecondes()}.
 */
class AgeDuScanTest {

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 5_000L, 86_399_999L, 86_400_000L})
    @DisplayName("CA1 - un scan d'âge 0, 1 ou jusqu'à 86 400 000 ms (borne incluse) est recevable")
    void doit_accepter_un_age_jusqu_a_vingt_quatre_heures_incluses(long age) {
        assertThatCode(() -> AgeDuScan.de(age)).doesNotThrowAnyException();
        assertThat(AgeDuScan.de(age).millisecondes()).isEqualTo(age);
    }

    @ParameterizedTest
    @ValueSource(longs = {86_400_001L, 90_000_000L, Long.MAX_VALUE})
    @DisplayName("CA1 - un scan d'âge 86 400 001 ms ou plus est refusé : ScanTropAncienException")
    void doit_refuser_un_age_superieur_a_vingt_quatre_heures(long age) {
        assertThatThrownBy(() -> AgeDuScan.de(age)).isInstanceOf(ScanTropAncienException.class);
    }
}
