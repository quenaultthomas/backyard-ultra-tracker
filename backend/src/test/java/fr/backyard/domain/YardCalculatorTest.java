package fr.backyard.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static fr.backyard.testsupport.TestData.T0;
import static fr.backyard.testsupport.TestData.at;
import static fr.backyard.testsupport.TestData.mentionsNumber;
import static fr.backyard.testsupport.TestData.r1;
import static fr.backyard.testsupport.TestData.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** Spec increment 2 - RG1 a RG4, CA1 a CA8. Fonctions pures, instant passe en parametre. */
class YardCalculatorTest {

    private static final long R1_ID = 101L;

    private final YardCalculator calculator = new YardCalculator();
    private final Race r1 = r1(R1_ID);

    @Test
    @DisplayName("CA1 - le premier instant de la course (started_at) appartient au yard 1")
    void ca1_firstInstantIsYardOne() {
        assertThat(calculator.yardAt(r1, at("08:00:00"))).isEqualTo(1);
    }

    @Test
    @DisplayName("CA2 - bascule exacte : 08:59:59.999 -> 1, 09:00:00.000 -> 2, 09:00:00.001 -> 2")
    void ca2_exactYardBoundary() {
        assertThat(calculator.yardAt(r1, at("08:59:59.999"))).isEqualTo(1);
        assertThat(calculator.yardAt(r1, at("09:00:00.000"))).isEqualTo(2);
        assertThat(calculator.yardAt(r1, at("09:00:00.001"))).isEqualTo(2);
    }

    @Test
    @DisplayName("CA2 / RG3 - les nanosecondes au-dela de la milliseconde sont tronquees (08:59:59.999999999 -> 1)")
    void ca2_nanosecondsAreTruncated() {
        assertThat(calculator.yardAt(r1, Instant.parse("2026-10-03T08:59:59.999999999Z"))).isEqualTo(1);
    }

    @Test
    @DisplayName("CA3 - 13:30 sur R1 est dans le yard 6")
    void ca3_yardInTheMiddleOfTheRace() {
        assertThat(calculator.yardAt(r1, at("13:30:00"))).isEqualTo(6);
    }

    @Test
    @DisplayName("CA4 - instant anterieur au depart : yard 0")
    void ca4_beforeStartIsYardZero() {
        assertThat(calculator.yardAt(r1, at("07:59:59"))).isZero();
    }

    @Test
    @DisplayName("CA4 - course SETUP sans started_at : yard 0")
    void ca4_raceWithoutStartedAtIsYardZero() {
        Race setup = race(102L, 6706, 3600, 50, RaceStatus.SETUP, null);

        assertThat(calculator.yardAt(setup, at("13:30:00"))).isZero();
    }

    @Test
    @DisplayName("CA5 - bornes du yard 3 de R1 : debut 10:00, fin 11:00")
    void ca5_yardBounds() {
        assertThat(calculator.yardStart(r1, 3)).isEqualTo(at("10:00:00"));
        assertThat(calculator.yardEnd(r1, 3)).isEqualTo(at("11:00:00"));
    }

    @Test
    @DisplayName("CA5 / RG2 - le yard 1 commence exactement a started_at")
    void ca5_yardOneStartsAtStartedAt() {
        assertThat(calculator.yardStart(r1, 1)).isEqualTo(T0);
        assertThat(calculator.yardEnd(r1, 1)).isEqualTo(at("09:00:00"));
    }

    @Test
    @DisplayName("CA5 - yardStart(R1, 0) : IllegalArgumentException")
    void ca5_yardStartBelowOneIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> calculator.yardStart(r1, 0));
    }

    @Test
    @DisplayName("CA6 - yard courant de R1 RUNNING a 13:30 : 6")
    void ca6_currentYardOfRunningRace() {
        assertThat(calculator.currentYard(r1, at("13:30:00"))).isEqualTo(6);
    }

    @Test
    @DisplayName("CA6 - yard courant d'une copie de R1 FINISHED (meme startedAt) : 0")
    void ca6_currentYardOfFinishedRaceIsZero() {
        Race finished = race(103L, 6706, 3600, 50, RaceStatus.FINISHED, T0);

        assertThat(calculator.currentYard(finished, at("13:30:00"))).isZero();
    }

    @Test
    @DisplayName("CA6 - yard courant d'une course SETUP sans startedAt : 0")
    void ca6_currentYardOfSetupRaceIsZero() {
        Race setup = race(104L, 6706, 3600, 50, RaceStatus.SETUP, null);

        assertThat(calculator.currentYard(setup, at("13:30:00"))).isZero();
    }

    @Test
    @DisplayName("CA7 - course RUNNING sans started_at : IllegalStateException citant l'id de la course")
    void ca7_runningRaceWithoutStartedAtIsInconsistent() {
        Race inconsistent = race(4242L, 6706, 3600, 50, RaceStatus.RUNNING, null);

        assertThatIllegalStateException()
            .isThrownBy(() -> calculator.currentYard(inconsistent, at("13:30:00")))
            .satisfies(e -> assertThat(mentionsNumber(e.getMessage(), 4242L))
                .as("message '%s' contient l'id 4242", e.getMessage()).isTrue());
    }

    @Test
    @DisplayName("CA8 - deux courses au meme instant 10:10 : R1 yard 3, R2 (1800 s, depart 09:30) yard 2")
    void ca8_twoRacesHaveIndependentYards() {
        Race r2 = race(202L, 6706, 1800, 50, RaceStatus.RUNNING, at("09:30:00"));

        assertThat(calculator.yardAt(r1, at("10:10:00"))).isEqualTo(3);
        assertThat(calculator.yardAt(r2, at("10:10:00"))).isEqualTo(2);
    }
}
