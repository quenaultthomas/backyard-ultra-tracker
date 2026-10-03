package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests de l'incrément 2.2 : modification d'une Course (CA1 à CA4).
 * Le domaine reçoit la date du jour en paramètre et ne lit jamais l'horloge.
 */
class CourseModificationTest {

    private static final LocalDate AUJOURDHUI = LocalDate.of(2026, 10, 3);
    private static final LocalDate DATE_COURSE = LocalDate.of(2026, 11, 14);

    // ---------- CA1 ----------

    @Test
    @DisplayName("CA1 - modifie tous les champs, garde l'id et le statut EN_PREPARATION, nom trimé")
    void doit_remplacer_tous_les_champs_modifiables_en_gardant_id_et_statut() {
        Course depart = courseDeReference();

        Course modifiee = depart.modifier("  Backyard des Alpes  ", LocalDate.of(2026, 12, 5), 8000, 45, 0, 80, 12,
                AUJOURDHUI);

        assertThat(modifiee.id()).isEqualTo(depart.id());
        assertThat(modifiee.statut()).isEqualTo(StatutCourse.EN_PREPARATION);
        assertThat(modifiee.nom()).isEqualTo("Backyard des Alpes");
        assertThat(modifiee.date()).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(modifiee.parametresBoucle()).isEqualTo(new ParametresBoucle(8000, 45, 0));
        assertThat(modifiee.nombreMaxParticipants()).isEqualTo(80);
        assertThat(modifiee.nombreMaxBoucles()).isEqualTo(12);
    }

    @Test
    @DisplayName("CA1 - l'objet de départ n'est pas affecté par la modification")
    void doit_laisser_la_course_de_depart_intacte() {
        Course depart = courseDeReference();

        depart.modifier("Backyard des Alpes", LocalDate.of(2026, 12, 5), 8000, 45, 0, 80, 12, AUJOURDHUI);

        assertThat(depart.nom()).isEqualTo("Backyard des Crêtes");
        assertThat(depart.date()).isEqualTo(DATE_COURSE);
        assertThat(depart.parametresBoucle()).isEqualTo(new ParametresBoucle(6706, 60, 120));
        assertThat(depart.nombreMaxParticipants()).isEqualTo(50);
        assertThat(depart.nombreMaxBoucles()).isEqualTo(24);
        assertThat(depart.statut()).isEqualTo(StatutCourse.EN_PREPARATION);
    }

    @Test
    @DisplayName("CA1 - une modification sans changement renvoie les mêmes valeurs (idempotence)")
    void doit_renvoyer_les_memes_valeurs_pour_une_modification_identique() {
        Course depart = courseDeReference();

        Course modifiee = depart.modifier("Backyard des Crêtes", DATE_COURSE, 6706, 60, 120, 50, 24, AUJOURDHUI);

        assertThat(modifiee.id()).isEqualTo(depart.id());
        assertThat(modifiee.nom()).isEqualTo(depart.nom());
        assertThat(modifiee.date()).isEqualTo(depart.date());
        assertThat(modifiee.parametresBoucle()).isEqualTo(depart.parametresBoucle());
        assertThat(modifiee.nombreMaxParticipants()).isEqualTo(depart.nombreMaxParticipants());
        assertThat(modifiee.nombreMaxBoucles()).isEqualTo(depart.nombreMaxBoucles());
    }

    @Test
    @DisplayName("CA1 - une Boucle peut devenir plate (dénivelé 120 vers 0) et inversement")
    void doit_accepter_de_passer_une_boucle_a_plat_et_inversement() {
        Course plate = courseDeReference().modifier("Backyard des Crêtes", DATE_COURSE, 6706, 60, 0, 50, 24, AUJOURDHUI);
        Course vallonnee = plate.modifier("Backyard des Crêtes", DATE_COURSE, 6706, 60, 120, 50, 24, AUJOURDHUI);

        assertThat(plate.parametresBoucle().denivelePositifMetres()).isZero();
        assertThat(vallonnee.parametresBoucle().denivelePositifMetres()).isEqualTo(120);
    }

    // ---------- CA2 ----------

    @ParameterizedTest(name = "nom [{0}] refusé avec {1}")
    @CsvSource(value = {
            "NULL,NOM_REQUIS",
            "'',NOM_REQUIS",
            "'   ',NOM_REQUIS",
            "ab,NOM_LONGUEUR",
            "'  ab  ',NOM_LONGUEUR"
    }, nullValues = "NULL")
    @DisplayName("CA2 - nom absent, vide ou trop court : même code que la déclaration")
    void doit_refuser_un_nom_absent_vide_ou_trop_court(String nom, String code) {
        assertThat(codesDesViolations(nom, DATE_COURSE, 6706, 60, 120, 50, 24)).containsExactly(code);
    }

    @Test
    @DisplayName("CA2 - nom de 101 caractères : NOM_LONGUEUR, nom de 100 et de 3 caractères acceptés")
    void doit_borner_le_nom_entre_3_et_100_caracteres() {
        assertThat(codesDesViolations("x".repeat(101), DATE_COURSE, 6706, 60, 120, 50, 24))
                .containsExactly("NOM_LONGUEUR");
        assertThat(courseDeReference().modifier("x".repeat(100), DATE_COURSE, 6706, 60, 120, 50, 24, AUJOURDHUI).nom())
                .hasSize(100);
        assertThat(courseDeReference().modifier("abc", DATE_COURSE, 6706, 60, 120, 50, 24, AUJOURDHUI).nom())
                .isEqualTo("abc");
    }

    @Test
    @DisplayName("CA2 - nom avec un caractère de contrôle : NOM_CARACTERES")
    void doit_refuser_un_nom_avec_caractere_de_controle() {
        assertThat(codesDesViolations("Back\nyard", DATE_COURSE, 6706, 60, 120, 50, 24))
                .containsExactly("NOM_CARACTERES");
    }

    @ParameterizedTest(name = "{0} = {1} refusé avec {2}")
    @CsvSource(value = {
            "distanceBoucleMetres,NULL,DISTANCE_REQUISE",
            "distanceBoucleMetres,0,DISTANCE_HORS_BORNES",
            "distanceBoucleMetres,-1,DISTANCE_HORS_BORNES",
            "distanceBoucleMetres,50001,DISTANCE_HORS_BORNES",
            "dureeBoucleMinutes,NULL,DUREE_REQUISE",
            "dureeBoucleMinutes,0,DUREE_HORS_BORNES",
            "dureeBoucleMinutes,-1,DUREE_HORS_BORNES",
            "dureeBoucleMinutes,1441,DUREE_HORS_BORNES",
            "denivelePositifBoucleMetres,NULL,DENIVELE_REQUIS",
            "denivelePositifBoucleMetres,-1,DENIVELE_HORS_BORNES",
            "denivelePositifBoucleMetres,10001,DENIVELE_HORS_BORNES",
            "nombreMaxParticipants,NULL,NOMBRE_MAX_PARTICIPANTS_REQUIS",
            "nombreMaxParticipants,0,NOMBRE_MAX_PARTICIPANTS_HORS_BORNES",
            "nombreMaxParticipants,-1,NOMBRE_MAX_PARTICIPANTS_HORS_BORNES",
            "nombreMaxParticipants,5001,NOMBRE_MAX_PARTICIPANTS_HORS_BORNES",
            "nombreMaxBoucles,NULL,NOMBRE_MAX_BOUCLES_REQUIS",
            "nombreMaxBoucles,0,NOMBRE_MAX_BOUCLES_HORS_BORNES",
            "nombreMaxBoucles,-1,NOMBRE_MAX_BOUCLES_HORS_BORNES",
            "nombreMaxBoucles,501,NOMBRE_MAX_BOUCLES_HORS_BORNES"
    }, nullValues = "NULL")
    @DisplayName("CA2 - entier absent ou hors bornes : mêmes codes que la déclaration")
    void doit_refuser_un_entier_absent_ou_hors_bornes(String champ, Integer valeur, String code) {
        Integer[] v = valeursDeReferenceAvec(champ, valeur);

        assertThat(codesDesViolations("Backyard des Crêtes", DATE_COURSE, v[0], v[1], v[2], v[3], v[4]))
                .containsExactly(code);
    }

    @ParameterizedTest(name = "{0} = {1} accepté")
    @CsvSource({
            "distanceBoucleMetres,1", "distanceBoucleMetres,50000",
            "dureeBoucleMinutes,1", "dureeBoucleMinutes,1440",
            "denivelePositifBoucleMetres,0", "denivelePositifBoucleMetres,1", "denivelePositifBoucleMetres,10000",
            "nombreMaxParticipants,1", "nombreMaxParticipants,5000",
            "nombreMaxBoucles,1", "nombreMaxBoucles,500"
    })
    @DisplayName("CA2 - bornes incluses acceptées (dénivelé 0 compris)")
    void doit_accepter_les_bornes_incluses(String champ, Integer valeur) {
        Integer[] v = valeursDeReferenceAvec(champ, valeur);

        Course modifiee = courseDeReference().modifier("Backyard des Crêtes", DATE_COURSE, v[0], v[1], v[2], v[3], v[4],
                AUJOURDHUI);

        assertThat(modifiee.parametresBoucle().distanceMetres()).isEqualTo(v[0]);
        assertThat(modifiee.parametresBoucle().dureeMinutes()).isEqualTo(v[1]);
        assertThat(modifiee.parametresBoucle().denivelePositifMetres()).isEqualTo(v[2]);
        assertThat(modifiee.nombreMaxParticipants()).isEqualTo(v[3]);
        assertThat(modifiee.nombreMaxBoucles()).isEqualTo(v[4]);
    }

    @Test
    @DisplayName("CA2 - tout à null : 7 violations dans l'ordre des champs")
    void doit_renvoyer_sept_violations_dans_l_ordre_des_champs_quand_tout_est_absent() {
        List<ViolationValidation> violations = violations(null, null, null, null, null, null, null);

        assertThat(violations).extracting(ViolationValidation::champ).containsExactly(
                "nom", "date", "distanceBoucleMetres", "dureeBoucleMinutes", "denivelePositifBoucleMetres",
                "nombreMaxParticipants", "nombreMaxBoucles");
        assertThat(violations).extracting(ViolationValidation::code).containsExactly(
                "NOM_REQUIS", "DATE_REQUISE", "DISTANCE_REQUISE", "DUREE_REQUISE", "DENIVELE_REQUIS",
                "NOMBRE_MAX_PARTICIPANTS_REQUIS", "NOMBRE_MAX_BOUCLES_REQUIS");
    }

    @Test
    @DisplayName("CA2 - nom 'ab', distance 0 et date passée différente de l'enregistrée : 3 violations")
    void doit_cumuler_les_violations_de_plusieurs_champs() {
        List<ViolationValidation> violations = violations("ab", LocalDate.of(2026, 10, 2), 0, 60, 120, 50, 24);

        assertThat(violations).extracting(ViolationValidation::code)
                .containsExactly("NOM_LONGUEUR", "DATE_PASSEE", "DISTANCE_HORS_BORNES");
    }

    @Test
    @DisplayName("CA2 - aucun message de violation ne reprend la valeur saisie")
    void doit_ne_jamais_reprendre_la_valeur_saisie_dans_les_messages() {
        String nomSaisi = "N".repeat(101);

        List<ViolationValidation> violations = violations(nomSaisi, DATE_COURSE, 987654, 60, 120, 50, 24);

        assertThat(violations).hasSize(2);
        assertThat(violations).extracting(ViolationValidation::message)
                .noneMatch(m -> m.contains(nomSaisi) || m.contains("987654"));
    }

    @Test
    @DisplayName("CA2 - une saisie invalide ne modifie pas la Course")
    void doit_laisser_la_course_intacte_quand_la_saisie_est_invalide() {
        Course depart = courseDeReference();

        assertThatThrownBy(() -> depart.modifier("ab", DATE_COURSE, 0, 60, 120, 50, 24, AUJOURDHUI))
                .isInstanceOf(DonneesCourseInvalidesException.class);

        assertThat(depart.nom()).isEqualTo("Backyard des Crêtes");
        assertThat(depart.parametresBoucle()).isEqualTo(new ParametresBoucle(6706, 60, 120));
    }

    @ParameterizedTest(name = "saisie {index}")
    @MethodSource("saisiesAComparer")
    @DisplayName("CA2 - declarer et modifier renvoient la même liste de violations pour une même saisie")
    void doit_renvoyer_les_memes_violations_que_la_declaration(String nom, LocalDate date, Integer distance,
                                                              Integer duree, Integer denivele, Integer participants,
                                                              Integer boucles) {
        List<ViolationValidation> attendues = violationsDeDeclaration(nom, date, distance, duree, denivele,
                participants, boucles);

        List<ViolationValidation> obtenues = violations(nom, date, distance, duree, denivele, participants, boucles);

        assertThat(obtenues).isEqualTo(attendues);
    }

    static Stream<Arguments> saisiesAComparer() {
        return Stream.of(
                Arguments.of(null, null, null, null, null, null, null),
                Arguments.of("ab", LocalDate.of(2026, 10, 2), 0, 0, -1, 0, 0),
                Arguments.of("Back\nyard", LocalDate.of(2031, 10, 5), 50001, 1441, 10001, 5001, 501),
                Arguments.of("   ", DATE_COURSE, -1, -1, 0, -1, -1),
                Arguments.of("x".repeat(101), LocalDate.of(2026, 10, 3), 1, 1, 0, 1, 1));
    }

    // ---------- CA3 ----------

    @Test
    @DisplayName("CA3 - date passée inchangée : modification acceptée (durée 45)")
    void doit_accepter_une_date_passee_inchangee() {
        LocalDate datePassee = LocalDate.of(2026, 10, 1);
        Course depart = courseEnPreparationLe(datePassee);

        Course modifiee = depart.modifier("Backyard des Crêtes", datePassee, 6706, 45, 120, 50, 24, AUJOURDHUI);

        assertThat(modifiee.date()).isEqualTo(datePassee);
        assertThat(modifiee.parametresBoucle().dureeMinutes()).isEqualTo(45);
    }

    @Test
    @DisplayName("CA3 - date passée changée vers une autre date passée : DATE_PASSEE")
    void doit_refuser_de_deplacer_la_course_vers_une_autre_date_passee() {
        Course depart = courseEnPreparationLe(LocalDate.of(2026, 10, 1));

        assertThat(codesDesViolations(depart, "Backyard des Crêtes", LocalDate.of(2026, 10, 2), AUJOURDHUI))
                .containsExactly("DATE_PASSEE");
    }

    @ParameterizedTest(name = "date {0} acceptée")
    @ValueSource(strings = {"2026-10-03", "2026-10-04"})
    @DisplayName("CA3 - le jour même et le lendemain sont acceptés pour une date changée")
    void doit_accepter_le_jour_du_jour_et_les_suivants(String date) {
        Course depart = courseEnPreparationLe(LocalDate.of(2026, 10, 1));

        Course modifiee = depart.modifier("Backyard des Crêtes", LocalDate.parse(date), 6706, 60, 120, 50, 24,
                AUJOURDHUI);

        assertThat(modifiee.date()).isEqualTo(LocalDate.parse(date));
    }

    @Test
    @DisplayName("CA3 - date absente : DATE_REQUISE, même si la Course a une date enregistrée")
    void doit_refuser_une_date_absente() {
        assertThat(codesDesViolations(courseDeReference(), "Backyard des Crêtes", null, AUJOURDHUI))
                .containsExactly("DATE_REQUISE");
    }

    @Test
    @DisplayName("CA3 - limite haute : jour + 5 ans accepté, jour + 5 ans + 1 : DATE_TROP_LOINTAINE")
    void doit_borner_une_date_changee_a_cinq_ans() {
        Course depart = courseDeReference();

        Course acceptee = depart.modifier("Backyard des Crêtes", LocalDate.of(2031, 10, 3), 6706, 60, 120, 50, 24,
                AUJOURDHUI);

        assertThat(acceptee.date()).isEqualTo(LocalDate.of(2031, 10, 3));
        assertThat(codesDesViolations(depart, "Backyard des Crêtes", LocalDate.of(2031, 10, 4), AUJOURDHUI))
                .containsExactly("DATE_TROP_LOINTAINE");
    }

    @Test
    @DisplayName("CA3 - date trop lointaine déjà enregistrée et inchangée : acceptée")
    void doit_accepter_une_date_trop_lointaine_inchangee() {
        LocalDate dateAuDela = LocalDate.of(2031, 10, 5);
        Course depart = courseEnPreparationLe(dateAuDela);

        Course modifiee = depart.modifier("Backyard des Crêtes", dateAuDela, 6706, 45, 120, 50, 24, AUJOURDHUI);

        assertThat(modifiee.date()).isEqualTo(dateAuDela);
    }

    @Test
    @DisplayName("CA3 - date du jour 2026-10-04 : Course du 3/10 modifiable à date inchangée")
    void doit_accepter_la_date_inchangee_la_veille_du_jour_courant() {
        LocalDate veille = LocalDate.of(2026, 10, 3);
        Course depart = courseEnPreparationLe(veille);

        Course modifiee = depart.modifier("Backyard des Crêtes", veille, 6706, 45, 120, 50, 24,
                LocalDate.of(2026, 10, 4));

        assertThat(modifiee.date()).isEqualTo(veille);
    }

    @Test
    @DisplayName("CA3 - date du jour 2026-10-04 : la date 2026-10-03 envoyée pour une Course du 1/11 est DATE_PASSEE")
    void doit_refuser_comme_passee_une_date_changee_anterieure_au_jour_courant() {
        Course depart = courseEnPreparationLe(LocalDate.of(2026, 11, 1));

        assertThat(codesDesViolations(depart, "Backyard des Crêtes", LocalDate.of(2026, 10, 3),
                LocalDate.of(2026, 10, 4))).containsExactly("DATE_PASSEE");
    }

    @Test
    @DisplayName("CA3 - date inchangée : seules les autres violations sont signalées, pas de violation de date")
    void doit_ne_signaler_aucune_violation_de_date_pour_une_date_inchangee() {
        LocalDate datePassee = LocalDate.of(2026, 10, 1);
        Course depart = courseEnPreparationLe(datePassee);

        List<ViolationValidation> violations = violationsDe(depart, "ab", datePassee, AUJOURDHUI);

        assertThat(violations).extracting(ViolationValidation::code).containsExactly("NOM_LONGUEUR");
    }

    // ---------- CA4 ----------

    @Test
    @DisplayName("CA4 - Course EN_COURS : CourseNonModifiableException et Course inchangée")
    void doit_refuser_la_modification_d_une_course_en_cours() {
        Course enCours = courseAvecStatut(StatutCourse.EN_COURS);

        assertThatThrownBy(() -> enCours.modifier("Backyard des Alpes", LocalDate.of(2026, 12, 5), 8000, 45, 0, 80, 12,
                AUJOURDHUI)).isInstanceOf(CourseNonModifiableException.class);

        assertThat(enCours.nom()).isEqualTo("Backyard des Crêtes");
        assertThat(enCours.statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(enCours.parametresBoucle()).isEqualTo(new ParametresBoucle(6706, 60, 120));
    }

    @Test
    @DisplayName("CA4 - Course TERMINEE : CourseNonModifiableException et Course inchangée")
    void doit_refuser_la_modification_d_une_course_terminee() {
        Course terminee = courseAvecStatut(StatutCourse.TERMINEE);

        assertThatThrownBy(() -> terminee.modifier("Backyard des Alpes", LocalDate.of(2026, 12, 5), 8000, 45, 0, 80,
                12, AUJOURDHUI)).isInstanceOf(CourseNonModifiableException.class);

        assertThat(terminee.nom()).isEqualTo("Backyard des Crêtes");
        assertThat(terminee.statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @ParameterizedTest(name = "statut {0}")
    @ValueSource(strings = {"EN_COURS", "TERMINEE"})
    @DisplayName("CA4 - valeurs invalides sur une Course non modifiable : le statut prime, pas de violations")
    void doit_privilegier_le_statut_sur_la_validation_des_champs(String statut) {
        Course course = courseAvecStatut(StatutCourse.valueOf(statut));

        assertThatThrownBy(() -> course.modifier(null, null, 0, -1, 10001, 0, 501, AUJOURDHUI))
                .isInstanceOf(CourseNonModifiableException.class)
                .isNotInstanceOf(DonneesCourseInvalidesException.class);
    }

    @Test
    @DisplayName("CA4 - une Course EN_PREPARATION est modifiable")
    void doit_accepter_la_modification_d_une_course_en_preparation() {
        Course modifiee = courseAvecStatut(StatutCourse.EN_PREPARATION)
                .modifier("Backyard des Alpes", DATE_COURSE, 6706, 60, 120, 50, 24, AUJOURDHUI);

        assertThat(modifiee.nom()).isEqualTo("Backyard des Alpes");
    }

    // ---------- utilitaires ----------

    private static Course courseDeReference() {
        return courseAvecStatut(StatutCourse.EN_PREPARATION);
    }

    private static Course courseAvecStatut(StatutCourse statut) {
        return Course.reconstituer(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "Backyard des Crêtes",
                DATE_COURSE, statut, new ParametresBoucle(6706, 60, 120), 50, 24);
    }

    private static Course courseEnPreparationLe(LocalDate date) {
        return Course.reconstituer(UUID.fromString("00000000-0000-0000-0000-0000000000a2"), "Backyard des Crêtes",
                date, StatutCourse.EN_PREPARATION, new ParametresBoucle(6706, 60, 120), 50, 24);
    }

    /** Valeurs de référence (distance, durée, dénivelé, participants, boucles) dont un champ est remplacé. */
    private static Integer[] valeursDeReferenceAvec(String champ, Integer valeur) {
        Integer[] v = {6706, 60, 120, 50, 24};
        switch (champ) {
            case "distanceBoucleMetres" -> v[0] = valeur;
            case "dureeBoucleMinutes" -> v[1] = valeur;
            case "denivelePositifBoucleMetres" -> v[2] = valeur;
            case "nombreMaxParticipants" -> v[3] = valeur;
            case "nombreMaxBoucles" -> v[4] = valeur;
            default -> throw new IllegalArgumentException(champ);
        }
        return v;
    }

    private static List<String> codesDesViolations(String nom, LocalDate date, Integer distance, Integer duree,
                                                   Integer denivele, Integer participants, Integer boucles) {
        return violations(nom, date, distance, duree, denivele, participants, boucles).stream()
                .map(ViolationValidation::code).toList();
    }

    private static List<String> codesDesViolations(Course course, String nom, LocalDate date, LocalDate aujourdhui) {
        return violationsDe(course, nom, date, aujourdhui).stream().map(ViolationValidation::code).toList();
    }

    private static List<ViolationValidation> violationsDe(Course course, String nom, LocalDate date,
                                                          LocalDate aujourdhui) {
        return violationsLevees(() -> course.modifier(nom, date, 6706, 60, 120, 50, 24, aujourdhui));
    }

    /** Violations levées en modifiant la Course de référence (date enregistrée : 2026-11-14). */
    private static List<ViolationValidation> violations(String nom, LocalDate date, Integer distance, Integer duree,
                                                        Integer denivele, Integer participants, Integer boucles) {
        return violationsLevees(() -> courseDeReference().modifier(nom, date, distance, duree, denivele, participants,
                boucles, AUJOURDHUI));
    }

    private static List<ViolationValidation> violationsDeDeclaration(String nom, LocalDate date, Integer distance,
                                                                     Integer duree, Integer denivele,
                                                                     Integer participants, Integer boucles) {
        return violationsLevees(() -> Course.declarer(nom, date, distance, duree, denivele, participants, boucles,
                AUJOURDHUI));
    }

    private static List<ViolationValidation> violationsLevees(Runnable action) {
        try {
            action.run();
        } catch (DonneesCourseInvalidesException e) {
            return e.violations();
        }
        throw new AssertionError("Aucune violation levée alors qu'une saisie invalide était attendue");
    }
}
