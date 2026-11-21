import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { config } from '../config';
import {
  Account,
  AccountActivity,
  Balance,
  Entry,
  NewPayment,
  OpenAccount,
  Page,
  Payment,
  PaymentEvent,
  PaymentStatus,
  PaymentSummary,
  Tenant,
} from './models';

/** Tous les appels passent par la Gateway ; le front n'a aucune règle métier. */
@Injectable({ providedIn: 'root' })
export class FinflowApi {
  private readonly http = inject(HttpClient);
  private readonly base = config.apiUrl + '/api/v1';

  tenant() {
    return this.http.get<Tenant>(`${this.base}/tenants/current`);
  }

  accounts(page = 0, size = 20) {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<Account>>(`${this.base}/accounts`, { params });
  }

  account(id: string) {
    return this.http.get<Account>(`${this.base}/accounts/${id}`);
  }

  openAccount(request: OpenAccount) {
    return this.http.post<Account>(`${this.base}/accounts`, request);
  }

  changeLimits(id: string, perTransactionLimitCents: number, dailyLimitCents: number) {
    return this.http.put<Account>(`${this.base}/accounts/${id}/limits`, {
      perTransactionLimitCents,
      dailyLimitCents,
    });
  }

  transition(id: string, action: 'freeze' | 'unfreeze' | 'close') {
    return this.http.post<Account>(`${this.base}/accounts/${id}/${action}`, null);
  }

  activity(id: string) {
    return this.http.get<AccountActivity>(`${this.base}/accounts/${id}/activity`);
  }

  balance(accountId: string) {
    return this.http.get<Balance>(`${this.base}/ledger/accounts/${accountId}/balance`);
  }

  entries(accountId: string, limit = 50) {
    const params = new HttpParams().set('limit', limit);
    return this.http.get<Entry[]>(`${this.base}/ledger/accounts/${accountId}/entries`, { params });
  }

  deposit(idempotencyKey: string, accountId: string, amountCents: number, description?: string) {
    return this.http.post(
      `${this.base}/ledger/deposits`,
      { accountId, amountCents, description },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    );
  }

  payments(status: PaymentStatus | null, page = 0, size = 20) {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<Page<PaymentSummary>>(`${this.base}/payments`, { params });
  }

  payment(id: string) {
    return this.http.get<Payment>(`${this.base}/payments/${id}`);
  }

  paymentEvents(id: string) {
    return this.http.get<PaymentEvent[]>(`${this.base}/payments/${id}/events`);
  }

  /** La clé d'idempotence est choisie par l'appelant : un nouvel envoi de la même clé ne débite pas deux fois. */
  initiatePayment(idempotencyKey: string, payment: NewPayment) {
    return this.http.post<Payment>(`${this.base}/payments`, payment, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  refund(id: string, reason?: string) {
    return this.http.post<Payment>(`${this.base}/payments/${id}/refunds`, reason ? { reason } : {});
  }
}
