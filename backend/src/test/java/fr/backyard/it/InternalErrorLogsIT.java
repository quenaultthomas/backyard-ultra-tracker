package fr.backyard.it;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.service.RaceBoardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec increment 6, CA14 (RG10, CL11, reserve R5-8) [IT, de bout en bout] : une exception interne dont le message
 * cite un pseudo, levee par un service reel sous {@code GET /api/public/runners/1}, traverse la vraie chaine
 * (securite, MVC, {@code ApiExceptionHandler}) ; aucune ligne de journal (message formate, arguments, message du
 * throwable, de sa cause, trace rendue) ne contient « lievre », sous aucune casse. Une seule ligne ERROR, avec
 * methode, chemin et nom de la classe de l'exception. Complete {@code ApiExceptionHandlerLoggingTest} (appel direct
 * du gestionnaire). Le service est un spy Mockito sur le bean reel (seul l'appel qui doit lever est force).
 */
@Tag("INC-6")
@Tag("INC6-CA14")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalErrorLogsIT {

    @Autowired
    MockMvc mvc;

    @MockitoSpyBean
    RaceBoardService raceBoardService;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void captureLogs() {
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        rootLogger.detachAppender(logs);
    }

    private ResultActions getRunnerOne() throws Exception {
        return mvc.perform(get("/api/public/runners/1"));
    }

    /** Toutes les chaines que la ligne de journal peut porter : message, arguments, throwable, causes, trace. */
    private static List<String> everyTextOf(ILoggingEvent event) {
        List<String> texts = new ArrayList<>();
        texts.add(event.getFormattedMessage());
        texts.add(event.getMessage());
        if (event.getArgumentArray() != null) {
            for (Object argument : event.getArgumentArray()) {
                texts.add(String.valueOf(argument));
            }
        }
        for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
            texts.add(proxy.getClassName());
            texts.add(proxy.getMessage());
            if (proxy instanceof ThrowableProxy throwableProxy) {
                texts.add(String.valueOf(throwableProxy.getThrowable()));
            }
            for (var element : proxy.getStackTraceElementProxyArray()) {
                texts.add(element.getSTEAsString());
            }
        }
        return texts;
    }

    private void assertNoPseudoAndOneErrorLineNaming(String exceptionClass) {
        List<ILoggingEvent> events = new ArrayList<>(logs.list);
        assertThat(events).as("le test capture bien des lignes (pas de passage a vide)").isNotEmpty();
        assertThat(events).allSatisfy(event -> assertThat(everyTextOf(event)).allSatisfy(text -> {
            if (text != null) {
                assertThat(text.toLowerCase(Locale.ROOT)).doesNotContain("lievre");
            }
        }));
        List<ILoggingEvent> errors = events.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage()).contains("GET").contains("/api/public/runners/1")
            .contains(exceptionClass);
        assertThat(errors.get(0).getThrowableProxy()).isNull();
    }

    @Test
    @DisplayName("CA14 (a) - IllegalStateException « Pseudo lievre incoherent » : 500 INTERNAL_INCONSISTENCY, journal sans pseudo")
    void ca14_a_illegalStateExceptionWithPseudo() throws Exception {
        // given
        Mockito.doThrow(new IllegalStateException("Pseudo lievre incohérent"))
            .when(raceBoardService).runnerDetail(1L);

        // when
        getRunnerOne().andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_INCONSISTENCY"));

        // then
        assertNoPseudoAndOneErrorLineNaming("IllegalStateException");
    }

    @Test
    @DisplayName("CA14 (b) - IllegalArgumentException dont la cause cite LIEVRE : 500 INTERNAL_INCONSISTENCY, journal sans pseudo")
    void ca14_b_illegalArgumentExceptionWithPseudoInCause() throws Exception {
        // given
        Mockito.doThrow(new IllegalArgumentException("lievre", new RuntimeException("cause LIEVRE")))
            .when(raceBoardService).runnerDetail(1L);

        // when
        getRunnerOne().andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_INCONSISTENCY"));

        // then
        assertNoPseudoAndOneErrorLineNaming("IllegalArgumentException");
    }

    @Test
    @DisplayName("CA14 (c) - RuntimeException « Erreur sur lievre » : 500 INTERNAL_ERROR, corps generique, journal sans pseudo")
    void ca14_c_unexpectedExceptionWithPseudo() throws Exception {
        // given
        Mockito.doThrow(new RuntimeException("Erreur sur lievre")).when(raceBoardService).runnerDetail(1L);

        // when
        String body = getRunnerOne().andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andReturn().getResponse().getContentAsString();

        // then
        assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("lievre");
        assertNoPseudoAndOneErrorLineNaming("RuntimeException");
    }
}
