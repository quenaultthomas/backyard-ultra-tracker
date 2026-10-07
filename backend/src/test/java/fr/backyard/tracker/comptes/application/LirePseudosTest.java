package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 3.5 : cas d'usage LirePseudos (CA4 ; RG6, RG7). */
class LirePseudosTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-02T09:00:00Z");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-00000000dead");

    private final DepotComptesEnMemoire depot = new DepotComptesEnMemoire();
    private final LirePseudos lirePseudos = new LirePseudos(depot);

    @Test
    @DisplayName("CA4 - renvoie le pseudo tel que saisi des comptes trouvés et omet l'identifiant inconnu")
    void doit_renvoyer_les_pseudos_saisis_et_omettre_le_compte_inconnu() {
        Compte alice = coureur("Alice");
        Compte bruno = coureur("BrUnO");
        depot.comptes.add(alice);
        depot.comptes.add(bruno);

        Map<UUID, String> pseudos = lirePseudos.executer(List.of(alice.id(), bruno.id(), ID_INCONNU));

        assertThat(pseudos).containsOnlyKeys(alice.id(), bruno.id());
        assertThat(pseudos).containsEntry(alice.id(), "Alice").containsEntry(bruno.id(), "BrUnO");
    }

    @Test
    @DisplayName("CA4 - une collection vide donne une Map vide sans accès au dépôt")
    void doit_renvoyer_une_map_vide_sans_acces_au_depot_pour_une_collection_vide() {
        depot.comptes.add(coureur("Alice"));

        assertThat(lirePseudos.executer(List.of())).isEmpty();
        assertThat(depot.nombreDeRecherches).isZero();
    }

    @Test
    @DisplayName("CA4 - tous les identifiants sont résolus en une seule recherche")
    void doit_resoudre_tous_les_identifiants_en_une_seule_recherche() {
        Compte alice = coureur("Alice");
        Compte bruno = coureur("Bruno");
        depot.comptes.add(alice);
        depot.comptes.add(bruno);

        lirePseudos.executer(List.of(alice.id(), bruno.id(), ID_INCONNU));

        assertThat(depot.nombreDeRecherches).isEqualTo(1);
    }

    private static Compte coureur(String pseudo) {
        return Compte.reconstituer(UUID.randomUUID(), new Pseudo(pseudo), "empreinte", Role.COUREUR, CREE_LE);
    }

    private static final class DepotComptesEnMemoire implements DepotComptes {
        final List<Compte> comptes = new ArrayList<>();
        int nombreDeRecherches;

        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            return false;
        }

        @Override
        public void enregistrer(Compte compte) {
            comptes.add(compte);
        }

        @Override
        public List<Compte> trouverParIds(Collection<UUID> ids) {
            nombreDeRecherches++;
            return comptes.stream().filter(c -> ids.contains(c.id())).toList();
        }
    }
}
