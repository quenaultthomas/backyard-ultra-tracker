package fr.backyard.tracker.courses.domaine;

/**
 * Coureur désigné par un jeton scanné, tel que restitué au bénévole qui le scanne : dossard et pseudo. Le pseudo
 * n'apparaît jamais dans {@link #toString()}, donc ni dans les journaux ni dans les messages d'erreur.
 */
public record CoureurScanne(int dossard, String pseudo) {

    @Override
    public String toString() {
        return "CoureurScanne[dossard=" + dossard + "]";
    }
}
