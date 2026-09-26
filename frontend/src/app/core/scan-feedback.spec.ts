import { describe, expect, it } from 'vitest';
import {
  acceptedText,
  captureFeedback,
  ERROR_VIBRATION,
  itemStateLabel,
  knownRunner,
  networkIndicator,
  rejectionText,
  serverFeedback,
  SUCCESS_VIBRATION,
} from './scan-feedback';
import { newScanItem, ScanItem } from './scan-item';
import { TOKEN_T, TOKEN_U } from './testing/fakes.spec-support';

const CAPTURED_AT = 1_000_000;

function item(overrides: Partial<ScanItem> = {}): ScanItem {
  return { ...newScanItem('l1', TOKEN_T, '2026-10-03T10:00:00.000Z', CAPTURED_AT, 1), ...overrides };
}

const RESPONSE = {
  passageId: 1, runnerId: 1, bib: 1, runnerName: 'Alice', runnerStatus: 'ACTIVE' as const, yardNumber: 1,
  source: 'SCAN' as const, scannedAt: '2026-10-03T10:00:00.000Z',
};

describe('RG50 - retour immédiat de la capture', () => {
  it('capture enregistrée : bleu, 1 bip, 50 ms ; texte selon l\'envoi', () => {
    const captured = { kind: 'captured' as const, item: item() };
    expect(captureFeedback(captured, true)).toMatchObject({
      tone: 'info', text: 'Enregistré — envoi…', sound: 'capture', vibration: [50],
    });
    expect(captureFeedback(captured, false).text).toBe('Enregistré — en attente de réseau');
  });

  it('QR non reconnu : rouge, 3 bips graves, 200-100-200, annonce assertive', () => {
    expect(captureFeedback({ kind: 'invalid' }, true)).toEqual({
      tone: 'error', icon: '✖', text: 'QR non reconnu', sound: 'error', vibration: ERROR_VIBRATION, live: 'assertive',
    });
  });

  it('relecture ignorée : discret, sans son ni vibration', () => {
    expect(captureFeedback({ kind: 'duplicate', qrToken: TOKEN_T }, true)).toMatchObject({
      tone: 'discreet', text: 'déjà enregistré', sound: null, vibration: null,
    });
  });

  it('échec de l\'écriture locale : rouge', () => {
    expect(captureFeedback({ kind: 'storage-failed', item: item(), error: new Error('quota') }, true).text)
      .toBe("Échec de l'enregistrement local : notez le dossard");
  });
});

describe('RG50 / PO16 - retour du serveur', () => {
  it('accepté en moins de 5 s : vert, 2 bips, 2 × 50 ms', () => {
    const accepted = item({ state: 'ACCEPTÉ', response: RESPONSE });
    expect(serverFeedback({ type: 'accepted', item: accepted, persisted: true }, CAPTURED_AT + 5000)).toMatchObject({
      tone: 'success', text: 'Dossard 1 — Alice — yard 1', sound: 'success', vibration: SUCCESS_VIBRATION,
    });
  });

  it('résultat arrivé plus de 5 s après la capture : aucun retour (ni son ni vibration)', () => {
    const accepted = item({ state: 'ACCEPTÉ', response: RESPONSE });
    expect(serverFeedback({ type: 'accepted', item: accepted, persisted: true }, CAPTURED_AT + 5001)).toBeNull();
  });

  it('rejet en moins de 5 s : rouge avec le detail et la consigne', () => {
    const rejected = item({ state: 'REJETÉ', lastError: { status: 409, code: 'BUSINESS_CONFLICT', detail: 'Scan tardif' } });
    expect(serverFeedback({ type: 'rejected', item: rejected, persisted: true }, CAPTURED_AT)).toMatchObject({
      tone: 'error', text: "Scan tardif — À signaler à l'organisateur", sound: 'error', live: 'assertive',
    });
  });

  it('suspension : message sans son (401 : reconnexion ; 403 : compte scanner)', () => {
    const waiting = item({ lastError: { status: 403, code: 'ACCESS_DENIED', detail: 'accès refusé' } });
    expect(serverFeedback({ type: 'suspended', item: waiting, suspension: 'ROLE' }, CAPTURED_AT)).toMatchObject({
      text: 'accès refusé — Connectez-vous avec le compte scanner', sound: null,
    });
    expect(serverFeedback({ type: 'suspended', item: item(), suspension: 'ROLE' }, CAPTURED_AT)?.text)
      .toBe('Accès refusé — Connectez-vous avec le compte scanner');
    expect(serverFeedback({ type: 'suspended', item: item(), suspension: 'AUTH' }, CAPTURED_AT)?.text)
      .toBe('Session expirée ou identifiants modifiés : reconnectez-vous');
  });

  it('nouvel essai et changements : pas de nouveau résultat affiché', () => {
    expect(serverFeedback({ type: 'retrying', item: item(), delayMs: 1000 }, CAPTURED_AT)).toBeNull();
    expect(serverFeedback({ type: 'changed' }, CAPTURED_AT)).toBeNull();
  });
});

describe('RG24 / RG25 - textes', () => {
  it('accepté : statut ajouté si le coureur n\'est pas en course', () => {
    expect(acceptedText(item({ response: { ...RESPONSE, runnerStatus: 'WINNER' } })))
      .toBe('Dossard 1 — Alice — yard 1 — Vainqueur');
    expect(acceptedText(item())).toBe('Accepté');
  });

  it('rejet : consigne selon le statut', () => {
    const rejection = (status: number | null, code: string | null) =>
      rejectionText(item({ lastError: { status, code, detail: 'detail' } }));
    expect(rejection(400, 'INVALID_INPUT')).toBe('detail');
    expect(rejection(404, 'RESOURCE_NOT_FOUND')).toBe('detail');
    expect(rejection(409, 'BUSINESS_CONFLICT')).toBe("detail — À signaler à l'organisateur");
    expect(rejection(415, 'MALFORMED_REQUEST')).toBe('detail — Erreur technique');
    expect(rejection(405, 'METHOD_NOT_ALLOWED')).toBe('detail — Erreur technique');
    expect(rejection(null, null)).toBe('detail');
    expect(rejectionText(item())).toBe('Rejeté');
  });

  it('dossard et nom connus d\'un token d\'après un scan accepté sur l\'appareil', () => {
    const items = [item({ response: RESPONSE }), item({ localId: 'l2', qrToken: TOKEN_U })];
    expect(knownRunner(TOKEN_T, items)).toEqual({ bib: 1, name: 'Alice' });
    expect(knownRunner(TOKEN_U, items)).toBeNull();
  });

  it('libellés d\'état', () => {
    expect(['EN_ATTENTE', 'EN_COURS', 'ACCEPTÉ', 'REJETÉ'].map((state) =>
      itemStateLabel(item({ state: state as ScanItem['state'] })))).toEqual(['en attente', 'envoi en cours', 'accepté', 'rejeté']);
  });
});

describe('RG26 - état réseau', () => {
  it('Hors ligne, Serveur injoignable (échec transitoire de moins de 60 s), En ligne', () => {
    expect(networkIndicator(false, { lastTransientFailureAt: null }, 0)).toBe('Hors ligne');
    expect(networkIndicator(true, { lastTransientFailureAt: 1000 }, 60_999)).toBe('Serveur injoignable');
    expect(networkIndicator(true, { lastTransientFailureAt: 1000 }, 61_000)).toBe('En ligne');
    expect(networkIndicator(true, { lastTransientFailureAt: null }, 0)).toBe('En ligne');
  });
});
