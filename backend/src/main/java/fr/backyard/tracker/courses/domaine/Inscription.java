package fr.backyard.tracker.courses.domaine;

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

    private final UUID id;
    private final UUID courseId;
    private final UUID compteId;
    private final int dossard;
    private final JetonQr jetonQr;
    private final StatutInscription statut;

    private Inscription(UUID id, UUID courseId, UUID compteId, int dossard, JetonQr jetonQr,
                        StatutInscription statut) {
        this.id = Objects.requireNonNull(id);
        this.courseId = Objects.requireNonNull(courseId);
        this.compteId = Objects.requireNonNull(compteId);
        this.dossard = dossard;
        this.jetonQr = Objects.requireNonNull(jetonQr);
        this.statut = Objects.requireNonNull(statut);
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
                StatutInscription.EN_COURSE);
    }

    /** Reconstitue une Inscription déjà enregistrée. */
    public static Inscription reconstituer(UUID id, UUID courseId, UUID compteId, int dossard, JetonQr jetonQr,
                                           StatutInscription statut) {
        return new Inscription(id, courseId, compteId, dossard, jetonQr, statut);
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
