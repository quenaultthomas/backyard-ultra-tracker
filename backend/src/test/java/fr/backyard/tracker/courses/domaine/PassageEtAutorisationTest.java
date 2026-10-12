package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests de l'incrément 4.4a : Inscription.autoriserPassage et Passage (CA3 ; RG6, RG11).
 * Signatures attendues : {@code void Inscription.autoriserPassage()} (InscriptionNonEnCourseException) ;
 * {@code enum OriginePassage {SCAN, CORRECTION}} ;
 * {@code static Passage Passage.scanner(UUID id, Inscription, int numeroBoucle, Instant scanneLe)} ;
 * {@code static Passage Passage.corriger(UUID id, Inscription, int numeroBoucle)} (origine CORRECTION, scanneLe vide) ;
 * accesseurs {@code id()}, {@code inscriptionId()}, {@code numeroBoucle()}, {@code Optional<Instant> scanneLe()},
 * {@code OriginePassage origine()}. Classe immuable (champs finals, aucun mutateur).
 */
class PassageEtAutorisationTest {

    private static final UUID PASSAGE_ID = UUID.fromString("3b6f0d5e-0f6a-4d7b-9e1c-5a2d8c7e1f40");
    private static final Instant T0 = Instant.parse("2026-10-12T14:03:27Z");

    @Test
    @DisplayName("CA3 - une Inscription EN_COURSE est autorisée à passer")
    void doit_autoriser_le_passage_d_une_inscription_en_course() {
        assertThatCode(() -> inscription(StatutInscription.EN_COURSE).autoriserPassage()).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "CA3 - statut {0} refusé")
    @EnumSource(value = StatutInscription.class, names = {"ABANDON", "VAINQUEUR"})
    void doit_refuser_le_passage_d_une_inscription_qui_n_est_plus_en_course(StatutInscription statut) {
        assertThatThrownBy(() -> inscription(statut).autoriserPassage())
                .isInstanceOf(InscriptionNonEnCourseException.class);
    }

    @Test
    @DisplayName("CA3 - scanner crée un Passage d'origine SCAN lié à l'Inscription, avec le numéro de Boucle et l'identifiant donnés")
    void doit_creer_un_passage_scan_pour_l_inscription() {
        Inscription bruno = inscription(StatutInscription.EN_COURSE);

        Passage passage = Passage.scanner(PASSAGE_ID, bruno, 3, T0);

        assertThat(passage.id()).isEqualTo(PASSAGE_ID);
        assertThat(passage.inscriptionId()).isEqualTo(bruno.id());
        assertThat(passage.numeroBoucle()).isEqualTo(3);
        assertThat(passage.origine()).isEqualTo(OriginePassage.SCAN);
        assertThat(passage.scanneLe()).contains(T0);
    }

    @Test
    @DisplayName("CA3 - scanneLe est tronqué à la milliseconde")
    void doit_tronquer_l_instant_du_scan_a_la_milliseconde() {
        Instant precis = Instant.parse("2026-10-12T14:03:57.250999999Z");

        Passage passage = Passage.scanner(PASSAGE_ID, inscription(StatutInscription.EN_COURSE), 1, precis);

        assertThat(passage.scanneLe()).contains(Instant.parse("2026-10-12T14:03:57.250Z"));
    }

    @ParameterizedTest(name = "CA3 - numéro de Boucle {0} refusé")
    @ValueSource(ints = {0, -1})
    void doit_refuser_un_numero_de_boucle_inferieur_a_un(int numeroBoucle) {
        Inscription bruno = inscription(StatutInscription.EN_COURSE);

        assertThatThrownBy(() -> Passage.scanner(PASSAGE_ID, bruno, numeroBoucle, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Passage.corriger(PASSAGE_ID, bruno, numeroBoucle))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CA3 - un Passage SCAN sans scanneLe est refusé")
    void doit_refuser_un_scan_sans_instant() {
        Inscription bruno = inscription(StatutInscription.EN_COURSE);

        assertThatThrownBy(() -> Passage.scanner(PASSAGE_ID, bruno, 1, null))
                .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CA3 - un Passage CORRECTION a un scanneLe vide et l'origine CORRECTION")
    void doit_creer_un_passage_correction_sans_instant() {
        Inscription bruno = inscription(StatutInscription.EN_COURSE);

        Passage passage = Passage.corriger(PASSAGE_ID, bruno, 2);

        assertThat(passage.origine()).isEqualTo(OriginePassage.CORRECTION);
        assertThat(passage.scanneLe()).isEqualTo(Optional.empty());
        assertThat(passage.numeroBoucle()).isEqualTo(2);
        assertThat(passage.inscriptionId()).isEqualTo(bruno.id());
    }

    @Test
    @DisplayName("CA3 - RG13 : toString ne contient pas le jeton de l'Inscription")
    void doit_ne_pas_reveler_le_jeton_dans_to_string() {
        Inscription bruno = inscription(StatutInscription.EN_COURSE);

        Passage passage = Passage.scanner(PASSAGE_ID, bruno, 1, T0);

        assertThat(passage.toString()).doesNotContain(bruno.jetonQr().valeur());
    }

    @Test
    @DisplayName("CA3 - RG11 : Passage immuable, classe finale, champs finals, aucun mutateur public")
    void doit_etre_immuable() {
        Class<?> classe = Passage.class;

        assertThat(Modifier.isFinal(classe.getModifiers()) || classe.isRecord()).isTrue();
        assertThat(Arrays.stream(classe.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers())))
                .allMatch(f -> Modifier.isFinal(f.getModifiers()), "champ final");
        assertThat(Arrays.stream(classe.getDeclaredMethods()).filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)).noneMatch(nom -> nom.startsWith("set") || nom.startsWith("modifier")
                || nom.startsWith("supprimer"));
    }

    private static Inscription inscription(StatutInscription statut) {
        return Inscription.reconstituer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                JetonQr.generer(), statut);
    }
}
