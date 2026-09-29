package fr.backyard.it;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec increment 5 - RG22, CA39 [config], correction D4 de l'arbitrage N1 : avec le profil {@code prod} (base H2 du
 * profil {@code test}), le contexte demarre et le journal applicatif est un fichier a rotation quotidienne, motif
 * {@code %d{yyyy-MM-dd}} resolu a partir de {@code logging.file.name}, 6 archives au plus.
 */
@Tag("INC-5")
@SpringBootTest(properties = "BACKYARD_LOG_FILE=target/prod-logging-it/application.log")
@ActiveProfiles({"test", "prod"})
class ProdLoggingConfigIT {

    @Test
    @DisplayName("CA39 / D4 - profil prod : fichier target/prod-logging-it/application.log, motif quotidien resolu, max-history 6")
    void ca39_dailyRollingFileWithSixArchives() {
        Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
        List<RollingFileAppender<ILoggingEvent>> fileAppenders = new ArrayList<>();
        for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
            if (it.next() instanceof RollingFileAppender<ILoggingEvent> rolling) {
                fileAppenders.add(rolling);
            }
        }

        assertThat(fileAppenders).hasSize(1);
        RollingFileAppender<ILoggingEvent> appender = fileAppenders.getFirst();
        assertThat(appender.getFile().replace('\\', '/')).endsWith("target/prod-logging-it/application.log");
        SizeAndTimeBasedRollingPolicy<?> policy = (SizeAndTimeBasedRollingPolicy<?>) appender.getRollingPolicy();
        assertThat(policy.getFileNamePattern().replace('\\', '/'))
            .isEqualTo(appender.getFile().replace('\\', '/') + ".%d{yyyy-MM-dd}.%i.gz");
        assertThat(policy.getMaxHistory()).isEqualTo(6);
    }
}
