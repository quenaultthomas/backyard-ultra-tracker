package fr.backyard.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.api.error.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec increment 6 - RG10, CA14, CL11 (reserve R5-8 de l'inc. 5) : les journaux d'erreur interne de
 * {@link ApiExceptionHandler} ne contiennent aucun pseudo (ni message, ni cause, ni trace). La ligne ERROR
 * garde la methode, le chemin et le nom de la classe de l'exception ; la reponse ne change pas.
 *
 * <p>Gestionnaire appele directement, sans Spring ni base ; journaux captures par un appender en memoire.
 */
@Tag("INC-6")
@Tag("INC6-CA14")
class ApiExceptionHandlerLoggingTest {

    private static final String PATH = "/api/public/runners/1";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        previousLevel = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.DEBUG);
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        handlerLogger.detachAppender(logs);
        handlerLogger.setLevel(previousLevel);
    }

    /** Tout ce qu'une ligne de journal peut porter : message, arguments, throwable, causes, trace rendue. */
    private static List<String> everythingWritten(ILoggingEvent event) {
        List<String> parts = new ArrayList<>();
        parts.add(event.getFormattedMessage());
        parts.add(event.getMessage());
        if (event.getArgumentArray() != null) {
            Arrays.stream(event.getArgumentArray()).forEach(argument -> parts.add(String.valueOf(argument)));
        }
        for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
            parts.add(proxy.getClassName());
            parts.add(String.valueOf(proxy.getMessage()));
        }
        if (event.getThrowableProxy() != null) {
            parts.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
        }
        return parts;
    }

    private void assertNoPseudoAnywhere() {
        assertThat(logs.list).as("au moins une ligne doit avoir ete ecrite").isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            assertThat(everythingWritten(event))
                .as("aucune partie de la ligne « %s » ne doit contenir le pseudo", event.getFormattedMessage())
                .noneMatch(part -> part.toLowerCase(Locale.ROOT).contains("lievre"));
        }
    }

    private void assertSingleErrorLineWith(String exceptionClassName) {
        List<ILoggingEvent> errors = logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
        assertThat(errors).as("exactement une ligne ERROR").hasSize(1);
        assertThat(errors.getFirst().getFormattedMessage())
            .contains("GET", PATH, exceptionClassName);
    }

    private static void assertProblem(ResponseEntity<ProblemDetail> response, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", code);
    }

    @Test
    @DisplayName("CA14 (a) - IllegalStateException « Pseudo ... incoherent » : aucun pseudo dans les journaux, 1 ligne ERROR avec GET, chemin et IllegalStateException, 500 INTERNAL_INCONSISTENCY")
    void ca14_a_illegalStateExceptionWithPseudo() {
        ResponseEntity<ProblemDetail> response =
            handler.handleInconsistency(new IllegalStateException("Pseudo lievre incohérent"), request);

        assertNoPseudoAnywhere();
        assertSingleErrorLineWith("IllegalStateException");
        assertProblem(response, "INTERNAL_INCONSISTENCY");
    }

    @Test
    @DisplayName("CA14 (b) - IllegalArgumentException avec cause « cause LIEVRE » : aucun pseudo (message, cause, trace), 1 ligne ERROR avec IllegalArgumentException, 500 INTERNAL_INCONSISTENCY")
    void ca14_b_illegalArgumentExceptionWithPseudoInCause() {
        ResponseEntity<ProblemDetail> response = handler.handleInconsistency(
            new IllegalArgumentException("lievre", new RuntimeException("cause LIEVRE")), request);

        assertNoPseudoAnywhere();
        assertSingleErrorLineWith("IllegalArgumentException");
        assertProblem(response, "INTERNAL_INCONSISTENCY");
    }

    @Test
    @DisplayName("CA14 (c) - RuntimeException « Erreur sur ... » : aucun pseudo dans les journaux, 1 ligne ERROR avec GET, chemin et RuntimeException, 500 INTERNAL_ERROR")
    void ca14_c_unexpectedExceptionWithPseudo() {
        ResponseEntity<ProblemDetail> response =
            handler.handleUnexpected(new RuntimeException("Erreur sur lievre"), request);

        assertNoPseudoAnywhere();
        assertSingleErrorLineWith("RuntimeException");
        assertProblem(response, "INTERNAL_ERROR");
    }

    @Test
    @DisplayName("CA14 - la ligne ERROR ne porte ni trace ni cause, meme sans pseudo dans le message")
    void ca14_noThrowableAttachedToTheErrorLine() {
        handler.handleInconsistency(new IllegalStateException("Incoherence sans donnee personnelle"), request);

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR)
            .allSatisfy(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("Incoherence sans donnee personnelle");
            });
    }

    @Test
    @DisplayName("CA14 - la ligne ERROR distingue la classe de l'exception : un nom de classe n'est pas confondu entre les cas")
    void ca14_errorLineNamesTheExceptionClass() {
        handler.handleUnexpected(new UnsupportedOperationException("x"), request);

        assertSingleErrorLineWith("UnsupportedOperationException");
    }
}
