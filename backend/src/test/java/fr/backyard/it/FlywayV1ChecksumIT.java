package fr.backyard.it;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec increment 5, CA1 (partie « somme de controle de V1 inchangee ») [IT] : V1__init.sql ne doit jamais etre
 * modifiee (RG6). La somme de controle Flyway lue est comparee a la valeur de reference relevee sur V1 avant
 * l'incrément 5 (identique a HEAD au moment du releve). Base H2 en memoire dediee, sans contexte Spring.
 */
@Tag("INC-5")
@Tag("INC5-CA1")
class FlywayV1ChecksumIT {

    private static final String URL = "jdbc:h2:mem:v1checksum;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private static final int V1_CHECKSUM = -1184617864;

    @Test
    @DisplayName("CA1 - la somme de controle Flyway de V1__init.sql est inchangee ; V2 est appliquee apres V1")
    void ca1_v1ChecksumUnchanged() {
        Flyway flyway = Flyway.configure().dataSource(URL, "sa", "").locations("classpath:db/migration").load();
        flyway.migrate();

        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).extracting(info -> info.getVersion().getVersion()).containsExactly("1", "2");
        assertThat(applied[0].getChecksum()).isEqualTo(V1_CHECKSUM);
    }
}
