package fr.backyard.it;

import fr.backyard.domain.Account;
import fr.backyard.it.support.AbstractApiIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Spec increment 5 (COH5-4) : unicite en base et concurrence. CA1 : contrainte {@code (race_id, account_id)} ; CA8 :
 * deux creations simultanees du meme pseudo (courses differentes, meme course, casse differente) donnent une 201 et une
 * 409, un seul compte et un seul coureur. H2 en mode PostgreSQL (RT1 : pas de PostgreSQL reel).
 */
@Tag("INC-5")
class AccountUniquenessIT extends AbstractApiIT {

    @Autowired
    JdbcTemplate jdbc;

    private int register(Long raceId, String pseudo) throws Exception {
        return mvc.perform(post("/api/public/races/" + raceId + "/registrations")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pseudo\":\"" + pseudo + "\",\"password\":\"motdepasse-1\"}"))
            .andReturn().getResponse().getStatus();
    }

    private List<Integer> concurrently(Long raceA, String pseudoA, Long raceB, String pseudoB) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = List.of(
                () -> { start.await(); return register(raceA, pseudoA); },
                () -> { start.await(); return register(raceB, pseudoB); });
            List<Future<Integer>> futures = new ArrayList<>();
            calls.forEach(call -> futures.add(executor.submit(call)));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertOneAccountOneRunner() {
        assertThat(accountRepository.findAll()).extracting(Account::getPseudo).containsExactly("tortue");
        Long accountId = accountRepository.findByPseudo("tortue").orElseThrow().getId();
        assertThat(runnerRepository.findByAccountId(accountId)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runner WHERE account_id IS NOT NULL", Long.class))
            .isEqualTo(1L);
    }

    @Test
    @Tag("INC5-CA1")
    @DisplayName("CA1 - un second coureur du meme compte dans la meme course est rejete par la base (uq_runner_race_account) ; une autre course reste possible")
    void ca1_raceAccountUniqueConstraint() throws Exception {
        // given
        Long r1 = createSetupRace("IT5U contrainte 1");
        trackRaceForCleanup(r1);
        Long r2 = createSetupRace("IT5U contrainte 2");
        trackRaceForCleanup(r2);
        register(r1, "Lievre");
        Long accountId = accountRepository.findByPseudo("lievre").orElseThrow().getId();
        String insert = "INSERT INTO runner (race_id, bib, qr_token, status, account_id) VALUES (?, ?, ?, 'ACTIVE', ?)";
        // when / then
        assertThatThrownBy(() -> jdbc.update(insert, r1, 99, "tok-doublon", accountId))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(runnerRepository.findByRaceId(r1)).hasSize(1);
        assertThat(jdbc.update(insert, r2, 1, "tok-autre-course", accountId)).isEqualTo(1);
    }

    @Test
    @Tag("INC5-CA8")
    @DisplayName("CA8 - Tortue et Tortue en parallele sur R1 et R2 : une 201, une 409, un compte tortue, un coureur")
    void ca8_samePseudoOnTwoRaces() throws Exception {
        Long r1 = createSetupRace("IT5U concurrence 1");
        trackRaceForCleanup(r1);
        Long r2 = createSetupRace("IT5U concurrence 2");
        trackRaceForCleanup(r2);

        assertThat(concurrently(r1, "Tortue", r2, "Tortue")).containsExactlyInAnyOrder(201, 409);
        assertOneAccountOneRunner();
    }

    @Test
    @Tag("INC5-CA8")
    @DisplayName("CA8 - Tortue et Tortue en parallele sur la meme course R1 : une 201, une 409, un compte tortue, un coureur")
    void ca8_samePseudoOnSameRace() throws Exception {
        Long r1 = createSetupRace("IT5U concurrence meme course");
        trackRaceForCleanup(r1);

        assertThat(concurrently(r1, "Tortue", r1, "Tortue")).containsExactlyInAnyOrder(201, 409);
        assertOneAccountOneRunner();
    }

    @Test
    @Tag("INC5-CA8")
    @DisplayName("CA8 - Tortue et TORTUE en parallele sur la meme course R1 : une 201, une 409, un compte tortue, un coureur")
    void ca8_differentCaseOnSameRace() throws Exception {
        Long r1 = createSetupRace("IT5U concurrence casse");
        trackRaceForCleanup(r1);

        assertThat(concurrently(r1, "Tortue", r1, "TORTUE")).containsExactlyInAnyOrder(201, 409);
        assertOneAccountOneRunner();
    }
}
