package fr.backyard.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec increment 3 - RG32, CA57 [context] : refus de demarrer si les identifiants sont incomplets. */
class SecurityCredentialsStartupTest {

    private static final String ADMIN_USERNAME = "backyard.security.admin.username=admin-test";
    private static final String ADMIN_HASH =
        "backyard.security.admin.password-hash=$2a$04$y5qZCIAHUZJzXkawr/klsep.f6zWCD7ErOpuAVwd.gwAalNLmDGZi";
    private static final String SCANNER_USERNAME = "backyard.security.scanner.username=scanner-test";
    private static final String SCANNER_HASH =
        "backyard.security.scanner.password-hash=$2a$04$OfMV7IR5R5uA64IgejaHN.NPGO6M507T2FWM7.j/113nmUPHwnxg2";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(SecurityConfig.class);

    /** Messages de toute la chaine de causes de l'echec de demarrage. */
    private static String allMessages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.add(String.valueOf(t.getMessage()));
        }
        return String.join("\n", messages);
    }

    @Test
    @DisplayName("CA57 - les quatre proprietes valides : le contexte demarre avec un UserDetailsService")
    void ca57_validCredentialsStart() {
        runner.withPropertyValues(ADMIN_USERNAME, ADMIN_HASH, SCANNER_USERNAME, SCANNER_HASH)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(UserDetailsService.class);
                UserDetailsService users = context.getBean(UserDetailsService.class);
                assertThat(users.loadUserByUsername("admin-test").getAuthorities())
                    .extracting(Object::toString).containsExactly("ROLE_ADMIN");
                assertThat(users.loadUserByUsername("scanner-test").getAuthorities())
                    .extracting(Object::toString).containsExactly("ROLE_SCANNER");
            });
    }

    @Test
    @DisplayName("CA57 - admin.password-hash absente : echec, message citant BACKYARD_SECURITY_ADMIN_PASSWORD_HASH")
    void ca57_missingAdminPasswordHash() {
        runner.withPropertyValues(ADMIN_USERNAME, SCANNER_USERNAME, SCANNER_HASH)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(allMessages(context.getStartupFailure()))
                    .contains("BACKYARD_SECURITY_ADMIN_PASSWORD_HASH");
            });
    }

    @Test
    @DisplayName("CA57 - scanner.username vide : echec, message citant BACKYARD_SECURITY_SCANNER_USERNAME")
    void ca57_blankScannerUsername() {
        runner.withPropertyValues(ADMIN_USERNAME, ADMIN_HASH, "backyard.security.scanner.username=", SCANNER_HASH)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(allMessages(context.getStartupFailure()))
                    .contains("BACKYARD_SECURITY_SCANNER_USERNAME");
            });
    }

    @Test
    @DisplayName("CA57 - admin.password-hash en clair ('motdepasse') : echec, message citant la variable et BCrypt, sans la valeur")
    void ca57_plainTextPasswordIsRejected() {
        runner.withPropertyValues(ADMIN_USERNAME, "backyard.security.admin.password-hash=motdepasse",
                SCANNER_USERNAME, SCANNER_HASH)
            .run(context -> {
                assertThat(context).hasFailed();
                String messages = allMessages(context.getStartupFailure());
                assertThat(messages).contains("BACKYARD_SECURITY_ADMIN_PASSWORD_HASH").contains("BCrypt");
                assertThat(messages).doesNotContain("motdepasse");
            });
    }

    @Test
    @DisplayName("CA57 - deux noms de compte identiques : echec, message mentionnant les noms identiques")
    void ca57_identicalUsernames() {
        runner.withPropertyValues(ADMIN_USERNAME, ADMIN_HASH, "backyard.security.scanner.username=admin-test",
                SCANNER_HASH)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(allMessages(context.getStartupFailure())).containsIgnoringCase("identiques");
            });
    }

    @Test
    @DisplayName("CA57 - revue : application.properties ne contient aucune des quatre proprietes de securite")
    void ca57_noDefaultCredentialsInApplicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(in);
        }

        assertThat(properties.stringPropertyNames()).noneMatch(name -> name.startsWith("backyard.security."));
    }
}
