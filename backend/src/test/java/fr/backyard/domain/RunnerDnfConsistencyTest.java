package fr.backyard.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec increment 1, addendum du 2026-09-26 - CA23 (RG10, RG11), partie [unit].
 * La partie [persistance] est dans {@code fr.backyard.persistence.Increment1AddendumPersistenceTest}.
 */
@Tag("INC-1")
@Tag("INC1-CA23")
class RunnerDnfConsistencyTest {

    private final Race race = new Race("Race CA23", LocalDate.of(2026, 10, 1), 6700, 3600, 100);

    @Test
    @DisplayName("CA23 - markDnf(null, 3) : IllegalArgumentException, le coureur reste ACTIVE sans champ DNF")
    void ca23_markDnfWithoutReasonIsRejectedAndRunnerUnchanged() {
        Runner alice = new Runner(race, 1, "Alice", "tok-ca23-a");

        assertThatThrownBy(() -> alice.markDnf(null, 3)).isInstanceOf(IllegalArgumentException.class);

        assertThat(alice.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(alice.getDnfReason()).isNull();
        assertThat(alice.getDnfYard()).isNull();
    }

    @Test
    @DisplayName("CA23 - markDnf(VOLUNTARY, 0) : IllegalArgumentException (dnf_yard >= 1), le coureur reste inchange")
    void ca23_markDnfWithYardZeroIsRejectedAndRunnerUnchanged() {
        Runner alice = new Runner(race, 1, "Alice", "tok-ca23-a");

        assertThatThrownBy(() -> alice.markDnf(DnfReason.VOLUNTARY, 0)).isInstanceOf(IllegalArgumentException.class);

        assertThat(alice.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(alice.getDnfReason()).isNull();
        assertThat(alice.getDnfYard()).isNull();
    }

    @Test
    @DisplayName("CA23 - markDnf(TIMEOUT, 3) : DNF, dnfReason TIMEOUT, dnfYard 3")
    void ca23_markDnfSetsStatusReasonAndYardTogether() {
        Runner alice = new Runner(race, 1, "Alice", "tok-ca23-a");

        alice.markDnf(DnfReason.TIMEOUT, 3);

        assertThat(alice.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(alice.getDnfReason()).isEqualTo(DnfReason.TIMEOUT);
        assertThat(alice.getDnfYard()).isEqualTo(3);
    }

    @Test
    @DisplayName("CA23 - markWinner() : WINNER, dnfReason et dnfYard null (lecture A3 de RG10)")
    void ca23_markWinnerLeavesDnfFieldsNull() {
        Runner bob = new Runner(race, 2, "Bob", "tok-ca23-b");

        bob.markWinner();

        assertThat(bob.getStatus()).isEqualTo(RunnerStatus.WINNER);
        assertThat(bob.getDnfReason()).isNull();
        assertThat(bob.getDnfYard()).isNull();
    }
}
