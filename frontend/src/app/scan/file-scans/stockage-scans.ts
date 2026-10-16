import { Injectable, signal } from '@angular/core';

import { ScanEnFile } from './scan-en-file';

const NOM_BASE = 'backyard-ultra-tracker';
const VERSION_BASE = 1;
const MAGASIN = 'scans-en-file';

/**
 * Stockage de la file de scans dans IndexedDB, base propre à l'application. Une copie en mémoire
 * est tenue à jour : si IndexedDB est indisponible ou échoue, la file continue en mémoire seule
 * (perdue au rechargement) et `persistant` passe à faux. Aucune méthode ne rejette.
 */
@Injectable({ providedIn: 'root' })
export class StockageScans {
  private readonly copie = new Map<string, ScanEnFile>();
  private base: Promise<IDBDatabase | null> | null = null;
  private readonly estPersistant = signal(true);

  readonly persistant = this.estPersistant.asReadonly();

  /** Toutes les entrées, de tous les Comptes, dans l'ordre de création. */
  async lister(): Promise<ScanEnFile[]> {
    const lues = await this.executer<ScanEnFile[]>('readonly', (magasin) => magasin.getAll());
    if (lues) {
      this.copie.clear();
      lues.forEach((scan) => this.copie.set(scan.identifiantScan, scan));
    }
    return [...this.copie.values()].sort((a, b) => a.sequence - b.sequence);
  }

  async enregistrer(scan: ScanEnFile): Promise<void> {
    this.copie.set(scan.identifiantScan, scan);
    await this.executer('readwrite', (magasin) => magasin.put(scan));
  }

  /** Supprime l'entrée, et avec elle le jeton. */
  async supprimer(identifiantScan: string): Promise<void> {
    this.copie.delete(identifiantScan);
    await this.executer('readwrite', (magasin) => magasin.delete(identifiantScan));
  }

  /** Résultat de la requête, ou `null` si IndexedDB est indisponible (repli en mémoire). */
  private async executer<T>(
    mode: IDBTransactionMode,
    requete: (magasin: IDBObjectStore) => IDBRequest,
  ): Promise<T | null> {
    const base = await this.ouvrir();
    if (!base) {
      return null;
    }
    try {
      return await new Promise<T>((resolve, reject) => {
        const transaction = base.transaction(MAGASIN, mode);
        const demande = requete(transaction.objectStore(MAGASIN));
        transaction.oncomplete = () => resolve(demande.result as T);
        transaction.onerror = () => reject(transaction.error);
        transaction.onabort = () => reject(transaction.error);
      });
    } catch {
      this.basculerEnMemoire();
      return null;
    }
  }

  private ouvrir(): Promise<IDBDatabase | null> {
    this.base ??= new Promise<IDBDatabase | null>((resolve) => {
      try {
        const ouverture = indexedDB.open(NOM_BASE, VERSION_BASE);
        ouverture.onupgradeneeded = () =>
          ouverture.result.createObjectStore(MAGASIN, { keyPath: 'identifiantScan' });
        ouverture.onsuccess = () => resolve(ouverture.result);
        ouverture.onerror = (evenement) => {
          evenement.preventDefault();
          resolve(null);
        };
      } catch {
        resolve(null);
      }
    }).then((base) => {
      if (!base) {
        this.basculerEnMemoire();
      }
      return base;
    });
    return this.base;
  }

  private basculerEnMemoire(): void {
    this.base = Promise.resolve(null);
    this.estPersistant.set(false);
  }
}
