package fr.backyard.tracker.comptes.domaine;

/** Port sortant : calcule l'empreinte non réversible d'un mot de passe. */
@FunctionalInterface
public interface EncodeurMotDePasse {

    String encoder(MotDePasse motDePasse);
}
