import { ScanResponse } from './api.types';

/**
 * Élément de la file locale des scans (RG20) et son format de stockage versionné. Un élément d'un format
 * antérieur est migré à la lecture ; un élément illisible est signalé, jamais supprimé automatiquement.
 */

export const SCAN_ITEM_FORMAT_VERSION = 2;

export type ScanItemState = 'EN_ATTENTE' | 'EN_COURS' | 'ACCEPTÉ' | 'REJETÉ';

export interface ScanError {
  readonly status: number | null;
  readonly code: string | null;
  readonly detail: string;
}

export interface ScanItem {
  readonly version: typeof SCAN_ITEM_FORMAT_VERSION;
  /** UUID généré sur l'appareil. */
  readonly localId: string;
  readonly qrToken: string;
  /** Fixé à la capture, jamais recalculé (RG19, RG23). */
  readonly scannedAt: string;
  /** Instant de l'appareil à la capture. */
  readonly capturedAt: number;
  /** Rang dans la file : ordre de capture, ou fin de file après « Renvoyer » (RG21, RG25). */
  readonly queuePosition: number;
  readonly state: ScanItemState;
  /** Nombre total d'envois. */
  readonly attempts: number;
  readonly nextAttemptAt: number | null;
  readonly lastError: ScanError | null;
  readonly response: ScanResponse | null;
  readonly acceptedAt: number | null;
  /** Rejet marqué « vu » (RG25). */
  readonly seen: boolean;
  readonly seenAt: number | null;
}

/** Enregistrement brut du stockage : sa clé et sa valeur, quel que soit son format. */
export interface StoredRecord {
  readonly key: string;
  readonly value: unknown;
}

export type StoredItemReading =
  | { readonly readable: true; readonly item: ScanItem; readonly migrated: boolean }
  | { readonly readable: false; readonly record: StoredRecord };

export interface StoredItemsReading {
  readonly items: ScanItem[];
  readonly migrated: ScanItem[];
  readonly unreadable: StoredRecord[];
}

const STATES: readonly unknown[] = ['EN_ATTENTE', 'EN_COURS', 'ACCEPTÉ', 'REJETÉ'];

export function newScanItem(localId: string, qrToken: string, scannedAt: string, capturedAt: number,
                            queuePosition: number): ScanItem {
  return {
    version: SCAN_ITEM_FORMAT_VERSION,
    localId,
    qrToken,
    scannedAt,
    capturedAt,
    queuePosition,
    state: 'EN_ATTENTE',
    attempts: 0,
    nextAttemptAt: null,
    lastError: null,
    response: null,
    acceptedAt: null,
    seen: false,
    seenAt: null,
  };
}

/** Corps exact envoyé à E6, identique à chaque envoi de l'élément (RG23). */
export function scanRequestBody(item: Pick<ScanItem, 'qrToken' | 'scannedAt'>): string {
  return JSON.stringify({ qrToken: item.qrToken, scannedAt: item.scannedAt });
}

export function isPending(item: ScanItem): boolean {
  return item.state === 'EN_ATTENTE' || item.state === 'EN_COURS';
}

export function isUnseenRejection(item: ScanItem): boolean {
  return item.state === 'REJETÉ' && !item.seen;
}

/**
 * Lecture d'un enregistrement : format courant, migration du format 1, ou illisible.
 * Format 1 : sans `queuePosition`, `nextAttemptAt`, `seen` ni `seenAt`, erreur dans `error`.
 * `fallbackPosition` donne le rang d'un élément migré.
 */
export function readStoredItem(record: StoredRecord, fallbackPosition: number): StoredItemReading {
  const value = record.value;
  if (!isRecord(value) || !hasCommonFields(value)) {
    return { readable: false, record };
  }
  if (value['version'] === SCAN_ITEM_FORMAT_VERSION && hasCurrentFields(value)) {
    return { readable: true, item: value as unknown as ScanItem, migrated: false };
  }
  if (value['version'] === 1) {
    return { readable: true, item: migrateFromVersion1(value, fallbackPosition), migrated: true };
  }
  return { readable: false, record };
}

/**
 * Lit tous les enregistrements. Les éléments migrés reçoivent un rang après ceux du format courant, dans
 * l'ordre de capture puis d'enregistrement (RG21).
 */
export function readStoredItems(records: readonly StoredRecord[]): StoredItemsReading {
  const byCapture = records
    .map((record, index) => ({ record, index }))
    .sort((a, b) => capturedAtOf(a.record) - capturedAtOf(b.record) || a.index - b.index)
    .map((entry) => entry.record);
  const maxCurrentPosition = Math.max(0, ...records.map(positionOf));
  const items: ScanItem[] = [];
  const migrated: ScanItem[] = [];
  const unreadable: StoredRecord[] = [];
  byCapture.forEach((record, index) => {
    const reading = readStoredItem(record, maxCurrentPosition + index + 1);
    if (!reading.readable) {
      unreadable.push(reading.record);
      return;
    }
    items.push(reading.item);
    if (reading.migrated) {
      migrated.push(reading.item);
    }
  });
  return { items: sortByQueuePosition(items), migrated, unreadable };
}

export function sortByQueuePosition(items: readonly ScanItem[]): ScanItem[] {
  return [...items].sort((a, b) => a.queuePosition - b.queuePosition || a.capturedAt - b.capturedAt);
}

function migrateFromVersion1(value: Record<string, unknown>, queuePosition: number): ScanItem {
  const error = isRecord(value['error']) ? value['error'] : null;
  return {
    version: SCAN_ITEM_FORMAT_VERSION,
    localId: value['localId'] as string,
    qrToken: value['qrToken'] as string,
    scannedAt: value['scannedAt'] as string,
    capturedAt: value['capturedAt'] as number,
    queuePosition,
    state: value['state'] as ScanItemState,
    attempts: typeof value['attempts'] === 'number' ? value['attempts'] : 0,
    nextAttemptAt: null,
    lastError: error === null ? null : {
      status: typeof error['status'] === 'number' ? error['status'] : null,
      code: typeof error['code'] === 'string' ? error['code'] : null,
      detail: typeof error['detail'] === 'string' ? error['detail'] : '',
    },
    response: isRecord(value['response']) ? (value['response'] as unknown as ScanResponse) : null,
    acceptedAt: typeof value['acceptedAt'] === 'number' ? value['acceptedAt'] : null,
    seen: false,
    seenAt: null,
  };
}

function hasCommonFields(value: Record<string, unknown>): boolean {
  return typeof value['localId'] === 'string'
    && typeof value['qrToken'] === 'string'
    && typeof value['scannedAt'] === 'string'
    && typeof value['capturedAt'] === 'number'
    && STATES.includes(value['state']);
}

function hasCurrentFields(value: Record<string, unknown>): boolean {
  return typeof value['queuePosition'] === 'number'
    && typeof value['attempts'] === 'number'
    && typeof value['seen'] === 'boolean';
}

function capturedAtOf(record: StoredRecord): number {
  return isRecord(record.value) && typeof record.value['capturedAt'] === 'number' ? record.value['capturedAt'] : 0;
}

function positionOf(record: StoredRecord): number {
  const value = record.value;
  return isRecord(value) && value['version'] === SCAN_ITEM_FORMAT_VERSION && typeof value['queuePosition'] === 'number'
    ? value['queuePosition']
    : 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
