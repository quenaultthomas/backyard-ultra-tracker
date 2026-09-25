package fr.backyard.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static fr.backyard.testsupport.TestData.T0;
import static fr.backyard.testsupport.TestData.at;
import static fr.backyard.testsupport.TestData.backyardTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** Spec increment 3 - RG13 (Race.start), RG14 (Race.isRegistrationOpen), CA21, CA22. */
class RaceStartAndRegistrationTest {

    @Test
    @DisplayName("CA21 - Race.start sur une course SETUP : RUNNING et startedAt positionne")
    void ca21_startSetupRace() {
        Race race = backyardTest(RaceStatus.SETUP);

        race.start(T0);

        assertThat(race.getStatus()).isEqualTo(RaceStatus.RUNNING);
        assertThat(race.getStartedAt()).isEqualTo(T0);
    }

    @ParameterizedTest(name = "CA21 - Race.start sur une course {0} : IllegalStateException")
    @EnumSource(value = RaceStatus.class, names = {"RUNNING", "FINISHED"})
    @DisplayName("CA21 - Race.start hors SETUP : IllegalStateException, statut et startedAt inchanges")
    void ca21_startNonSetupRaceIsRejected(RaceStatus status) {
        Race race = backyardTest(status);

        assertThatIllegalStateException().isThrownBy(() -> race.start(at("10:00:00")));

        assertThat(race.getStatus()).isEqualTo(status);
        assertThat(race.getStartedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("CA22 - isRegistrationOpen vaut true pour SETUP")
    void ca22_registrationOpenForSetup() {
        assertThat(backyardTest(RaceStatus.SETUP).isRegistrationOpen()).isTrue();
    }

    @ParameterizedTest(name = "CA22 - isRegistrationOpen vaut false pour {0}")
    @EnumSource(value = RaceStatus.class, names = {"RUNNING", "FINISHED"})
    @DisplayName("CA22 - isRegistrationOpen vaut false pour RUNNING et FINISHED")
    void ca22_registrationClosedOtherwise(RaceStatus status) {
        assertThat(backyardTest(status).isRegistrationOpen()).isFalse();
    }
}
