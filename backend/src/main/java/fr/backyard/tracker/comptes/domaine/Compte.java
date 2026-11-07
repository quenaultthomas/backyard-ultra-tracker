package fr.backyard.tracker.comptes.domaine;

import fr.backyard.tracker.partage.domaine.FormatImage;
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
    /** Photo de profil, nulle sans photo (10.1 RG4) ; jamais portée par un Compte anonymisé. */
    private final ReferencePhoto photo;

    private Compte(UUID id, Pseudo pseudo, String empreinteMotDePasse, Role role, Instant creeLe, boolean actif,
                   ReferencePhoto photo) {
        this.id = Objects.requireNonNull(id);
        this.pseudo = Objects.requireNonNull(pseudo);
        this.pseudoNormalise = pseudo.estAnonyme() ? cleAnonyme(id) : pseudo.normalise();
        this.empreinteMotDePasse = empreinteMotDePasse;
        this.role = Objects.requireNonNull(role);
        this.creeLe = Objects.requireNonNull(creeLe);
        this.actif = actif;
        this.photo = photo;
    }

    /** Les comptes créés librement sont toujours des comptes coureurs. */
    public static Compte creerCoureur(Pseudo pseudo, String empreinteMotDePasse, Instant creeLe) {
        return new Compte(UUID.randomUUID(), pseudo, Objects.requireNonNull(empreinteMotDePasse), Role.COUREUR,
                creeLe, true, null);
    }

    /** Les comptes administrateurs sont créés par l'admin master, toujours avec le rôle ADMIN. */
    public static Compte creerAdmin(Pseudo pseudo, String empreinteMotDePasse, Instant creeLe) {
        return new Compte(UUID.randomUUID(), pseudo, Objects.requireNonNull(empreinteMotDePasse), Role.ADMIN,
                creeLe, true, null);
    }

    /** Les comptes bénévoles sont créés par un admin (ou l'admin master), toujours avec le rôle BENEVOLE. */
    public static Compte creerBenevole(Pseudo pseudo, String empreinteMotDePasse, Instant creeLe) {
        return new Compte(UUID.randomUUID(), pseudo, Objects.requireNonNull(empreinteMotDePasse), Role.BENEVOLE,
                creeLe, true, null);
    }

    /** L'unique admin master, créé au premier démarrage à partir de la configuration, jamais depuis l'interface. */
    public static Compte creerAdminMaster(Pseudo pseudo, String empreinteMotDePasse, Instant creeLe) {
        return new Compte(UUID.randomUUID(), pseudo, Objects.requireNonNull(empreinteMotDePasse), Role.ADMIN_MASTER,
                creeLe, true, null);
    }

    /** Recharge un compte existant sans photo, dans l'état lu en base ; l'empreinte est nulle s'il est anonymisé. */
    public static Compte reconstituer(UUID id, Pseudo pseudo, String empreinteMotDePasse, Role role, Instant creeLe,
                                      boolean actif) {
        return reconstituer(id, pseudo, empreinteMotDePasse, role, creeLe, actif, null);
    }

    /** Recharge un compte existant, dans l'état lu en base ; {@code photo} est nulle sans photo de profil. */
    public static Compte reconstituer(UUID id, Pseudo pseudo, String empreinteMotDePasse, Role role, Instant creeLe,
                                      boolean actif, ReferencePhoto photo) {
        return new Compte(id, pseudo, empreinteMotDePasse, role, creeLe, actif, photo);
    }

    /**
     * Unique test de connectabilité : un compte sans empreinte (anonymisé) ou désactivé ne peut jamais se connecter,
     * quel que soit le mot de passe.
     */
    public boolean peutSeConnecter() {
        return empreinteMotDePasse != null && actif;
    }

    /**
     * Relecture d'un Compte juste après l'ouverture de sa session (8.1 RG10, 9.6 RG15) : la session n'est conservée
     * que si ce Compte relu peut toujours se connecter, sous le rôle avec lequel il a été authentifié. Un rôle changé
     * entre l'authentification et l'ouverture de la session (passage en bénévole) la rend caduque.
     */
    public boolean confirmeLaSessionDe(Compte authentifie) {
        return peutSeConnecter() && id.equals(authentifie.id) && role == authentifie.role;
    }

    /**
     * Unique définition d'un coureur trouvable par la recherche de coureurs (9.5) : rôle COUREUR et
     * {@link #peutSeConnecter()} (ni anonymisé, ni inactif).
     */
    public boolean estCoureurConnectable() {
        return role == Role.COUREUR && peutSeConnecter();
    }

    /** Même Compte, inactif : il ne peut plus se connecter ; pseudo (toujours réservé) et empreinte conservés. */
    public Compte desactiver() {
        return new Compte(id, pseudo, empreinteMotDePasse, role, creeLe, false, photo);
    }

    /** Même Compte, actif : il se connecte de nouveau avec son mot de passe inchangé. */
    public Compte reactiver() {
        return new Compte(id, pseudo, empreinteMotDePasse, role, creeLe, true, photo);
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

    /**
     * Seul un Compte BENEVOLE, actif ou désactivé, se supprime par la route des bénévoles. Unique contrôle de cette
     * règle ; tout autre rôle reçoit le même refus qu'un Compte inconnu.
     *
     * @throws CompteNonGerableException le Compte n'est pas un Compte BENEVOLE
     */
    public void verifierSuppressionBenevole() {
        if (role != Role.BENEVOLE) {
            throw new CompteNonGerableException();
        }
    }

    /**
     * Unique contrôle des refus de suppression par la route des admins, dans cet ordre : Compte d'un autre rôle (même
     * refus qu'un Compte inconnu), admin master, puis dernier admin actif. Ce dernier refus ne s'applique pas à une
     * cible déjà inactive, qui ne retire aucun admin actif ; l'admin master n'est jamais compté parmi les admins.
     *
     * @param admins Comptes de rôle ADMIN relus sous verrou (la cible y figure si elle est ADMIN)
     * @throws CompteNonGerableException                 le Compte n'est ni ADMIN ni ADMIN_MASTER
     * @throws AdminMasterNonSupprimableException        le Compte est l'admin master
     * @throws DernierAdminActifNonSupprimableException  le Compte est le dernier admin actif
     */
    public void verifierSuppressionAdmin(Collection<Compte> admins) {
        if (role != Role.ADMIN && role != Role.ADMIN_MASTER) {
            throw new CompteNonGerableException();
        }
        if (role == Role.ADMIN_MASTER) {
            throw new AdminMasterNonSupprimableException();
        }
        if (actif && aucunAutreAdminActif(admins)) {
            throw new DernierAdminActifNonSupprimableException();
        }
    }

    private boolean aucunAutreAdminActif(Collection<Compte> admins) {
        return admins.stream().noneMatch(admin -> admin.role == Role.ADMIN && admin.actif && !admin.id.equals(id));
    }

    /**
     * Changement de rôle d'un coureur en bénévole (9.6b RG12) : voir {@link #changerRoleDuCoureur(Role)}.
     *
     * @throws CompteNonGerableException le Compte n'est pas un coureur connectable (même refus qu'un Compte inconnu)
     */
    public Compte passerEnBenevole() {
        return changerRoleDuCoureur(Role.BENEVOLE);
    }

    /**
     * Changement de rôle d'un coureur en admin (9.7 RG2) : voir {@link #changerRoleDuCoureur(Role)}.
     *
     * @throws CompteNonGerableException le Compte n'est pas un coureur connectable (même refus qu'un Compte inconnu)
     */
    public Compte passerEnAdmin() {
        return changerRoleDuCoureur(Role.ADMIN);
    }

    /**
     * Unique transformation d'un changement de rôle (9.6b, 9.7 RG11) : même identité, pseudo, empreinte, date de
     * création et état actif, rôle cible. Unique contrôle de la cible : un coureur connectable
     * ({@link #estCoureurConnectable()}).
     */
    private Compte changerRoleDuCoureur(Role roleCible) {
        if (!estCoureurConnectable()) {
            throw new CompteNonGerableException();
        }
        return new Compte(id, pseudo, empreinteMotDePasse, roleCible, creeLe, actif, photo);
    }

    /**
     * Remplace l'empreinte du mot de passe ; identité, pseudo, rôle et date de création inchangés.
     * Une empreinte est exigée : un changement ne rend jamais un compte inutilisable.
     */
    public Compte changerMotDePasse(String nouvelleEmpreinte) {
        return new Compte(id, pseudo, Objects.requireNonNull(nouvelleEmpreinte), role, creeLe, actif, photo);
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
        return enCompteAnonyme();
    }

    /**
     * Suppression d'un Compte BENEVOLE ou ADMIN qui a des Inscriptions (9.6a RG3) : même Compte anonymisé que
     * {@link #anonymiser()}, rôle ramené à COUREUR, état actif inchangé. Unique contrôle de la règle « l'admin master
     * n'est jamais anonymisé ».
     *
     * @throws SuppressionCompteInterditeException le Compte n'est ni BENEVOLE ni ADMIN
     */
    public Compte anonymiserBenevoleOuAdmin() {
        if (role != Role.BENEVOLE && role != Role.ADMIN) {
            throw new SuppressionCompteInterditeException();
        }
        return enCompteAnonyme();
    }

    /** Forme unique d'un Compte anonymisé : même identité, pseudo anonyme, sans empreinte, rôle COUREUR, sans photo. */
    private Compte enCompteAnonyme() {
        return new Compte(id, Pseudo.anonyme(), null, Role.COUREUR, creeLe, actif, null);
    }

    /**
     * Unique contrôle de la règle « seul un COUREUR ajoute ou remplace sa photo » (10.1 RG1) ; un BENEVOLE ou un ADMIN
     * ex-coureur garde la sienne mais ne peut que la retirer.
     *
     * @throws PhotoNonAutoriseeException le Compte n'est pas un Compte coureur
     */
    public void verifierPhotoModifiable() {
        if (role != Role.COUREUR) {
            throw new PhotoNonAutoriseeException();
        }
    }

    /**
     * Même Compte avec cette photo, qui remplace l'éventuelle précédente.
     *
     * @throws PhotoNonAutoriseeException le Compte n'est pas un Compte coureur
     */
    public Compte avecPhoto(String jeton, FormatImage format) {
        verifierPhotoModifiable();
        return new Compte(id, pseudo, empreinteMotDePasse, role, creeLe, actif, new ReferencePhoto(jeton, format));
    }

    /** Même Compte sans photo, quel que soit son rôle (droit de retrait à tout moment) ; sans photo, inchangé. */
    public Compte retirerPhoto() {
        return new Compte(id, pseudo, empreinteMotDePasse, role, creeLe, actif, null);
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

    /** Nulle sans photo de profil. */
    public ReferencePhoto photo() {
        return photo;
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
