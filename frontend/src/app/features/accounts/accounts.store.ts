import { computed, inject } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { firstValueFrom } from 'rxjs';
import { FinflowApi } from '../../core/api/finflow-api';
import { Account, OpenAccount } from '../../core/api/models';
import { messageOf } from '../../core/util/problem';

interface AccountsState {
  accounts: Account[];
  /** Soldes lus dans le Ledger, jamais calculés par le front. */
  balances: Record<string, number>;
  page: number;
  size: number;
  total: number;
  loading: boolean;
  error: string | null;
}

const initial: AccountsState = { accounts: [], balances: {}, page: 0, size: 10, total: 0, loading: false, error: null };

export const AccountsStore = signalStore(
  { providedIn: 'root' },
  withState(initial),
  withComputed(({ accounts }) => ({
    /** Comptes sur lesquels on peut encore émettre ou recevoir un virement. */
    active: computed(() => accounts().filter((a) => a.status === 'ACTIVE')),
  })),
  withMethods((store, api = inject(FinflowApi)) => ({
    async load(page = store.page(), size = store.size()): Promise<void> {
      patchState(store, { loading: true, error: null });
      try {
        const result = await firstValueFrom(api.accounts(page, size));
        patchState(store, { accounts: result.content, page: result.page, size: result.size, total: result.totalElements });
        const balances = await Promise.all(
          result.content.map(async (a) => {
            try {
              return [a.id, (await firstValueFrom(api.balance(a.id))).balanceCents] as const;
            } catch {
              return null; // un solde illisible n'empêche pas d'afficher la liste
            }
          }),
        );
        patchState(store, {
          balances: Object.fromEntries(balances.filter((b): b is readonly [string, number] => b !== null)),
        });
      } catch (e) {
        patchState(store, { error: messageOf(e) });
      } finally {
        patchState(store, { loading: false });
      }
    },

    async open(request: OpenAccount): Promise<Account> {
      const account = await firstValueFrom(api.openAccount(request));
      await this.load(0);
      return account;
    },
  })),
);
