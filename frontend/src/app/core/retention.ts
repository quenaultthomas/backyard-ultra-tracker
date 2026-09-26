import { ScanItem } from './scan-item';

/**
 * Rétention locale de la file (RG27) : acceptés 24 h après acceptation, rejetés 7 jours après « vu »,
 * éléments en attente ou en cours jamais supprimés automatiquement.
 */

export const ACCEPTED_RETENTION_MS = 24 * 60 * 60 * 1000;
export const SEEN_REJECTION_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

export function isRetentionExpired(item: ScanItem, now: number): boolean {
  if (item.state === 'ACCEPTÉ') {
    return item.acceptedAt !== null && now - item.acceptedAt >= ACCEPTED_RETENTION_MS;
  }
  if (item.state === 'REJETÉ') {
    return item.seen && item.seenAt !== null && now - item.seenAt >= SEEN_REJECTION_RETENTION_MS;
  }
  return false;
}
