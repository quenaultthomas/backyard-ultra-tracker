package fr.backyard.tracker.comptes.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

/** Capture du journal de l'application (loggers fr.backyard) pour les tests d'intégration de l'horloge (4.3). */
final class JournalDeTest implements AutoCloseable {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    private JournalDeTest() {
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        racine.addAppender(appender);
    }

    static JournalDeTest demarrer() {
        return new JournalDeTest();
    }

    /** Événements de l'application, hors bibliothèques. */
    List<ILoggingEvent> evenements() {
        return appender.list.stream().filter(e -> e.getLoggerName().startsWith("fr.backyard")).toList();
    }

    /** Messages formatés des événements de l'application (nuls écartés). */
    List<String> messages() {
        return evenements().stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m != null).toList();
    }

    /** Messages formatés et messages d'exception, nuls écartés. */
    List<String> messagesEtExceptions() {
        return appender.list.stream().flatMap(e -> {
            var proxy = e.getThrowableProxy();
            return java.util.stream.Stream.of(e.getFormattedMessage(), proxy == null ? null : proxy.getMessage());
        }).filter(m -> m != null).toList();
    }

    /**
     * Rattache l'appender au journal racine (idempotent) : le démarrage de Spring Boot réinitialise Logback, ce qui
     * détache et arrête les appenders existants.
     */
    void rattacher() {
        if (!appender.isStarted()) {
            appender.start();
        }
        racine.addAppender(appender);
    }

    void vider() {
        appender.list.clear();
    }

    @Override
    public void close() {
        racine.detachAppender(appender);
        appender.stop();
    }
}
