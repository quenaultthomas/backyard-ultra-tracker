package fr.backyard.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec increment 5, parties [config] de CA39 et CA40 : lecture automatisee des fichiers de deploiement versionnes sous
 * {@code deploy/} (logrotate, journald, nginx). Les parties [manuel] (VPS) ne sont pas automatisables. Sans contexte
 * Spring. Le repertoire de travail de failsafe est {@code backend/}.
 */
@Tag("INC-5")
class DeployConfigIT {

    private static Path deploy(String relative) {
        Path fromBackend = Path.of("..", "deploy", relative);
        return Files.exists(fromBackend) ? fromBackend : Path.of("deploy", relative);
    }

    private static List<String> activeLines(Path file) throws IOException {
        return Files.readAllLines(file).stream().map(String::strip).filter(line -> !line.startsWith("#")).toList();
    }

    @Test
    @Tag("INC5-CA39")
    @DisplayName("CA39 [config] - logrotate nginx : daily et rotate 6 ; journald : MaxRetentionSec=7day")
    void ca39_logRetentionConfigFiles() throws IOException {
        List<String> logrotate = activeLines(deploy("logrotate/nginx-backyard-ultra-tracker"));
        assertThat(logrotate).contains("daily", "rotate 6");
        assertThat(logrotate).noneMatch(line -> line.matches("rotate\\s+(?!6\\b)\\d+"));
        assertThat(logrotate).anyMatch(line -> line.endsWith("access.log")).anyMatch(line -> line.endsWith("error.log"));
        assertThat(activeLines(deploy("journald/backyard-ultra-tracker.conf"))).contains("MaxRetentionSec=7day");
    }

    @Test
    @Tag("INC5-CA40")
    @DisplayName("CA40 [config] - nginx : deux zones limit_req par $binary_remote_addr (10r/m, 30r/m), 429, burst 5 sur E3 et burst 10 sur /api/account/, aucun autre limit_req")
    void ca40_nginxRateLimiting() throws IOException {
        String conf = String.join("\n", activeLines(deploy("nginx/backyard-ultra-tracker.conf")));

        List<String> zones = Pattern.compile("limit_req_zone \\$binary_remote_addr zone=(\\w+):\\S+ rate=(\\d+r/m);")
            .matcher(conf).results().map(m -> m.group(1) + "=" + m.group(2)).toList();
        assertThat(zones).hasSize(2);
        assertThat(zones).containsExactlyInAnyOrder("backyard_registration=10r/m", "backyard_account=30r/m");
        assertThat(conf).contains("limit_req_status 429;");

        Matcher registration = Pattern.compile(
            "location ~ \\^/api/public/races/\\[\\^/\\]\\+/registrations\\$ \\{\\s*limit_req zone=backyard_registration "
                + "burst=5 nodelay;").matcher(conf);
        assertThat(registration.find()).as("location E3 : zone 10r/m, burst=5 nodelay").isTrue();
        Matcher account = Pattern.compile(
            "location /api/account/ \\{\\s*limit_req zone=backyard_account burst=10 nodelay;").matcher(conf);
        assertThat(account.find()).as("location /api/account/ : zone 30r/m, burst=10 nodelay").isTrue();
        assertThat(Pattern.compile("\\blimit_req\\s+zone=").matcher(conf).results().count())
            .as("aucun autre limit_req").isEqualTo(2);
    }
}
