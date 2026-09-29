import { describe, expect, it } from 'vitest';
import { classify, errorMessage, TOO_MANY_ATTEMPTS } from './http-classification';
import {
  accountDeletionConfirmation,
  INVALID_CREDENTIALS,
  registrationDecision,
  RUNNER_LOGIN_UNREACHABLE,
  runnerLoginFailureMessage,
} from './outcomes';
import { jsonResponse, problem } from './testing/fakes.spec-support';

const HTML_429 = { kind: 'response', status: 429, json: false, body: null } as const;

describe('CA41 - réponse 429 du reverse proxy (RG23, CL20)', () => {
  it('corps HTML ou JSON : « Trop de tentatives. Réessayez dans une minute. », jamais « Serveur injoignable »', () => {
    expect(errorMessage(classify(HTML_429))).toBe(TOO_MANY_ATTEMPTS);
    expect(errorMessage(classify(jsonResponse(429, {})))).toBe(TOO_MANY_ATTEMPTS);
  });

  it('inscription : décision TOO_MANY_ATTEMPTS, sans nouvel essai automatique', () => {
    expect(registrationDecision(classify(HTML_429), 1)).toBe('TOO_MANY_ATTEMPTS');
  });
});

describe('RG21 - messages de la connexion coureur (E21)', () => {
  it('200 : aucun message ; 401 : Identifiants invalides ; 429 : message de RG23', () => {
    expect(runnerLoginFailureMessage(classify(jsonResponse(200, { pseudo: 'lievre', registrations: [] })))).toBeNull();
    expect(runnerLoginFailureMessage(classify(problem(401, 'UNAUTHENTICATED', 'Identifiants invalides'))))
      .toBe(INVALID_CREDENTIALS);
    expect(runnerLoginFailureMessage(classify(HTML_429))).toBe(TOO_MANY_ATTEMPTS);
  });

  it('erreur transitoire : « Connexion impossible : serveur injoignable » ; autre refus : detail du serveur', () => {
    expect(runnerLoginFailureMessage(classify({ kind: 'network-error' }))).toBe(RUNNER_LOGIN_UNREACHABLE);
    expect(runnerLoginFailureMessage(classify({ kind: 'timeout' }))).toBe(RUNNER_LOGIN_UNREACHABLE);
    expect(runnerLoginFailureMessage(classify(jsonResponse(503, {})))).toBe(RUNNER_LOGIN_UNREACHABLE);
    expect(runnerLoginFailureMessage(classify(problem(404, 'RESOURCE_NOT_FOUND', 'Compte introuvable : id 5'))))
      .toBe('Compte introuvable : id 5');
  });
});

describe('RG17 - confirmation de suppression d\'un compte', () => {
  it('texte exact avec le pseudo stocké et le nombre d\'inscriptions', () => {
    expect(accountDeletionConfirmation('lievre-42', 2)).toBe(
      'Supprimer le compte lievre-42 ? Ses 2 inscriptions et leurs passages sont conservés, sans compte. '
      + 'Action irréversible.');
  });
});
