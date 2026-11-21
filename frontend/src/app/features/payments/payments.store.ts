import { inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { firstValueFrom } from 'rxjs';
import { FinflowApi } from '../../core/api/finflow-api';
import { NewPayment, Payment, PaymentStatus, PaymentSummary } from '../../core/api/models';
import { messageOf } from '../../core/util/problem';

interface PaymentsState {
  payments: PaymentSummary[];
  status: PaymentStatus | null;
  page: number;
  size: number;
  total: number;
  loading: boolean;
  error: string | null;
}

const initial: PaymentsState = { payments: [], status: null, page: 0, size: 10, total: 0, loading: false, error: null };

/** Statuts qui ne changeront plus d'eux-mêmes : on arrête de relire le paiement. */
export const SETTLED: ReadonlySet<PaymentStatus> = new Set<PaymentStatus>([
  'COMPLETED',
  'REJECTED',
  'REFUNDED',
  'FAILED',
]);

export const PaymentsStore = signalStore(
  { providedIn: 'root' },
  withState(initial),
  withMethods((store, api = inject(FinflowApi)) => ({
    async load(status: PaymentStatus | null = store.status(), page = 0, size = store.size()): Promise<void> {
      patchState(store, { loading: true, error: null, status });
      try {
        const result = await firstValueFrom(api.payments(status, page, size));
        patchState(store, { payments: result.content, page: result.page, size: result.size, total: result.totalElements });
      } catch (e) {
        patchState(store, { error: messageOf(e) });
      } finally {
        patchState(store, { loading: false });
      }
    },

    /**
     * Émet un virement. La clé d'idempotence vient de l'appelant : un second envoi avec la même clé
     * (double clic, nouvel essai après une coupure) renvoie le même paiement au lieu d'en créer un autre.
     */
    async initiate(idempotencyKey: string, payment: NewPayment): Promise<Payment> {
      const created = await firstValueFrom(api.initiatePayment(idempotencyKey, payment));
      await this.load(store.status(), 0);
      return created;
    },

    /** Relit un paiement jusqu'à ce que son statut soit définitif (la saga peut encore tourner). */
    async settle(id: string, attempts = 10, delayMs = 500): Promise<Payment> {
      let payment = await firstValueFrom(api.payment(id));
      for (let i = 0; i < attempts && !SETTLED.has(payment.status); i++) {
        await new Promise((resolve) => setTimeout(resolve, delayMs));
        payment = await firstValueFrom(api.payment(id));
      }
      return payment;
    },
  })),
);
