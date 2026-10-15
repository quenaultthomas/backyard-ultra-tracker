package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de la règle de contrôle de fin de Boucle du domaine : 4.8 (abandon hors délai, CA2) étendue par 4.9
 * (vainqueur unique, CA1 ; RG1 à RG5, RG7, RG8). Tests de 4.8 adaptés à RG15 de la spec 4.9 sans être affaiblis :
 * là où un seul coureur avait un Passage, il est désormais vainqueur (les abandons attendus sont inchangés) ; là où
 * l'intention était l'absence de décision malgré un seul passant, un second passant est ajouté.
 *
 * Signatures supposées (spec 4.9 §4 « Port interne ») :
 * {@code static AbandonAutomatique.Decision AbandonAutomatique.decider(Collection<Inscription> inscriptions,
 * BiPredicate<Inscription, Integer> aUnPassageSur, int bouclesTerminees)} remplace {@code designer} ; fonction pure
 * (ne modifie aucune Inscription) ; {@code Decision} expose {@code List<Designation> abandons()},
 * {@code Optional<Inscription> vainqueur()} et {@code boolean courseTerminee()} ;
 * {@code Designation(Inscription inscription, MotifAbandon motif, int boucleAbandon)} inchangé, motif toujours
 * HORS_DELAI. Le contrôle s'applique Boucle après Boucle (k de 1 à N) : 0 passant = arrêt sans décision, 1 passant =
 * vainqueur puis arrêt définitif, 2 passants ou plus = abandon des autres puis k + 1. Le prédicat {@code aUnPassageSur}
 * couvre les Passages de toute origine (SCAN et CORRECTION), voir le test du cas d'usage pour CORRECTION.
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

    private AbandonAutomatique.Decision decider(int bouclesTerminees) {
        return AbandonAutomatique.decider(List.copyOf(inscriptions),
                (inscription, boucle) -> passagesParInscription.get(inscription.id()).contains(boucle),
                bouclesTerminees);
    }

    private static org.assertj.core.groups.Tuple abandon(int dossard, int boucle) {
        return tuple(dossard, MotifAbandon.HORS_DELAI, boucle);
    }

    private List<org.assertj.core.groups.Tuple> abandons(int bouclesTerminees) {
        return decider(bouclesTerminees).abandons().stream()
                .<org.assertj.core.groups.Tuple>map(d -> tuple(d.inscription().dossard(), d.motif(), d.boucleAbandon()))
                .toList();
    }

    private Optional<Integer> dossardDuVainqueur(int bouclesTerminees) {
        return decider(bouclesTerminees).vainqueur().map(Inscription::dossard);
    }

    private void assertAucuneDecision(int bouclesTerminees) {
        AbandonAutomatique.Decision decision = decider(bouclesTerminees);
        assertThat(decision.abandons()).isEmpty();
        assertThat(decision.vainqueur()).isEmpty();
        assertThat(decision.courseTerminee()).isFalse();
    }

    // ---------- vainqueur unique (RG1) ----------

    @Test
    @DisplayName("CA1 - RG1 : Bruno {1}, Chloé {}, Tom {}, N = 1 : Bruno vainqueur, Chloé et Tom HORS_DELAI boucle 1, Course à terminer")
    void doit_designer_le_vainqueur_unique_et_abandonner_les_autres() {
        enCourse(1, 1);
        enCourse(2);
        enCourse(3);

        assertThat(dossardDuVainqueur(1)).contains(1);
        assertThat(abandons(1)).containsExactlyInAnyOrder(abandon(2, 1), abandon(3, 1));
        assertThat(decider(1).courseTerminee()).isTrue();
    }

    @Test
    @DisplayName("CA1 - RG1 : le vainqueur n'est jamais dans les abandons")
    void doit_ne_jamais_abandonner_le_vainqueur() {
        enCourse(1, 1);
        enCourse(2);

        assertThat(decider(1).abandons()).extracting(d -> d.inscription().dossard()).doesNotContain(1);
    }

    @Test
    @DisplayName("CA1 - RG4 : Bruno seul inscrit {1}, N = 1 : vainqueur, aucun abandon, Course à terminer")
    void doit_designer_vainqueur_le_seul_inscrit_qui_a_boucle() {
        enCourse(1, 1);

        assertThat(dossardDuVainqueur(1)).contains(1);
        assertThat(decider(1).abandons()).isEmpty();
        assertThat(decider(1).courseTerminee()).isTrue();
    }

    @Test
    @DisplayName("CA1 - RG4 : Bruno seul inscrit {}, N = 1 : aucune décision")
    void doit_ne_rien_decider_pour_le_seul_inscrit_sans_passage() {
        enCourse(1);

        assertAucuneDecision(1);
    }

    @Test
    @DisplayName("CA1 - RG4 : Chloé seule EN_COURSE (Bruno et Tom ABANDON) {1, 2}, N = 2 : Chloé vainqueur, rien d'autre")
    void doit_designer_vainqueur_la_seule_survivante_qui_a_boucle() {
        inscrit(1, StatutInscription.ABANDON, 1);
        enCourse(2, 1, 2);
        inscrit(3, StatutInscription.ABANDON);

        assertThat(dossardDuVainqueur(2)).contains(2);
        assertThat(decider(2).abandons()).isEmpty();
        assertThat(decider(2).courseTerminee()).isTrue();
    }

    // ---------- aucune décision, pas de vainqueur (RG3) ----------

    @Test
    @DisplayName("CA1 - RG3 : Bruno {}, Chloé {}, Tom {}, N = 1 : aucune décision (personne n'a bouclé)")
    void doit_ne_rien_designer_quand_personne_n_a_boucle() {
        enCourse(1);
        enCourse(2);
        enCourse(3);

        assertAucuneDecision(1);
    }

    @Test
    @DisplayName("CA1 - RG3 : Bruno {1}, Chloé {1}, Tom {}, N = 1 : Tom seul en abandon (boucle 1), pas de vainqueur")
    void doit_designer_seulement_le_coureur_sans_passage_quand_deux_ont_boucle() {
        enCourse(1, 1);
        enCourse(2, 1);
        enCourse(3);

        assertThat(abandons(1)).containsExactly(abandon(3, 1));
        assertThat(decider(1).vainqueur()).isEmpty();
        assertThat(decider(1).courseTerminee()).isFalse();
    }

    @Test
    @DisplayName("CA1 - RG3 : Bruno {1}, Chloé {}, Tom {1}, N = 1 : Chloé seule désignée (HORS_DELAI, boucle 1), pas de vainqueur")
    void doit_designer_seulement_le_coureur_sans_passage_sur_la_boucle_terminee() {
        enCourse(1, 1);
        enCourse(2);
        enCourse(3, 1);

        assertThat(abandons(1)).containsExactly(abandon(2, 1));
        assertThat(decider(1).vainqueur()).isEmpty();
        assertThat(decider(1).courseTerminee()).isFalse();
    }

    // ---------- examen séquentiel (RG2) ----------

    @Test
    @DisplayName("CA1 - RG2 : Bruno {1, 2}, Chloé {1}, Tom {}, N = 3 : Tom boucle 1, Bruno vainqueur, Chloé boucle 2, k = 3 non examinée")
    void doit_rattraper_puis_s_arreter_des_qu_un_vainqueur_est_designe() {
        enCourse(1, 1, 2);
        enCourse(2, 1);
        enCourse(3);

        assertThat(abandons(3)).containsExactlyInAnyOrder(abandon(3, 1), abandon(2, 2));
        assertThat(dossardDuVainqueur(3)).contains(1);
        assertThat(decider(3).courseTerminee()).isTrue();
    }

    @Test
    @DisplayName("CA1 - RG2/RG7 : Bruno {1}, Chloé {}, Tom {}, N = 3 : décision prise à k = 1, k = 2 et k = 3 non examinées")
    void doit_prendre_la_decision_a_la_premiere_boucle_et_ne_pas_examiner_les_suivantes() {
        enCourse(1, 1);
        enCourse(2);
        enCourse(3);

        assertThat(dossardDuVainqueur(3)).contains(1);
        assertThat(abandons(3)).containsExactlyInAnyOrder(abandon(2, 1), abandon(3, 1));
    }

    @Test
    @DisplayName("CA1 - RG2 : Bruno {1}, Chloé {1}, N = 3 : aucune décision (arrêt à k = 2, personne n'a bouclé la 2)")
    void doit_s_arreter_au_premier_k_sans_passant() {
        enCourse(1, 1);
        enCourse(2, 1);

        assertAucuneDecision(3);
    }

    @Test
    @DisplayName("CA1 - RG2 : Bruno {1, 2, 3}, Chloé {1}, N = 3 : Chloé reçoit la première boucle manquée (2), Bruno vainqueur")
    void doit_retenir_la_premiere_boucle_manquee() {
        enCourse(1, 1, 2, 3);
        enCourse(2, 1);

        assertThat(abandons(3)).containsExactly(abandon(2, 2));
        assertThat(dossardDuVainqueur(3)).contains(1);
    }

    @Test
    @DisplayName("CA1 - RG2 : Bruno {1, 3}, Chloé {1, 2}, N = 3 : Bruno abandon boucle 2, Chloé vainqueur (passages hors continuité)")
    void doit_retenir_la_boucle_2_pour_un_coureur_avec_passages_hors_continuite() {
        enCourse(1, 1, 3);
        enCourse(2, 1, 2);

        assertThat(abandons(3)).containsExactly(abandon(1, 2));
        assertThat(dossardDuVainqueur(3)).contains(2);
    }

    // ---------- dernière boucle autorisée (RG8) ----------

    @Test
    @DisplayName("CA1 - RG8 : M = 2, N = 2, Bruno {1, 2}, Chloé {1} : Bruno vainqueur, Chloé abandon boucle 2")
    void doit_designer_le_vainqueur_unique_de_la_derniere_boucle_autorisee() {
        enCourse(1, 1, 2);
        enCourse(2, 1);

        assertThat(abandons(2)).containsExactly(abandon(2, 2));
        assertThat(dossardDuVainqueur(2)).contains(1);
        assertThat(decider(2).courseTerminee()).isTrue();
    }

    @Test
    @DisplayName("CA1 - RG8 : M = 2, N = 2, Bruno {1, 2}, Chloé {1, 2} : aucune décision (vainqueurs partagés : 4.10)")
    void doit_ne_rien_decider_quand_deux_coureurs_bouclent_la_derniere_boucle() {
        enCourse(1, 1, 2);
        enCourse(2, 1, 2);

        assertAucuneDecision(2);
    }

    // ---------- inscriptions hors course, N = 0, immutabilité, idempotence ----------

    @Test
    @DisplayName("CA1 - RG1 : les inscriptions ABANDON et VAINQUEUR existantes ne sont jamais touchées, avec ou sans passage")
    void doit_ignorer_les_inscriptions_abandon_et_vainqueur() {
        enCourse(1, 1);
        enCourse(6, 1);
        inscrit(2, StatutInscription.ABANDON);
        inscrit(3, StatutInscription.ABANDON, 1);
        inscrit(4, StatutInscription.VAINQUEUR);
        inscrit(5, StatutInscription.VAINQUEUR, 1);

        assertAucuneDecision(1);
    }

    @Test
    @DisplayName("CA1 - RG1 : Bruno seul EN_COURSE avec Chloé ABANDON et Tom VAINQUEUR : seul Bruno est examiné, les autres jamais citées")
    void doit_ne_citer_ni_abandon_ni_vainqueur_existants_dans_la_decision() {
        enCourse(1, 1);
        inscrit(2, StatutInscription.ABANDON);
        inscrit(3, StatutInscription.VAINQUEUR, 1);

        assertThat(dossardDuVainqueur(1)).contains(1);
        assertThat(decider(1).abandons()).isEmpty();
    }

    @Test
    @DisplayName("CA1 - RG3 : les passages d'une inscription ABANDON ne comptent pas pour décider (personne EN_COURSE n'a bouclé)")
    void doit_ignorer_les_passages_des_inscriptions_qui_ne_sont_plus_en_course() {
        enCourse(1);
        inscrit(2, StatutInscription.ABANDON, 1);

        assertAucuneDecision(1);
    }

    @Test
    @DisplayName("CA1 - N = 0 : rien, même si un coureur a un passage")
    void doit_ne_rien_designer_quand_aucune_boucle_n_est_terminee() {
        enCourse(1);
        enCourse(2, 1);

        assertAucuneDecision(0);
    }

    @Test
    @DisplayName("CA1 - RG7 : rejouer la règle sur l'état résultant (vainqueur et abandons appliqués) ne décide plus rien")
    void doit_etre_idempotent_sur_l_etat_resultant() {
        enCourse(1, 1);
        enCourse(2);
        enCourse(3);
        AbandonAutomatique.Decision premiere = decider(1);
        assertThat(premiere.vainqueur()).isPresent();
        assertThat(premiere.abandons()).hasSize(2);
        Inscription vainqueur = premiere.vainqueur().orElseThrow();
        inscriptions.replaceAll(i -> {
            if (i.id().equals(vainqueur.id())) {
                return i.devenirVainqueur();
            }
            return premiere.abandons().stream().filter(d -> d.inscription().id().equals(i.id())).findFirst()
                    .map(d -> i.abandonner(d.motif(), d.boucleAbandon())).orElse(i);
        });

        assertAucuneDecision(1);
    }

    @Test
    @DisplayName("CA1 - la règle ne modifie aucune Inscription (les statuts restent EN_COURSE)")
    void doit_laisser_les_inscriptions_inchangees() {
        enCourse(1, 1);
        enCourse(2);

        decider(1);

        assertThat(inscriptions).allSatisfy(i -> assertThat(i.statut()).isEqualTo(StatutInscription.EN_COURSE));
    }
}
