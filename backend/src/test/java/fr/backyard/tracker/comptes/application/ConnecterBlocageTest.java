package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.ConnexionBloqueeException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.IdentifiantsInvalidesException;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.RegistreTentativesConnexion;
import fr.backyard.tracker.comptes.domaine.Role;
import fr.backyard.tracker.comptes.domaine.TentativesConnexion;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Blocage temporaire des tentatives de connexion (incrément 1.3). */
class ConnecterBlocageTest {

    private static final Instant T = Instant.parse("2026-10-02T10:00:00Z");
    private static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    private static final String MOT_DE_PASSE_FAUX = "mauvais-mot-de-passe-1";
    private static final String PREFIXE_EMPREINTE = "empreinte:";

    private final HorlogeMutable horloge = new HorlogeMutable(T);
    private final DepotComptesEnMemoire depot = new DepotComptesEnMemoire();
    private final EncodeurEspion encodeur = new EncodeurEspion();
    private final RegistreEnMemoire registre = new RegistreEnMemoire();
    private final Connecter connecter = new Connecter(depot, encodeur, registre, PolitiqueBlocage.parDefaut(), horloge);

    private Compte compte(String pseudo) {
        Compte compte = Compte.creerCoureur(new Pseudo(pseudo), PREFIXE_EMPREINTE + MOT_DE_PASSE, T);
        depot.comptes.add(compte);
        return compte;
    }

    private void compteSansEmpreinte(String pseudo) {
        depot.comptes.add(Compte.reconstituer(UUID.randomUUID(), new Pseudo(pseudo), null, Role.COUREUR, T));
    }

    private void echouer(String pseudo, int fois) {
        for (int i = 0; i < fois; i++) {
            assertThatThrownBy(() -> connecter.executer(pseudo, MOT_DE_PASSE_FAUX))
                    .isInstanceOf(IdentifiantsInvalidesException.class);
        }
    }

    private Duration tempsRestantALaTentative(String pseudo, String motDePasse) {
        try {
            connecter.executer(pseudo, motDePasse);
        } catch (ConnexionBloqueeException e) {
            return e.tempsRestant();
        }
        throw new AssertionError("Une ConnexionBloqueeException était attendue");
    }

    @Test
    @DisplayName("CA1 - 5 échecs en IdentifiantsInvalides puis blocage, sans encodeur ni dépôt pour la tentative bloquée")
    void doit_bloquer_la_sixieme_tentative_sans_verifier_ni_interroger_le_depot() {
        compte("Alice");
        echouer("Alice", 5);
        int verificationsAvant = encodeur.empreintesVerifiees.size();
        int recherchesAvant = depot.recherches;

        assertThatThrownBy(() -> connecter.executer("Alice", MOT_DE_PASSE))
                .isInstanceOfSatisfying(ConnexionBloqueeException.class,
                        e -> assertThat(e.tempsRestant()).isEqualTo(Duration.ofSeconds(900)));

        assertThat(encodeur.empreintesVerifiees).hasSize(verificationsAvant);
        assertThat(depot.recherches).isEqualTo(recherchesAvant);
    }

    @Test
    @DisplayName("CA2 - à T+899,5 s reste 1 s ; à T+900 s le bon mot de passe est accepté")
    void doit_debloquer_a_l_instant_exact_de_fin_de_blocage() {
        Compte alice = compte("Alice");
        echouer("Alice", 5);

        horloge.avancerA(T.plusSeconds(900).minusMillis(500));
        assertThat(tempsRestantALaTentative("Alice", MOT_DE_PASSE)).isEqualTo(Duration.ofSeconds(1));

        horloge.avancerA(T.plusSeconds(900));
        assertThat(connecter.executer("Alice", MOT_DE_PASSE)).isEqualTo(alice);
    }

    @Test
    @DisplayName("CA2 - après la fin du blocage, il faut de nouveau 5 échecs : 4 ne bloquent pas, le 5e bloque")
    void doit_exiger_de_nouveau_cinq_echecs_apres_la_fin_du_blocage() {
        compte("Alice");
        echouer("Alice", 5);
        horloge.avancerA(T.plusSeconds(900));

        echouer("Alice", 4);
        assertThatThrownBy(() -> connecter.executer("Alice", MOT_DE_PASSE_FAUX))
                .isInstanceOf(IdentifiantsInvalidesException.class);

        assertThatThrownBy(() -> connecter.executer("Alice", MOT_DE_PASSE))
                .isInstanceOf(ConnexionBloqueeException.class);
    }

    @Test
    @DisplayName("CA3 - pseudo existant, inconnu et sans empreinte : même exception et même temps restant")
    void doit_bloquer_de_la_meme_facon_un_pseudo_existant_inconnu_ou_sans_empreinte() {
        compte("Alice");
        compteSansEmpreinte("Anonyme1");
        echouer("Alice", 5);
        echouer("Inconnu", 5);
        echouer("Anonyme1", 5);

        Duration pourAlice = tempsRestantALaTentative("Alice", MOT_DE_PASSE_FAUX);
        Duration pourInconnu = tempsRestantALaTentative("Inconnu", MOT_DE_PASSE_FAUX);
        Duration pourAnonyme = tempsRestantALaTentative("Anonyme1", MOT_DE_PASSE_FAUX);

        assertThat(pourAlice).isEqualTo(Duration.ofSeconds(900));
        assertThat(pourInconnu).isEqualTo(pourAlice);
        assertThat(pourAnonyme).isEqualTo(pourAlice);
    }

    @Test
    @DisplayName("CA4 - ALICE et ' alice ' partagent le même compteur que Alice")
    void doit_partager_le_compteur_entre_les_variantes_de_casse_et_d_espaces() {
        compte("Alice");
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> connecter.executer("ALICE", MOT_DE_PASSE_FAUX))
                    .isInstanceOf(IdentifiantsInvalidesException.class);
        }
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> connecter.executer(" alice ", MOT_DE_PASSE_FAUX))
                    .isInstanceOf(IdentifiantsInvalidesException.class);
        }

        assertThatThrownBy(() -> connecter.executer("Alice", MOT_DE_PASSE))
                .isInstanceOf(ConnexionBloqueeException.class);
    }

    @Test
    @DisplayName("CA5 - 4 échecs, un succès, 4 échecs : aucun blocage, le 5e appel avec le bon mot de passe réussit")
    void doit_remettre_le_compteur_a_zero_apres_un_succes() {
        Compte alice = compte("Alice");
        echouer("Alice", 4);
        assertThat(connecter.executer("Alice", MOT_DE_PASSE)).isEqualTo(alice);
        echouer("Alice", 4);

        assertThat(connecter.executer("Alice", MOT_DE_PASSE)).isEqualTo(alice);
    }

    @Test
    @DisplayName("CA5 - 4 échecs, un succès, 5 échecs : le 6e appel est bloqué")
    void doit_bloquer_apres_cinq_nouveaux_echecs_suivant_un_succes() {
        compte("Alice");
        echouer("Alice", 4);
        connecter.executer("Alice", MOT_DE_PASSE);
        echouer("Alice", 5);

        assertThatThrownBy(() -> connecter.executer("Alice", MOT_DE_PASSE))
                .isInstanceOf(ConnexionBloqueeException.class);
    }

    @Test
    @DisplayName("CA6 - 4 échecs à T puis un 5e à T+899 s : blocage déclenché")
    void doit_bloquer_quand_le_cinquieme_echec_survient_avant_la_fin_de_la_fenetre() {
        compte("Alice");
        echouer("Alice", 4);
        horloge.avancerA(T.plusSeconds(899));
        echouer("Alice", 1);

        assertThatThrownBy(() -> connecter.executer("Alice", MOT_DE_PASSE))
                .isInstanceOf(ConnexionBloqueeException.class);
    }

    @Test
    @DisplayName("CA6 - 4 échecs à T puis un échec à T+900 s : compteur 1, la tentative suivante n'est pas bloquée")
    void doit_oublier_les_echecs_partiels_apres_la_duree_de_blocage() {
        Compte alice = compte("Alice");
        echouer("Alice", 4);
        horloge.avancerA(T.plusSeconds(900));
        echouer("Alice", 1);

        assertThat(connecter.executer("Alice", MOT_DE_PASSE)).isEqualTo(alice);
    }

    @Test
    @DisplayName("CA7 - 10 tentatives pendant le blocage sont refusées sans prolonger ; à T+900 s traitement normal")
    void doit_ne_pas_prolonger_le_blocage_par_les_tentatives_refusees() {
        Compte alice = compte("Alice");
        echouer("Alice", 5);

        for (int secondes = 10; secondes <= 100; secondes += 10) {
            horloge.avancerA(T.plusSeconds(secondes));
            assertThat(tempsRestantALaTentative("Alice", MOT_DE_PASSE_FAUX))
                    .isEqualTo(Duration.ofSeconds(900 - secondes));
        }

        horloge.avancerA(T.plusSeconds(900));
        assertThat(connecter.executer("Alice", MOT_DE_PASSE)).isEqualTo(alice);
    }

    @Test
    @DisplayName("CA8 - les saisies hors bornes ou vides ne comptent pas ; un pseudo inconnu de 30 caractères compte")
    void doit_ne_compter_que_les_echecs_dans_les_bornes() {
        Compte alice = compte("Alice");
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> connecter.executer("a".repeat(31), MOT_DE_PASSE_FAUX))
                    .isInstanceOf(IdentifiantsInvalidesException.class);
            assertThatThrownBy(() -> connecter.executer("Alice", "m".repeat(129)))
                    .isInstanceOf(IdentifiantsInvalidesException.class);
            assertThatThrownBy(() -> connecter.executer("", MOT_DE_PASSE_FAUX)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> connecter.executer("Alice", "")).isInstanceOf(RuntimeException.class);
        }

        assertThat(connecter.executer("Alice", MOT_DE_PASSE)).isEqualTo(alice);

        String pseudo30 = "b".repeat(30);
        echouer(pseudo30, 5);
        assertThat(tempsRestantALaTentative(pseudo30, MOT_DE_PASSE_FAUX)).isEqualTo(Duration.ofSeconds(900));
    }

    @Test
    @DisplayName("CA9 - le blocage d'Alice n'affecte pas Bob")
    void doit_isoler_le_blocage_entre_pseudos() {
        compte("Alice");
        Compte bob = compte("Bob");
        echouer("Alice", 5);

        assertThat(connecter.executer("Bob", MOT_DE_PASSE)).isEqualTo(bob);
    }

    @Test
    @DisplayName("CA10 - avec une politique de 1 échec, le premier échec bloque la tentative suivante")
    void doit_bloquer_des_le_premier_echec_avec_echecs_max_a_1() {
        compte("Alice");
        Connecter strict = new Connecter(depot, encodeur, registre, new PolitiqueBlocage(1, 60), horloge);

        assertThatThrownBy(() -> strict.executer("Alice", MOT_DE_PASSE_FAUX))
                .isInstanceOf(IdentifiantsInvalidesException.class);

        assertThatThrownBy(() -> strict.executer("Alice", MOT_DE_PASSE))
                .isInstanceOfSatisfying(ConnexionBloqueeException.class,
                        e -> assertThat(e.tempsRestant()).isEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("CA11 - le message de blocage ne contient ni le pseudo ni le mot de passe saisis")
    void doit_ne_pas_exposer_la_saisie_dans_l_exception_de_blocage() {
        compte("Alice");
        echouer("Alice", 5);

        assertThatThrownBy(() -> connecter.executer("Alice", "secret-de-test-123"))
                .isInstanceOfSatisfying(ConnexionBloqueeException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain("Alice").doesNotContain("secret-de-test-123");
                    assertThat(e.toString()).doesNotContain("Alice").doesNotContain("secret-de-test-123");
                });
    }

    /** Registre en mémoire : l'atomicité est assurée par Map.compute ; les règles viennent du domaine. */
    private static final class RegistreEnMemoire implements RegistreTentativesConnexion {
        private final Map<String, TentativesConnexion> entrees = new HashMap<>();

        @Override
        public TentativesConnexion constater(String cle, Instant maintenant) {
            return entrees.getOrDefault(cle, TentativesConnexion.aucune());
        }

        @Override
        public TentativesConnexion enregistrerEchec(String cle, Instant maintenant, PolitiqueBlocage politique) {
            return entrees.compute(cle, (k, actuelle) ->
                    (actuelle == null ? TentativesConnexion.aucune() : actuelle).apresEchec(maintenant, politique));
        }

        @Override
        public void effacer(String cle) {
            entrees.remove(cle);
        }
    }

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

    private static final class DepotComptesEnMemoire implements DepotComptes {
        final List<Compte> comptes = new ArrayList<>();
        int recherches;

        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            return trouverParPseudoNormalise(pseudoNormalise).isPresent();
        }

        @Override
        public Optional<Compte> trouverParPseudoNormalise(String pseudoNormalise) {
            recherches++;
            return comptes.stream().filter(c -> c.pseudoNormalise().equals(pseudoNormalise)).findFirst();
        }

        @Override
        public void enregistrer(Compte compte) {
            comptes.add(compte);
        }
    }

    private static final class EncodeurEspion implements EncodeurMotDePasse {
        final List<String> empreintesVerifiees = new ArrayList<>();

        @Override
        public String encoder(MotDePasse motDePasse) {
            return PREFIXE_EMPREINTE + motDePasse.valeur();
        }

        @Override
        public boolean verifier(String motDePasseEnClair, String empreinte) {
            empreintesVerifiees.add(empreinte);
            return empreinte != null && empreinte.equals(PREFIXE_EMPREINTE + motDePasseEnClair);
        }
    }
}
