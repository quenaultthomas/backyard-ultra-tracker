package fr.backyard.tracker.comptes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CA1 - horloge décalée (RG2, RG6), construite sur une horloge de base réglable. Test unitaire sans Spring. */
class HorlogeDecaleeTest {

    private static final Instant BASE = Instant.parse("2026-10-12T14:00:00Z");

    /** Horloge de base de test : UTC, avance uniquement quand le test le demande. */
    private static final class HorlogeDeBaseReglable extends Clock {
        private volatile Instant maintenant;
        private final ZoneId fuseau;

        HorlogeDeBaseReglable(Instant depart, ZoneId fuseau) {
            this.maintenant = depart;
            this.fuseau = fuseau;
        }

        void avancer(Duration duree) {
            maintenant = maintenant.plus(duree);
        }

        @Override
        public ZoneId getZone() {
            return fuseau;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new HorlogeDeBaseReglable(maintenant, zone);
        }

        @Override
        public Instant instant() {
            return maintenant;
        }
    }

    private final HorlogeDeBaseReglable base = new HorlogeDeBaseReglable(BASE, ZoneOffset.UTC);
    private final HorlogeDecalee horloge = new HorlogeDecalee(base);

    @Test
    @DisplayName("CA1 : sans avance, le décalage est nul et l'instant est celui de la base")
    void doit_avoir_un_decalage_nul_et_l_instant_de_la_base_au_depart() {
        assertThat(horloge.decalage()).isEqualTo(Duration.ZERO);
        assertThat(horloge.instant()).isEqualTo(BASE);
    }

    @Test
    @DisplayName("CA1 : avancer de 60 minutes donne 15:00:00Z")
    void doit_donner_quinze_heures_apres_une_avance_de_soixante_minutes() {
        horloge.avancer(Duration.ofMinutes(60));

        assertThat(horloge.instant()).isEqualTo(Instant.parse("2026-10-12T15:00:00Z"));
    }

    @Test
    @DisplayName("CA1 : les avances se cumulent (PT1H30M, 15:30:00Z)")
    void doit_cumuler_les_avances() {
        horloge.avancer(Duration.ofMinutes(60));
        horloge.avancer(Duration.ofMinutes(30));

        assertThat(horloge.instant()).isEqualTo(Instant.parse("2026-10-12T15:30:00Z"));
        assertThat(horloge.decalage()).isEqualTo(Duration.parse("PT1H30M"));
    }

    @Test
    @DisplayName("CA1 : avancer renvoie le décalage cumulé")
    void doit_renvoyer_le_decalage_cumule_apres_une_avance() {
        horloge.avancer(Duration.ofMinutes(60));

        assertThat(horloge.avancer(Duration.ofMinutes(30))).isEqualTo(Duration.parse("PT1H30M"));
    }

    @Test
    @DisplayName("CA1 : le temps continue, la base qui avance de 5 s fait avancer l'instant de 5 s")
    void doit_continuer_a_s_ecouler_quand_la_base_avance() {
        horloge.avancer(Duration.ofMinutes(60));
        Instant avant = horloge.instant();

        base.avancer(Duration.ofSeconds(5));

        assertThat(horloge.instant()).isEqualTo(avant.plusSeconds(5));
    }

    @Test
    @DisplayName("CA1 : millis() est cohérent avec instant()")
    void doit_avoir_des_millis_coherents_avec_l_instant() {
        horloge.avancer(Duration.ofMinutes(60));

        assertThat(horloge.millis()).isEqualTo(horloge.instant().toEpochMilli());
    }

    @Test
    @DisplayName("CA1 : le fuseau est UTC")
    void doit_etre_en_utc() {
        assertThat(horloge.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("CA1 : withZone(Paris) conserve le décalage")
    void doit_conserver_le_decalage_avec_un_autre_fuseau() {
        horloge.avancer(Duration.ofMinutes(60));
        ZoneId paris = ZoneId.of("Europe/Paris");

        Clock enParis = horloge.withZone(paris);

        assertThat(enParis.getZone()).isEqualTo(paris);
        assertThat(enParis.instant()).isEqualTo(Instant.parse("2026-10-12T15:00:00Z"));
    }

    @Test
    @DisplayName("CA1 : reinitialiser remet le décalage à zéro et l'instant à celui de la base")
    void doit_revenir_a_l_instant_de_la_base_apres_reinitialisation() {
        horloge.avancer(Duration.ofMinutes(90));

        horloge.reinitialiser();

        assertThat(horloge.decalage()).isEqualTo(Duration.ZERO);
        assertThat(horloge.instant()).isEqualTo(BASE);
    }

    @Test
    @DisplayName("CA1 : cent avances concurrentes de PT1S donnent exactement PT100S")
    void doit_ne_perdre_aucune_avance_concurrente() throws Exception {
        int nombre = 100;
        ExecutorService executeur = Executors.newFixedThreadPool(nombre);
        CountDownLatch depart = new CountDownLatch(1);
        try {
            var taches = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < nombre; i++) {
                taches.add(executeur.submit(() -> {
                    depart.await();
                    horloge.avancer(Duration.ofSeconds(1));
                    return null;
                }));
            }
            depart.countDown();
            for (var tache : taches) {
                tache.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executeur.shutdownNow();
        }

        assertThat(horloge.decalage()).isEqualTo(Duration.parse("PT100S"));
        assertThat(horloge.instant()).isEqualTo(BASE.plusSeconds(100));
    }
}
