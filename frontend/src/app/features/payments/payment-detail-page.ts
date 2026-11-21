import { DatePipe, JsonPipe } from '@angular/common';
import { Component, inject, input, OnInit, signal } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatProgressBar } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { FinflowApi } from '../../core/api/finflow-api';
import { Payment, PaymentEvent } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { messageOf } from '../../core/util/problem';
import { EurosPipe, PaymentStatusPipe } from '../../shared/labels';
import { PaymentsStore } from './payments.store';

const EVENT_LABELS: Record<string, string> = {
  PaymentCreated: 'Paiement initié',
  PaymentValidationStarted: 'Validation lancée',
  PaymentValidated: 'Validé (compte, plafonds)',
  PaymentRejected: 'Refusé',
  PaymentCompleted: 'Comptabilisé au Ledger',
  PaymentFailed: 'Échec technique',
  PaymentRetried: 'Reprise de la saga',
  RefundRequested: 'Remboursement demandé',
  RefundRejected: 'Remboursement refusé',
  RefundCompleted: 'Remboursement comptabilisé',
};

@Component({
  selector: 'ff-payment-detail-page',
  imports: [MatButton, MatProgressBar, RouterLink, DatePipe, JsonPipe, EurosPipe, PaymentStatusPipe],
  template: `
    <a routerLink="/payments" class="muted">← Paiements</a>
    @if (loading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    @if (error()) {
      <p class="error" role="alert" data-testid="payment-error">{{ error() }}</p>
    }

    @if (payment(); as p) {
      <header class="head">
        <h1>Paiement {{ p.id.slice(0, 8) }}</h1>
        <span [class]="'chip ' + p.status.toLowerCase()" data-testid="payment-status">{{ p.status | paymentStatus }}</span>
      </header>

      <section class="cards">
        <div class="card">
          <div class="label">Montant</div>
          <div class="value" data-testid="payment-amount-value">{{ p.amountCents | euros }}</div>
          <div class="hint">{{ p.description ?? 'Sans libellé' }}</div>
        </div>
        <div class="card">
          <div class="label">Débit</div>
          <div class="value"><a [routerLink]="['/accounts', p.sourceAccountId]">{{ p.sourceAccountId.slice(0, 8) }}</a></div>
        </div>
        <div class="card">
          <div class="label">Crédit</div>
          <div class="value"><a [routerLink]="['/accounts', p.destinationAccountId]">{{ p.destinationAccountId.slice(0, 8) }}</a></div>
        </div>
      </section>

      @if (p.rejection) {
        <p class="error" data-testid="payment-rejection">Motif du refus ({{ p.rejection.rule }}) : {{ p.rejection.detail }}</p>
      }
      @if (p.refundRejection) {
        <p class="error">Remboursement refusé : {{ p.refundRejection }}</p>
      }

      @if (auth.isAdmin() && p.status === 'COMPLETED') {
        <div class="actions">
          <button mat-stroked-button (click)="refund(p)" [disabled]="busy()" data-testid="refund">Rembourser</button>
        </div>
      }

      <h2>Historique (journal d'événements)</h2>
      <ol class="timeline" data-testid="timeline">
        @for (e of events(); track e.eventId) {
          <li>
            <div class="when">{{ e.occurredAt | date: 'HH:mm:ss.SSS' }}</div>
            <div>
              <strong>{{ label(e) }}</strong>
              <div class="muted small">
                v{{ e.version }} · corrélation {{ e.correlationId?.slice(0, 8) ?? '—' }}
                @if (e.causationId) {
                  · cause {{ e.causationId.slice(0, 8) }}
                }
              </div>
              <details>
                <summary>Contenu</summary>
                <pre>{{ e.payload | json }}</pre>
              </details>
            </div>
          </li>
        }
      </ol>
    }
  `,
  styleUrl: '../../shared/page.scss',
  styles: `
    .timeline {
      list-style: none;
      margin: 0;
      padding: 0;
      border-left: 2px solid var(--mat-sys-outline-variant);
    }
    .timeline li {
      display: flex;
      gap: 16px;
      padding: 8px 0 8px 16px;
    }
    .when {
      width: 96px;
      font-variant-numeric: tabular-nums;
      color: var(--mat-sys-on-surface-variant);
    }
    .small {
      font: var(--mat-sys-body-small);
    }
    pre {
      margin: 4px 0 0;
      font-size: 12px;
      overflow: auto;
    }
  `,
})
export class PaymentDetailPage implements OnInit {
  readonly id = input.required<string>();

  protected readonly auth = inject(AuthService);
  private readonly api = inject(FinflowApi);
  private readonly store = inject(PaymentsStore);

  protected readonly payment = signal<Payment | null>(null);
  protected readonly events = signal<PaymentEvent[]>([]);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal('');

  ngOnInit(): void {
    void this.refresh();
  }

  protected label(event: PaymentEvent): string {
    return EVENT_LABELS[event.eventType] ?? event.eventType;
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      // Si la saga tourne encore, on attend un statut définitif avant d'afficher.
      this.payment.set(await this.store.settle(this.id()));
      this.events.set(await firstValueFrom(this.api.paymentEvents(this.id())));
    } catch (e) {
      this.payment.set(null);
      this.error.set(messageOf(e));
    } finally {
      this.loading.set(false);
    }
  }

  protected async refund(payment: Payment): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      await firstValueFrom(this.api.refund(payment.id, 'Remboursement demandé depuis le back-office'));
      await this.refresh();
    } catch (e) {
      this.error.set(messageOf(e));
    } finally {
      this.busy.set(false);
    }
  }
}
