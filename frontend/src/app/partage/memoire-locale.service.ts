import { Injectable } from '@angular/core';

/** Dernier Compte bénévole connecté sur l'appareil : `{ id, pseudo, role }`, aucun secret. */
export const CLE_DERNIER_BENEVOLE = 'backyard.dernier-benevole';
/** Dernière liste des Courses de ce bénévole, avec `compteId` et `enregistreLe`. */
export const CLE_COURSES_BENEVOLE = 'backyard.courses-benevole';

/**
 * Seules données de l'application dans `localStorage` : de quoi rouvrir l'espace bénévole sans
 * réseau. Elles n'accordent aucun droit (le serveur contrôle chaque requête) et ne servent que
 * lorsque le serveur ne répond pas. Un stockage indisponible (quota, navigation privée) fait
 * seulement perdre l'ouverture hors ligne.
 */
@Injectable({ providedIn: 'root' })
export class MemoireLocaleService {
  /** Valeur enregistrée, ou `null` si absente ou illisible. */
  lire(cle: string): unknown {
    try {
      const valeur = localStorage.getItem(cle);
      return valeur === null ? null : JSON.parse(valeur);
    } catch {
      return null;
    }
  }

  ecrire(cle: string, valeur: unknown): void {
    try {
      localStorage.setItem(cle, JSON.stringify(valeur));
    } catch {
      // Stockage plein ou refusé : l'application fonctionne, sans ouverture hors ligne.
    }
  }

  /** Efface le Compte et la liste mémorisés (déconnexion, session expirée, autre Compte). */
  oublier(): void {
    try {
      localStorage.removeItem(CLE_DERNIER_BENEVOLE);
      localStorage.removeItem(CLE_COURSES_BENEVOLE);
    } catch {
      // Stockage inaccessible : rien n'a pu y être écrit.
    }
  }
}
