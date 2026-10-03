package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests de l'incrément 2.1a : déclaration d'une Course (CA1 à CA6).
 * Le domaine reçoit la date du jour en paramètre et ne lit jamais l'horloge.
 */
class CourseDeclarationTest {

    private static final LocalDate AUJOURDHUI = LocalDate.of(2026, 10, 3);
    private static final LocalDate DATE_COURSE = LocalDate.of(2026, 11, 14);

    // ---------- CA1 ----------

    @Test
    @DisplayName("CA1 - déclare une Course complète : id, nom trimé, EN_PREPARATION, paramètres de Boucle, limites")
    void doit_declarer_une_course_en_preparation_avec_ses_parametres() {
        Course course = Course.declarer("  Backyard des Crêtes  ", DATE_COURSE, 6706, 60, 120, 50, 24, AUJOURDHUI);

        assertThat(course.id()).isNotNull();
        assertThat(course.nom()).isEqualTo("Backyard des Crêtes");
        assertThat(course.date()).isEqualTo(DATE_COURSE);
        assertThat(course.statut()).isEqualTo(StatutCourse.EN_PREPARATION);
        assertThat(course.parametresBoucle()).isEqualTo(new ParametresBoucle(6706, 60, 120));
        assertThat(course.nombreMaxParticipants()).isEqualTo(50);
        assertThat(course.nombreMaxBoucles()).isEqualTo(24);
    }

    @Test
    @DisplayName("CA1 - deux déclarations identiques donnent deux id distincts")
    void doit_generer_un_id_distinct_pour_deux_declarations_identiques() {
        Course premiere = Course.declarer("Backyard des Crêtes", DATE_COURSE, 6706, 60, 120, 50, 24, AUJOURDHUI);
        Course seconde = Course.declarer("Backyard des Crêtes", DATE_COURSE, 6706, 60, 120, 50, 24, AUJOURDHUI);

        assertThat(premiere.id()).isNotEqualTo(seconde.id());
    }

    @Test
    @DisplayName("CA1 - la Course n'expose ni logo, ni bénévoles, ni démarréeLe en 2.1a")
    void doit_ne_pas_exposer_logo_benevoles_ni_demarree_le() {
        List<String> accesseurs = Arrays.stream(Course.class.getMethods()).map(Method::getName).toList();

        assertThat(accesseurs).doesNotContain("logo", "benevoles", "demarreeLe");
    }

    // ---------- CA2 ----------

    @ParameterizedTest(name = "nom [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t  "})
    @DisplayName("CA2 - nom absent ou vide après trim : NOM_REQUIS")
    void doit_refuser_un_nom_absent_ou_vide(String nom) {
        assertUneViolation(() -> declarerAvecNom(nom), "nom", "NOM_REQUIS");
    }

    @ParameterizedTest(name = "nom [{0}]")
    @ValueSource(strings = {"ab", "  ab  ", "a"})
    @DisplayName("CA2 - nom de moins de 3 caractères après trim : NOM_LONGUEUR")
    void doit_refuser_un_nom_trop_court_apres_trim(String nom) {
        assertUneViolation(() -> declarerAvecNom(nom), "nom", "NOM_LONGUEUR");
    }

    @Test
    @DisplayName("CA2 - nom de 101 caractères : NOM_LONGUEUR")
    void doit_refuser_un_nom_de_101_caracteres() {
        assertUneViolation(() -> declarerAvecNom("a".repeat(101)), "nom", "NOM_LONGUEUR");
    }

    @ParameterizedTest(name = "nom contenant le caractère de code {0}")
    @ValueSource(ints = {'\n', '\r', '\t', 0, 0x7F})
    @DisplayName("CA2 - nom avec un caractère de contrôle : NOM_CARACTERES")
    void doit_refuser_un_nom_avec_caractere_de_controle(int codePoint) {
        String nom = "Back" + new String(Character.toChars(codePoint)) + "yard";

        assertUneViolation(() -> declarerAvecNom(nom), "nom", "NOM_CARACTERES");
    }

    @ParameterizedTest(name = "nom de {0} caractères accepté")
    @ValueSource(ints = {3, 100})
    @DisplayName("CA2 - bornes du nom : 3 et 100 caractères acceptés")
    void doit_accepter_un_nom_aux_bornes(int longueur) {
        Course course = declarerAvecNom("a".repeat(longueur));

        assertThat(course.nom()).hasSize(longueur);
    }

    @Test
    @DisplayName("CA2 - la longueur se mesure en points de code, pas en unités UTF-16")
    void doit_mesurer_la_longueur_du_nom_en_points_de_code() {
        // 100 points de code, 200 unités UTF-16
        String nomCent = "🏃".repeat(100);
        // 101 points de code
        String nomCentUn = "🏃".repeat(101);

        assertThat(declarerAvecNom(nomCent).nom()).isEqualTo(nomCent);
        assertUneViolation(() -> declarerAvecNom(nomCentUn), "nom", "NOM_LONGUEUR");
    }

    @Test
    @DisplayName("CA2 - ponctuation, accents et espaces internes acceptés, nom conservé tel que saisi")
    void doit_accepter_ponctuation_et_accents() {
        Course course = declarerAvecNom("Backyard d'été 2026 - Édition #2");

        assertThat(course.nom()).isEqualTo("Backyard d'été 2026 - Édition #2");
    }

    // ---------- CA3 (le calcul de la date du jour par le Clock est testé dans DeclarerCourseTest) ----------

    @Test
    @DisplayName("CA3 - date absente : DATE_REQUISE")
    void doit_refuser_une_date_absente() {
        assertUneViolation(() -> declarerAvecDate(null), "date", "DATE_REQUISE");
    }

    @Test
    @DisplayName("CA3 - la veille de la date du jour : DATE_PASSEE")
    void doit_refuser_une_date_passee() {
        assertUneViolation(() -> declarerAvecDate(AUJOURDHUI.minusDays(1)), "date", "DATE_PASSEE");
    }

    @Test
    @DisplayName("CA3 - la date du jour est acceptée")
    void doit_accepter_la_date_du_jour() {
        assertThat(declarerAvecDate(AUJOURDHUI).date()).isEqualTo(AUJOURDHUI);
    }

    @Test
    @DisplayName("CA3 - date du jour + 5 ans acceptée, + 5 ans + 1 jour : DATE_TROP_LOINTAINE")
    void doit_borner_la_date_a_cinq_ans() {
        assertThat(declarerAvecDate(AUJOURDHUI.plusYears(5)).date()).isEqualTo(LocalDate.of(2031, 10, 3));
        assertUneViolation(() -> declarerAvecDate(AUJOURDHUI.plusYears(5).plusDays(1)),
                "date", "DATE_TROP_LOINTAINE");
    }

    // ---------- CA4 ----------

    @ParameterizedTest(name = "distance {0}")
    @ValueSource(ints = {0, -1, 50001})
    @DisplayName("CA4 - distance hors de 1 à 50000 : DISTANCE_HORS_BORNES")
    void doit_refuser_une_distance_hors_bornes(int distance) {
        assertUneViolation(() -> declarer(distance, 60, 120, 50, 24), "distanceBoucleMetres", "DISTANCE_HORS_BORNES");
    }

    @Test
    @DisplayName("CA4 - distance absente : DISTANCE_REQUISE")
    void doit_refuser_une_distance_absente() {
        assertUneViolation(() -> declarer(null, 60, 120, 50, 24), "distanceBoucleMetres", "DISTANCE_REQUISE");
    }

    @ParameterizedTest(name = "distance {0} acceptée")
    @ValueSource(ints = {1, 50000})
    @DisplayName("CA4 - distance aux bornes 1 et 50000 acceptée")
    void doit_accepter_une_distance_aux_bornes(int distance) {
        assertThat(declarer(distance, 60, 120, 50, 24).parametresBoucle().distanceMetres()).isEqualTo(distance);
    }

    @ParameterizedTest(name = "durée {0}")
    @ValueSource(ints = {0, -1, 1441})
    @DisplayName("CA4 - durée hors de 1 à 1440 : DUREE_HORS_BORNES")
    void doit_refuser_une_duree_hors_bornes(int duree) {
        assertUneViolation(() -> declarer(6706, duree, 120, 50, 24), "dureeBoucleMinutes", "DUREE_HORS_BORNES");
    }

    @Test
    @DisplayName("CA4 - durée absente : DUREE_REQUISE")
    void doit_refuser_une_duree_absente() {
        assertUneViolation(() -> declarer(6706, null, 120, 50, 24), "dureeBoucleMinutes", "DUREE_REQUISE");
    }

    @ParameterizedTest(name = "durée {0} acceptée")
    @ValueSource(ints = {1, 1440})
    @DisplayName("CA4 - durée aux bornes 1 et 1440 acceptée")
    void doit_accepter_une_duree_aux_bornes(int duree) {
        assertThat(declarer(6706, duree, 120, 50, 24).parametresBoucle().dureeMinutes()).isEqualTo(duree);
    }

    @ParameterizedTest(name = "dénivelé {0}")
    @ValueSource(ints = {-1, 10001})
    @DisplayName("CA4 - dénivelé hors de 0 à 10000 : DENIVELE_HORS_BORNES")
    void doit_refuser_un_denivele_hors_bornes(int denivele) {
        assertUneViolation(() -> declarer(6706, 60, denivele, 50, 24),
                "denivelePositifBoucleMetres", "DENIVELE_HORS_BORNES");
    }

    @Test
    @DisplayName("CA4 - dénivelé absent : DENIVELE_REQUIS")
    void doit_refuser_un_denivele_absent() {
        assertUneViolation(() -> declarer(6706, 60, null, 50, 24),
                "denivelePositifBoucleMetres", "DENIVELE_REQUIS");
    }

    @ParameterizedTest(name = "dénivelé {0} accepté")
    @ValueSource(ints = {0, 1, 10000})
    @DisplayName("CA4 - dénivelé aux bornes 0, 1 et 10000 accepté")
    void doit_accepter_un_denivele_aux_bornes(int denivele) {
        assertThat(declarer(6706, 60, denivele, 50, 24).parametresBoucle().denivelePositifMetres())
                .isEqualTo(denivele);
    }

    @Test
    @DisplayName("CA4 - une Boucle plate (dénivelé 0) crée la Course avec ParametresBoucle (x, y, 0)")
    void doit_creer_une_course_a_boucle_plate() {
        Course course = declarer(400, 1, 0, 10, 5);

        assertThat(course.parametresBoucle()).isEqualTo(new ParametresBoucle(400, 1, 0));
        assertThat(course.statut()).isEqualTo(StatutCourse.EN_PREPARATION);
    }

    // ---------- CA5 ----------

    @Test
    @DisplayName("CA5 - nombre max de participants absent : NOMBRE_MAX_PARTICIPANTS_REQUIS")
    void doit_refuser_un_nombre_max_de_participants_absent() {
        assertUneViolation(() -> declarer(6706, 60, 120, null, 24),
                "nombreMaxParticipants", "NOMBRE_MAX_PARTICIPANTS_REQUIS");
    }

    @ParameterizedTest(name = "participants {0}")
    @ValueSource(ints = {0, -1, 5001})
    @DisplayName("CA5 - nombre max de participants hors de 1 à 5000 : NOMBRE_MAX_PARTICIPANTS_HORS_BORNES")
    void doit_refuser_un_nombre_max_de_participants_hors_bornes(int participants) {
        assertUneViolation(() -> declarer(6706, 60, 120, participants, 24),
                "nombreMaxParticipants", "NOMBRE_MAX_PARTICIPANTS_HORS_BORNES");
    }

    @ParameterizedTest(name = "participants {0} accepté")
    @ValueSource(ints = {1, 5000})
    @DisplayName("CA5 - nombre max de participants aux bornes 1 et 5000 accepté")
    void doit_accepter_un_nombre_max_de_participants_aux_bornes(int participants) {
        assertThat(declarer(6706, 60, 120, participants, 24).nombreMaxParticipants()).isEqualTo(participants);
    }

    @Test
    @DisplayName("CA5 - nombre max de boucles obligatoire : NOMBRE_MAX_BOUCLES_REQUIS")
    void doit_refuser_un_nombre_max_de_boucles_absent() {
        assertUneViolation(() -> declarer(6706, 60, 120, 50, null),
                "nombreMaxBoucles", "NOMBRE_MAX_BOUCLES_REQUIS");
    }

    @ParameterizedTest(name = "boucles {0}")
    @ValueSource(ints = {0, -1, 501})
    @DisplayName("CA5 - nombre max de boucles hors de 1 à 500 : NOMBRE_MAX_BOUCLES_HORS_BORNES")
    void doit_refuser_un_nombre_max_de_boucles_hors_bornes(int boucles) {
        assertUneViolation(() -> declarer(6706, 60, 120, 50, boucles),
                "nombreMaxBoucles", "NOMBRE_MAX_BOUCLES_HORS_BORNES");
    }

    @ParameterizedTest(name = "boucles {0} accepté")
    @ValueSource(ints = {1, 500})
    @DisplayName("CA5 - nombre max de boucles aux bornes 1 et 500 accepté (une Course d'une seule Boucle est valide)")
    void doit_accepter_un_nombre_max_de_boucles_aux_bornes(int boucles) {
        assertThat(declarer(6706, 60, 120, 50, boucles).nombreMaxBoucles()).isEqualTo(boucles);
    }

    // ---------- CA6 ----------

    @Test
    @DisplayName("CA6 - tous les champs absents : 7 violations, une par champ, dans l'ordre du formulaire")
    void doit_remonter_sept_violations_dans_l_ordre_des_champs() {
        assertThatThrownBy(() -> Course.declarer(null, null, null, null, null, null, null, AUJOURDHUI))
                .isInstanceOfSatisfying(DonneesCourseInvalidesException.class, e -> {
                    assertThat(e.violations()).extracting(ViolationValidation::champ).containsExactly(
                            "nom", "date", "distanceBoucleMetres", "dureeBoucleMinutes",
                            "denivelePositifBoucleMetres", "nombreMaxParticipants", "nombreMaxBoucles");
                    assertThat(e.violations()).extracting(ViolationValidation::code).containsExactly(
                            "NOM_REQUIS", "DATE_REQUISE", "DISTANCE_REQUISE", "DUREE_REQUISE",
                            "DENIVELE_REQUIS", "NOMBRE_MAX_PARTICIPANTS_REQUIS", "NOMBRE_MAX_BOUCLES_REQUIS");
                });
    }

    @Test
    @DisplayName("CA6 - nom \"ab\", distance 0, date passée : 3 violations dans l'ordre nom, date, distance")
    void doit_remonter_trois_violations_dans_l_ordre_des_champs() {
        assertThatThrownBy(() ->
                Course.declarer("ab", LocalDate.of(2026, 10, 2), 0, 60, 120, 50, 24, AUJOURDHUI))
                .isInstanceOfSatisfying(DonneesCourseInvalidesException.class, e ->
                        assertThat(e.violations()).extracting(ViolationValidation::code)
                                .containsExactly("NOM_LONGUEUR", "DATE_PASSEE", "DISTANCE_HORS_BORNES"));
    }

    @Test
    @DisplayName("CA6 - aucun message de violation ne reprend la valeur saisie")
    void doit_ne_jamais_reprendre_la_valeur_saisie_dans_les_messages() {
        assertThatThrownBy(() ->
                Course.declarer("ab", LocalDate.of(2026, 10, 2), 0, 60, 120, 50, 24, AUJOURDHUI))
                .isInstanceOfSatisfying(DonneesCourseInvalidesException.class, e -> {
                    // Le message de borne contient légitimement les chiffres des bornes : on vérifie les valeurs non ambiguës.
                    assertThat(e.violations()).allSatisfy(v -> {
                        assertThat(v.message()).isNotBlank().doesNotContain("2026-10-02").doesNotContain("\"ab\"");
                    });
                    assertThat(e.getMessage()).doesNotContain("2026-10-02").doesNotContain("\"ab\"");
                });
    }

    // ---------- outils ----------

    private static Course declarerAvecNom(String nom) {
        return Course.declarer(nom, DATE_COURSE, 6706, 60, 120, 50, 24, AUJOURDHUI);
    }

    private static Course declarerAvecDate(LocalDate date) {
        return Course.declarer("Backyard des Crêtes", date, 6706, 60, 120, 50, 24, AUJOURDHUI);
    }

    /** Déclare une Course valide (nom et date de référence) dont on fait varier les paramètres numériques. */
    private static Course declarer(Integer distance, Integer duree, Integer denivele,
            Integer participants, Integer boucles) {
        return Course.declarer("Backyard des Crêtes", DATE_COURSE, distance, duree, denivele,
                participants, boucles, AUJOURDHUI);
    }

    private static void assertUneViolation(Runnable declaration, String champ, String code) {
        assertThatThrownBy(declaration::run)
                .isInstanceOfSatisfying(DonneesCourseInvalidesException.class, e -> {
                    assertThat(e.violations()).hasSize(1);
                    assertThat(e.violations().get(0).champ()).isEqualTo(champ);
                    assertThat(e.violations().get(0).code()).isEqualTo(code);
                });
    }
}
