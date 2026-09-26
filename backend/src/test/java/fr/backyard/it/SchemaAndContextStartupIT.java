package fr.backyard.it;

import fr.backyard.domain.Passage;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.Race;
import fr.backyard.domain.Runner;
import fr.backyard.it.support.AbstractApiIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mode rattrapage INC-1 : critères non couverts par {@code RacePersistenceTest} (@DataJpaTest, slice JPA
 * uniquement) parce qu'ils exigent le contexte Spring complet (contrôleurs, sécurité, JPA, Flyway ensemble)
 * ou parce qu'aucun critère d'acceptation numéroté ne les couvre (CL8, CL9 : suppression bloquée par les
 * clés étrangères RESTRICT).
 */
@Tag("INC-1")
class SchemaAndContextStartupIT extends AbstractApiIT {

    @Autowired
    private ApplicationContext applicationContext;

    // ── CA20 — Validation du schéma par Flyway (RG17, RG18), contexte complet ──────────────────────
    @Test
    @Tag("INC1-CA20")
    @DisplayName("CA20 - le contexte complet (web, sécurité, JPA) démarre : Flyway a appliqué V1 et "
        + "Hibernate a validé le schéma (ddl-auto=validate)")
    void fullContextStartsWithFlywayMigrationAndSchemaValidation() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getBean(fr.backyard.repository.RaceRepository.class)).isNotNull();

        // Si Hibernate n'avait pas pu valider le schéma créé par Flyway, le contexte n'aurait pas démarré :
        // une requête réelle confirme que le schéma est utilisable.
        assertThat(raceRepository.count()).isGreaterThanOrEqualTo(0L);
    }

    // ── CA20 (complément statique) — RG18 : ddl-auto=validate en profil de production ─────────────
    @Test
    @Tag("INC1-CA20")
    @DisplayName("CA20 - revue : ddl-auto=validate est actif pour le profil de production (RG18), "
        + "non écrasé par application-prod.properties (limite : pas de vrai PostgreSQL disponible ici)")
    void productionProfileKeepsDdlAutoValidate() throws IOException {
        Properties base = loadProperties("application.properties");
        assertThat(base.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");

        Properties prod = loadProperties("application-prod.properties");
        assertThat(prod.getProperty("spring.jpa.hibernate.ddl-auto"))
            .as("application-prod.properties ne doit pas réintroduire une génération automatique du schéma")
            .isNull();
    }

    private static Properties loadProperties(String classpathName) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource(classpathName).getInputStream()) {
            properties.load(in);
        }
        return properties;
    }

    // ── CL8 — Suppression d'une Race avec coureurs bloquée (aucun CA numéroté ne la couvre) ────────
    @Test
    @DisplayName("CL8 - la suppression d'une Race ayant un Runner est bloquée par la contrainte de clé "
        + "étrangère RESTRICT (fk_runner_race) : DataIntegrityViolationException")
    void deletingRaceWithRunnersIsBlockedByForeignKeyRestrict() {
        Race race = raceRepository.saveAndFlush(
            new Race("IT CL8 " + Instant.now(), LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Runner runner = runnerRepository.saveAndFlush(new Runner(race, 1, "Alice", "tok-it-cl8-" + race.getId()));

        try {
            assertThatThrownBy(() -> raceRepository.delete(race))
                .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            runnerRepository.delete(runner);
            raceRepository.delete(race);
        }
    }

    // ── CL9 — Suppression d'un Runner avec passages bloquée (aucun CA numéroté ne la couvre) ───────
    @Test
    @DisplayName("CL9 - la suppression d'un Runner ayant un Passage est bloquée par la contrainte de clé "
        + "étrangère RESTRICT (fk_passage_runner) : DataIntegrityViolationException")
    void deletingRunnerWithPassagesIsBlockedByForeignKeyRestrict() {
        Race race = raceRepository.saveAndFlush(
            new Race("IT CL9 " + Instant.now(), LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Runner runner = runnerRepository.saveAndFlush(new Runner(race, 1, "Bob", "tok-it-cl9-" + race.getId()));
        Passage passage = passageRepository.saveAndFlush(
            new Passage(runner, 1, PassageSource.SCAN, Instant.parse("2026-10-01T08:00:00Z")));

        try {
            assertThatThrownBy(() -> runnerRepository.delete(runner))
                .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            passageRepository.delete(passage);
            runnerRepository.delete(runner);
            raceRepository.delete(race);
        }
    }
}
