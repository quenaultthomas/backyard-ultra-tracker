package fr.backyard.tracker.comptes.domaine;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * Identité d'une personne qui se connecte (racine d'agrégat du contexte comptes).
 * Ne contient jamais le mot de passe en clair, seulement son empreinte, absente pour un compte anonymisé.
 * Tout Compte est actif à sa création ; seuls les Comptes ADMIN et BENEVOLE se désactivent (8.1).
 */
public final class Compte {

    /** Ordre d'affichage des listes de comptes : date de création, puis pseudo normalisé. */
    public static final Comparator<Compte> ORDRE_DE_CREATION =
            Comparator.comparing(Compte::creeLe).thenComparing(Compte::pseudoNormalise);

    /** Longueur de la clé d'unicité d'un Compte anonymisé : « # » puis des chiffres hexadécimaux de l'id. */
    private static final int LONGUEUR_CLE_ANONYME = 30;
    private static final String PREFIXE_CLE_ANONYME = "#";

    private final UUID id;
    private final Pseudo pseudo;
    /** Clé d'unicité : pseudo normalisé, ou clé propre au Compte s'il est anonymisé. */
    private final String pseudoNormalise;
    private final String empreinteMotDePasse;
    private final Role role;
    private final Instant creeLe;
    private final boolean actif;

    private Compte(UUID id, Pseudo pseudo, String empreinteMotDePasse, Role role, Instant creeLe, boolean actif) {
        this.id = Objects.requireNonNull(id);
        this.pseudo = Objects.requireNonNull(pseudo);
        this.pseudoNormalise = pseudo.estAnonyme() ? cleAnonyme(id) : pseudo.normalise();
        this.empreinteMotDePasse = empreinteMotDePasse;
        this.role = Objects.requireNonNull(role);
        this.creeLe = Objects.requireNonNull(creeLe);
        this.actif = actif;
    }

    /** Les comptes créés librement sont toujours des comptes coureurs. */
    public static Compte creerCoureur(Pseudo pseudo, String empreinteMotDePasse, Instant creeLe) {
        return new Compte(UUID.randomUUID(), pseudo, Objects.requireNonNull(empreinteMotDePasse), Role.COUREUR,
                creeLe, true);
    }

    /** Les comptes administrateurs sont créés par l'admin master, toujours avec le rôle ADMIN. */
    public static Compte creerAdmin(Pseudo pseudo, String empreinteMotDePasse, Instant creeLe) {
        return new Compte(UUID.randomUUID(), pseudo, Objects.requireNonNull(empreinteMotDePasse), Role.ADMIN,
                creeLe, true);
    }

    /** Les comptes bénévoles sont créés par un admin (ou l'admin master), toujours avec le rôle BENEVOLE. */
    public static Compte creerBenevole(Pseudo pseudo, String empreinteMotDePasse, Instant creeLe) {
        return new Compte(UUID.randomUUID(), pseudo, Objects.requireNonNull(empreinteMotDePasse), Role.BENEVOLE,
                creeLe, true);
    }

    /** L'unique admin master, créé au premier démarrage à partir de la configuration, jamais depuis l'interface. */
    public static Compte creerAdminMaster(Pseudo pseudo, String empreinteMotDePasse, Instant creeLe) {
        return new Compte(UUID.randomUUID(), pseudo, Objects.requireNonNull(empreinteMotDePasse), Role.ADMIN_MASTER,
                creeLe, true);
    }

    /** Recharge un compte existant, dans l'état lu en base ; l'empreinte est nulle pour un compte anonymisé. */
    public static Compte reconstituer(UUID id, Pseudo pseudo, String empreinteMotDePasse, Role role, Instant creeLe,
                                      boolean actif) {
        return new Compte(id, pseudo, empreinteMotDePasse, role, creeLe, actif);
    }

    /**
     * Unique test de connectabilité : un compte sans empreinte (anonymisé) ou désactivé ne peut jamais se connecter,
     * quel que soit le mot de passe.
     */
    public boolean peutSeConnecter() {
        return empreinteMotDePasse != null && actif;
    }

    /** Même Compte, inactif : il ne peut plus se connecter ; pseudo (toujours réservé) et empreinte conservés. */
    public Compte desactiver() {
        return new Compte(id, pseudo, empreinteMotDePasse, role, creeLe, false);
    }

    /** Même Compte, actif : il se connecte de nouveau avec son mot de passe inchangé. */
    public Compte reactiver() {
        return new Compte(id, pseudo, empreinteMotDePasse, role, creeLe, true);
    }

    /**
     * Unique contrôle des refus de désactivation, dans cet ordre : Compte coureur (non géré), admin master, Compte de
     * l'appelant, puis dernier admin actif. Ce dernier refus ne s'applique pas à une cible déjà inactive, qui ne retire
     * aucun admin actif ; l'admin master n'est jamais compté parmi les admins.
     *
     * @param appelantId identifiant du Compte qui demande la désactivation
     * @param admins     Comptes de rôle ADMIN relus sous verrou (la cible y figure si elle est ADMIN)
     * @throws CompteNonGerableException           le Compte est un Compte coureur
     * @throws AdminMasterNonDesactivableException le Compte est l'admin master
     * @throws AutoDesactivationInterditeException le Compte est celui de l'appelant
     * @throws DernierAdminActifException          le Compte est le dernier admin actif
     */
    public void verifierDesactivable(UUID appelantId, Collection<Compte> admins) {
        if (role == Role.COUREUR) {
            throw new CompteNonGerableException();
        }
        if (role == Role.ADMIN_MASTER) {
            throw new AdminMasterNonDesactivableException();
        }
        if (id.equals(appelantId)) {
            throw new AutoDesactivationInterditeException();
        }
        if (role == Role.ADMIN && actif && aucunAutreAdminActif(admins)) {
            throw new DernierAdminActifException();
        }
    }

    private boolean aucunAutreAdminActif(Collection<Compte> admins) {
        return admins.stream().noneMatch(admin -> admin.role == Role.ADMIN && admin.actif && !admin.id.equals(id));
    }

    /**
     * Remplace l'empreinte du mot de passe ; identité, pseudo, rôle et date de création inchangés.
     * Une empreinte est exigée : un changement ne rend jamais un compte inutilisable.
     */
    public Compte changerMotDePasse(String nouvelleEmpreinte) {
        return new Compte(id, pseudo, Objects.requireNonNull(nouvelleEmpreinte), role, creeLe, actif);
    }

    /**
     * Suppression d'un Compte coureur par lui-même : même identifiant, rôle et date de création, pseudo
     * {@link Pseudo#anonyme()}, clé d'unicité propre au Compte (le pseudo d'origine est libéré), plus d'empreinte
     * (connexion impossible). Unique contrôle de la règle « seul un coureur supprime son Compte ».
     *
     * @throws SuppressionCompteInterditeException le Compte n'est pas un Compte coureur
     */
    public Compte anonymiser() {
        if (role != Role.COUREUR) {
            throw new SuppressionCompteInterditeException();
        }
        return new Compte(id, Pseudo.anonyme(), null, role, creeLe, actif);
    }

    public boolean estAnonyme() {
        return pseudo.estAnonyme();
    }

    /** « # » (refusé à la saisie d'un pseudo) suivi des premiers chiffres hexadécimaux de l'identifiant. */
    private static String cleAnonyme(UUID id) {
        String hexadecimal = id.toString().replace("-", "");
        return PREFIXE_CLE_ANONYME + hexadecimal.substring(0, LONGUEUR_CLE_ANONYME - PREFIXE_CLE_ANONYME.length());
    }

    public UUID id() {
        return id;
    }

    public Pseudo pseudo() {
        return pseudo;
    }

    public String pseudoNormalise() {
        return pseudoNormalise;
    }

    /** Nulle si le compte ne peut pas se connecter ({@link #peutSeConnecter()}). */
    public String empreinteMotDePasse() {
        return empreinteMotDePasse;
    }

    public Role role() {
        return role;
    }

    public Instant creeLe() {
        return creeLe;
    }

    public boolean actif() {
        return actif;
    }

    @Override
    public boolean equals(Object autre) {
        return autre instanceof Compte compte && id.equals(compte.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Compte[id=" + id + ", pseudo=" + pseudo.valeur() + ", role=" + role + ", actif=" + actif + "]";
    }
}
