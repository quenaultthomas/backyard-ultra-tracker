package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.BackyardUltraTrackerApplication;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contrat de l'incrément 8.1, CA18 : migration du schéma 0010 vers 0011-compte-actif sur une base contenant déjà des
 * Comptes de chaque rôle ; tous deviennent actifs, ddl-auto=validate passe au démarrage, 0001 à 0010 inchangés.
 */
@Testcontainers
class CompteActifMigrationIntegrationTest {

    static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";
    static final int CHANGESETS_JUSQU_A_0010 = 9;
    static final String INSERTION = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, role, "
            + "cree_le) values (?, ?, ?, ?, ?, ?)";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @AfterAll
    static void restaurerPontJournaux() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    @Test
    @DisplayName("CA18 : base migrée jusqu'à 0010 avec admin master, admin, bénévole et coureur : après 0011 tous sont actifs, 0002 à 0010 inchangés, validate passe au démarrage")
    void ca18_migration_depuis_0010() throws Exception {
        try (Connection connexion = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            Database base = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connexion));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), base)) {
                liquibase.update(CHANGESETS_JUSQU_A_0010, new Contexts(), new LabelExpression());
            }
        }
        JdbcTemplate jdbc = jdbc();
        List<String> avant = jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class);
        assertThat(avant).hasSize(CHANGESETS_JUSQU_A_0010).last().isEqualTo("0010-abandon-inscription");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns "
                + "where table_name = 'compte' and column_name = 'actif'")).isEmpty();
        for (String[] compte : new String[][] {{"Patron", "ADMIN_MASTER"}, {"Nadia", "ADMIN"}, {"Leo", "BENEVOLE"},
                {"Alice", "COUREUR"}}) {
            jdbc.update(INSERTION, UUID.randomUUID(), compte[0], compte[0].toLowerCase(), "$argon2id$factice",
                    compte[1], Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        }

        try (ConfigurableApplicationContext contexte = demarrer()) {
            assertThat(contexte.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        }

        assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from compte where actif", Integer.class)).isEqualTo(4);
        List<String> apres = jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class);
        assertThat(apres).containsExactlyElementsOf(java.util.stream.Stream.concat(avant.stream(),
                java.util.stream.Stream.of("0011-compte-actif")).toList());
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }

    private ConfigurableApplicationContext demarrer() {
        return new SpringApplicationBuilder(BackyardUltraTrackerApplication.class).run(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.port=0",
                "--backyard.admin-master.pseudo=Patron",
                "--backyard.admin-master.mot-de-passe=mot-de-passe-patron-1").toArray(String[]::new));
    }
}
