package fr.backyard.tracker.courses.domaine;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * Participation d'un Compte coureur à une Course, l'une et l'autre référencées par leur identifiant. Le dossard est
 * attribué à la création, unique dans la Course ; le jeton QR n'est jamais montré par {@link #toString()}.
 */
public final class Inscription {

    private static final int PREMIER_DOSSARD = 1;

    /**
     * Ordre public des abandons : Boucle d'abandon décroissante (les plus tardifs d'abord, sans Boucle en dernier),
     * puis dossard croissant. Unique définition de cet ordre.
     */
    public static final Comparator<Inscription> ORDRE_DES_ABANDONS = Comparator
            .comparing((Inscription inscription) -> inscription.boucleAbandon,
                    Comparator.nullsLast(Comparator.<Integer>reverseOrder()))
            .thenComparingInt(Inscription::dossard);

    private final UUID id;
    private final UUID courseId;
    private final UUID compteId;
    private final int dossard;
    private final JetonQr jetonQr;
    private final StatutInscription statut;
    private final MotifAbandon motifAbandon;
    private final Integer boucleAbandon;

    private Inscription(UUID id, UUID courseId, UUID compteId, int dossard, JetonQr jetonQr,
                        StatutInscription statut, MotifAbandon motifAbandon, Integer boucleAbandon) {
        this.id = Objects.requireNonNull(id);
        this.courseId = Objects.requireNonNull(courseId);
        this.compteId = Objects.requireNonNull(compteId);
        this.dossard = dossard;
        this.jetonQr = Objects.requireNonNull(jetonQr);
        this.statut = Objects.requireNonNull(statut);
        if (statut != StatutInscription.ABANDON && (motifAbandon != null || boucleAbandon != null)) {
            throw new IllegalArgumentException("Motif et Boucle d'abandon réservés à une Inscription en abandon");
        }
        this.motifAbandon = motifAbandon;
        this.boucleAbandon = boucleAbandon;
    }

    /**
     * Inscrit le Compte à la Course, EN_COURSE. Unique règle d'attribution du dossard : le plus grand dossard
     * existant de la Course plus un, ou 1 sans Inscription (les trous ne sont pas comblés).
     *
     * @param plusGrandDossard plus grand dossard déjà attribué dans cette Course, vide s'il n'y en a aucun
     */
    public static Inscription creer(Course course, UUID compteId, Optional<Integer> plusGrandDossard,
                                    JetonQr jetonQr) {
        int dossard = plusGrandDossard.map(dernier -> dernier + 1).orElse(PREMIER_DOSSARD);
        return new Inscription(UUID.randomUUID(), course.id(), compteId, dossard, jetonQr,
                StatutInscription.EN_COURSE, null, null);
    }

    /** Reconstitue une Inscription déjà enregistrée, sans motif ni Boucle d'abandon. */
    public static Inscription reconstituer(UUID id, UUID courseId, UUID compteId, int dossard, JetonQr jetonQr,
                                           StatutInscription statut) {
        return reconstituer(id, courseId, compteId, dossard, jetonQr, statut, null, null);
    }

    /**
     * Reconstitue une Inscription déjà enregistrée. Un abandon enregistré sans motif ni Boucle reste possible
     * (données antérieures) ; hors abandon, motif et Boucle sont absents.
     *
     * @throws IllegalArgumentException motif ou Boucle d'abandon pour une Inscription qui n'est pas en abandon
     */
    public static Inscription reconstituer(UUID id, UUID courseId, UUID compteId, int dossard, JetonQr jetonQr,
                                           StatutInscription statut, MotifAbandon motifAbandon,
                                           Integer boucleAbandon) {
        return new Inscription(id, courseId, compteId, dossard, jetonQr, statut, motifAbandon, boucleAbandon);
    }

    /**
     * Met l'Inscription en abandon pour ce motif sur cette Boucle : l'Inscription renvoyée est ABANDON, celle-ci
     * n'est pas affectée. Seule une Inscription EN_COURSE abandonne : un abandon n'est jamais remplacé.
     *
     * @throws InscriptionNonEnCourseException l'Inscription est déjà en abandon ou vainqueur
     */
    public Inscription abandonner(MotifAbandon motif, int boucle) {
        verifierEnCourse();
        if (boucle < 1) {
            throw new IllegalArgumentException("Boucle d'abandon invalide : " + boucle);
        }
        return new Inscription(id, courseId, compteId, dossard, jetonQr, StatutInscription.ABANDON,
                Objects.requireNonNull(motif), boucle);
    }

    /**
     * Abandon manuel décidé par un admin : l'Inscription renvoyée est ABANDON sur la première Boucle que le coureur
     * n'a pas terminée ({@code nombrePassages + 1}, toute origine de Passage comprise, sans lecture de l'horloge) ;
     * celle-ci n'est pas affectée. Unique définition de la Boucle d'un abandon manuel.
     *
     * @param nombrePassages nombre de Passages de l'Inscription, quelle que soit leur origine
     * @throws IllegalArgumentException motif réservé à l'abandon automatique (HORS_DELAI)
     * @throws InscriptionNonEnCourseException l'Inscription est déjà en abandon ou vainqueur
     */
    public Inscription abandonnerManuellement(MotifAbandon motif, int nombrePassages) {
        if (!motif.estPermisPourAbandonManuel()) {
            throw new IllegalArgumentException("Motif réservé à l'abandon automatique : " + motif);
        }
        return abandonner(motif, nombrePassages + 1);
    }

    /**
     * Déclare l'Inscription vainqueur : l'Inscription renvoyée est VAINQUEUR, sans motif ni Boucle d'abandon ; celle-ci
     * n'est pas affectée. Seule une Inscription EN_COURSE devient vainqueur.
     *
     * @throws InscriptionNonEnCourseException l'Inscription est déjà en abandon ou vainqueur
     */
    public Inscription devenirVainqueur() {
        verifierEnCourse();
        return new Inscription(id, courseId, compteId, dossard, jetonQr, StatutInscription.VAINQUEUR, null, null);
    }

    /**
     * Réintégration décidée par un admin : l'Inscription renvoyée est EN_COURSE, sans motif ni Boucle d'abandon ;
     * celle-ci n'est pas affectée. Seule une Inscription en abandon, quel que soit son motif, est réintégrable.
     *
     * @throws InscriptionNonEnAbandonException l'Inscription est EN_COURSE ou VAINQUEUR
     */
    public Inscription reintegrer() {
        if (statut != StatutInscription.ABANDON) {
            throw new InscriptionNonEnAbandonException(statut);
        }
        return new Inscription(id, courseId, compteId, dossard, jetonQr, StatutInscription.EN_COURSE, null, null);
    }

    public UUID id() {
        return id;
    }

    public UUID courseId() {
        return courseId;
    }

    public UUID compteId() {
        return compteId;
    }

    public int dossard() {
        return dossard;
    }

    public JetonQr jetonQr() {
        return jetonQr;
    }

    public StatutInscription statut() {
        return statut;
    }

    /** Motif de l'abandon ; vide hors abandon ou pour un abandon enregistré sans motif. */
    public Optional<MotifAbandon> motifAbandon() {
        return Optional.ofNullable(motifAbandon);
    }

    /** Boucle de l'abandon ; vide hors abandon ou pour un abandon enregistré sans Boucle. */
    public Optional<Integer> boucleAbandon() {
        return Optional.ofNullable(boucleAbandon);
    }

    /** L'Inscription est encore en course : ni en abandon, ni vainqueur. */
    public boolean estEnCourse() {
        return statut == StatutInscription.EN_COURSE;
    }

    /**
     * Seule une Inscription EN_COURSE obtient un Passage ; le scan ne change jamais son statut.
     *
     * @throws InscriptionNonEnCourseException l'Inscription est en abandon ou vainqueur
     */
    public void autoriserPassage() {
        verifierEnCourse();
    }

    private void verifierEnCourse() {
        if (!estEnCourse()) {
            throw new InscriptionNonEnCourseException(statut);
        }
    }

    /**
     * Unicité et continuité des Boucles : au plus un Passage par Boucle, et un Passage de la Boucle N > 1 exige un
     * Passage (de toute origine) sur la Boucle N - 1. Unique définition de ces règles.
     *
     * @param aUnPassageSur indique si cette Inscription a un Passage sur une Boucle donnée
     * @throws PassageDejaEnregistreException l'Inscription a déjà un Passage sur cette Boucle (contrôlé en premier)
     * @throws PassagePrecedentManquantException l'Inscription n'a pas de Passage sur la Boucle précédente
     */
    public void verifierBouclePassable(int numeroBoucle, IntPredicate aUnPassageSur) {
        if (aUnPassageSur.test(numeroBoucle)) {
            throw new PassageDejaEnregistreException(numeroBoucle);
        }
        if (numeroBoucle > 1 && !aUnPassageSur.test(numeroBoucle - 1)) {
            throw new PassagePrecedentManquantException(numeroBoucle);
        }
    }

    /** Identifiant uniquement : ni Compte ni jeton dans les journaux. */
    @Override
    public String toString() {
        return "Inscription[id=" + id + "]";
    }
}
