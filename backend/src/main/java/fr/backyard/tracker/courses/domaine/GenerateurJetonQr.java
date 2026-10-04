package fr.backyard.tracker.courses.domaine;

/** Port de génération des jetons QR des Inscriptions (aléatoire en production, déterministe en test). */
public interface GenerateurJetonQr {

    JetonQr generer();
}
