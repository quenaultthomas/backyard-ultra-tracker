package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.GenerateurJetonQr;
import fr.backyard.tracker.courses.domaine.JetonQr;

/** Générateur déterministe : jetons de 43 chiffres, distincts à chaque appel. */
final class GenerateurJetonQrDeterministeDeTest implements GenerateurJetonQr {

    private int compteur;

    @Override
    public JetonQr generer() {
        compteur++;
        return new JetonQr(String.format("%043d", compteur));
    }
}
