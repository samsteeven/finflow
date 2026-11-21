import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { decodeClaims } from './jwt';
import { formatCents, parseEuros } from './money';
import { messageOf } from './problem';

function token(claims: object): string {
  const encode = (o: object) =>
    btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(o))))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');
  return `${encode({ alg: 'RS256' })}.${encode(claims)}.signature`;
}

describe('parseEuros', () => {
  it.each([
    ['12', 1200],
    ['12,5', 1250],
    ['12.50', 1250],
    ['0,01', 1],
    [' 1500 ', 150000],
  ])('convertit %s en %i centimes, sans nombre à virgule', (input, cents) => {
    expect(parseEuros(input)).toBe(cents);
  });

  it.each(['', 'abc', '0', '0,00', '-5', '12,345', '1 000', '1e3', '12,', '9999999999'])(
    'refuse %j',
    (input) => {
      expect(parseEuros(input)).toBeNull();
    },
  );
});

describe('formatCents', () => {
  it('affiche des euros à la française', () => {
    expect(formatCents(12050).replace(/\s/g, ' ')).toBe('120,50 €');
    expect(formatCents(-100).replace(/\s/g, ' ')).toContain('1,00');
  });
});

describe('decodeClaims', () => {
  it('lit les revendications, accents compris', () => {
    const claims = decodeClaims(token({ name: 'Élodie Épargne', realm_access: { roles: ['tenant-admin'] } }));
    expect(claims.name).toBe('Élodie Épargne');
    expect(claims.realm_access?.roles).toEqual(['tenant-admin']);
  });

  it.each([undefined, null, '', 'pas-un-jeton', 'a.%%%.c'])('renvoie un objet vide pour %j', (input) => {
    expect(decodeClaims(input as string | null | undefined)).toEqual({});
  });
});

describe('messageOf', () => {
  it('reprend le titre et le détail du problème renvoyé par le service', () => {
    const error = new HttpErrorResponse({ status: 409, error: { title: 'Conflit', detail: 'Compte gelé' } });
    expect(messageOf(error)).toBe('Conflit : Compte gelé');
  });

  it('distingue serveur injoignable et droit manquant', () => {
    expect(messageOf(new HttpErrorResponse({ status: 0 }))).toContain('injoignable');
    expect(messageOf(new HttpErrorResponse({ status: 403 }))).toContain("pas le droit");
  });

  it('accepte une erreur quelconque', () => {
    expect(messageOf(new Error('boum'))).toBe('boum');
    expect(messageOf('autre chose')).toBe('Erreur inattendue');
  });
});
