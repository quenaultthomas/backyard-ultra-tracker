package fr.backyard.testsupport;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.Passage;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Fixtures de la spec increment 2 (section 2) : course R1 et helpers de construction.
 * Les statuts de depart des coureurs sont positionnes via les setters de l'increment 1
 * (donnees de test), jamais via le code metier teste.
 */
public final class TestData {

    public static final Instant T0 = Instant.parse("2026-10-03T08:00:00Z");
    public static final LocalDate RACE_DATE = LocalDate.of(2026, 10, 3);

    private static final AtomicInteger BIB_SEQUENCE = new AtomicInteger(1);

    private TestData() {
    }

    /** Instant du 2026-10-03 en UTC, format "HH:mm:ss" ou "HH:mm:ss.SSS". */
    public static Instant at(String time) {
        return Instant.parse("2026-10-03T" + time + "Z");
    }

    public static Race race(long id, int loopDistance, int loopDuration, int loopElevation,
                            RaceStatus status, Instant startedAt) {
        Race race = new Race("Course " + id, RACE_DATE, loopDistance, loopDuration, loopElevation);
        ReflectionTestUtils.setField(race, "id", id);
        race.setStatus(status);
        race.setStartedAt(startedAt);
        return race;
    }

    /** Course R1 de la spec : 6706 m, 3600 s, 50 m D+, RUNNING, startedAt = T0. */
    public static Race r1(long id) {
        return race(id, 6706, 3600, 50, RaceStatus.RUNNING, T0);
    }

    /** Course R1 de la spec increment 3 : id 1, "Backyard Test", 2026-10-03, 6706 m, 3600 s, 50 m D+. */
    public static Race backyardTest(RaceStatus status) {
        return namedRace(1L, "Backyard Test", RACE_DATE, 6706, 3600, 50, status,
            status == RaceStatus.SETUP ? null : T0);
    }

    public static Race namedRace(long id, String name, LocalDate raceDate, int loopDistance, int loopDuration,
                                 int loopElevation, RaceStatus status, Instant startedAt) {
        Race race = new Race(name, raceDate, loopDistance, loopDuration, loopElevation);
        ReflectionTestUtils.setField(race, "id", id);
        race.setStatus(status);
        race.setStartedAt(startedAt);
        return race;
    }

    /** Coureur avec identifiant, dossard, nom et token explicites. */
    public static Runner runner(long id, Race race, int bib, String name, String qrToken) {
        Runner runner = new Runner(race, bib, name, qrToken);
        ReflectionTestUtils.setField(runner, "id", id);
        return runner;
    }

    /** Positionne l'identifiant d'un passage (entite sans setter d'id). */
    public static Passage withId(Passage passage, long id) {
        ReflectionTestUtils.setField(passage, "id", id);
        return passage;
    }

    public static Runner runner(long id, Race race, String qrToken) {
        Runner runner = new Runner(race, BIB_SEQUENCE.getAndIncrement(), "Coureur " + id, qrToken);
        ReflectionTestUtils.setField(runner, "id", id);
        return runner;
    }

    public static Runner runner(long id, Race race) {
        return runner(id, race, "tok-" + id);
    }

    public static Runner asDnf(Runner runner, DnfReason reason, int dnfYard) {
        runner.setStatus(RunnerStatus.DNF);
        runner.setDnfReason(reason);
        runner.setDnfYard(dnfYard);
        return runner;
    }

    public static Runner asWinner(Runner runner) {
        runner.setStatus(RunnerStatus.WINNER);
        return runner;
    }

    public static Passage scan(Runner runner, int yard, Instant scannedAt) {
        return new Passage(runner, yard, PassageSource.SCAN, scannedAt);
    }

    /** Passage SCAN effectue 50 minutes apres le debut du yard (race d'1 h). */
    public static Passage scanInWindow(Runner runner, int yard) {
        Race race = runner.getRace();
        Instant yardStart = race.getStartedAt().plusSeconds((long) (yard - 1) * race.getLoopDuration());
        return scan(runner, yard, yardStart.plusSeconds(race.getLoopDuration() * 5L / 6L));
    }

    public static Passage manual(Runner runner, int yard) {
        return new Passage(runner, yard, PassageSource.MANUAL, null);
    }

    /**
     * Vrai si le message contient le nombre {@code n} isole (non colle a d'autres chiffres),
     * pour que "yard 2" ne soit pas confondu avec "2026" ou "1002".
     */
    public static boolean mentionsNumber(String message, long n) {
        return message != null
            && Pattern.compile("(?<!\\d)" + n + "(?!\\d)").matcher(message).find();
    }
}
