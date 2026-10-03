package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 1.6a : liste des comptes BENEVOLE (le rôle de l'appelant est contrôlé en exposition). */
class ListerBenevolesTest {

    private static final Instant NEUF_HEURES = Instant.parse("2026-10-02T09:00:00Z");
    private static final Instant DIX_HEURES = Instant.parse("2026-10-02T10:00:00Z");

    private final DepotComptesEnMemoire depot = new DepotComptesEnMemoire();
    private final ListerBenevoles listerBenevoles = new ListerBenevoles(depot);

    // CA7
    @Test
    @DisplayName("CA7 - liste uniquement les BENEVOLE, triés par date de création puis pseudo normalisé")
    void doit_lister_les_benevoles_par_date_de_creation_puis_pseudo() {
        depot.comptes.add(compte("Patron", Role.ADMIN_MASTER, NEUF_HEURES));
        depot.comptes.add(compte("Nadia", Role.ADMIN, NEUF_HEURES));
        depot.comptes.add(compte("Alice", Role.COUREUR, NEUF_HEURES));
        depot.comptes.add(compte("Zoé", Role.BENEVOLE, DIX_HEURES));
        depot.comptes.add(compte("Léo", Role.BENEVOLE, DIX_HEURES));
        depot.comptes.add(compte("Marc", Role.BENEVOLE, NEUF_HEURES));

        List<Compte> benevoles = listerBenevoles.executer();

        assertThat(benevoles).extracting(c -> c.pseudo().valeur()).containsExactly("Marc", "Léo", "Zoé");
        assertThat(benevoles).extracting(Compte::role).containsOnly(Role.BENEVOLE);
    }

    @Test
    @DisplayName("CA7 - à date égale, le tri se fait sur le pseudo normalisé (insensible à la casse)")
    void doit_trier_a_date_egale_sur_le_pseudo_normalise() {
        depot.comptes.add(compte("zoe", Role.BENEVOLE, DIX_HEURES));
        depot.comptes.add(compte("Bob", Role.BENEVOLE, DIX_HEURES));
        depot.comptes.add(compte("alice", Role.BENEVOLE, DIX_HEURES));

        assertThat(listerBenevoles.executer()).extracting(c -> c.pseudo().valeur())
                .containsExactly("alice", "Bob", "zoe");
    }

    @Test
    @DisplayName("CA7 - sans compte BENEVOLE, la liste est vide (admins, admin master et coureurs jamais listés)")
    void doit_renvoyer_une_liste_vide_sans_benevole() {
        depot.comptes.add(compte("Patron", Role.ADMIN_MASTER, NEUF_HEURES));
        depot.comptes.add(compte("Nadia", Role.ADMIN, NEUF_HEURES));
        depot.comptes.add(compte("Alice", Role.COUREUR, NEUF_HEURES));

        assertThat(listerBenevoles.executer()).isEmpty();
    }

    @Test
    @DisplayName("CA7 - dépôt entièrement vide : liste vide")
    void doit_renvoyer_une_liste_vide_sur_un_depot_vide() {
        assertThat(listerBenevoles.executer()).isEmpty();
    }

    private static Compte compte(String pseudo, Role role, Instant creeLe) {
        return Compte.reconstituer(UUID.randomUUID(), new Pseudo(pseudo), "empreinte", role, creeLe);
    }

    /** Dépôt en mémoire ; la liste par rôle est rendue dans l'ordre inverse d'insertion, sans tri. */
    private static final class DepotComptesEnMemoire implements DepotComptes {
        final List<Compte> comptes = new ArrayList<>();

        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            return comptes.stream().anyMatch(c -> c.pseudoNormalise().equals(pseudoNormalise));
        }

        @Override
        public void enregistrer(Compte compte) {
            comptes.add(compte);
        }

        @Override
        public List<Compte> listerParRole(Role role) {
            List<Compte> resultat = new ArrayList<>(comptes.stream().filter(c -> c.role() == role).toList());
            Collections.reverse(resultat);
            return resultat;
        }
    }
}
