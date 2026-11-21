import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { FinflowApi } from '../core/api/finflow-api';
import { Account, Payment, PaymentStatus } from '../core/api/models';
import { AccountsStore } from './accounts/accounts.store';
import { PaymentsStore } from './payments/payments.store';

function account(id: string, status: Account['status'] = 'ACTIVE'): Account {
  return {
    id,
    name: `Compte ${id}`,
    type: 'CURRENT',
    currency: 'EUR',
    status,
    limits: { perTransactionCents: 100, dailyCents: 1000 },
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

function payment(status: PaymentStatus): Payment {
  return { id: 'p1', status } as Payment;
}

function provide(api: Partial<Record<keyof FinflowApi, unknown>>) {
  TestBed.configureTestingModule({ providers: [{ provide: FinflowApi, useValue: api }] });
}

describe('AccountsStore', () => {
  it('charge la liste et les soldes lus dans le Ledger', async () => {
    provide({
      accounts: () => of({ content: [account('a'), account('b')], page: 0, size: 10, totalElements: 2 }),
      balance: (id: string) => of({ accountId: id, currency: 'EUR', balanceCents: id === 'a' ? 500 : 0 }),
    });
    const store = TestBed.inject(AccountsStore);

    await store.load(0);

    expect(store.accounts()).toHaveLength(2);
    expect(store.balances()).toEqual({ a: 500, b: 0 });
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeNull();
  });

  it('un solde illisible n\'empêche pas d\'afficher les comptes', async () => {
    provide({
      accounts: () => of({ content: [account('a'), account('b')], page: 0, size: 10, totalElements: 2 }),
      balance: (id: string) =>
        id === 'a' ? throwError(() => new Error('Ledger arrêté')) : of({ accountId: id, currency: 'EUR', balanceCents: 7 }),
    });
    const store = TestBed.inject(AccountsStore);

    await store.load(0);

    expect(store.accounts()).toHaveLength(2);
    expect(store.balances()).toEqual({ b: 7 });
  });

  it('ne propose que les comptes actifs pour un virement', async () => {
    provide({
      accounts: () =>
        of({ content: [account('a'), account('b', 'FROZEN'), account('c', 'CLOSED')], page: 0, size: 10, totalElements: 3 }),
      balance: () => of({ accountId: 'x', currency: 'EUR', balanceCents: 0 }),
    });
    const store = TestBed.inject(AccountsStore);

    await store.load(0);

    expect(store.active().map((a) => a.id)).toEqual(['a']);
  });

  it('expose l\'erreur du serveur au lieu de la perdre', async () => {
    provide({ accounts: () => throwError(() => new Error('Gateway injoignable')) });
    const store = TestBed.inject(AccountsStore);

    await store.load(0);

    expect(store.error()).toBe('Gateway injoignable');
    expect(store.loading()).toBe(false);
  });
});

describe('PaymentsStore', () => {
  it('relit un paiement jusqu\'à un statut définitif', async () => {
    const statuses: PaymentStatus[] = ['PROCESSING', 'PROCESSING', 'COMPLETED'];
    const read = vi.fn(() => of(payment(statuses.shift() ?? 'COMPLETED')));
    provide({ payment: read });
    const store = TestBed.inject(PaymentsStore);

    const result = await store.settle('p1', 10, 1);

    expect(result.status).toBe('COMPLETED');
    expect(read).toHaveBeenCalledTimes(3);
  });

  it('un statut déjà définitif n\'est lu qu\'une fois', async () => {
    const read = vi.fn(() => of(payment('REJECTED')));
    provide({ payment: read });

    await TestBed.inject(PaymentsStore).settle('p1', 10, 1);

    expect(read).toHaveBeenCalledTimes(1);
  });

  it('abandonne après le nombre d\'essais prévu si la saga traîne', async () => {
    const read = vi.fn(() => of(payment('PROCESSING')));
    provide({ payment: read });

    const result = await TestBed.inject(PaymentsStore).settle('p1', 3, 1);

    expect(result.status).toBe('PROCESSING');
    expect(read).toHaveBeenCalledTimes(4);
  });

  it('envoie la clé d\'idempotence choisie par l\'appelant', async () => {
    const initiate = vi.fn(() => of(payment('COMPLETED')));
    provide({
      initiatePayment: initiate,
      payments: () => of({ content: [], page: 0, size: 10, totalElements: 0 }),
    });
    const store = TestBed.inject(PaymentsStore);
    const request = { sourceAccountId: 'a', destinationAccountId: 'b', amountCents: 100 };

    await store.initiate('cle-fixe-0001', request);
    await store.initiate('cle-fixe-0001', request);

    expect(initiate).toHaveBeenNthCalledWith(1, 'cle-fixe-0001', request);
    expect(initiate).toHaveBeenNthCalledWith(2, 'cle-fixe-0001', request);
  });
});
