package fr.backyard.persistence;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.Passage;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.Race;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.repository.PassageRepository;
import fr.backyard.repository.RaceRepository;
import fr.backyard.repository.RunnerRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Spec increment 1, addendum du 2026-09-26 (reserve R1-3) - CA23 et CA25 (parties [persistance]),
 * CA26, CA27, CA28, CA29. Conventions A2 : {@code flush()} + {@code clear()} avant toute relecture ;
 * les CA « contrainte CHECK » inserent par SQL natif ({@link JdbcTemplate}, qui contourne Bean Validation
 * et traduit l'erreur en {@link DataIntegrityViolationException}) puis verifient le chemin JPA.
 * H2 en mode PostgreSQL (PO7) : la reserve RT1 s'applique.
 */
@DataJpaTest
@ActiveProfiles("test")
@Tag("INC-1")
class Increment1AddendumPersistenceTest {

    private static final LocalDate RACE_DATE = LocalDate.of(2026, 10, 1);
    private static final Instant SCANNED_AT = Instant.parse("2026-10-01T08:45:00Z");

    @Autowired
    private RaceRepository raceRepository;

    @Autowired
    private RunnerRepository runnerRepository;

    @Autowired
    private PassageRepository passageRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void createJdbcTemplate() {
        jdbc = new JdbcTemplate(dataSource);
    }

    private void flushAndClearPersistenceContext() {
        entityManager.flush();
        entityManager.clear();
    }

    /** Comptage JDBC : ne declenche pas l'auto-flush Hibernate d'une entite dont l'insertion a echoue. */
    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    private Race persistedRace(String name) {
        return raceRepository.saveAndFlush(new Race(name, RACE_DATE, 6700, 3600, 100));
    }

    private static void assertViolationOnlyOn(Throwable thrown, String property) {
        assertThat(thrown).isInstanceOf(ConstraintViolationException.class);
        assertThat(((ConstraintViolationException) thrown).getConstraintViolations())
            .extracting(ConstraintViolation::getPropertyPath)
            .extracting(Object::toString)
            .containsExactly(property);
    }

    /** Accepte l'une des deux exceptions prevues par la spec pour le chemin JPA. */
    private static void assertRejectedByValidationOnOrDatabase(Throwable thrown, String property) {
        assertThat(thrown).isInstanceOfAny(ConstraintViolationException.class, DataIntegrityViolationException.class);
        if (thrown instanceof ConstraintViolationException) {
            assertViolationOnlyOn(thrown, property);
        }
    }

    // ── CA23 — Cohérence DNF, partie persistance ────────────────────────────

    @Test
    @Tag("INC1-CA23")
    @DisplayName("CA23 - un coureur DNF TIMEOUT yard 3 est relu depuis la base avec DNF, TIMEOUT, 3")
    void ca23_dnfRunnerIsPersistedWithReasonAndYard() {
        Race race = persistedRace("Race CA23");
        Runner alice = new Runner(race, 1, "Alice", "tok-ca23-a");
        alice.markDnf(DnfReason.TIMEOUT, 3);
        Runner saved = runnerRepository.saveAndFlush(alice);

        flushAndClearPersistenceContext();
        Runner found = runnerRepository.findById(saved.getId()).orElseThrow();

        assertThat(found).isNotSameAs(saved);
        assertThat(found.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(found.getDnfReason()).isEqualTo(DnfReason.TIMEOUT);
        assertThat(found.getDnfYard()).isEqualTo(3);
    }

    @Test
    @Tag("INC1-CA23")
    @DisplayName("CA23 - un coureur WINNER est relu depuis la base avec dnfReason et dnfYard null")
    void ca23_winnerIsPersistedWithoutDnfFields() {
        Race race = persistedRace("Race CA23");
        Runner bob = new Runner(race, 2, "Bob", "tok-ca23-b");
        bob.markWinner();
        Runner saved = runnerRepository.saveAndFlush(bob);

        flushAndClearPersistenceContext();
        Runner found = runnerRepository.findById(saved.getId()).orElseThrow();

        assertThat(found).isNotSameAs(saved);
        assertThat(found.getStatus()).isEqualTo(RunnerStatus.WINNER);
        assertThat(found.getDnfReason()).isNull();
        assertThat(found.getDnfYard()).isNull();
    }

    // ── CA25 — Immuabilité des passages, partie persistance ─────────────────

    @Test
    @Tag("INC1-CA25")
    @DisplayName("CA25 - re-sauvegarder un passage relu ne change ni ses champs ni le nombre de passages")
    void ca25_savingAPersistedPassageAgainChangesNothing() {
        Race race = persistedRace("Race CA25");
        Runner runner = runnerRepository.saveAndFlush(new Runner(race, 1, "Alice", "tok-ca25"));
        Passage saved = passageRepository.saveAndFlush(new Passage(runner, 1, PassageSource.SCAN, SCANNED_AT));
        flushAndClearPersistenceContext();
        long countBefore = passageRepository.count();

        Passage reloaded = passageRepository.findById(saved.getId()).orElseThrow();
        passageRepository.save(reloaded);
        flushAndClearPersistenceContext();
        Passage found = passageRepository.findById(saved.getId()).orElseThrow();

        assertThat(found).isNotSameAs(reloaded);
        assertThat(found.getRunner().getId()).isEqualTo(runner.getId());
        assertThat(found.getYardNumber()).isEqualTo(1);
        assertThat(found.getSource()).isEqualTo(PassageSource.SCAN);
        assertThat(found.getScannedAt()).isEqualTo(SCANNED_AT);
        assertThat(passageRepository.count()).isEqualTo(countBefore);
    }

    // ── CA26 — Nom obligatoire et non vide pour Runner et Race ──────────────

    @Test
    @Tag("INC1-CA26")
    @DisplayName("CA26 - Runner avec name null : rejete (ConstraintViolation sur name ou NOT NULL), aucune ligne")
    void ca26_runnerWithNullNameIsRejected() {
        Race race = persistedRace("Race CA26");
        long runnersBefore = countRows("runner");

        Throwable thrown = catchThrowable(() ->
            runnerRepository.saveAndFlush(new Runner(race, 1, null, "tok-ca26-null")));

        assertRejectedByValidationOnOrDatabase(thrown, "name");
        assertThat(countRows("runner")).isEqualTo(runnersBefore);
    }

    @Test
    @Tag("INC1-CA26")
    @DisplayName("CA26 - Runner avec name vide \"\" : ConstraintViolationException sur name, aucune ligne")
    void ca26_runnerWithEmptyNameIsRejectedByDomainValidation() {
        Race race = persistedRace("Race CA26");
        long runnersBefore = countRows("runner");

        Throwable thrown = catchThrowable(() ->
            runnerRepository.saveAndFlush(new Runner(race, 2, "", "tok-ca26-empty")));

        assertViolationOnlyOn(thrown, "name");
        assertThat(countRows("runner")).isEqualTo(runnersBefore);
    }

    @Test
    @Tag("INC1-CA26")
    @DisplayName("CA26 - Runner avec name blanc \"   \" : ConstraintViolationException sur name, aucune ligne")
    void ca26_runnerWithBlankNameIsRejectedByDomainValidation() {
        Race race = persistedRace("Race CA26");
        long runnersBefore = countRows("runner");

        Throwable thrown = catchThrowable(() ->
            runnerRepository.saveAndFlush(new Runner(race, 3, "   ", "tok-ca26-blank")));

        assertViolationOnlyOn(thrown, "name");
        assertThat(countRows("runner")).isEqualTo(runnersBefore);
    }

    @Test
    @Tag("INC1-CA26")
    @DisplayName("CA26 - Race avec name blanc \"   \" : ConstraintViolationException sur name, aucune course")
    void ca26_raceWithBlankNameIsRejectedByDomainValidation() {
        long racesBefore = countRows("race");

        Throwable thrown = catchThrowable(() ->
            raceRepository.saveAndFlush(new Race("   ", RACE_DATE, 6700, 3600, 100)));

        assertViolationOnlyOn(thrown, "name");
        assertThat(countRows("race")).isEqualTo(racesBefore);
    }

    // ── CA27 — CHECK des paramètres de boucle ───────────────────────────────

    private void insertRaceNatively(String name, int loopDistance, int loopDuration, int loopElevation) {
        jdbc.update("INSERT INTO race (name, race_date, status, loop_distance, loop_duration, loop_elevation) "
                + "VALUES (?, ?, 'SETUP', ?, ?, ?)",
            name, Date.valueOf(RACE_DATE), loopDistance, loopDuration, loopElevation);
    }

    @Test
    @Tag("INC1-CA27")
    @DisplayName("CA27 - SQL natif : loop_distance = 0 rejete par le CHECK")
    void ca27_nativeInsertWithZeroLoopDistanceIsRejected() {
        long raceRowsBefore = countRows("race");
        assertThatThrownBy(() -> insertRaceNatively("CA27-distance-0", 0, 3600, 0))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countRows("race")).isEqualTo(raceRowsBefore);
    }

    @Test
    @Tag("INC1-CA27")
    @DisplayName("CA27 - SQL natif : loop_duration = 0 rejete par le CHECK")
    void ca27_nativeInsertWithZeroLoopDurationIsRejected() {
        long raceRowsBefore = countRows("race");
        assertThatThrownBy(() -> insertRaceNatively("CA27-duration-0", 6700, 0, 0))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countRows("race")).isEqualTo(raceRowsBefore);
    }

    @Test
    @Tag("INC1-CA27")
    @DisplayName("CA27 - SQL natif : loop_elevation = -1 rejete par le CHECK")
    void ca27_nativeInsertWithNegativeLoopElevationIsRejected() {
        long raceRowsBefore = countRows("race");
        assertThatThrownBy(() -> insertRaceNatively("CA27-elevation-neg", 6700, 3600, -1))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countRows("race")).isEqualTo(raceRowsBefore);
    }

    @Test
    @Tag("INC1-CA27")
    @DisplayName("CA27 - SQL natif : valeurs limites distance 1, duree 1, D+ 0 acceptees")
    void ca27_nativeInsertWithBoundaryLoopValuesIsAccepted() {
        insertRaceNatively("CA27-limites", 1, 1, 0);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM race WHERE name = 'CA27-limites' AND loop_distance = 1 "
                + "AND loop_duration = 1 AND loop_elevation = 0", Long.class)).isEqualTo(1L);
    }

    @Test
    @Tag("INC1-CA27")
    @DisplayName("CA27 - chemin JPA : Race avec loop_distance 0 rejetee, aucune course creee")
    void ca27_jpaRaceWithZeroLoopDistanceIsRejected() {
        long raceRowsBefore = countRows("race");

        Throwable thrown = catchThrowable(() ->
            raceRepository.saveAndFlush(new Race("CA27-jpa-0", RACE_DATE, 0, 3600, 0)));

        assertRejectedByValidationOnOrDatabase(thrown, "loopDistance");
        assertThat(countRows("race")).isEqualTo(raceRowsBefore);
    }

    // ── CA28 — CHECK du dossard ─────────────────────────────────────────────

    private void insertRunnerNatively(long raceId, int bib, String qrToken) {
        jdbc.update("INSERT INTO runner (race_id, bib, name, qr_token, status) VALUES (?, ?, 'X', ?, 'ACTIVE')",
            raceId, bib, qrToken);
    }

    @ParameterizedTest(name = "bib = {0}")
    @ValueSource(ints = {0, -1})
    @Tag("INC1-CA28")
    @DisplayName("CA28 - SQL natif : bib <= 0 rejete par le CHECK")
    void ca28_nativeInsertWithNonPositiveBibIsRejected(int bib) {
        Race race = persistedRace("Race CA28");
        long runnerRowsBefore = countRows("runner");

        assertThatThrownBy(() -> insertRunnerNatively(race.getId(), bib, "tok-ca28-" + bib))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countRows("runner")).isEqualTo(runnerRowsBefore);
    }

    @Test
    @Tag("INC1-CA28")
    @DisplayName("CA28 - SQL natif : bib = 1 accepte")
    void ca28_nativeInsertWithBibOneIsAccepted() {
        Race race = persistedRace("Race CA28");

        insertRunnerNatively(race.getId(), 1, "tok-ca28-1");

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM runner WHERE race_id = ? AND bib = 1", Long.class, race.getId())).isEqualTo(1L);
    }

    @Test
    @Tag("INC1-CA28")
    @DisplayName("CA28 - chemin JPA : Runner bib 0 rejete (ConstraintViolation sur bib ou CHECK), aucun coureur")
    void ca28_jpaRunnerWithBibZeroIsRejected() {
        Race race = persistedRace("Race CA28");
        long runnerRowsBefore = countRows("runner");

        Throwable thrown = catchThrowable(() ->
            runnerRepository.saveAndFlush(new Runner(race, 0, "Zero", "tok-ca28-jpa")));

        assertRejectedByValidationOnOrDatabase(thrown, "bib");
        assertThat(countRows("runner")).isEqualTo(runnerRowsBefore);
    }

    // ── CA29 — CHECK du numéro de yard ──────────────────────────────────────

    private Runner persistedRunner() {
        Race race = persistedRace("Race CA29");
        return runnerRepository.saveAndFlush(new Runner(race, 1, "Alice", "tok-ca29"));
    }

    private void insertPassageNatively(long runnerId, int yardNumber) {
        jdbc.update("INSERT INTO passage (runner_id, scanned_at, yard_number, source) VALUES (?, ?, ?, 'SCAN')",
            runnerId, Timestamp.from(SCANNED_AT), yardNumber);
    }

    @ParameterizedTest(name = "yard_number = {0}")
    @ValueSource(ints = {0, -1})
    @Tag("INC1-CA29")
    @DisplayName("CA29 - SQL natif : yard_number < 1 rejete par le CHECK")
    void ca29_nativeInsertWithYardNumberBelowOneIsRejected(int yardNumber) {
        Runner runner = persistedRunner();
        long passageRowsBefore = countRows("passage");

        assertThatThrownBy(() -> insertPassageNatively(runner.getId(), yardNumber))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countRows("passage")).isEqualTo(passageRowsBefore);
    }

    @Test
    @Tag("INC1-CA29")
    @DisplayName("CA29 - SQL natif : yard_number = 1 accepte")
    void ca29_nativeInsertWithYardNumberOneIsAccepted() {
        Runner runner = persistedRunner();

        insertPassageNatively(runner.getId(), 1);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM passage WHERE runner_id = ? AND yard_number = 1", Long.class, runner.getId()))
            .isEqualTo(1L);
    }

    @Test
    @Tag("INC1-CA29")
    @DisplayName("CA29 - chemin JPA : Passage yard 0 rejete (ConstraintViolation sur yardNumber ou CHECK), aucun passage")
    void ca29_jpaPassageWithYardZeroIsRejected() {
        Runner runner = persistedRunner();
        long passageRowsBefore = countRows("passage");

        Throwable thrown = catchThrowable(() ->
            passageRepository.saveAndFlush(new Passage(runner, 0, PassageSource.SCAN, SCANNED_AT)));

        assertRejectedByValidationOnOrDatabase(thrown, "yardNumber");
        assertThat(countRows("passage")).isEqualTo(passageRowsBefore);
    }
}
