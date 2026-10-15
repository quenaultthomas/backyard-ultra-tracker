package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.BackyardUltraTrackerApplication;
import fr.backyard.tracker.comptes.domaine.ReglageHorloge;
import fr.backyard.tracker.courses.infrastructure.PlanificateurAbandonAutomatique;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contrat de l'incrément 4.8 : démarrage du planificateur dans chaque configuration (CA8 rattrapage au démarrage,
 * CA11 socle des tests, profil par défaut, profil e2e). Les contextes sont démarrés à la main sur la même base : les
 * données sont posées par SQL entre deux démarrages.
 */
@Testcontainers
class AbandonAutomatiqueDemarrageIntegrationTest {

    static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");
    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final Duration DELAI = Duration.ofSeconds(8);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    /** Horloge fixée à t0 + 3 min, pour rattraper des Boucles terminées avant le démarrage. */
    @Configuration(proxyBeanMethods = false)
    static class HorlogeEnRetard {
        @Bean
        @Primary
        Clock horlogeEnRetard() {
            return Clock.fixed(T0.plusSeconds(180), ZoneOffset.UTC);
        }
    }

    JdbcTemplate jdbc;
    UUID bruno;
    UUID chloe;
    UUID tom;

    @AfterAll
    static void restaurerPontJournaux() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    @BeforeEach
    void preparer() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : le socle des tests désactive le planificateur (propriété à false, bean absent) ; la configuration de l'application le déclare actif par défaut")
    void ca11_socle_desactive_et_defaut_actif() {
        try (ConfigurableApplicationContext contexte = demarrer(List.of())) {
            Environment environnement = contexte.getEnvironment();
            assertThat(environnement.getProperty("backyard.abandon-automatique.actif")).isEqualTo("false");
            assertThat(contexte.getBeansOfType(PlanificateurAbandonAutomatique.class)).isEmpty();
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        assertThat(yaml.getObject().getProperty("backyard.abandon-automatique.actif")).isEqualTo("true");
    }

    @Test
    @DisplayName("CA11 : planificateur désactivé : une Course en retard n'est jamais contrôlée")
    void ca11_planificateur_desactive_aucun_abandon() throws Exception {
        nettoyer();
        try (ConfigurableApplicationContext contexte = demarrer(List.of())) {
            assertThat(contexte.getBeansOfType(PlanificateurAbandonAutomatique.class)).isEmpty();
            UUID course = poserCourseEnRetard();

            Thread.sleep(2_500);

            assertThat(etat(course, tom)).isEqualTo("EN_COURSE:-:-");
            assertThat(etat(course, chloe)).isEqualTo("EN_COURSE:-:-");
            // 4.9 CA7 : planificateur désactivé, aucun vainqueur ni fin de Course non plus
            assertThat(etat(course, bruno)).isEqualTo("EN_COURSE:-:-");
            assertThat(jdbc.queryForObject("select statut from course where id = ?", String.class, course))
                    .isEqualTo("EN_COURS");
        }
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : au démarrage de l'application, une Course déjà en retard de 3 Boucles est rattrapée dès le premier tick (Tom Boucle 1, Chloé Boucle 2, Bruno EN_COURSE)")
    void ca8_rattrapage_au_demarrage() throws Exception {
        nettoyer();
        demarrer(List.of()).close();
        UUID course = poserCourseEnRetard();

        try (ConfigurableApplicationContext contexte = demarrer(List.of("--backyard.abandon-automatique.actif=true"),
                HorlogeEnRetard.class)) {
            assertThat(contexte.getBeansOfType(PlanificateurAbandonAutomatique.class)).hasSize(1);
            assertThat(attendre(() -> etat(course, tom).startsWith("ABANDON") && etat(course, chloe)
                    .startsWith("ABANDON"))).isTrue();
        }

        assertThat(etat(course, tom)).isEqualTo("ABANDON:HORS_DELAI:1");
        assertThat(etat(course, chloe)).isEqualTo("ABANDON:HORS_DELAI:2");
        assertThat(jdbc.queryForObject("select statut from course where id = ?", String.class, course))
                .isEqualTo("TERMINEE");
        // 4.9 RG1/RG2 : Bruno, seul passant de la Boucle décisive, est VAINQUEUR (4.8 attendait EN_COURSE)
        assertThat(etat(course, bruno)).isEqualTo("VAINQUEUR:-:-");
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : profil par défaut : planificateur présent et horloge système (une Course démarrée il y a 61 s est contrôlée en temps réel)")
    void ca11_profil_par_defaut_horloge_systeme() throws Exception {
        nettoyer();
        demarrer(List.of()).close();
        UUID course = poserCourse(Instant.now().minusSeconds(61));

        try (ConfigurableApplicationContext contexte = demarrer(List.of("--backyard.abandon-automatique.actif=true"))) {
            assertThat(contexte.getBeansOfType(PlanificateurAbandonAutomatique.class)).hasSize(1);
            assertThat(contexte.getBeansOfType(ReglageHorloge.class)).isEmpty();
            assertThat(attendre(() -> etat(course, chloe).startsWith("ABANDON"))).isTrue();
        }

        assertThat(etat(course, chloe)).isEqualTo("ABANDON:HORS_DELAI:1");
        // 4.9 RG1/RG2 : Bruno, seul passant de la Boucle décisive, est VAINQUEUR (4.8 attendait EN_COURSE)
        assertThat(etat(course, bruno)).isEqualTo("VAINQUEUR:-:-");
    }

    @Test
    @DisplayName("CA11 : profil e2e : planificateur présent, la décision utilise l'horloge décalée (avancer d'une Boucle fait abandonner Chloé, sans attendre une minute réelle)")
    void ca11_profil_e2e_horloge_decalee() throws Exception {
        nettoyer();
        demarrer(List.of()).close();
        UUID course = poserCourse(Instant.now().minusSeconds(5));

        try (ConfigurableApplicationContext contexte = demarrer(
                List.of("--spring.profiles.active=e2e", "--backyard.abandon-automatique.actif=true"))) {
            assertThat(contexte.getBeansOfType(PlanificateurAbandonAutomatique.class)).hasSize(1);
            Thread.sleep(2_500);
            assertThat(etat(course, chloe)).as("horloge réelle : Boucle 1 non terminée").isEqualTo("EN_COURSE:-:-");

            contexte.getBean(ReglageHorloge.class).avancer(Duration.ofMinutes(1));

            assertThat(attendre(() -> etat(course, chloe).startsWith("ABANDON"))).isTrue();
        }

        assertThat(etat(course, chloe)).isEqualTo("ABANDON:HORS_DELAI:1");
        // 4.9 RG1/RG2 : Bruno, seul passant de la Boucle décisive, est VAINQUEUR (4.8 attendait EN_COURSE)
        assertThat(etat(course, bruno)).isEqualTo("VAINQUEUR:-:-");
    }

    // ---------------------------------------------------------------- utilitaires

    private void nettoyer() {
        demarrer(List.of()).close();
        jdbc.update("delete from course");
        jdbc.update("delete from compte where role <> 'ADMIN_MASTER'");
    }

    /** Course de 1 min démarrée à t0 ; Bruno {1, 2}, Chloé {1}, Tom {} (dossards 1, 2, 3). */
    private UUID poserCourseEnRetard() {
        UUID course = poserCourse(T0);
        jdbc.update("insert into passage (id, inscription_id, numero_boucle, scanne_le, origine) values "
                + "(?, ?, 2, ?, 'SCAN')", UUID.randomUUID(), inscription(course, bruno),
                Timestamp.from(T0.plusSeconds(65)));
        jdbc.update("insert into passage (id, inscription_id, numero_boucle, scanne_le, origine) values "
                + "(?, ?, 1, ?, 'SCAN')", UUID.randomUUID(), inscription(course, chloe),
                Timestamp.from(T0.plusSeconds(6)));
        return course;
    }

    /** Course EN_COURS de 1 min démarrée à cet instant ; Bruno a un Passage 1, Chloé et Tom aucun. */
    private UUID poserCourse(Instant demarreeLe) {
        UUID course = UUID.randomUUID();
        jdbc.update("insert into course (id, nom, date_course, statut, distance_boucle_metres, duree_boucle_minutes, "
                + "denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles, demarree_le) "
                + "values (?, 'Backyard rattrapage', ?, 'EN_COURS', 400, 1, 5, 100, 5, ?)", course,
                Date.valueOf("2026-10-12"), Timestamp.from(demarreeLe));
        bruno = compte("Bruno");
        chloe = compte("Chloé");
        tom = compte("Tom");
        UUID inscriptionBruno = inscrire(course, bruno, 1);
        inscrire(course, chloe, 2);
        inscrire(course, tom, 3);
        jdbc.update("insert into passage (id, inscription_id, numero_boucle, scanne_le, origine) values "
                + "(?, ?, 1, ?, 'SCAN')", UUID.randomUUID(), inscriptionBruno,
                Timestamp.from(demarreeLe.plusSeconds(5)));
        return course;
    }

    private UUID compte(String pseudo) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, role, cree_le) "
                + "values (?, ?, lower(?), 'empreinte-de-test', 'COUREUR', ?)", id, pseudo, pseudo,
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        return id;
    }

    private UUID inscrire(UUID course, UUID compte, int dossard) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, ?, ?, 'EN_COURSE')", id, course, compte, dossard, UUID.randomUUID().toString());
        return id;
    }

    private UUID inscription(UUID course, UUID compte) {
        return jdbc.queryForObject("select id from inscription where course_id = ? and compte_id = ?", UUID.class,
                course, compte);
    }

    private String etat(UUID course, UUID compte) {
        return jdbc.queryForObject("select statut || ':' || coalesce(motif_abandon, '-') || ':' "
                + "|| coalesce(boucle_abandon::text, '-') from inscription where course_id = ? and compte_id = ?",
                String.class, course, compte);
    }

    private static boolean attendre(java.util.function.BooleanSupplier condition) throws InterruptedException {
        return DecorAbandon.attendre(condition, DELAI);
    }

    private ConfigurableApplicationContext demarrer(List<String> options, Class<?>... sourcesSupplementaires) {
        List<String> arguments = new ArrayList<>(options);
        arguments.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
        arguments.add("--spring.datasource.username=" + POSTGRES.getUsername());
        arguments.add("--spring.datasource.password=" + POSTGRES.getPassword());
        arguments.add("--server.port=0");
        arguments.add("--backyard.admin-master.pseudo=Patron");
        arguments.add("--backyard.admin-master.mot-de-passe=" + MOT_DE_PASSE_PATRON);
        List<Class<?>> sources = new ArrayList<>(List.of(BackyardUltraTrackerApplication.class));
        sources.addAll(List.of(sourcesSupplementaires));
        return new SpringApplicationBuilder(sources.toArray(Class<?>[]::new)).run(arguments.toArray(String[]::new));
    }
}
