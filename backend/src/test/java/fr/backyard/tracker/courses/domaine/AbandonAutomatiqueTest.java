package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.8 : règle de contrôle de fin de Boucle du domaine (CA2 ; RG2 à RG7).
 *
 * Signatures supposées (spec §4 « Port interne », nom « par exemple AbandonAutomatique ») :
 * {@code static List<AbandonAutomatique.Designation> AbandonAutomatique.designer(Collection<Inscription> inscriptions,
 * BiPredicate<Inscription, Integer> aUnPassageSur, int bouclesTerminees)} ; fonction pure (ne modifie aucune
 * Inscription) ; le résultat contient un élément par Inscription à mettre en abandon, avec
 * {@code Designation(Inscription inscription, MotifAbandon motif, int boucleAbandon)}. Le motif est toujours HORS_DELAI.
 * Le contrôle s'applique Boucle après Boucle (k de 1 à N) et s'arrête au premier k où aucune Inscription EN_COURSE
 * n'a de Passage (RG4, RG5).
 */
class AbandonAutomatiqueTest {

    private static final UUID COURSE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final Map<UUID, Set<Integer>> passagesParInscription = new HashMap<>();
    private final List<Inscription> inscriptions = new ArrayList<>();

    private Inscription inscrit(int dossard, StatutInscription statut, Integer... boucles) {
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), COURSE, UUID.randomUUID(), dossard,
                JetonQr.generer(), statut);
        inscriptions.add(inscription);
        passagesParInscription.put(inscription.id(), Set.of(boucles));
        return inscription;
    }

    private Inscription enCourse(int dossard, Integer... boucles) {
        return inscrit(dossard, StatutInscription.EN_COURSE, boucles);
    }

    private List<AbandonAutomatique.Designation> designer(int bouclesTerminees) {
        return AbandonAutomatique.designer(List.copyOf(inscriptions),
                (inscription, boucle) -> passagesParInscription.get(inscription.id()).contains(boucle),
                bouclesTerminees);
    }

    private static org.assertj.core.groups.Tuple abandon(int dossard, int boucle) {
        return tuple(dossard, MotifAbandon.HORS_DELAI, boucle);
    }

    private List<org.assertj.core.groups.Tuple> abandons(int bouclesTerminees) {
        return designer(bouclesTerminees).stream()
                .<org.assertj.core.groups.Tuple>map(d -> tuple(d.inscription().dossard(), d.motif(), d.boucleAbandon()))
                .toList();
    }

    @Test
    @DisplayName("CA2 - Bruno {1}, Chloé {}, Tom {1}, N = 1 : Chloé seule désignée (HORS_DELAI, boucle 1)")
    void doit_designer_seulement_le_coureur_sans_passage_sur_la_boucle_terminee() {
        enCourse(1, 1);
        enCourse(2);
        enCourse(3, 1);

        assertThat(abandons(1)).containsExactly(abandon(2, 1));
    }

    @Test
    @DisplayName("CA2 - RG4 : personne n'a de passage sur la boucle 1 (N = 1) : aucune désignation")
    void doit_ne_rien_designer_quand_personne_n_a_boucle() {
        enCourse(1);
        enCourse(2);
        enCourse(3);

        assertThat(designer(1)).isEmpty();
    }

    @Test
    @DisplayName("CA2 - un seul coureur a un passage 1 sur trois : les deux autres désignés (boucle 1), lui jamais")
    void doit_designer_les_deux_autres_quand_un_seul_a_boucle() {
        enCourse(1, 1);
        enCourse(2);
        enCourse(3);

        assertThat(abandons(1)).containsExactlyInAnyOrder(abandon(2, 1), abandon(3, 1));
    }

    @Test
    @DisplayName("CA2 - RG5 : Bruno {1, 2}, Chloé {1}, Tom {}, N = 3 : Tom boucle 1, Chloé boucle 2, arrêt à k = 3")
    void doit_rattraper_boucle_apres_boucle_et_s_arreter_quand_personne_n_a_boucle() {
        enCourse(1, 1, 2);
        enCourse(2, 1);
        enCourse(3);

        assertThat(abandons(3)).containsExactlyInAnyOrder(abandon(3, 1), abandon(2, 2));
    }

    @Test
    @DisplayName("CA2 - RG5 : Bruno {1}, Chloé {1}, N = 3 : personne (arrêt à k = 2, personne n'a bouclé la 2)")
    void doit_s_arreter_au_premier_k_sans_decision() {
        enCourse(1, 1);
        enCourse(2, 1);

        assertThat(designer(3)).isEmpty();
    }

    @Test
    @DisplayName("CA2 - RG5 : le coureur qui a manqué plusieurs boucles reçoit la première boucle manquée")
    void doit_retenir_la_premiere_boucle_manquee() {
        enCourse(1, 1, 2, 3);
        enCourse(2, 1);

        assertThat(abandons(3)).containsExactly(abandon(2, 2));
    }

    @Test
    @DisplayName("CA2 - RG2 : les inscriptions ABANDON et VAINQUEUR ne sont jamais désignées, avec ou sans passage")
    void doit_ignorer_les_inscriptions_abandon_et_vainqueur() {
        enCourse(1, 1);
        inscrit(2, StatutInscription.ABANDON);
        inscrit(3, StatutInscription.ABANDON, 1);
        inscrit(4, StatutInscription.VAINQUEUR);
        inscrit(5, StatutInscription.VAINQUEUR, 1);

        assertThat(designer(1)).isEmpty();
    }

    @Test
    @DisplayName("CA2 - RG4 : les passages d'une inscription ABANDON ne comptent pas pour décider (personne EN_COURSE n'a bouclé)")
    void doit_ignorer_les_passages_des_inscriptions_qui_ne_sont_plus_en_course() {
        enCourse(1);
        inscrit(2, StatutInscription.ABANDON, 1);

        assertThat(designer(1)).isEmpty();
    }

    @Test
    @DisplayName("CA2 - N = 0 : aucune désignation")
    void doit_ne_rien_designer_quand_aucune_boucle_n_est_terminee() {
        enCourse(1);
        enCourse(2, 1);

        assertThat(designer(0)).isEmpty();
    }

    @Test
    @DisplayName("CA2 - RG6 : N = M = 2, Bruno {1, 2}, Chloé {1} : Chloé désignée boucle 2")
    void doit_controler_la_derniere_boucle_autorisee() {
        enCourse(1, 1, 2);
        enCourse(2, 1);

        assertThat(abandons(2)).containsExactly(abandon(2, 2));
    }

    @Test
    @DisplayName("CA2 - RG7 : rejouer sur l'état résultant (Chloé déjà ABANDON) ne désigne plus personne")
    void doit_etre_idempotent_sur_l_etat_resultant() {
        Inscription bruno = enCourse(1, 1);
        Inscription chloe = enCourse(2);
        List<AbandonAutomatique.Designation> premier = designer(1);
        assertThat(premier).hasSize(1);
        inscriptions.replaceAll(i -> i.id().equals(chloe.id())
                ? i.abandonner(premier.get(0).motif(), premier.get(0).boucleAbandon()) : i);

        assertThat(designer(1)).isEmpty();
        assertThat(bruno.statut()).isEqualTo(StatutInscription.EN_COURSE);
    }

    @Test
    @DisplayName("CA2 - RG5 : passages hors continuité Bruno {1, 3}, Chloé {1, 2}, N = 3 : Bruno boucle 2")
    void doit_retenir_la_boucle_2_pour_un_coureur_avec_passages_hors_continuite() {
        enCourse(1, 1, 3);
        enCourse(2, 1, 2);

        assertThat(abandons(3)).containsExactly(abandon(1, 2));
    }

    @Test
    @DisplayName("CA2 - la règle ne modifie aucune Inscription (les statuts restent EN_COURSE)")
    void doit_laisser_les_inscriptions_inchangees() {
        enCourse(1, 1);
        enCourse(2);

        designer(1);

        assertThat(inscriptions).allSatisfy(i -> assertThat(i.statut()).isEqualTo(StatutInscription.EN_COURSE));
    }
}
