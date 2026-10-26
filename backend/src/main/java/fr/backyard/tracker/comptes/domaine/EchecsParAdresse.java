package fr.backyard.tracker.comptes.domaine;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Échecs de connexion retenus pour une adresse cliente, du plus ancien au plus récent. Immuable. Seule
 * définition de la fenêtre glissante (SEC-14) : un échec d'instant {@code e} est actif tant que
 * {@code t < e + fenêtre} ; l'adresse est plafonnée quand ses échecs actifs atteignent {@code echecsMax}.
 * Au plus {@code echecsMax} instants sont retenus (les plus récents).
 */
public final class EchecsParAdresse {

    private static final EchecsParAdresse AUCUN = new EchecsParAdresse(List.of(), false);

    private final List<Instant> instants;
    /** Vrai si le dernier échec enregistré a fait passer l'adresse de non plafonnée à plafonnée. */
    private final boolean plafondAtteintParDernierEchec;

    private EchecsParAdresse(List<Instant> instants, boolean plafondAtteintParDernierEchec) {
        this.instants = List.copyOf(instants);
        this.plafondAtteintParDernierEchec = plafondAtteintParDernierEchec;
    }

    public static EchecsParAdresse aucun() {
        return AUCUN;
    }

    /** Oublie les échecs expirés, ajoute celui-ci et ne retient que les {@code echecsMax} plus récents. */
    public EchecsParAdresse apresEchec(Instant maintenant, PlafondEchecsParAdresse plafond) {
        boolean plafonneeAvant = estPlafonnee(maintenant, plafond);
        List<Instant> retenus = Stream.concat(actifsA(maintenant, plafond).stream(), Stream.of(maintenant))
                .sorted()
                .toList();
        List<Instant> plusRecents = retenus.subList(Math.max(0, retenus.size() - plafond.echecsMax()), retenus.size());
        boolean plafonneeApres = plusRecents.size() >= plafond.echecsMax();
        return new EchecsParAdresse(plusRecents, !plafonneeAvant && plafonneeApres);
    }

    /** Les mêmes échecs, sans ceux qui ont expiré à cet instant. */
    public EchecsParAdresse sansEchecsExpires(Instant maintenant, PlafondEchecsParAdresse plafond) {
        return new EchecsParAdresse(actifsA(maintenant, plafond), plafondAtteintParDernierEchec);
    }

    public int actifs(Instant maintenant, PlafondEchecsParAdresse plafond) {
        return actifsA(maintenant, plafond).size();
    }

    public boolean estPlafonnee(Instant maintenant, PlafondEchecsParAdresse plafond) {
        return actifs(maintenant, plafond) >= plafond.echecsMax();
    }

    /** Durée jusqu'à ce que les échecs actifs repassent sous le plafond ; nulle si l'adresse n'est pas plafonnée. */
    public Duration tempsRestant(Instant maintenant, PlafondEchecsParAdresse plafond) {
        List<Instant> actifs = actifsA(maintenant, plafond);
        if (actifs.size() < plafond.echecsMax()) {
            return Duration.ZERO;
        }
        Instant liberant = actifs.get(actifs.size() - plafond.echecsMax());
        return Duration.between(maintenant, liberant.plus(plafond.fenetre()));
    }

    /** Vrai si l'échec enregistré à cet instant est celui qui a fait atteindre le plafond à l'adresse. */
    public boolean atteintLePlafondA(Instant instant, PlafondEchecsParAdresse plafond) {
        return plafondAtteintParDernierEchec && dernierEchec().filter(instant::equals).isPresent()
                && estPlafonnee(instant, plafond);
    }

    /** Vrai quand aucun échec n'est plus actif : l'adresse peut être oubliée. */
    public boolean estOubliee(Instant maintenant, PlafondEchecsParAdresse plafond) {
        return actifs(maintenant, plafond) == 0;
    }

    public List<Instant> instantsRetenus() {
        return instants;
    }

    public Optional<Instant> dernierEchec() {
        return instants.isEmpty() ? Optional.empty() : Optional.of(instants.getLast());
    }

    private List<Instant> actifsA(Instant maintenant, PlafondEchecsParAdresse plafond) {
        return instants.stream().filter(echec -> maintenant.isBefore(echec.plus(plafond.fenetre()))).toList();
    }
}
