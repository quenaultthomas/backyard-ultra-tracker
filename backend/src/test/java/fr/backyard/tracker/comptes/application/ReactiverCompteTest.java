package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DepotComptesVerrouilleDeTest.CHARGEMENT_COMPTE;
import static fr.backyard.tracker.comptes.application.DepotComptesVerrouilleDeTest.LECTURE_SIMPLE;
import static fr.backyard.tracker.comptes.application.DepotComptesVerrouilleDeTest.MISE_A_JOUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 8.1, CA4 : réactivation d'un admin ou d'un bénévole (RG7, RG8, RG11).
 * Signatures attendues : {@code new ReactiverAdmin(DepotComptes)} et {@code new ReactiverBenevole(DepotComptes)},
 * chacun avec {@code executer(UUID appelantId, UUID cibleId)}. Aucun port de désaffectation ni de sessions : la
 * réactivation ne réaffecte rien et ne ferme aucune session.
 */
class ReactiverCompteTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final UUID ID_PATRON = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
    private static final UUID ID_PAUL = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID ID_LEO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID ID_ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

    private final List<String> journal = new ArrayList<>();
    private final DepotComptesVerrouilleDeTest depot = new DepotComptesVerrouilleDeTest(journal);
    private final ReactiverAdmin reactiverAdmin = new ReactiverAdmin(depot);
    private final ReactiverBenevole reactiverBenevole = new ReactiverBenevole(depot);

    private Compte compte(UUID id, String pseudo, Role role, boolean actif) {
        return depot.ajouter(Compte.reconstituer(id, new Pseudo(pseudo), "empreinte-" + pseudo, role, CREE_LE, actif));
    }

    @Test
    @DisplayName("CA4 - réactiver un admin inactif : chargé sous verrou, enregistré actif, empreinte et identité inchangées")
    void doit_reactiver_un_admin_inactif() {
        Compte avant = compte(ID_PAUL, "Paul", Role.ADMIN, false);

        reactiverAdmin.executer(ID_PATRON, ID_PAUL);

        Compte apres = depot.enDepot(ID_PAUL);
        assertThat(journal).containsExactly(CHARGEMENT_COMPTE, MISE_A_JOUR);
        assertThat(apres.actif()).isTrue();
        assertThat(apres.empreinteMotDePasse()).isEqualTo(avant.empreinteMotDePasse());
        assertThat(apres.pseudo()).isEqualTo(avant.pseudo());
        assertThat(apres.role()).isEqualTo(Role.ADMIN);
        assertThat(apres.creeLe()).isEqualTo(CREE_LE);
        assertThat(apres.peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA4 - réactiver un bénévole inactif : chargé sous verrou, enregistré actif")
    void doit_reactiver_un_benevole_inactif() {
        compte(ID_LEO, "Léo", Role.BENEVOLE, false);

        reactiverBenevole.executer(ID_PATRON, ID_LEO);

        assertThat(journal).containsExactly(CHARGEMENT_COMPTE, MISE_A_JOUR);
        assertThat(depot.enDepot(ID_LEO).actif()).isTrue();
        assertThat(depot.misesAJour).isEqualTo(1);
    }

    @Test
    @DisplayName("CA4 - réactiver un admin déjà actif : aucune écriture")
    void doit_ne_rien_ecrire_pour_un_admin_deja_actif() {
        compte(ID_PAUL, "Paul", Role.ADMIN, true);

        reactiverAdmin.executer(ID_PATRON, ID_PAUL);

        assertThat(depot.misesAJour).isZero();
        assertThat(depot.enDepot(ID_PAUL).actif()).isTrue();
    }

    @Test
    @DisplayName("CA4 - réactiver un bénévole déjà actif : aucune écriture")
    void doit_ne_rien_ecrire_pour_un_benevole_deja_actif() {
        compte(ID_LEO, "Léo", Role.BENEVOLE, true);

        reactiverBenevole.executer(ID_PATRON, ID_LEO);

        assertThat(depot.misesAJour).isZero();
    }

    @Test
    @DisplayName("CA4 - réactiver l'admin master (déjà actif) réussit sans effet")
    void doit_accepter_la_reactivation_de_l_admin_master_sans_effet() {
        compte(ID_PATRON, "Patron", Role.ADMIN_MASTER, true);

        reactiverAdmin.executer(ID_PATRON, ID_PATRON);

        assertThat(depot.misesAJour).isZero();
        assertThat(depot.enDepot(ID_PATRON).actif()).isTrue();
    }

    @Test
    @DisplayName("CA4 - réactiver : jamais de lecture simple, toujours le chargement sous verrou")
    void doit_utiliser_le_chargement_sous_verrou() {
        compte(ID_LEO, "Léo", Role.BENEVOLE, false);

        reactiverBenevole.executer(ID_PATRON, ID_LEO);

        assertThat(journal).doesNotContain(LECTURE_SIMPLE);
    }

    @Test
    @DisplayName("CA4 - un bénévole sur la route admins est introuvable, sans effet")
    void doit_refuser_un_benevole_sur_reactiver_admin() {
        compte(ID_LEO, "Léo", Role.BENEVOLE, false);

        assertThatThrownBy(() -> reactiverAdmin.executer(ID_PATRON, ID_LEO))
                .isInstanceOf(CompteNonGerableException.class);

        assertThat(depot.misesAJour).isZero();
        assertThat(depot.enDepot(ID_LEO).actif()).isFalse();
    }

    @Test
    @DisplayName("CA4 - un admin sur la route bénévoles est introuvable, sans effet")
    void doit_refuser_un_admin_sur_reactiver_benevole() {
        compte(ID_PAUL, "Paul", Role.ADMIN, false);

        assertThatThrownBy(() -> reactiverBenevole.executer(ID_PATRON, ID_PAUL))
                .isInstanceOf(CompteNonGerableException.class);

        assertThat(depot.misesAJour).isZero();
        assertThat(depot.enDepot(ID_PAUL).actif()).isFalse();
    }

    @Test
    @DisplayName("CA4 - l'admin master sur la route bénévoles est introuvable")
    void doit_refuser_l_admin_master_sur_reactiver_benevole() {
        compte(ID_PATRON, "Patron", Role.ADMIN_MASTER, true);

        assertThatThrownBy(() -> reactiverBenevole.executer(ID_PATRON, ID_PATRON))
                .isInstanceOf(CompteNonGerableException.class);
    }

    @Test
    @DisplayName("CA4 - un coureur est introuvable sur les deux routes, sans effet")
    void doit_refuser_un_coureur_sur_les_deux_routes() {
        compte(ID_ALICE, "Alice", Role.COUREUR, false);

        assertThatThrownBy(() -> reactiverAdmin.executer(ID_PATRON, ID_ALICE))
                .isInstanceOf(CompteNonGerableException.class);
        assertThatThrownBy(() -> reactiverBenevole.executer(ID_PATRON, ID_ALICE))
                .isInstanceOf(CompteNonGerableException.class);

        assertThat(depot.misesAJour).isZero();
        assertThat(depot.enDepot(ID_ALICE).actif()).isFalse();
    }

    @Test
    @DisplayName("CA4 - un identifiant inconnu est introuvable sur les deux routes")
    void doit_refuser_un_identifiant_inconnu_sur_les_deux_routes() {
        assertThatThrownBy(() -> reactiverAdmin.executer(ID_PATRON, ID_INCONNU))
                .isInstanceOf(CompteNonGerableException.class);
        assertThatThrownBy(() -> reactiverBenevole.executer(ID_PATRON, ID_INCONNU))
                .isInstanceOf(CompteNonGerableException.class);

        assertThat(depot.misesAJour).isZero();
    }
}
