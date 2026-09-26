import { describe, expect, it } from 'vitest';
import { classify } from './http-classification';
import {
  ADMIN_ACTION_UNREACHABLE,
  adminFailureMessage,
  loadFailureMessage,
  OFFLINE_NO_DATA,
  registrationDecision,
  shouldReloadAfterFailure,
} from './outcomes';
import { jsonResponse, problem } from './testing/fakes.spec-support';

describe('RG13 - suites d\'une inscription', () => {
  it('201 : confirmation', () => {
    expect(registrationDecision(classify(jsonResponse(201, {})), 1)).toBe('CONFIRMED');
  });

  it('409 DATA_INTEGRITY : un seul nouvel essai automatique, puis erreur avec « Réessayer »', () => {
    const conflict = classify(problem(409, 'DATA_INTEGRITY', 'réessayer'));
    expect(registrationDecision(conflict, 1)).toBe('RETRY_AUTOMATICALLY');
    expect(registrationDecision(conflict, 2)).toBe('FAILED_WITH_RETRY');
  });

  it('400 VALIDATION_FAILED : erreurs par champ ; 409 BUSINESS_CONFLICT : inscriptions fermées', () => {
    expect(registrationDecision(classify(problem(400, 'VALIDATION_FAILED', 'x')), 1)).toBe('FIELD_ERRORS');
    expect(registrationDecision(classify(problem(409, 'BUSINESS_CONFLICT', 'fermées')), 1)).toBe('CLOSED');
  });

  it('404 ou 400 (id non numérique) : course introuvable', () => {
    expect(registrationDecision(classify(problem(404, 'RESOURCE_NOT_FOUND', 'x')), 1)).toBe('NOT_FOUND');
    expect(registrationDecision(classify(problem(400, 'MALFORMED_REQUEST', 'x')), 1)).toBe('NOT_FOUND');
  });

  it('transitoire : serveur injoignable, sans nouvel essai automatique', () => {
    expect(registrationDecision(classify({ kind: 'network-error' }), 1)).toBe('UNREACHABLE');
    expect(registrationDecision(classify(jsonResponse(500, {})), 1)).toBe('UNREACHABLE');
    expect(registrationDecision(classify(problem(403, 'ACCESS_DENIED', 'x')), 1)).toBe('FAILED');
  });
});

describe('RG45 - échec d\'un chargement', () => {
  it('hors ligne et sans réponse : « Hors ligne : données indisponibles » ; sinon message du serveur', () => {
    expect(loadFailureMessage(classify({ kind: 'network-error' }), false)).toBe(OFFLINE_NO_DATA);
    expect(loadFailureMessage(classify({ kind: 'network-error' }), true)).toBe('Serveur injoignable');
    expect(loadFailureMessage(classify(problem(404, 'RESOURCE_NOT_FOUND', 'Course 9 introuvable')), false))
      .toBe('Course 9 introuvable');
  });
});

describe('RG43 - échec d\'une action admin', () => {
  it('hors ligne ou erreur serveur : message fixe « Action impossible… »', () => {
    expect(adminFailureMessage(classify({ kind: 'network-error' }))).toBe(ADMIN_ACTION_UNREACHABLE);
    expect(adminFailureMessage(classify({ kind: 'timeout' }))).toBe(ADMIN_ACTION_UNREACHABLE);
    expect(adminFailureMessage(classify(jsonResponse(503, {})))).toBe(ADMIN_ACTION_UNREACHABLE);
  });

  it('refus du serveur : detail affiché, puis rechargement', () => {
    const conflict = classify(problem(409, 'BUSINESS_CONFLICT', 'Coureur déjà DNF'));
    expect(adminFailureMessage(conflict)).toBe('Coureur déjà DNF');
    expect(shouldReloadAfterFailure(conflict)).toBe(true);
    expect(shouldReloadAfterFailure(classify(problem(409, 'DATA_INTEGRITY', 'x')))).toBe(true);
    expect(shouldReloadAfterFailure(classify({ kind: 'network-error' }))).toBe(false);
    expect(shouldReloadAfterFailure(classify(problem(401, 'UNAUTHENTICATED', 'x')))).toBe(false);
  });
});
