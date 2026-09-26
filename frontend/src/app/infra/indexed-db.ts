import { CredentialPersistence, Session } from '../core/credentials';
import { ScanItem, StoredRecord } from '../core/scan-item';
import { ScanQueueStorage } from '../core/scan-queue';

/**
 * Stockage persistant de l'origine (IndexedDB) : file des scans (RG20) et identifiants SCANNER mémorisés
 * 24 h (RG7). Les versions du schéma IndexedDB sont indépendantes du format des éléments de la file
 * (versionné dans chaque élément, voir scan-item.ts).
 */

const DATABASE_NAME = 'backyard-ultra-tracker';
const DATABASE_VERSION = 1;
const SCAN_QUEUE_STORE = 'scan-queue';
const CREDENTIALS_STORE = 'credentials';
const SCANNER_CREDENTIALS_KEY = 'scanner';

let database: Promise<IDBDatabase> | null = null;

function openDatabase(): Promise<IDBDatabase> {
  if (database === null) {
    database = new Promise<IDBDatabase>((resolve, reject) => {
      if (typeof indexedDB === 'undefined') {
        reject(new Error('IndexedDB indisponible sur ce navigateur'));
        return;
      }
      const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(SCAN_QUEUE_STORE)) {
          db.createObjectStore(SCAN_QUEUE_STORE);
        }
        if (!db.objectStoreNames.contains(CREDENTIALS_STORE)) {
          db.createObjectStore(CREDENTIALS_STORE);
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error('Ouverture du stockage local impossible'));
      request.onblocked = () => reject(new Error('Stockage local bloqué par un autre onglet'));
    });
    database.catch(() => {
      database = null;
    });
  }
  return database;
}

async function withStore<T>(storeName: string, mode: IDBTransactionMode,
                            operation: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDatabase();
  return new Promise<T>((resolve, reject) => {
    const transaction = db.transaction(storeName, mode);
    const request = operation(transaction.objectStore(storeName));
    transaction.oncomplete = () => resolve(request.result);
    transaction.onerror = () => reject(transaction.error ?? request.error ?? new Error('Erreur du stockage local'));
    transaction.onabort = () => reject(transaction.error ?? new Error('Écriture locale annulée'));
  });
}

async function readAllRecords(storeName: string): Promise<StoredRecord[]> {
  const db = await openDatabase();
  return new Promise<StoredRecord[]>((resolve, reject) => {
    const records: StoredRecord[] = [];
    const transaction = db.transaction(storeName, 'readonly');
    const cursorRequest = transaction.objectStore(storeName).openCursor();
    cursorRequest.onsuccess = () => {
      const cursor = cursorRequest.result;
      if (cursor !== null) {
        records.push({ key: String(cursor.key), value: cursor.value as unknown });
        cursor.continue();
      }
    };
    transaction.oncomplete = () => resolve(records);
    transaction.onerror = () => reject(transaction.error ?? new Error('Lecture du stockage local impossible'));
  });
}

export const indexedDbScanQueueStorage: ScanQueueStorage = {
  loadAll: () => readAllRecords(SCAN_QUEUE_STORE),
  save: async (item: ScanItem) => {
    await withStore(SCAN_QUEUE_STORE, 'readwrite', (store) => store.put(item, item.localId));
  },
  remove: async (key: string) => {
    await withStore(SCAN_QUEUE_STORE, 'readwrite', (store) => store.delete(key));
  },
};

export const indexedDbCredentialPersistence: CredentialPersistence = {
  read: () => withStore<unknown>(CREDENTIALS_STORE, 'readonly', (store) => store.get(SCANNER_CREDENTIALS_KEY)),
  write: async (session: Session) => {
    await withStore(CREDENTIALS_STORE, 'readwrite', (store) => store.put(session, SCANNER_CREDENTIALS_KEY));
  },
  clear: async () => {
    await withStore(CREDENTIALS_STORE, 'readwrite', (store) => store.delete(SCANNER_CREDENTIALS_KEY));
  },
};
