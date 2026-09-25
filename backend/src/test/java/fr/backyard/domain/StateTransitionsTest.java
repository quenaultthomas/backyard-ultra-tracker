package fr.backyard.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static fr.backyard.testsupport.TestData.asDnf;
import static fr.backyard.testsupport.TestData.asWinner;
import static fr.backyard.testsupport.TestData.r1;
import static fr.backyard.testsupport.TestData.runner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** Spec increment 2 - RG29 (transitions uniques du domaine), CA61. */
class StateTransitionsTest {

    private final Race r1 = r1(101L);

    @Test
    @DisplayName("CA61 - reactivate() sur un coureur DNF TIMEOUT dnfYard 3 : ACTIVE, dnfReason et dnfYard null")
    void ca61_reactivateDnfRunner() {
        Runner runner = asDnf(runner(1001L, r1), DnfReason.TIMEOUT, 3);

        runner.reactivate();

        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(runner.getDnfReason()).isNull();
        assertThat(runner.getDnfYard()).isNull();
    }

    @Test
    @DisplayName("CA61 - reactivate() sur un coureur ACTIVE : IllegalStateException, statut inchange")
    void ca61_reactivateActiveRunnerIsRejected() {
        Runner runner = runner(1002L, r1);

        assertThatIllegalStateException().isThrownBy(runner::reactivate);

        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(runner.getDnfReason()).isNull();
        assertThat(runner.getDnfYard()).isNull();
    }

    @Test
    @DisplayName("CA61 - reactivate() sur un coureur WINNER : IllegalStateException, statut inchange")
    void ca61_reactivateWinnerIsRejected() {
        Runner runner = asWinner(runner(1003L, r1));

        assertThatIllegalStateException().isThrownBy(runner::reactivate);

        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.WINNER);
    }

    @Test
    @DisplayName("CA40 / RG29 - markDnf(VOLUNTARY, 3) sur un coureur ACTIVE : DNF, VOLUNTARY, dnfYard 3")
    void rg29_markDnf() {
        Runner runner = runner(1004L, r1);

        runner.markDnf(DnfReason.VOLUNTARY, 3);

        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(runner.getDnfReason()).isEqualTo(DnfReason.VOLUNTARY);
        assertThat(runner.getDnfYard()).isEqualTo(3);
    }

    @Test
    @DisplayName("CA33 / RG29 - markWinner() sur un coureur ACTIVE : WINNER, dnfReason et dnfYard null")
    void rg29_markWinner() {
        Runner runner = runner(1005L, r1);

        runner.markWinner();

        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.WINNER);
        assertThat(runner.getDnfReason()).isNull();
        assertThat(runner.getDnfYard()).isNull();
    }

    @Test
    @DisplayName("CA33 / RG29 - Race.finish() sur une course RUNNING : FINISHED")
    void rg29_raceFinish() {
        r1.finish();

        assertThat(r1.getStatus()).isEqualTo(RaceStatus.FINISHED);
    }
}
