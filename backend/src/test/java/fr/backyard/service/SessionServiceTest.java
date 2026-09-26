package fr.backyard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec increment 4 - RG52 (E19) : rôle sans préfixe ROLE_ et heure du serveur lue sur la Clock injectée. */
class SessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:20:00Z");

    private final SessionService service = new SessionService(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("RG52 - compte SCANNER : nom, rôle SCANNER sans préfixe, heure de la Clock")
    void describesScannerSession() {
        SessionView view = service.describe("scanner-test", List.of("ROLE_SCANNER"));

        assertThat(view).isEqualTo(new SessionView("scanner-test", "SCANNER", NOW));
    }

    @Test
    @DisplayName("RG52 - compte ADMIN : rôle ADMIN ; les autorités qui ne sont pas des rôles sont ignorées")
    void describesAdminSessionIgnoringNonRoleAuthorities() {
        SessionView view = service.describe("admin-test", List.of("FACTOR_PASSWORD", "ROLE_ADMIN"));

        assertThat(view.role()).isEqualTo("ADMIN");
        assertThat(view.username()).isEqualTo("admin-test");
    }

    @Test
    @DisplayName("RG52 - compte sans rôle ou avec plusieurs rôles : incohérence interne explicite, jamais ignorée")
    void rejectsAccountWithoutExactlyOneRole() {
        assertThatThrownBy(() -> service.describe("inconnu", List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("inconnu")
            .hasMessageContaining("exactement un rôle");
        assertThatThrownBy(() -> service.describe("double", List.of("ROLE_ADMIN", "ROLE_SCANNER")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("il en a 2");
    }
}
