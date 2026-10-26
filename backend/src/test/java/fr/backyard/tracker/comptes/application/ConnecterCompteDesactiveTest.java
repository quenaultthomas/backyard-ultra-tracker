package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.ConnexionBloqueeException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.IdentifiantsInvalidesException;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.RegistreTentativesConnexion;
import fr.backyard.tracker.comptes.domaine.Role;
import fr.backyard.tracker.comptes.domaine.TentativesConnexion;
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

/** Incrément 8.1, CA5 : connexion d'un Compte désactivé, indiscernable d'un échec ordinaire (RG1, RG9). */
class ConnecterCompteDesactiveTest {

    private static final Instant T = Instant.parse("2026-10-26T10:00:00Z");
    private static final String MOT_DE_PASSE = "mot-de-passe-benevole-1";
    private static final String PREFIXE_EMPREINTE = "empreinte:";
    private static final String EMPREINTE_LEO = PREFIXE_EMPREINTE + MOT_DE_PASSE;
    private static final UUID ID_LEO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final String CLE_LEO = "léo";

    private final Clock horloge = Clock.fixed(T, ZoneOffset.UTC);
    private final DepotComptesEnMemoire depot = new DepotComptesEnMemoire();
    private final EncodeurEspion encodeur = new EncodeurEspion();
    private final RegistreEnMemoire registre = new RegistreEnMemoire();
    private final Connecter connecter = new Connecter(depot, encodeur, registre, PolitiqueBlocage.parDefaut(), horloge);

    private Compte leo(boolean actif) {
        Compte leo = Compte.reconstituer(ID_LEO, new Pseudo("Léo"), EMPREINTE_LEO, Role.BENEVOLE, T, actif);
        depot.comptes.removeIf(c -> c.id().equals(ID_LEO));
        depot.comptes.add(leo);
        return leo;
    }

    @Test
    @DisplayName("CA5 - un Compte inactif avec le bon mot de passe : IdentifiantsInvalidesException")
    void doit_refuser_la_connexion_d_un_compte_desactive_avec_le_bon_mot_de_passe() {
        leo(false);

        assertThatThrownBy(() -> connecter.executer("Léo", MOT_DE_PASSE))
                .isInstanceOf(IdentifiantsInvalidesException.class);
    }

    @Test
    @DisplayName("CA5 - l'encodeur est appelé une seule fois, avec l'empreinte factice et jamais celle de Léo")
    void doit_verifier_l_empreinte_factice_et_jamais_celle_du_compte_desactive() {
        leo(false);
        int verificationsAvant = encodeur.empreintesVerifiees.size();

        assertThatThrownBy(() -> connecter.executer("Léo", MOT_DE_PASSE))
                .isInstanceOf(IdentifiantsInvalidesException.class);

        List<String> verifiees = encodeur.empreintesVerifiees.subList(verificationsAvant, encodeur.empreintesVerifiees.size());
        assertThat(verifiees).hasSize(1).doesNotContain(EMPREINTE_LEO);
    }

    @Test
    @DisplayName("CA5 - l'échec d'un Compte inactif a le même message qu'un pseudo inconnu")
    void doit_avoir_le_meme_message_qu_un_pseudo_inconnu() {
        leo(false);

        String pourDesactive = messageDEchec("Léo", MOT_DE_PASSE);
        String pourInconnu = messageDEchec("Inconnu", MOT_DE_PASSE);

        assertThat(pourDesactive).isEqualTo(pourInconnu);
    }

    @Test
    @DisplayName("CA5 - l'échec est compté une fois pour la clé de Léo")
    void doit_compter_un_echec_pour_la_cle_du_compte_desactive() {
        leo(false);

        assertThatThrownBy(() -> connecter.executer("Léo", MOT_DE_PASSE))
                .isInstanceOf(IdentifiantsInvalidesException.class);

        assertThat(registre.constater(CLE_LEO, T).echecs()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 - pendant un blocage, un Compte inactif reçoit ConnexionBloqueeException sans vérification d'empreinte")
    void doit_bloquer_un_compte_desactive_sans_verification() {
        leo(false);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> connecter.executer("Léo", MOT_DE_PASSE))
                    .isInstanceOf(IdentifiantsInvalidesException.class);
        }
        int verificationsAvant = encodeur.empreintesVerifiees.size();

        assertThatThrownBy(() -> connecter.executer("Léo", MOT_DE_PASSE))
                .isInstanceOf(ConnexionBloqueeException.class);

        assertThat(encodeur.empreintesVerifiees).hasSize(verificationsAvant);
    }

    @Test
    @DisplayName("CA5 - après réactivation, le bon mot de passe connecte et le compteur est remis à zéro")
    void doit_connecter_apres_reactivation_et_remettre_le_compteur_a_zero() {
        Compte desactive = leo(false);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> connecter.executer("Léo", MOT_DE_PASSE))
                    .isInstanceOf(IdentifiantsInvalidesException.class);
        }
        depot.comptes.removeIf(c -> c.id().equals(ID_LEO));
        depot.comptes.add(desactive.reactiver());

        Compte connecte = connecter.executer("Léo", MOT_DE_PASSE);

        assertThat(connecte.id()).isEqualTo(ID_LEO);
        assertThat(connecte.actif()).isTrue();
        assertThat(registre.constater(CLE_LEO, T).echecs()).isZero();
    }

    @Test
    @DisplayName("CA5 - un Compte actif se connecte normalement avec sa propre empreinte")
    void doit_connecter_un_compte_actif() {
        Compte leo = leo(true);

        assertThat(connecter.executer("Léo", MOT_DE_PASSE)).isEqualTo(leo);
        assertThat(encodeur.empreintesVerifiees).contains(EMPREINTE_LEO);
    }

    private String messageDEchec(String pseudo, String motDePasse) {
        try {
            connecter.executer(pseudo, motDePasse);
        } catch (IdentifiantsInvalidesException e) {
            return e.getMessage();
        }
        throw new AssertionError("Une IdentifiantsInvalidesException était attendue");
    }

    private static final class DepotComptesEnMemoire implements DepotComptes {
        final List<Compte> comptes = new ArrayList<>();

        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            return trouverParPseudoNormalise(pseudoNormalise).isPresent();
        }

        @Override
        public Optional<Compte> trouverParPseudoNormalise(String pseudoNormalise) {
            return comptes.stream().filter(c -> c.pseudoNormalise().equals(pseudoNormalise)).findFirst();
        }

        @Override
        public void enregistrer(Compte compte) {
            comptes.add(compte);
        }
    }

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
