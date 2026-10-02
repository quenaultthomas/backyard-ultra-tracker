package fr.backyard.tracker.comptes.domaine;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Échecs de connexion consécutifs pour une clé (pseudo normalisé). Immuable.
 * Le blocage court jusqu'à {@code bloqueJusqu} exclu ; ensuite, comme après une fenêtre d'oubli
 * écoulée depuis le dernier échec, le compteur repart de zéro.
 */
public final class TentativesConnexion {

    private static final TentativesConnexion AUCUNE = new TentativesConnexion(0, null, null);

    private final int echecs;
    private final Instant dernierEchec;
    private final Instant bloqueJusqu;

    private TentativesConnexion(int echecs, Instant dernierEchec, Instant bloqueJusqu) {
        this.echecs = echecs;
        this.dernierEchec = dernierEchec;
        this.bloqueJusqu = bloqueJusqu;
    }

    public static TentativesConnexion aucune() {
        return AUCUNE;
    }

    /** Un échec pendant le blocage ne compte pas et ne le prolonge pas. */
    public TentativesConnexion apresEchec(Instant maintenant, PolitiqueBlocage politique) {
        if (estBloquee(maintenant)) {
            return this;
        }
        int compteur = estOubliee(maintenant, politique) ? 1 : echecs + 1;
        Instant fin = compteur >= politique.echecsMax() ? maintenant.plus(politique.blocage()) : null;
        return new TentativesConnexion(compteur, maintenant, fin);
    }

    public boolean estBloquee(Instant maintenant) {
        return bloqueJusqu != null && maintenant.isBefore(bloqueJusqu);
    }

    /** Vrai si l'échec enregistré à cet instant est celui qui a déclenché le blocage en cours. */
    public boolean blocageDeclencheA(Instant instant) {
        return estBloquee(instant) && instant.equals(dernierEchec);
    }

    /** Durée exacte jusqu'à la fin du blocage, nulle si la clé n'est pas bloquée. */
    public Duration tempsRestant(Instant maintenant) {
        return estBloquee(maintenant) ? Duration.between(maintenant, bloqueJusqu) : Duration.ZERO;
    }

    /**
     * Vrai quand plus rien n'est retenu contre la clé : aucun échec, ou fenêtre d'oubli écoulée depuis
     * le dernier échec (ce qui couvre aussi un blocage expiré, qui dure exactement cette fenêtre).
     */
    public boolean estOubliee(Instant maintenant, PolitiqueBlocage politique) {
        return dernierEchec == null || !maintenant.isBefore(dernierEchec.plus(politique.blocage()));
    }

    public int echecs() {
        return echecs;
    }

    public Optional<Instant> dernierEchec() {
        return Optional.ofNullable(dernierEchec);
    }

    public Optional<Instant> bloqueJusqu() {
        return Optional.ofNullable(bloqueJusqu);
    }
}
