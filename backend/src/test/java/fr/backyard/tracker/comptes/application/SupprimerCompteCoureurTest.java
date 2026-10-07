package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.SupprimerCompteCoureur.Commande;
import fr.backyard.tracker.comptes.domaine.AnnulationInscriptions;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteIntrouvableOuInutilisableException;
import fr.backyard.tracker.comptes.domaine.ConnexionBloqueeException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.comptes.domaine.MotDePasseActuelIncorrectException;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.RegistreTentativesConnexion;
import fr.backyard.tracker.comptes.domaine.Role;
import fr.backyard.tracker.comptes.domaine.SuppressionCompteInterditeException;
import fr.backyard.tracker.comptes.domaine.TentativesConnexion;
import fr.backyard.tracker.comptes.domaine.ViolationValidation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Suppression (anonymisation) de son propre Compte coureur (incrément 3.6, CA2 ; RG1, RG2, RG3, RG6, RG8, RG10).
 * Les doubles inscrivent leurs appels dans un journal commun pour vérifier l'ordre des opérations.
 */
class SupprimerCompteCoureurTest {

    private static final Instant T = Instant.parse("2026-10-07T10:00:00Z");
    private static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    private static final String FAUX = "secret-de-test-123";
    private static final String PREFIXE_EMPREINTE = "empreinte:";
    private static final String ANCIENNE_CLE = "alice";

    private static final String VERIFICATION = "verification-mot-de-passe";
    private static final String ANNULATION = "annulation-inscriptions";
    private static final String ENREGISTREMENT = "enregistrement-compte";
    private static final String REMISE_A_ZERO = "remise-a-zero-compteur:" + ANCIENNE_CLE;
    private static final String FERMETURE = "fermeture-toutes-sessions";

    private final List<String> journal = new ArrayList<>();
    private final Clock horloge = Clock.fixed(T, ZoneOffset.UTC);
    private final DepotComptesEnMemoire depot = new DepotComptesEnMemoire(journal);
    private final EncodeurEspion encodeur = new EncodeurEspion(journal);
    private final RegistreEnMemoire registre = new RegistreEnMemoire(journal);
    private final AnnulationEspion annulation = new AnnulationEspion(journal);
    private final InvalidationEspion invalidation = new InvalidationEspion(journal);
    private final SupprimerCompteCoureur supprimer = new SupprimerCompteCoureur(
            depot, encodeur, registre, PolitiqueBlocage.parDefaut(), annulation, invalidation, horloge);

    // --- fabriques de test --------------------------------------------------------------------------------

    private Compte alice() {
        return compte("Alice", Role.COUREUR, PREFIXE_EMPREINTE + MOT_DE_PASSE);
    }

    private Compte compte(String pseudo, Role role, String empreinte) {
        Compte compte = Compte.reconstituer(UUID.randomUUID(), new Pseudo(pseudo), empreinte, role, T.minusSeconds(3600));
        depot.comptes.put(compte.id(), compte);
        return compte;
    }

    private void supprimerAvec(Compte compte, String motDePasse) {
        supprimer.executer(new Commande(compte.id(), motDePasse));
    }

    private int echecs() {
        return registre.constater(ANCIENNE_CLE, T).echecs();
    }

    private void echecsDejaEnregistres(int nombre) {
        for (int i = 0; i < nombre; i++) {
            registre.enregistrerEchec(ANCIENNE_CLE, T, PolitiqueBlocage.parDefaut());
        }
        journal.clear();
    }

    private Compte enDepot(Compte compte) {
        return depot.comptes.get(compte.id());
    }

    private void assertAucunEffet(Compte compte) {
        assertThat(annulation.appels).isEmpty();
        assertThat(depot.misesAJour).isZero();
        assertThat(invalidation.toutesLesSessions).isEmpty();
        assertThat(enDepot(compte).peutSeConnecter()).isTrue();
    }

    // --- CA2 : succès --------------------------------------------------------------------------------------

    @Test
    @DisplayName("CA2 - avec le bon mot de passe : contrôle, annulation, enregistrement, remise à zéro, fermeture en dernier")
    void doit_enchainer_les_operations_dans_l_ordre_et_fermer_les_sessions_en_dernier() {
        Compte alice = alice();
        annulation.nombreAnnule = 2;

        supprimerAvec(alice, MOT_DE_PASSE);

        assertThat(journal).containsExactly(VERIFICATION, ANNULATION, ENREGISTREMENT, REMISE_A_ZERO, FERMETURE);
    }

    @Test
    @DisplayName("CA2 - l'annulation des inscriptions est demandée pour l'identifiant du Compte")
    void doit_demander_l_annulation_des_inscriptions_du_compte() {
        Compte alice = alice();

        supprimerAvec(alice, MOT_DE_PASSE);

        assertThat(annulation.appels).containsExactly(alice.id());
    }

    @Test
    @DisplayName("CA2 - le Compte enregistré est l'anonymisé : même id, pseudo « Coureur anonyme », sans empreinte")
    void doit_enregistrer_le_compte_anonymise() {
        Compte alice = alice();

        supprimerAvec(alice, MOT_DE_PASSE);

        Compte apres = enDepot(alice);
        assertThat(depot.misesAJour).isEqualTo(1);
        assertThat(apres.id()).isEqualTo(alice.id());
        assertThat(apres.pseudo().valeur()).isEqualTo("Coureur anonyme");
        assertThat(apres.empreinteMotDePasse()).isNull();
        assertThat(apres.peutSeConnecter()).isFalse();
        assertThat(apres.pseudoNormalise()).isEqualTo(alice.anonymiser().pseudoNormalise());
    }

    @Test
    @DisplayName("CA2 - le compteur de l'ancienne clé est remis à zéro et le pseudo libéré n'hérite d'aucun échec")
    void doit_remettre_a_zero_le_compteur_de_l_ancienne_cle() {
        Compte alice = alice();
        echecsDejaEnregistres(4);

        supprimerAvec(alice, MOT_DE_PASSE);

        assertThat(echecs()).isZero();
    }

    @Test
    @DisplayName("CA2 - toutes les sessions du Compte sont fermées, pas seulement les autres")
    void doit_fermer_toutes_les_sessions_du_compte() {
        Compte alice = alice();

        supprimerAvec(alice, MOT_DE_PASSE);

        assertThat(invalidation.toutesLesSessions).containsExactly(alice.id());
        assertThat(invalidation.autresSessions).isEmpty();
    }

    @Test
    @DisplayName("CA2 - un coureur sans inscription à annuler est tout de même anonymisé")
    void doit_anonymiser_un_coureur_sans_inscription_a_annuler() {
        Compte alice = alice();
        annulation.nombreAnnule = 0;

        supprimerAvec(alice, MOT_DE_PASSE);

        assertThat(enDepot(alice).peutSeConnecter()).isFalse();
        assertThat(invalidation.toutesLesSessions).containsExactly(alice.id());
    }

    @Test
    @DisplayName("CA2 - un mot de passe de 128 caractères est vérifié (borne incluse), donc accepté s'il est juste")
    void doit_verifier_un_mot_de_passe_de_128_caracteres() {
        String long128 = "a".repeat(128);
        Compte alice = compte("Alice", Role.COUREUR, PREFIXE_EMPREINTE + long128);

        supprimerAvec(alice, long128);

        assertThat(enDepot(alice).peutSeConnecter()).isFalse();
    }

    // --- CA2 : mot de passe faux ---------------------------------------------------------------------------

    @Test
    @DisplayName("CA2 - un mot de passe faux lève « incorrect », sans appel au port, Compte inchangé, un échec compté")
    void doit_refuser_un_mot_de_passe_faux_sans_rien_modifier_et_compter_un_echec() {
        Compte alice = alice();

        assertThatThrownBy(() -> supprimerAvec(alice, FAUX)).isInstanceOf(MotDePasseActuelIncorrectException.class);

        assertAucunEffet(alice);
        assertThat(enDepot(alice)).isSameAs(alice);
        assertThat(echecs()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA2 - un mot de passe de 129 caractères lève « incorrect » sans calcul d'empreinte ni comptage")
    void doit_refuser_un_mot_de_passe_de_129_caracteres_sans_calcul_ni_comptage() {
        Compte alice = alice();

        assertThatThrownBy(() -> supprimerAvec(alice, "a".repeat(129)))
                .isInstanceOf(MotDePasseActuelIncorrectException.class);

        assertAucunEffet(alice);
        assertThat(encodeur.verifications).isZero();
        assertThat(echecs()).isZero();
    }

    @Test
    @DisplayName("CA2 - l'échec est compté dans le registre partagé : 5 échecs bloquent ensuite même le bon mot de passe")
    void doit_bloquer_apres_cinq_echecs_de_suppression() {
        Compte alice = alice();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> supprimerAvec(alice, FAUX)).isInstanceOf(MotDePasseActuelIncorrectException.class);
        }

        assertThatThrownBy(() -> supprimerAvec(alice, MOT_DE_PASSE)).isInstanceOf(ConnexionBloqueeException.class);

        assertAucunEffet(alice);
    }

    // --- CA2 : mot de passe absent -------------------------------------------------------------------------

    @Test
    @DisplayName("CA2 - un mot de passe absent ou vide lève DonneesCompteInvalidesException sur motDePasseActuel")
    void doit_signaler_un_mot_de_passe_absent_ou_vide_sur_le_champ_mot_de_passe_actuel() {
        Compte alice = alice();

        for (String saisie : new String[] {null, ""}) {
            assertThatThrownBy(() -> supprimerAvec(alice, saisie))
                    .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e -> {
                        assertThat(e.violations()).extracting(ViolationValidation::champ)
                                .containsExactly("motDePasseActuel");
                        assertThat(e.violations()).extracting(ViolationValidation::code)
                                .containsExactly("MOT_DE_PASSE_ACTUEL_REQUIS");
                    });
        }
        assertAucunEffet(alice);
        assertThat(encodeur.verifications).isZero();
        assertThat(echecs()).isZero();
    }

    // --- CA2 : compte introuvable ou déjà anonymisé --------------------------------------------------------

    @Test
    @DisplayName("CA2 - un Compte déjà anonymisé lève « introuvable ou inutilisable » sans aucun effet")
    void doit_refuser_un_compte_deja_anonymise_sans_effet() {
        Compte anonyme = alice().anonymiser();
        depot.comptes.put(anonyme.id(), anonyme);

        assertThatThrownBy(() -> supprimerAvec(anonyme, MOT_DE_PASSE))
                .isInstanceOf(CompteIntrouvableOuInutilisableException.class);

        assertThat(annulation.appels).isEmpty();
        assertThat(depot.misesAJour).isZero();
        assertThat(invalidation.toutesLesSessions).isEmpty();
        assertThat(registre.effacements).isEmpty();
    }

    @Test
    @DisplayName("CA2 - un Compte introuvable lève « introuvable ou inutilisable », avec le même coût de calcul d'empreinte")
    void doit_refuser_un_compte_introuvable_en_sollicitant_l_encodeur() {
        assertThatThrownBy(() -> supprimer.executer(new Commande(UUID.randomUUID(), MOT_DE_PASSE)))
                .isInstanceOf(CompteIntrouvableOuInutilisableException.class);

        assertThat(encodeur.verifications).isEqualTo(1);
        assertThat(annulation.appels).isEmpty();
        assertThat(depot.misesAJour).isZero();
        assertThat(invalidation.toutesLesSessions).isEmpty();
    }

    // --- CA2 : blocage -------------------------------------------------------------------------------------

    @Test
    @DisplayName("CA2 - un pseudo bloqué lève ConnexionBloqueeException sans vérification, même avec le bon mot de passe")
    void doit_refuser_un_pseudo_bloque_sans_verifier_le_mot_de_passe() {
        Compte alice = alice();
        echecsDejaEnregistres(5);

        assertThatThrownBy(() -> supprimerAvec(alice, MOT_DE_PASSE)).isInstanceOf(ConnexionBloqueeException.class);

        assertThat(encodeur.verifications).isZero();
        assertAucunEffet(alice);
    }

    // --- CA2 : atomicité -----------------------------------------------------------------------------------

    @Test
    @DisplayName("CA2 - une erreur du port d'annulation ne ferme aucune session ni n'enregistre le Compte")
    void doit_ne_fermer_aucune_session_quand_le_port_echoue() {
        Compte alice = alice();
        annulation.panne = new IllegalStateException("panne du port");

        assertThatThrownBy(() -> supprimerAvec(alice, MOT_DE_PASSE)).isSameAs(annulation.panne);

        assertThat(invalidation.toutesLesSessions).isEmpty();
        assertThat(depot.misesAJour).isZero();
        assertThat(enDepot(alice).peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA2 - une erreur d'enregistrement du Compte ne ferme aucune session")
    void doit_ne_fermer_aucune_session_quand_l_enregistrement_du_compte_echoue() {
        Compte alice = alice();
        depot.panne = new IllegalStateException("panne du dépôt");

        assertThatThrownBy(() -> supprimerAvec(alice, MOT_DE_PASSE)).isSameAs(depot.panne);

        assertThat(invalidation.toutesLesSessions).isEmpty();
        assertThat(enDepot(alice).peutSeConnecter()).isTrue();
    }

    // --- CA2 : rôles (défense en profondeur) ---------------------------------------------------------------

    @Test
    @DisplayName("CA2 - un Compte qui n'est pas coureur lève SuppressionCompteInterditeException : ni enregistrement ni fermeture")
    void doit_refuser_la_suppression_d_un_compte_qui_n_est_pas_coureur() {
        Compte admin = compte("Nadia", Role.ADMIN, PREFIXE_EMPREINTE + MOT_DE_PASSE);

        assertThatThrownBy(() -> supprimerAvec(admin, MOT_DE_PASSE))
                .isInstanceOf(SuppressionCompteInterditeException.class);

        assertThat(depot.misesAJour).isZero();
        assertThat(invalidation.toutesLesSessions).isEmpty();
        assertThat(enDepot(admin).peutSeConnecter()).isTrue();
    }

    // --- CA2 : confidentialité -----------------------------------------------------------------------------

    @Test
    @DisplayName("CA2 - le toString de la commande masque le mot de passe")
    void doit_masquer_le_mot_de_passe_dans_le_to_string_de_la_commande() {
        String texte = new Commande(UUID.randomUUID(), FAUX).toString();

        assertThat(texte).doesNotContain(FAUX).contains("masqué");
    }

    @Test
    @DisplayName("CA2 - aucune exception ne contient le mot de passe saisi, ni dans son message ni dans son toString")
    void doit_ne_jamais_exposer_le_mot_de_passe_dans_les_exceptions() {
        Compte alice = alice();
        Throwable incorrect = echecDe(() -> supprimerAvec(alice, FAUX));
        Throwable absent = echecDe(() -> supprimerAvec(alice, null));
        Throwable introuvable = echecDe(() -> supprimer.executer(new Commande(UUID.randomUUID(), FAUX)));
        echecsDejaEnregistres(5);
        Throwable bloque = echecDe(() -> supprimerAvec(alice, FAUX));

        for (Throwable echec : List.of(incorrect, absent, introuvable, bloque)) {
            assertThat(List.of(String.valueOf(echec.getMessage()), echec.toString()))
                    .allSatisfy(texte -> assertThat(texte).doesNotContain(FAUX).doesNotContain(MOT_DE_PASSE));
        }
    }

    private static Throwable echecDe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("Une exception métier était attendue");
    }

    // --- doubles de test -----------------------------------------------------------------------------------

    private static final class DepotComptesEnMemoire implements DepotComptes {
        final Map<UUID, Compte> comptes = new HashMap<>();
        final List<String> journal;
        int misesAJour;
        RuntimeException panne;

        DepotComptesEnMemoire(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            return trouverParPseudoNormalise(pseudoNormalise).isPresent();
        }

        @Override
        public Optional<Compte> trouverParPseudoNormalise(String pseudoNormalise) {
            return comptes.values().stream().filter(c -> c.pseudoNormalise().equals(pseudoNormalise)).findFirst();
        }

        @Override
        public Optional<Compte> trouverParId(UUID id) {
            return Optional.ofNullable(comptes.get(id));
        }

        @Override
        public void enregistrer(Compte compte) {
            comptes.put(compte.id(), compte);
        }

        @Override
        public void mettreAJour(Compte compte) {
            if (panne != null) {
                throw panne;
            }
            journal.add(ENREGISTREMENT);
            misesAJour++;
            comptes.put(compte.id(), compte);
        }
    }

    private static final class EncodeurEspion implements EncodeurMotDePasse {
        final List<String> journal;
        int verifications;

        EncodeurEspion(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public String encoder(MotDePasse motDePasse) {
            return PREFIXE_EMPREINTE + motDePasse.valeur();
        }

        @Override
        public boolean verifier(String motDePasseEnClair, String empreinte) {
            verifications++;
            journal.add(VERIFICATION);
            return empreinte != null && empreinte.equals(PREFIXE_EMPREINTE + motDePasseEnClair);
        }
    }

    private static final class AnnulationEspion implements AnnulationInscriptions {
        final List<String> journal;
        final List<UUID> appels = new ArrayList<>();
        int nombreAnnule;
        RuntimeException panne;

        AnnulationEspion(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public int annulerPour(UUID compteId) {
            journal.add(ANNULATION);
            appels.add(compteId);
            if (panne != null) {
                throw panne;
            }
            return nombreAnnule;
        }
    }

    private static final class InvalidationEspion implements InvalidationAutresSessions {
        final List<String> journal;
        final List<UUID> toutesLesSessions = new ArrayList<>();
        final List<UUID> autresSessions = new ArrayList<>();

        InvalidationEspion(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public void invaliderAutresSessions(UUID compteId) {
            autresSessions.add(compteId);
        }

        @Override
        public void invaliderToutesLesSessions(UUID compteId) {
            journal.add(FERMETURE);
            toutesLesSessions.add(compteId);
        }
    }

    private static final class RegistreEnMemoire implements RegistreTentativesConnexion {
        private final Map<String, TentativesConnexion> entrees = new HashMap<>();
        final List<String> journal;
        final List<String> effacements = new ArrayList<>();

        RegistreEnMemoire(List<String> journal) {
            this.journal = journal;
        }

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
            journal.add("remise-a-zero-compteur:" + cle);
            effacements.add(cle);
            entrees.remove(cle);
        }
    }
}
