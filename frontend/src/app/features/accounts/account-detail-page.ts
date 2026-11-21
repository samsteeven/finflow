import { DatePipe } from '@angular/common';
import { Component, inject, input, OnInit, signal } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressBar } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { FinflowApi } from '../../core/api/finflow-api';
import { Account, AccountActivity, Entry } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { messageOf } from '../../core/util/problem';
import {
  ACCOUNT_STATUS_LABELS,
  ACCOUNT_TYPE_LABELS,
  ENTRY_KIND_LABELS,
  EurosPipe,
  PaymentStatusPipe,
} from '../../shared/labels';
import { DepositDialog, LimitsDialog } from './account-dialogs';

@Component({
  selector: 'ff-account-detail-page',
  imports: [MatButton, MatProgressBar, MatTableModule, RouterLink, DatePipe, EurosPipe, PaymentStatusPipe],
  template: `
    <a routerLink="/accounts" class="muted">← Comptes</a>
    @if (loading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    @if (error()) {
      <p class="error" role="alert" data-testid="account-error">{{ error() }}</p>
    }

    @if (account(); as a) {
      <header class="head">
        <h1 data-testid="account-name">{{ a.name }}</h1>
        <span [class]="'chip ' + a.status.toLowerCase()" data-testid="account-status">
          {{ statuses[a.status] }}
        </span>
      </header>
      <p class="muted">{{ types[a.type] }} · {{ a.currency }}</p>

      <section class="cards">
        <div class="card">
          <div class="label">Solde (Ledger)</div>
          <div class="value" data-testid="ledger-balance">{{ balance() | euros }}</div>
          <div class="hint">Source de vérité comptable</div>
        </div>
        <div class="card">
          <div class="label">Solde projeté (Kafka)</div>
          <div class="value" data-testid="projected-balance">{{ activity()?.projectedBalanceCents | euros }}</div>
          <div class="hint">Projection asynchrone, peut avoir un léger retard</div>
        </div>
        <div class="card">
          <div class="label">Plafonds</div>
          <div class="value">{{ a.limits.perTransactionCents | euros }}</div>
          <div class="hint">par virement, {{ a.limits.dailyCents | euros }} par jour</div>
        </div>
      </section>

      @if (auth.isAdmin()) {
        <div class="actions">
          @if (a.status === 'ACTIVE') {
            <button mat-flat-button (click)="deposit(a)" data-testid="deposit">Déposer</button>
          }
          @if (a.status !== 'CLOSED') {
            <button mat-stroked-button (click)="limits(a)" data-testid="limits">Plafonds</button>
          }
          @if (a.status === 'ACTIVE') {
            <button mat-stroked-button (click)="transition(a, 'freeze')" data-testid="freeze">Geler</button>
          }
          @if (a.status === 'FROZEN') {
            <button mat-stroked-button (click)="transition(a, 'unfreeze')" data-testid="unfreeze">Dégeler</button>
          }
          @if (a.status !== 'CLOSED') {
            <button mat-stroked-button (click)="transition(a, 'close')" data-testid="close">Clôturer</button>
          }
        </div>
      }

      <h2>Journal comptable</h2>
      <table mat-table [dataSource]="entries()" data-testid="entries-table">
        <ng-container matColumnDef="postedAt">
          <th mat-header-cell *matHeaderCellDef>Date</th>
          <td mat-cell *matCellDef="let e">{{ e.postedAt | date: 'dd/MM/yyyy HH:mm:ss' }}</td>
        </ng-container>
        <ng-container matColumnDef="kind">
          <th mat-header-cell *matHeaderCellDef>Opération</th>
          <td mat-cell *matCellDef="let e">{{ kinds[e.kind] ?? e.kind }}</td>
        </ng-container>
        <ng-container matColumnDef="side">
          <th mat-header-cell *matHeaderCellDef>Sens</th>
          <td mat-cell *matCellDef="let e">{{ e.side === 'CREDIT' ? 'Crédit' : 'Débit' }}</td>
        </ng-container>
        <ng-container matColumnDef="amount">
          <th mat-header-cell *matHeaderCellDef class="num">Montant</th>
          <td mat-cell *matCellDef="let e" class="num">
            {{ (e.side === 'CREDIT' ? e.amountCents : -e.amountCents) | euros }}
          </td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="entryColumns"></tr>
        <tr mat-row *matRowDef="let row; columns: entryColumns"></tr>
        <tr class="mat-row" *matNoDataRow>
          <td class="mat-cell empty" [attr.colspan]="entryColumns.length">Aucune écriture pour ce compte.</td>
        </tr>
      </table>

      <h2>Paiements récents (projection)</h2>
      <table mat-table [dataSource]="activity()?.payments ?? []" data-testid="activity-table">
        <ng-container matColumnDef="payment">
          <th mat-header-cell *matHeaderCellDef>Paiement</th>
          <td mat-cell *matCellDef="let p">
            <a [routerLink]="['/payments', p.paymentId]">{{ p.paymentId.slice(0, 8) }}</a>
          </td>
        </ng-container>
        <ng-container matColumnDef="status">
          <th mat-header-cell *matHeaderCellDef>Statut</th>
          <td mat-cell *matCellDef="let p">{{ p.status | paymentStatus }}</td>
        </ng-container>
        <ng-container matColumnDef="amount">
          <th mat-header-cell *matHeaderCellDef class="num">Montant</th>
          <td mat-cell *matCellDef="let p" class="num">{{ p.amountCents | euros }}</td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="activityColumns"></tr>
        <tr mat-row *matRowDef="let row; columns: activityColumns"></tr>
        <tr class="mat-row" *matNoDataRow>
          <td class="mat-cell empty" [attr.colspan]="activityColumns.length">Aucun paiement projeté.</td>
        </tr>
      </table>
    }
  `,
  styleUrl: '../../shared/page.scss',
})
export class AccountDetailPage implements OnInit {
  readonly id = input.required<string>();

  protected readonly auth = inject(AuthService);
  private readonly api = inject(FinflowApi);
  private readonly dialog = inject(MatDialog);

  protected readonly account = signal<Account | null>(null);
  protected readonly balance = signal<number | null>(null);
  protected readonly entries = signal<Entry[]>([]);
  protected readonly activity = signal<AccountActivity | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal('');

  protected readonly types = ACCOUNT_TYPE_LABELS;
  protected readonly statuses = ACCOUNT_STATUS_LABELS;
  protected readonly kinds = ENTRY_KIND_LABELS;
  protected readonly entryColumns = ['postedAt', 'kind', 'side', 'amount'];
  protected readonly activityColumns = ['payment', 'status', 'amount'];

  ngOnInit(): void {
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      // Un compte d'une autre organisation répond 404 : on n'affiche rien d'autre que l'erreur.
      this.account.set(await firstValueFrom(this.api.account(this.id())));
      const [balance, entries, activity] = await Promise.allSettled([
        firstValueFrom(this.api.balance(this.id())),
        firstValueFrom(this.api.entries(this.id())),
        firstValueFrom(this.api.activity(this.id())),
      ]);
      this.balance.set(balance.status === 'fulfilled' ? balance.value.balanceCents : null);
      this.entries.set(entries.status === 'fulfilled' ? entries.value : []);
      this.activity.set(activity.status === 'fulfilled' ? activity.value : null);
    } catch (e) {
      this.account.set(null);
      this.error.set(messageOf(e));
    } finally {
      this.loading.set(false);
    }
  }

  protected deposit(account: Account): void {
    this.dialog
      .open(DepositDialog, { data: account })
      .afterClosed()
      .subscribe((done) => done && void this.refresh());
  }

  protected limits(account: Account): void {
    this.dialog
      .open(LimitsDialog, { data: account })
      .afterClosed()
      .subscribe((updated) => updated && void this.refresh());
  }

  protected async transition(account: Account, action: 'freeze' | 'unfreeze' | 'close'): Promise<void> {
    this.error.set('');
    try {
      await firstValueFrom(this.api.transition(account.id, action));
      await this.refresh();
    } catch (e) {
      this.error.set(messageOf(e));
    }
  }
}
