package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.PseudoDejaUtiliseException;
import fr.backyard.tracker.comptes.domaine.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CreerCompteCoureurTest {

    private static final Instant MAINTENANT = Instant.parse("2026-10-02T10:00:00Z");
    private static final String MOT_DE_PASSE = "un-mot-de-passe-12";

    private final Clock horlogeFixe = Clock.fixed(MAINTENANT, ZoneOffset.UTC);
    private final DepotComptesEnMemoire depot = new DepotComptesEnMemoire();
    private final EncodeurMotDePasse encodeurFactice = motDePasse -> "empreinte-factice";
    private final CreerCompteCoureur creerCompteCoureur = new CreerCompteCoureur(depot, encodeurFactice, horlogeFixe);

    @Test
    @DisplayName("CA5 - crée un compte COUREUR avec pseudo normalisé, date du Clock et empreinte de l'encodeur")
    void doit_creer_un_compte_coureur_complet() {
        Compte compte = creerCompteCoureur.executer("Alice", MOT_DE_PASSE);

        assertThat(depot.enregistres).containsExactly(compte);
        assertThat(compte.id()).isNotNull();
        assertThat(compte.pseudo().valeur()).isEqualTo("Alice");
        assertThat(compte.pseudoNormalise()).isEqualTo("alice");
        assertThat(compte.role()).isEqualTo(Role.COUREUR);
        assertThat(compte.creeLe()).isEqualTo(MAINTENANT);
        assertThat(compte.empreinteMotDePasse()).isEqualTo("empreinte-factice").isNotEqualTo(MOT_DE_PASSE);
    }

    @Test
    @DisplayName("CA5 - le mot de passe en clair n'est jamais conservé dans le compte")
    void doit_ne_pas_conserver_le_mot_de_passe_en_clair() {
        Compte compte = creerCompteCoureur.executer("Alice", MOT_DE_PASSE);

        assertThat(compte.toString()).doesNotContain(MOT_DE_PASSE);
    }

    @Test
    @DisplayName("CA5 - le pseudo est conservé tel que saisi après trim")
    void doit_conserver_la_casse_et_retirer_les_espaces_du_pseudo() {
        Compte compte = creerCompteCoureur.executer("  Alice  ", MOT_DE_PASSE);

        assertThat(compte.pseudo().valeur()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("CA5 - l'encodeur reçoit le mot de passe saisi")
    void doit_encoder_le_mot_de_passe_saisi() {
        List<String> recus = new ArrayList<>();
        EncodeurMotDePasse espion = motDePasse -> {
            recus.add(motDePasse.valeur());
            return "hash";
        };

        new CreerCompteCoureur(depot, espion, horlogeFixe).executer("Alice", MOT_DE_PASSE);

        assertThat(recus).containsExactly(MOT_DE_PASSE);
    }

    @Test
    @DisplayName("CA6 - un pseudo en minuscules déjà pris sous une autre casse est refusé")
    void doit_refuser_un_pseudo_deja_utilise_sous_une_autre_casse_minuscule() {
        creerCompteCoureur.executer("Alice", MOT_DE_PASSE);

        assertThatThrownBy(() -> creerCompteCoureur.executer("alice", MOT_DE_PASSE))
                .isInstanceOf(PseudoDejaUtiliseException.class);
        assertThat(depot.enregistres).hasSize(1);
    }

    @Test
    @DisplayName("CA6 - un pseudo en majuscules déjà pris sous une autre casse est refusé")
    void doit_refuser_un_pseudo_deja_utilise_sous_une_autre_casse_majuscule() {
        creerCompteCoureur.executer("Alice", MOT_DE_PASSE);

        assertThatThrownBy(() -> creerCompteCoureur.executer("ALICE", MOT_DE_PASSE))
                .isInstanceOf(PseudoDejaUtiliseException.class);
        assertThat(depot.enregistres).hasSize(1);
    }

    @Test
    @DisplayName("CA6 - le pseudo affiché reste celui du premier compte")
    void doit_conserver_le_pseudo_du_premier_compte() {
        creerCompteCoureur.executer("Alice", MOT_DE_PASSE);

        assertThatThrownBy(() -> creerCompteCoureur.executer("ALICE", MOT_DE_PASSE))
                .isInstanceOf(PseudoDejaUtiliseException.class);
        assertThat(depot.enregistres.get(0).pseudo().valeur()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("CA7 - pseudo et mot de passe invalides : deux violations, ni encodeur ni dépôt sollicités")
    void doit_remonter_les_deux_violations_sans_solliciter_encodeur_ni_depot() {
        DepotComptes depotEspion = mock(DepotComptes.class);
        EncodeurMotDePasse encodeurEspion = mock(EncodeurMotDePasse.class);
        CreerCompteCoureur casUsage = new CreerCompteCoureur(depotEspion, encodeurEspion, horlogeFixe);

        assertThatThrownBy(() -> casUsage.executer("ab", "12345"))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e -> {
                    assertThat(e.violations()).hasSize(2);
                    assertThat(e.violations()).anySatisfy(v -> {
                        assertThat(v.champ()).isEqualTo("pseudo");
                        assertThat(v.code()).isEqualTo("PSEUDO_LONGUEUR");
                    });
                    assertThat(e.violations()).anySatisfy(v -> {
                        assertThat(v.champ()).isEqualTo("motDePasse");
                        assertThat(v.code()).isEqualTo("MOT_DE_PASSE_TROP_COURT");
                    });
                    assertThat(e.getMessage()).doesNotContain("12345");
                });
        verifyNoInteractions(depotEspion, encodeurEspion);
    }

    @Test
    @DisplayName("CA7 - un seul champ invalide : une seule violation")
    void doit_ne_remonter_que_la_violation_du_champ_invalide() {
        assertThatThrownBy(() -> creerCompteCoureur.executer("Alice", "court"))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e -> {
                    assertThat(e.violations()).hasSize(1);
                    assertThat(e.violations().get(0).champ()).isEqualTo("motDePasse");
                });
        assertThat(depot.enregistres).isEmpty();
    }

    @Test
    @DisplayName("CA7 - champs absents : PSEUDO_REQUIS et MOT_DE_PASSE_REQUIS")
    void doit_signaler_les_deux_champs_requis_quand_ils_sont_null() {
        assertThatThrownBy(() -> creerCompteCoureur.executer(null, null))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e ->
                        assertThat(e.violations()).extracting(v -> v.code())
                                .containsExactlyInAnyOrder("PSEUDO_REQUIS", "MOT_DE_PASSE_REQUIS"));
    }

    /** Dépôt en mémoire : l'unicité se juge sur le pseudo normalisé. */
    private static final class DepotComptesEnMemoire implements DepotComptes {
        final List<Compte> enregistres = new ArrayList<>();

        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            return enregistres.stream().anyMatch(c -> c.pseudoNormalise().equals(pseudoNormalise));
        }

        @Override
        public void enregistrer(Compte compte) {
            enregistres.add(compte);
        }
    }
}
