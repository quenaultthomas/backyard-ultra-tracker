package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DoublesAnonymisationDeTest.ANNULATION_INSCRIPTIONS;
import static fr.backyard.tracker.comptes.application.DoublesAnonymisationDeTest.CONSULTATION_INSCRIPTIONS;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionAdminDeTest.CHARGEMENT_ADMINS;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.FERMETURE_TOUTES_SESSIONS;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.MISE_A_JOUR;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.REMISE_A_ZERO_COMPTEUR;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.SUPPRESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.DoublesAnonymisationDeTest.AnnulationEspion;
import fr.backyard.tracker.comptes.application.DoublesAnonymisationDeTest.ConsultationEspion;
import fr.backyard.tracker.comptes.application.DoublesSuppressionAdminDeTest.DepotAdmins;
import fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.RegistreEspion;
import fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.SessionsEspion;
import fr.backyard.tracker.comptes.domaine.AdminMasterNonSupprimableException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.DernierAdminActifNonSupprimableException;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 9.6a, CA3 : suppression d'un admin, avec anonymisation s'il a des Inscriptions (RG2, RG8).
 * Signature attendue : {@code new SupprimerAdmin(DepotComptes, InvalidationAutresSessions,
 * RegistreTentativesConnexion, PolitiqueBlocage, AnnulationInscriptions, ConsultationInscriptions)}.
 */
class SupprimerAdminAnonymisationTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final UUID ID_PATRON = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
    private static final UUID ID_NADIA = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID ID_PAUL = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID ID_LEO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private final List<String> journal = new ArrayList<>();
    private final DepotAdmins depot = new DepotAdmins(journal);
    private final SessionsEspion sessions = new SessionsEspion(journal);
    private final RegistreEspion registre = new RegistreEspion(journal);
    private final AnnulationEspion annulation = new AnnulationEspion(journal);
    private final ConsultationEspion consultation = new ConsultationEspion(journal);
    private final SupprimerAdmin supprimer = new SupprimerAdmin(depot, sessions, registre,
            PolitiqueBlocage.parDefaut(), annulation, consultation);

    private Compte compte(UUID id, String pseudo, Role role, boolean actif) {
        return depot.ajouter(Compte.reconstituer(id, new Pseudo(pseudo), "empreinte-" + pseudo, role, CREE_LE, actif));
    }

    private void patronEtNadia() {
        compte(ID_PATRON, "Patron", Role.ADMIN_MASTER, true);
        compte(ID_NADIA, "Nadia", Role.ADMIN, true);
    }

    private Compte paul(boolean actif) {
        return compte(ID_PAUL, "Paul", Role.ADMIN, actif);
    }

    private void assertRienEcritNiConsulte() {
        assertThat(depot.suppressions).isEmpty();
        assertThat(depot.misesAJour).isZero();
        assertThat(registre.effacements).isEmpty();
        assertThat(sessions.toutesLesSessions).isEmpty();
        assertThat(annulation.appels).isEmpty();
        assertThat(consultation.appels).isEmpty();
    }

    @Test
    @DisplayName("CA3 - un ADMIN avec Inscription et un autre admin actif est anonymisé : mise à jour, pas de suppression")
    void doit_anonymiser_un_admin_avec_inscription() {
        patronEtNadia();
        paul(true);
        consultation.inscrire(ID_PAUL);

        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThat(depot.suppressions).isEmpty();
        assertThat(depot.misesAJour).isEqualTo(1);
        Compte apres = depot.trouverParId(ID_PAUL).orElseThrow();
        assertThat(apres.id()).isEqualTo(ID_PAUL);
        assertThat(apres.estAnonyme()).isTrue();
        assertThat(apres.empreinteMotDePasse()).isNull();
        assertThat(apres.role()).isEqualTo(Role.COUREUR);
        assertThat(apres.creeLe()).isEqualTo(CREE_LE);
    }

    @Test
    @DisplayName("CA3 - l'anonymisation d'un admin remet à zéro le compteur de l'ancien pseudo et ferme ses sessions en dernier")
    void doit_remettre_le_compteur_a_zero_et_fermer_les_sessions_apres_anonymisation() {
        patronEtNadia();
        Compte paul = paul(true);
        consultation.inscrire(ID_PAUL);

        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThat(registre.effacements).containsExactly(paul.pseudoNormalise());
        assertThat(sessions.toutesLesSessions).containsExactly(ID_PAUL);
        assertThat(sessions.autresSessions).isEmpty();
        assertThat(journal.indexOf(MISE_A_JOUR)).isLessThan(journal.indexOf(REMISE_A_ZERO_COMPTEUR));
        assertThat(journal.getLast()).isEqualTo(FERMETURE_TOUTES_SESSIONS);
    }

    @Test
    @DisplayName("CA3 - les gardes précèdent l'annulation, la consultation puis l'anonymisation de l'admin")
    void doit_annuler_puis_consulter_apres_les_gardes_et_avant_l_ecriture() {
        patronEtNadia();
        paul(true);
        consultation.inscrire(ID_PAUL);

        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThat(journal.indexOf(CHARGEMENT_ADMINS)).isLessThan(journal.indexOf(ANNULATION_INSCRIPTIONS));
        assertThat(journal.indexOf(ANNULATION_INSCRIPTIONS)).isLessThan(journal.indexOf(CONSULTATION_INSCRIPTIONS));
        assertThat(journal.indexOf(CONSULTATION_INSCRIPTIONS)).isLessThan(journal.indexOf(MISE_A_JOUR));
        assertThat(annulation.appels).containsExactly(ID_PAUL);
        assertThat(consultation.appels).containsExactly(ID_PAUL);
    }

    @Test
    @DisplayName("CA3 - un admin désactivé avec Inscription est anonymisé et reste désactivé")
    void doit_anonymiser_un_admin_desactive_sans_modifier_son_etat() {
        patronEtNadia();
        paul(false);
        consultation.inscrire(ID_PAUL);

        supprimer.executer(ID_PATRON, ID_PAUL);

        Compte apres = depot.trouverParId(ID_PAUL).orElseThrow();
        assertThat(apres.estAnonyme()).isTrue();
        assertThat(apres.actif()).isFalse();
    }

    @Test
    @DisplayName("CA3 - un ADMIN sans Inscription est supprimé physiquement")
    void doit_supprimer_physiquement_un_admin_sans_inscription() {
        patronEtNadia();
        paul(true);

        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThat(depot.suppressions).containsExactly(ID_PAUL);
        assertThat(depot.misesAJour).isZero();
        assertThat(consultation.appels).containsExactly(ID_PAUL);
        assertThat(sessions.toutesLesSessions).containsExactly(ID_PAUL);
        assertThat(journal.getLast()).isEqualTo(FERMETURE_TOUTES_SESSIONS);
    }

    @Test
    @DisplayName("CA3 - Nadia seule admin actif avec Inscription : 409 dernier admin actif, rien écrit ni consulté")
    void doit_refuser_le_dernier_admin_actif_meme_avec_inscription() {
        compte(ID_PATRON, "Patron", Role.ADMIN_MASTER, true);
        compte(ID_NADIA, "Nadia", Role.ADMIN, true);
        consultation.inscrire(ID_NADIA);

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_NADIA))
                .isInstanceOf(DernierAdminActifNonSupprimableException.class);

        assertRienEcritNiConsulte();
        assertThat(depot.trouverParId(ID_NADIA).orElseThrow().estAnonyme()).isFalse();
    }

    @Test
    @DisplayName("CA3 - l'admin master est refusé avant toute consultation d'Inscription, rien écrit")
    void doit_refuser_l_admin_master_avant_toute_consultation() {
        patronEtNadia();
        consultation.inscrire(ID_PATRON);

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_PATRON))
                .isInstanceOf(AdminMasterNonSupprimableException.class);

        assertRienEcritNiConsulte();
        assertThat(depot.contient(ID_PATRON)).isTrue();
    }

    @Test
    @DisplayName("CA3 - une cible bénévole est introuvable côté admins : ni consultation, ni anonymisation")
    void doit_refuser_une_cible_qui_n_est_pas_admin_sans_consulter_les_inscriptions() {
        patronEtNadia();
        paul(true);
        compte(ID_LEO, "Léo", Role.BENEVOLE, true);
        consultation.inscrire(ID_LEO);

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_LEO)).isInstanceOf(CompteNonGerableException.class);

        assertRienEcritNiConsulte();
    }

    @Test
    @DisplayName("CA3 - panne de la consultation des Inscriptions : rien écrit, aucune session fermée")
    void doit_ne_rien_ecrire_ni_fermer_quand_la_consultation_echoue() {
        patronEtNadia();
        paul(true);
        consultation.panne = new IllegalStateException("panne de la consultation");

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_PAUL)).isSameAs(consultation.panne);

        assertThat(depot.suppressions).isEmpty();
        assertThat(depot.misesAJour).isZero();
        assertThat(sessions.toutesLesSessions).isEmpty();
        assertThat(registre.effacements).isEmpty();
    }

    @Test
    @DisplayName("CA3 - panne de l'annulation des Inscriptions : rien écrit, aucune session fermée")
    void doit_ne_rien_ecrire_ni_fermer_quand_l_annulation_echoue() {
        patronEtNadia();
        paul(true);
        annulation.panne = new IllegalStateException("panne de l'annulation");

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_PAUL)).isSameAs(annulation.panne);

        assertThat(depot.suppressions).isEmpty();
        assertThat(depot.misesAJour).isZero();
        assertThat(sessions.toutesLesSessions).isEmpty();
    }

    @Test
    @DisplayName("CA3 - un admin anonymisé quitte les admins actifs : Nadia devient le dernier admin actif")
    void doit_considerer_un_admin_anonymise_comme_retire_des_admins_actifs() {
        patronEtNadia();
        paul(true);
        consultation.inscrire(ID_PAUL);
        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_NADIA))
                .isInstanceOf(DernierAdminActifNonSupprimableException.class);

        assertThat(journal).doesNotContain(SUPPRESSION);
    }
}
