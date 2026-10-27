package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.CreerBenevole.Commande;
import fr.backyard.tracker.comptes.domaine.AdresseDeLaRequete;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CreationsCompteExcessivesException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.HachageSatureException;
import fr.backyard.tracker.comptes.domaine.PlafondCreationsCompteParAdresse;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.PseudoDejaUtiliseException;
import fr.backyard.tracker.comptes.domaine.Role;
import fr.backyard.tracker.comptes.infrastructure.RegistreCreationsCompteParAdresseEnMemoire;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 8.5, CA2 à CA4 et CA7 : plafond de créations de Comptes coureur par adresse dans le cas d'usage
 * (RG1 à RG5, RG8). Le registre est l'implémentation en mémoire réelle, espionnée par Mockito.
 */
class CreerCompteCoureurPlafondAdresseTest {

    private static final Instant T = Instant.parse("2026-10-26T10:00:00Z");
    private static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    private static final String ADRESSE_A = "203.0.113.1";
    private static final String ADRESSE_B = "203.0.113.2";
    private static final PlafondCreationsCompteParAdresse TROIS_PAR_MINUTE = new PlafondCreationsCompteParAdresse(3, 60);

    private final HorlogeMutable horloge = new HorlogeMutable(T);
    private final DepotEspion depot = new DepotEspion();
    private final EncodeurEspion encodeur = new EncodeurEspion();
    private final AdresseDeLaRequeteFausse adresseCourante = new AdresseDeLaRequeteFausse(ADRESSE_A);
    private final RegistreCreationsCompteParAdresseEnMemoire registre =
            spy(new RegistreCreationsCompteParAdresseEnMemoire());
    private final CreerCompteCoureur creerCompteCoureur =
            new CreerCompteCoureur(depot, encodeur, horloge, adresseCourante, registre, TROIS_PAR_MINUTE);
    private JournalApplicatif journal;

    @AfterEach
    void fermerLeJournal() {
        if (journal != null) {
            journal.fermer();
        }
    }

    private Compte creerA(Instant instant, String pseudo) {
        horloge.avancerA(instant);
        return creerCompteCoureur.executer(pseudo, MOT_DE_PASSE);
    }

    private CreationsCompteExcessivesException refusA(Instant instant, String pseudo) {
        horloge.avancerA(instant);
        try {
            creerCompteCoureur.executer(pseudo, MOT_DE_PASSE);
        } catch (CreationsCompteExcessivesException e) {
            return e;
        }
        throw new AssertionError("Une CreationsCompteExcessivesException était attendue");
    }

    private void creerSansLimiteDepuis(String adresse, String prefixe, int nombre) {
        adresseCourante.definir(adresse);
        for (int i = 0; i < nombre; i++) {
            creerCompteCoureur.executer(prefixe + i, MOT_DE_PASSE);
        }
    }

    // ------------------------------------------------------------------ CA2

    @Test
    @DisplayName("CA2 - trois créations valides à T, T+10 s et T+20 s sont admises et les Comptes créés")
    void doit_admettre_les_trois_premieres_creations_de_la_fenetre() {
        creerA(T, "Alice");
        creerA(T.plusSeconds(10), "Berthe");
        creerA(T.plusSeconds(20), "Chloe");

        assertThat(depot.enregistres).extracting(c -> c.pseudo().valeur()).containsExactly("Alice", "Berthe", "Chloe");
        assertThat(depot.enregistres).allSatisfy(c -> assertThat(c.role()).isEqualTo(Role.COUREUR));
    }

    @Test
    @DisplayName("CA2 - la 4e création à T+20 s est refusée avec 40 s à attendre")
    void doit_refuser_la_quatrieme_creation_avec_le_temps_restant_de_la_plus_ancienne() {
        creerA(T, "Alice");
        creerA(T.plusSeconds(10), "Berthe");
        creerA(T.plusSeconds(20), "Chloe");

        CreationsCompteExcessivesException refus = refusA(T.plusSeconds(20), "Denis");

        assertThat(refus.reessayerDansSecondes()).isEqualTo(40);
        assertThat(depot.enregistres).hasSize(3);
    }

    @Test
    @DisplayName("CA2 - à T+59 s l'adresse est encore plafonnée, 1 s à attendre")
    void doit_refuser_une_seconde_avant_l_expiration_du_premier_evenement() {
        creerA(T, "Alice");
        creerA(T.plusSeconds(10), "Berthe");
        creerA(T.plusSeconds(20), "Chloe");

        assertThat(refusA(T.plusSeconds(59), "Denis").reessayerDansSecondes()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA2 - les tentatives refusées à T+20 s, T+30 s et T+59 s ne sont pas enregistrées : à T+60 s une création est admise")
    void doit_admettre_a_l_expiration_du_premier_evenement_car_les_refus_n_ont_rien_decale() {
        creerA(T, "Alice");
        creerA(T.plusSeconds(10), "Berthe");
        creerA(T.plusSeconds(20), "Chloe");
        refusA(T.plusSeconds(20), "Denis");
        refusA(T.plusSeconds(30), "Denis");
        refusA(T.plusSeconds(59), "Denis");

        Compte admise = creerA(T.plusSeconds(60), "Denis");

        assertThat(admise.pseudo().valeur()).isEqualTo("Denis");
    }

    @Test
    @DisplayName("CA2 - après l'admission à T+60 s, une nouvelle tentative à T+60 s est refusée avec 10 s à attendre (T+10 s libère à T+70 s)")
    void doit_replafonner_selon_le_deuxieme_evenement_apres_l_admission_a_l_expiration() {
        creerA(T, "Alice");
        creerA(T.plusSeconds(10), "Berthe");
        creerA(T.plusSeconds(20), "Chloe");
        creerA(T.plusSeconds(60), "Denis");

        assertThat(refusA(T.plusSeconds(60), "Elsa").reessayerDansSecondes()).isEqualTo(10);
    }

    @Test
    @DisplayName("CA2 - fenêtre glissante : 3 créations à T+59 s, la 4e à T+60 s est refusée (pas de rafale à la jonction)")
    void doit_refuser_a_la_jonction_de_deux_minutes_sans_rafale_double() {
        creerA(T.plusSeconds(59), "Alice");
        creerA(T.plusSeconds(59), "Berthe");
        creerA(T.plusSeconds(59), "Chloe");

        assertThat(refusA(T.plusSeconds(60), "Denis").reessayerDansSecondes()).isEqualTo(59);
    }

    @Test
    @DisplayName("CA2 - un refus n'appelle ni le dépôt ni l'encodeur, même avec une saisie invalide ou un pseudo déjà pris")
    void doit_refuser_avant_toute_lecture_du_depot_et_tout_hachage() {
        creerA(T, "Alice");
        creerA(T, "Berthe");
        creerA(T, "Chloe");
        int recherchesAvant = depot.recherches;
        int enregistrementsAvant = depot.enregistres.size();
        int encodagesAvant = encodeur.encodages;

        assertThatThrownBy(() -> creerCompteCoureur.executer("Denis", MOT_DE_PASSE))
                .isInstanceOf(CreationsCompteExcessivesException.class);
        assertThatThrownBy(() -> creerCompteCoureur.executer("", "court"))
                .isInstanceOf(CreationsCompteExcessivesException.class);
        assertThatThrownBy(() -> creerCompteCoureur.executer("Alice", MOT_DE_PASSE))
                .isInstanceOf(CreationsCompteExcessivesException.class);
        assertThatThrownBy(() -> creerCompteCoureur.executer(null, null))
                .isInstanceOf(CreationsCompteExcessivesException.class);

        assertThat(depot.recherches).isEqualTo(recherchesAvant);
        assertThat(depot.enregistres).hasSize(enregistrementsAvant);
        assertThat(encodeur.encodages).isEqualTo(encodagesAvant);
    }

    @Test
    @DisplayName("CA2 - la 10e création est admise et la 11e refusée avec le plafond par défaut (10, 60)")
    void doit_refuser_la_onzieme_creation_avec_le_plafond_par_defaut() {
        CreerCompteCoureur parDefaut = new CreerCompteCoureur(depot, encodeur, horloge, adresseCourante,
                new RegistreCreationsCompteParAdresseEnMemoire(), PlafondCreationsCompteParAdresse.parDefaut());
        for (int i = 1; i <= 10; i++) {
            parDefaut.executer("Coureur" + i, MOT_DE_PASSE);
        }

        assertThatThrownBy(() -> parDefaut.executer("Coureur11", MOT_DE_PASSE))
                .isInstanceOf(CreationsCompteExcessivesException.class);
        assertThat(depot.enregistres).hasSize(10);
    }

    // ------------------------------------------------------------------ CA3

    @Test
    @DisplayName("CA3 - une saisie invalide, un pseudo déjà utilisé et une saturation du hachage comptent : la 4e tentative valide est refusée")
    void doit_compter_les_tentatives_quelle_que_soit_leur_issue() {
        depot.enregistres.add(Compte.creerCoureur(new Pseudo("Alice"), "empreinte", T));

        assertThatThrownBy(() -> creerCompteCoureur.executer("", MOT_DE_PASSE))
                .isInstanceOf(DonneesCompteInvalidesException.class);
        assertThatThrownBy(() -> creerCompteCoureur.executer("Alice", MOT_DE_PASSE))
                .isInstanceOf(PseudoDejaUtiliseException.class);
        encodeur.saturer = true;
        assertThatThrownBy(() -> creerCompteCoureur.executer("Berthe", MOT_DE_PASSE))
                .isInstanceOf(HachageSatureException.class);
        encodeur.saturer = false;

        assertThatThrownBy(() -> creerCompteCoureur.executer("Chloe", MOT_DE_PASSE))
                .isInstanceOf(CreationsCompteExcessivesException.class);
    }

    @Test
    @DisplayName("CA3 - la création d'un BENEVOLE ou d'un ADMIN n'est jamais limitée ni comptée par le registre de création")
    void doit_ne_jamais_consulter_le_registre_de_creation_pour_un_benevole_ou_un_admin() {
        creerSansLimiteDepuis(ADRESSE_A, "Coureur", 3);
        CreerBenevole creerBenevole = new CreerBenevole(depot, encodeur, horloge);
        CreerAdmin creerAdmin = new CreerAdmin(depot, encodeur, horloge);

        assertThatCode(() -> {
            for (int i = 0; i < 5; i++) {
                creerBenevole.executer(new Commande("Benevole" + i, MOT_DE_PASSE));
                creerAdmin.executer(new CreerAdmin.Commande("Admin" + i, MOT_DE_PASSE));
            }
        }).doesNotThrowAnyException();
        verify(registre, times(3)).admettre(any(), any(), any());
    }

    // ------------------------------------------------------------------ CA4

    @Test
    @DisplayName("CA4 - sans adresse de requête, 100 créations sont admises sans appel au registre")
    void doit_ne_ni_controler_ni_compter_quand_l_adresse_est_inconnue() {
        adresseCourante.definirInconnue();

        for (int i = 0; i < 100; i++) {
            creerCompteCoureur.executer("Coureur" + i, MOT_DE_PASSE);
        }

        assertThat(depot.enregistres).hasSize(100);
        verify(registre, never()).admettre(any(), any(), any());
    }

    @Test
    @DisplayName("CA4 - deux adresses IPv6 du même /64 partagent le compteur : la 4e création est refusée")
    void doit_partager_le_compteur_entre_adresses_ipv6_d_un_meme_prefixe_64() {
        adresseCourante.definir("2001:db8:1:2::1");
        creerCompteCoureur.executer("Alice", MOT_DE_PASSE);
        adresseCourante.definir("2001:db8:1:2:aaaa:bbbb:cccc:dddd");
        creerCompteCoureur.executer("Berthe", MOT_DE_PASSE);
        adresseCourante.definir("2001:db8:1:2::1");
        creerCompteCoureur.executer("Chloe", MOT_DE_PASSE);

        adresseCourante.definir("2001:db8:1:2:aaaa:bbbb:cccc:dddd");
        assertThatThrownBy(() -> creerCompteCoureur.executer("Denis", MOT_DE_PASSE))
                .isInstanceOf(CreationsCompteExcessivesException.class);
    }

    @Test
    @DisplayName("CA4 - un autre /64 IPv6 et une IPv4 ont chacun leur compteur, indépendant de l'adresse plafonnée")
    void doit_avoir_un_compteur_independant_par_prefixe_ou_par_adresse() {
        creerSansLimiteDepuis("2001:db8:1:2::1", "Plafonnee", 3);

        assertThatCode(() -> creerSansLimiteDepuis("2001:db8:1:3::1", "AutrePrefixe", 3)).doesNotThrowAnyException();
        assertThatCode(() -> creerSansLimiteDepuis(ADRESSE_B, "AutreIpv4", 3)).doesNotThrowAnyException();
        adresseCourante.definir("2001:db8:1:2::9");
        assertThatThrownBy(() -> creerCompteCoureur.executer("Intrus", MOT_DE_PASSE))
                .isInstanceOf(CreationsCompteExcessivesException.class);
    }

    @Test
    @DisplayName("CA4 - le compteur d'une adresse se libère indépendamment de celui d'une autre")
    void doit_plafonner_une_adresse_sans_affecter_l_autre() {
        creerSansLimiteDepuis(ADRESSE_A, "Plafonnee", 3);

        adresseCourante.definir(ADRESSE_B);

        assertThat(creerCompteCoureur.executer("Berthe", MOT_DE_PASSE).pseudo().valeur()).isEqualTo("Berthe");
    }

    // ------------------------------------------------------------------ CA7

    @Test
    @DisplayName("CA7 - 10 créations dans la fenêtre : une seule ligne WARN de plafond atteint et 7 lignes DEBUG de refus")
    void doit_journaliser_un_warn_au_plafond_et_un_debug_par_refus() {
        journal = JournalApplicatif.demarrerEnDebug();

        for (int i = 0; i < 10; i++) {
            try {
                creerCompteCoureur.executer("Coureur" + i, MOT_DE_PASSE);
            } catch (CreationsCompteExcessivesException attendue) {
                // refus comptés par le journal
            }
        }

        assertThat(lignes(Level.WARN)).containsExactly("Création de compte : plafond par adresse atteint");
        assertThat(lignes(Level.DEBUG))
                .hasSize(7)
                .containsOnly("Création de compte refusée : plafond par adresse atteint");
    }

    @Test
    @DisplayName("CA7 - aucune ligne du journal ne contient un pseudo, un mot de passe, une adresse, un cookie ni un jeton")
    void doit_ne_journaliser_aucune_donnee_sensible() {
        journal = JournalApplicatif.demarrerEnDebug();

        for (int i = 0; i < 10; i++) {
            try {
                creerCompteCoureur.executer("Coureur" + i, MOT_DE_PASSE);
            } catch (CreationsCompteExcessivesException attendue) {
                // refus comptés par le journal
            }
        }

        assertThat(journal.messagesEtExceptions())
                .isNotEmpty()
                .noneMatch(m -> m.contains("Coureur") || m.contains(MOT_DE_PASSE) || m.contains(ADRESSE_A)
                        || m.toLowerCase().contains("cookie") || m.toLowerCase().contains("jeton")
                        || m.toLowerCase().contains("token"));
    }

    @Test
    @DisplayName("CA7 - une admission qui n'atteint pas le plafond ne journalise aucun WARN")
    void doit_ne_pas_avertir_sous_le_plafond() {
        journal = JournalApplicatif.demarrerEnDebug();

        creerCompteCoureur.executer("Alice", MOT_DE_PASSE);
        creerCompteCoureur.executer("Berthe", MOT_DE_PASSE);

        assertThat(lignes(Level.WARN)).isEmpty();
    }

    private List<String> lignes(Level niveau) {
        return journal.evenementsApplicatifs().stream()
                .filter(e -> e.getLevel() == niveau)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("Création de compte"))
                .toList();
    }

    // ------------------------------------------------------------------ doublures

    private static final class HorlogeMutable extends Clock {
        private Instant instant;

        HorlogeMutable(Instant instant) {
            this.instant = instant;
        }

        void avancerA(Instant nouvelInstant) {
            this.instant = nouvelInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class AdresseDeLaRequeteFausse implements AdresseDeLaRequete {
        private Optional<String> adresse;

        AdresseDeLaRequeteFausse(String adresse) {
            this.adresse = Optional.of(adresse);
        }

        void definir(String nouvelleAdresse) {
            this.adresse = Optional.of(nouvelleAdresse);
        }

        void definirInconnue() {
            this.adresse = Optional.empty();
        }

        @Override
        public Optional<String> courante() {
            return adresse;
        }
    }

    private static final class DepotEspion implements DepotComptes {
        final List<Compte> enregistres = new ArrayList<>();
        int recherches;

        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            recherches++;
            return enregistres.stream().anyMatch(c -> c.pseudoNormalise().equals(pseudoNormalise));
        }

        @Override
        public void enregistrer(Compte compte) {
            enregistres.add(compte);
        }
    }

    private static final class EncodeurEspion implements EncodeurMotDePasse {
        int encodages;
        boolean saturer;

        @Override
        public String encoder(fr.backyard.tracker.comptes.domaine.MotDePasse motDePasse) {
            if (saturer) {
                throw new HachageSatureException();
            }
            encodages++;
            return "empreinte-factice";
        }
    }
}
