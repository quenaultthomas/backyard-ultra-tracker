package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.domaine.AdresseDeLaRequete;
import fr.backyard.tracker.comptes.domaine.AdresseSuivie;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.ConnexionBloqueeException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EchecsParAdresse;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.HachageSatureException;
import fr.backyard.tracker.comptes.domaine.IdentifiantsInvalidesException;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.comptes.domaine.PlafondEchecsParAdresse;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.RegistreEchecsParAdresse;
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

/** Incrément 8.4, CA5 à CA8 : plafond d'échecs par adresse dans la connexion (RG2 à RG6, RG8). */
class ConnecterPlafondAdresseTest {

    private static final Instant T = Instant.parse("2026-10-26T10:00:00Z");
    private static final String MOT_DE_PASSE_KARIM = "un-mot-de-passe-12";
    private static final String MOT_DE_PASSE_LEO = "mot-de-passe-benevole-1";
    private static final String MOT_DE_PASSE_FAUX = "mauvais-mot-de-passe-1";
    private static final String PREFIXE_EMPREINTE = "empreinte:";
    private static final String ADRESSE_A = "203.0.113.1";
    private static final String ADRESSE_B = "203.0.113.2";

    private final HorlogeMutable horloge = new HorlogeMutable(T);
    private final DepotComptesEnMemoire depot = new DepotComptesEnMemoire();
    private final EncodeurEspion encodeur = new EncodeurEspion();
    private final RegistreParPseudoEnMemoire registrePseudos = new RegistreParPseudoEnMemoire();
    private final RegistreParAdresseEnMemoire registreAdresses = new RegistreParAdresseEnMemoire();
    private final AdresseDeLaRequeteFausse adresseCourante = new AdresseDeLaRequeteFausse(ADRESSE_A);
    private final Connecter connecter = new Connecter(depot, encodeur, registrePseudos, PolitiqueBlocage.parDefaut(),
            registreAdresses, PlafondEchecsParAdresse.parDefaut(), adresseCourante, horloge);

    private Compte karim() {
        return ajouter(Compte.creerCoureur(new Pseudo("Karim"), PREFIXE_EMPREINTE + MOT_DE_PASSE_KARIM, T));
    }

    private Compte leo() {
        return ajouter(Compte.creerBenevole(new Pseudo("Léo"), PREFIXE_EMPREINTE + MOT_DE_PASSE_LEO, T));
    }

    private Compte compteDesactive(String pseudo) {
        return ajouter(Compte.reconstituer(UUID.randomUUID(), new Pseudo(pseudo),
                PREFIXE_EMPREINTE + MOT_DE_PASSE_KARIM, Role.COUREUR, T, false));
    }

    private Compte compteSansEmpreinte(String pseudo) {
        return ajouter(Compte.reconstituer(UUID.randomUUID(), new Pseudo(pseudo), null, Role.COUREUR, T, true));
    }

    private Compte ajouter(Compte compte) {
        depot.comptes.add(compte);
        return compte;
    }

    private void echouer(String pseudo, String motDePasse) {
        assertThatThrownBy(() -> connecter.executer(pseudo, motDePasse))
                .isInstanceOf(IdentifiantsInvalidesException.class);
    }

    /** Échecs sur des pseudos inconnus distincts (aucun pseudo ne cumule plus d'un échec). */
    private void echouerSurPseudosInconnus(int nombre, String prefixe) {
        for (int i = 0; i < nombre; i++) {
            echouer(prefixe + i, MOT_DE_PASSE_FAUX);
        }
    }

    private Duration tempsRestantRefuse(String pseudo, String motDePasse) {
        try {
            connecter.executer(pseudo, motDePasse);
        } catch (ConnexionBloqueeException e) {
            return e.tempsRestant();
        }
        throw new AssertionError("Une ConnexionBloqueeException était attendue");
    }

    @Test
    @DisplayName("CA5 - 20 échecs de natures variées depuis A sont des 401 ; la 21e, avec le bon mot de passe, est refusée pour 60 s sans hachage ni lecture du dépôt")
    void doit_refuser_la_vingt_et_unieme_tentative_d_une_adresse_sans_hacher_ni_lire_le_depot() {
        Compte karim = karim();
        leo();
        compteDesactive("Desactive1");
        compteDesactive("Desactive2");
        compteDesactive("Desactive3");
        compteSansEmpreinte("Anonyme1");
        compteSansEmpreinte("Anonyme2");
        compteSansEmpreinte("Anonyme3");

        echouerSurPseudosInconnus(7, "Inconnu");
        for (int i = 0; i < 4; i++) {
            echouer("Karim", MOT_DE_PASSE_FAUX);
        }
        for (int i = 0; i < 3; i++) {
            echouer("Léo", MOT_DE_PASSE_FAUX);
        }
        for (int i = 1; i <= 3; i++) {
            echouer("Desactive" + i, MOT_DE_PASSE_KARIM);
            echouer("Anonyme" + i, MOT_DE_PASSE_KARIM);
        }
        int verificationsAvant = encodeur.empreintesVerifiees.size();
        int recherchesAvant = depot.recherches;
        int echecsKarimAvant = registrePseudos.constater("karim", T).echecs();

        assertThatThrownBy(() -> connecter.executer("Karim", MOT_DE_PASSE_KARIM))
                .isInstanceOfSatisfying(ConnexionBloqueeException.class,
                        e -> assertThat(e.tempsRestant()).isEqualTo(Duration.ofSeconds(60)));

        assertThat(encodeur.empreintesVerifiees).hasSize(verificationsAvant);
        assertThat(depot.recherches).isEqualTo(recherchesAvant);
        assertThat(registrePseudos.constater("karim", T).echecs()).isEqualTo(echecsKarimAvant).isEqualTo(4);

        adresseCourante.definir(ADRESSE_B);
        assertThat(connecter.executer("Karim", MOT_DE_PASSE_KARIM)).isEqualTo(karim);
    }

    @Test
    @DisplayName("CA6 - 19 échecs depuis A, un succès, un échec : l'adresse est plafonnée, le succès n'a pas effacé son compteur")
    void doit_conserver_le_compteur_d_adresse_apres_une_connexion_reussie() {
        Compte karim = karim();
        echouerSurPseudosInconnus(19, "Inconnu");

        assertThat(connecter.executer("Karim", MOT_DE_PASSE_KARIM)).isEqualTo(karim);
        echouer("Inconnu-final", MOT_DE_PASSE_FAUX);

        assertThatThrownBy(() -> connecter.executer("Karim", MOT_DE_PASSE_KARIM))
                .isInstanceOf(ConnexionBloqueeException.class);
    }

    @Test
    @DisplayName("CA6 - 4 échecs sur Karim, un succès, 4 échecs : le compteur par pseudo est bien effacé, pas de blocage")
    void doit_effacer_le_compteur_par_pseudo_apres_un_succes() {
        Compte karim = karim();
        for (int i = 0; i < 4; i++) {
            echouer("Karim", MOT_DE_PASSE_FAUX);
        }
        assertThat(connecter.executer("Karim", MOT_DE_PASSE_KARIM)).isEqualTo(karim);
        for (int i = 0; i < 4; i++) {
            echouer("Karim", MOT_DE_PASSE_FAUX);
        }

        assertThat(connecter.executer("Karim", MOT_DE_PASSE_KARIM)).isEqualTo(karim);
    }

    @Test
    @DisplayName("CA7 - champs absents, saisies hors bornes, saturation du hachage et refus par pseudo ne comptent pas pour l'adresse")
    void doit_ne_compter_que_les_authentifications_menees_a_leur_terme_et_rejetees() {
        karim();
        for (int i = 0; i < 5; i++) {
            echouer("Karim", MOT_DE_PASSE_FAUX);
        }
        assertThat(registreAdresses.enregistrements).isEqualTo(5);

        for (int i = 0; i < 25; i++) {
            assertThatThrownBy(() -> connecter.executer(null, null))
                    .isInstanceOf(DonneesCompteInvalidesException.class);
            assertThatThrownBy(() -> connecter.executer("a".repeat(31), MOT_DE_PASSE_FAUX))
                    .isInstanceOf(IdentifiantsInvalidesException.class);
        }
        encodeur.saturer = true;
        for (int i = 0; i < 25; i++) {
            String pseudo = "Inconnu" + i;
            assertThatThrownBy(() -> connecter.executer(pseudo, MOT_DE_PASSE_FAUX))
                    .isInstanceOf(HachageSatureException.class);
        }
        encodeur.saturer = false;
        for (int i = 0; i < 30; i++) {
            assertThatThrownBy(() -> connecter.executer("Karim", MOT_DE_PASSE_KARIM))
                    .isInstanceOf(ConnexionBloqueeException.class);
        }

        assertThat(registreAdresses.enregistrements).isEqualTo(5);
    }

    @Test
    @DisplayName("CA7 - 19 échecs comptés laissent A non plafonnée ; le 20e la plafonne et la tentative suivante est refusée")
    void doit_plafonner_exactement_au_vingtieme_echec_compte() {
        karim();
        for (int i = 0; i < 5; i++) {
            echouer("Karim", MOT_DE_PASSE_FAUX);
        }
        echouerSurPseudosInconnus(14, "Autre");

        echouer("Dernier", MOT_DE_PASSE_FAUX);

        assertThatThrownBy(() -> connecter.executer("Suivant", MOT_DE_PASSE_FAUX))
                .isInstanceOf(ConnexionBloqueeException.class);
    }

    @Test
    @DisplayName("CA8 - pseudo bloqué 900 s et adresse plafonnée 40 s : une seule exception de 900 s, rien d'enregistré")
    void doit_refuser_avec_le_maximum_des_deux_temps_quand_pseudo_bloque_et_adresse_plafonnee() {
        karim();
        adresseSature(40);
        bloquerParPseudoDepuis(ADRESSE_B, "Karim");
        int enregistrementsAvant = registreAdresses.enregistrements;

        assertThat(tempsRestantRefuse("Karim", MOT_DE_PASSE_KARIM)).isEqualTo(Duration.ofSeconds(900));

        assertThat(registreAdresses.enregistrements).isEqualTo(enregistrementsAvant);
    }

    @Test
    @DisplayName("CA8 - pseudo non bloqué et adresse plafonnée (40 s) : refus de 40 s, rien d'enregistré")
    void doit_refuser_avec_le_temps_de_l_adresse_quand_seule_l_adresse_est_plafonnee() {
        karim();
        adresseSature(40);
        int enregistrementsAvant = registreAdresses.enregistrements;

        assertThat(tempsRestantRefuse("Karim", MOT_DE_PASSE_KARIM)).isEqualTo(Duration.ofSeconds(40));

        assertThat(registreAdresses.enregistrements).isEqualTo(enregistrementsAvant);
    }

    @Test
    @DisplayName("CA8 - pseudo bloqué (900 s) et adresse non plafonnée : refus de 900 s, rien d'enregistré pour l'adresse")
    void doit_refuser_avec_le_temps_du_pseudo_quand_seul_le_pseudo_est_bloque() {
        karim();
        bloquerParPseudoDepuis(ADRESSE_B, "Karim");
        int enregistrementsAvant = registreAdresses.enregistrements;

        assertThat(tempsRestantRefuse("Karim", MOT_DE_PASSE_KARIM)).isEqualTo(Duration.ofSeconds(900));

        assertThat(registreAdresses.enregistrements).isEqualTo(enregistrementsAvant);
    }

    @Test
    @DisplayName("CA8 - A plafonnée n'affecte pas un autre pseudo non bloqué qui se connecte depuis B")
    void doit_ne_pas_affecter_une_autre_adresse() {
        Compte leo = leo();
        adresseSature(40);

        adresseCourante.definir(ADRESSE_B);

        assertThat(connecter.executer("Léo", MOT_DE_PASSE_LEO)).isEqualTo(leo);
    }

    @Test
    @DisplayName("CA8 - sans adresse de requête, 50 échecs sur 50 pseudos ne plafonnent jamais et rien n'est compté")
    void doit_ignorer_le_plafond_d_adresse_quand_l_adresse_est_inconnue() {
        adresseCourante.definirInconnue();

        echouerSurPseudosInconnus(50, "Inconnu");

        assertThat(registreAdresses.enregistrements).isZero();
        assertThatThrownBy(() -> connecter.executer("Encore", MOT_DE_PASSE_FAUX))
                .isInstanceOf(IdentifiantsInvalidesException.class);
    }

    /** Plafonne l'adresse A par 20 échecs datés d'il y a (60 - secondesRestantes) s, puis revient à T. */
    private void adresseSature(int secondesRestantes) {
        horloge.avancerA(T.minusSeconds(60 - secondesRestantes));
        echouerSurPseudosInconnus(20, "Sature");
        horloge.avancerA(T);
    }

    private void bloquerParPseudoDepuis(String adresse, String pseudo) {
        String adressePrecedente = adresseCourante.valeurCourante();
        adresseCourante.definir(adresse);
        for (int i = 0; i < 5; i++) {
            echouer(pseudo, MOT_DE_PASSE_FAUX);
        }
        adresseCourante.definir(adressePrecedente);
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

        String valeurCourante() {
            return adresse.orElseThrow();
        }

        @Override
        public Optional<String> courante() {
            return adresse;
        }
    }

    /** Registre par adresse : les règles viennent du domaine ; compte les enregistrements d'échec. */
    private static final class RegistreParAdresseEnMemoire implements RegistreEchecsParAdresse {
        private final Map<AdresseSuivie, EchecsParAdresse> entrees = new HashMap<>();
        int enregistrements;

        @Override
        public EchecsParAdresse constater(AdresseSuivie adresse, Instant maintenant) {
            return entrees.getOrDefault(adresse, EchecsParAdresse.aucun());
        }

        @Override
        public EchecsParAdresse enregistrerEchec(AdresseSuivie adresse, Instant maintenant,
                                                 PlafondEchecsParAdresse plafond) {
            enregistrements++;
            return entrees.compute(adresse, (cle, actuelle) ->
                    (actuelle == null ? EchecsParAdresse.aucun() : actuelle).apresEchec(maintenant, plafond));
        }
    }

    private static final class RegistreParPseudoEnMemoire implements RegistreTentativesConnexion {
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
        boolean saturer;

        @Override
        public String encoder(MotDePasse motDePasse) {
            return PREFIXE_EMPREINTE + motDePasse.valeur();
        }

        @Override
        public boolean verifier(String motDePasseEnClair, String empreinte) {
            if (saturer) {
                throw new HachageSatureException();
            }
            empreintesVerifiees.add(empreinte);
            return empreinte != null && empreinte.equals(PREFIXE_EMPREINTE + motDePasseEnClair);
        }
    }
}
