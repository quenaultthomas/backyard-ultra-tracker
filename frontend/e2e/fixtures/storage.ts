import type { Page } from '@playwright/test';

/**
 * Contenu sérialisé de tout le stockage du navigateur pour l'origine courante (localStorage, sessionStorage,
 * cookies, IndexedDB, Cache Storage), pour vérifier qu'aucun secret n'y est écrit (même méthode que
 * `tests/ca25-credentials-storage.spec.ts`, INC-4).
 */
export async function storageDump(page: Page): Promise<string> {
  return page.evaluate(async () => {
    const parts: string[] = [JSON.stringify(localStorage), JSON.stringify(sessionStorage), document.cookie];
    try {
      const db = await new Promise<IDBDatabase>((resolve, reject) => {
        const request = indexedDB.open('backyard-ultra-tracker');
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      });
      const stores = Array.from(db.objectStoreNames);
      for (const storeName of stores) {
        const records = await new Promise<unknown[]>((resolve, reject) => {
          const tx = db.transaction(storeName, 'readonly');
          const out: unknown[] = [];
          const cursorReq = tx.objectStore(storeName).openCursor();
          cursorReq.onsuccess = () => {
            const cursor = cursorReq.result;
            if (cursor !== null) {
              out.push(cursor.value);
              cursor.continue();
            }
          };
          tx.oncomplete = () => resolve(out);
          tx.onerror = () => reject(tx.error);
        });
        parts.push(JSON.stringify(records));
      }
    } catch {
      // Base absente : rien à ajouter.
    }
    // Cache Storage (écart mineur comblé, reprise du 2026-09-27) : contenu des réponses mises en cache, pas
    // seulement leur présence, pour qu'un identifiant éventuellement mis en cache dans un corps ou une URL
    // soit bien détecté par les assertions `not.toContain(...)` ci-dessous.
    try {
      const cacheNames = await caches.keys();
      const cacheParts: string[] = [];
      for (const name of cacheNames) {
        const cache = await caches.open(name);
        const requests = await cache.keys();
        for (const request of requests) {
          const response = await cache.match(request);
          const body = response ? await response.text() : '';
          cacheParts.push(`${request.url}::${body}`);
        }
      }
      parts.push(cacheParts.join(';'));
    } catch {
      // Cache Storage indisponible : rien à ajouter.
    }
    return parts.join('|');
  });
}
