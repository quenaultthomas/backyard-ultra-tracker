package fr.backyard.tracker.comptes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.domaine.AdresseSuivie;
import fr.backyard.tracker.comptes.domaine.PlafondEchecsParAdresse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 8.5, CA5 : registre mémoire des créations de Comptes, atomique, borné, séparé de celui des échecs de
 * connexion, sans Spring (RG6, RG7). Le résultat de {@code admettre} expose {@code admise()} et
 * {@code tempsRestant()}.
 */
class RegistreCreationsCompteParAdresseEnMemoireTest {

    private static final int CAPACITE = RegistreEchecsParAdresseEnMemoire.CAPACITE;
    private static final Instant T = Instant.parse("2026-10-26T10:00:00Z");
    private static final PlafondEchecsParAdresse TROIS_PAR_MINUTE = new PlafondEchecsParAdresse(3, 60);
    private static final PlafondEchecsParAdresse DIX_PAR_MINUTE = new PlafondEchecsParAdresse(10, 60);

    private final RegistreCreationsCompteParAdresseEnMemoire registre = new RegistreCreationsCompteParAdresseEnMemoire();

    private static AdresseSuivie adresse(int numero) {
        return AdresseSuivie.depuis("10." + ((numero >> 16) & 255) + "." + ((numero >> 8) & 255) + "." + (numero & 255));
    }

    private boolean admise(int numeroAdresse, Instant instant) {
        return registre.admettre(adresse(numeroAdresse), instant, TROIS_PAR_MINUTE).admise();
    }

    @Test
    @DisplayName("CA5 - 50 fils qui demandent l'admission de la même adresse avec le plafond (10, 60) : exactement 10 admis")
    void doit_admettre_exactement_le_plafond_sous_acces_concurrents() throws Exception {
        int fils = 50;
        ExecutorService executeur = Executors.newFixedThreadPool(fils);
        CountDownLatch depart = new CountDownLatch(1);
        List<Future<Boolean>> taches = new ArrayList<>();
        for (int f = 0; f < fils; f++) {
            taches.add(executeur.submit(() -> {
                depart.await();
                return registre.admettre(adresse(1), T, DIX_PAR_MINUTE).admise();
            }));
        }
        depart.countDown();
        int admis = 0;
        for (Future<Boolean> tache : taches) {
            if (tache.get(10, TimeUnit.SECONDS)) {
                admis++;
            }
        }
        executeur.shutdown();

        assertThat(admis).isEqualTo(10);
        assertThat(registre.taille()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 - les trois premières admissions passent, la quatrième est refusée avec le temps restant de la plus ancienne")
    void doit_refuser_apres_le_plafond_avec_le_temps_restant() {
        registre.admettre(adresse(1), T, TROIS_PAR_MINUTE);
        registre.admettre(adresse(1), T.plusSeconds(10), TROIS_PAR_MINUTE);
        registre.admettre(adresse(1), T.plusSeconds(20), TROIS_PAR_MINUTE);

        var refus = registre.admettre(adresse(1), T.plusSeconds(20), TROIS_PAR_MINUTE);

        assertThat(refus.admise()).isFalse();
        assertThat(refus.tempsRestant()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("CA5 - un refus n'est pas enregistré : il ne décale pas la libération de l'adresse")
    void doit_ne_pas_enregistrer_un_refus() {
        registre.admettre(adresse(1), T, TROIS_PAR_MINUTE);
        registre.admettre(adresse(1), T.plusSeconds(10), TROIS_PAR_MINUTE);
        registre.admettre(adresse(1), T.plusSeconds(20), TROIS_PAR_MINUTE);
        registre.admettre(adresse(1), T.plusSeconds(30), TROIS_PAR_MINUTE);

        assertThat(registre.admettre(adresse(1), T.plusSeconds(60), TROIS_PAR_MINUTE).admise()).isTrue();
    }

    @Test
    @DisplayName("CA5 - plus de 10 000 adresses distinctes : la taille reste au plus 10 000")
    void doit_borner_le_nombre_d_adresses_suivies() {
        for (int i = 0; i <= CAPACITE; i++) {
            admise(i, T);
        }

        assertThat(registre.taille()).isLessThanOrEqualTo(CAPACITE);
    }

    @Test
    @DisplayName("CA5 - une adresse plafonnée n'est évincée qu'en dernier recours : elle reste refusée après saturation")
    void doit_proteger_une_adresse_plafonnee_de_l_eviction() {
        for (int i = 0; i < 3; i++) {
            admise(0, T);
        }
        for (int i = 1; i < CAPACITE; i++) {
            admise(i, T.plusSeconds(1).plusMillis(i));
        }

        admise(CAPACITE, T.plusSeconds(20));

        assertThat(registre.taille()).isEqualTo(CAPACITE);
        assertThat(admise(0, T.plusSeconds(20))).isFalse();
    }

    @Test
    @DisplayName("CA5 - les adresses oubliées sont purgées avant toute éviction d'adresse active")
    void doit_purger_les_adresses_oubliees_avant_d_evincer() {
        for (int i = 0; i < CAPACITE / 2; i++) {
            admise(i, T);
        }
        Instant plusTard = T.plusSeconds(100);
        for (int i = CAPACITE / 2; i < CAPACITE; i++) {
            admise(i, plusTard);
        }

        admise(CAPACITE, plusTard);

        assertThat(registre.taille()).isEqualTo(CAPACITE / 2 + 1);
    }

    @Test
    @DisplayName("CA5 - des refus répétés ne créent aucune entrée supplémentaire")
    void doit_ne_creer_aucune_entree_sur_refus() {
        for (int i = 0; i < 3; i++) {
            admise(1, T);
        }
        assertThat(registre.taille()).isEqualTo(1);

        for (int i = 0; i < 20; i++) {
            assertThat(admise(1, T.plusSeconds(1))).isFalse();
        }

        assertThat(registre.taille()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 - les échecs de connexion d'une adresse dans le registre de la connexion n'affectent pas ses admissions")
    void doit_ignorer_les_echecs_de_connexion_de_la_meme_adresse() {
        RegistreEchecsParAdresseEnMemoire registreConnexion = new RegistreEchecsParAdresseEnMemoire();
        for (int i = 0; i < 3; i++) {
            registreConnexion.enregistrerEchec(adresse(1), T, TROIS_PAR_MINUTE);
        }

        assertThat(admise(1, T)).isTrue();
        assertThat(admise(1, T)).isTrue();
        assertThat(admise(1, T)).isTrue();
    }

    @Test
    @DisplayName("CA5 - les admissions d'une adresse n'entament pas son compteur d'échecs de connexion")
    void doit_ne_pas_toucher_au_compteur_d_echecs_de_connexion() {
        RegistreEchecsParAdresseEnMemoire registreConnexion = new RegistreEchecsParAdresseEnMemoire();
        for (int i = 0; i < 3; i++) {
            admise(1, T);
        }

        assertThat(registreConnexion.constater(adresse(1), T).estPlafonnee(T, TROIS_PAR_MINUTE)).isFalse();
        assertThat(registreConnexion.taille()).isZero();
    }

    @Test
    @DisplayName("CA5 - vider() remet le registre à zéro et libère les adresses plafonnées")
    void doit_liberer_toutes_les_adresses_apres_vider() {
        for (int i = 0; i < 3; i++) {
            admise(1, T);
        }

        registre.vider();

        assertThat(registre.taille()).isZero();
        assertThat(admise(1, T)).isTrue();
    }
}
