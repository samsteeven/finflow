import { DatePipe } from '@angular/common';
import { Component, inject, OnInit } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatProgressBar } from '@angular/material/progress-bar';
import { MatOption, MatSelect, MatSelectChange } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { Router, RouterLink } from '@angular/router';
import { PaymentStatus } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { EurosPipe, PAYMENT_STATUS_LABELS, PaymentStatusPipe } from '../../shared/labels';
import { AccountsStore } from '../accounts/accounts.store';
import { NewPaymentDialog } from './new-payment-dialog';
import { PaymentsStore } from './payments.store';

@Component({
  selector: 'ff-payments-page',
  imports: [
    MatTableModule,
    MatPaginator,
    MatButton,
    MatProgressBar,
    MatFormField,
    MatLabel,
    MatSelect,
    MatOption,
    RouterLink,
    DatePipe,
    EurosPipe,
    PaymentStatusPipe,
  ],
  template: `
    <header class="head">
      <h1>Paiements</h1>
      <button mat-flat-button data-testid="new-payment" (click)="create()">Nouveau virement</button>
    </header>

    <mat-form-field class="filter">
      <mat-label>Statut</mat-label>
      <mat-select [value]="store.status()" (selectionChange)="filter($event)" data-testid="status-filter">
        <mat-option [value]="null">Tous</mat-option>
        @for (s of statuses; track s) {
          <mat-option [value]="s">{{ s | paymentStatus }}</mat-option>
        }
      </mat-select>
    </mat-form-field>

    @if (store.loading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    @if (store.error()) {
      <p class="error" role="alert">{{ store.error() }}</p>
    }

    <table mat-table [dataSource]="store.payments()" data-testid="payments-table">
      <ng-container matColumnDef="createdAt">
        <th mat-header-cell *matHeaderCellDef>Date</th>
        <td mat-cell *matCellDef="let p">{{ p.createdAt | date: 'dd/MM/yyyy HH:mm:ss' }}</td>
      </ng-container>
      <ng-container matColumnDef="accounts">
        <th mat-header-cell *matHeaderCellDef>Comptes</th>
        <td mat-cell *matCellDef="let p">{{ accountName(p.sourceAccountId) }} → {{ accountName(p.destinationAccountId) }}</td>
      </ng-container>
      <ng-container matColumnDef="amount">
        <th mat-header-cell *matHeaderCellDef class="num">Montant</th>
        <td mat-cell *matCellDef="let p" class="num">{{ p.amountCents | euros }}</td>
      </ng-container>
      <ng-container matColumnDef="status">
        <th mat-header-cell *matHeaderCellDef>Statut</th>
        <td mat-cell *matCellDef="let p">
          <span [class]="'chip ' + p.status.toLowerCase()">{{ p.status | paymentStatus }}</span>
        </td>
      </ng-container>
      <ng-container matColumnDef="detail">
        <th mat-header-cell *matHeaderCellDef></th>
        <td mat-cell *matCellDef="let p">
          <a [routerLink]="['/payments', p.id]" data-testid="payment-link">Détail</a>
        </td>
      </ng-container>
      <tr mat-header-row *matHeaderRowDef="columns"></tr>
      <tr mat-row *matRowDef="let row; columns: columns"></tr>
      <tr class="mat-row" *matNoDataRow>
        <td class="mat-cell empty" [attr.colspan]="columns.length">
          @if (!store.loading()) {
            Aucun paiement.
          }
        </td>
      </tr>
    </table>
    <mat-paginator
      [length]="store.total()"
      [pageSize]="store.size()"
      [pageIndex]="store.page()"
      [pageSizeOptions]="[10, 20, 50]"
      (page)="changePage($event)"
    />
  `,
  styleUrl: '../../shared/page.scss',
  styles: `
    .filter {
      width: 240px;
    }
  `,
})
export class PaymentsPage implements OnInit {
  protected readonly store = inject(PaymentsStore);
  private readonly accounts = inject(AccountsStore);
  protected readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);

  protected readonly columns = ['createdAt', 'accounts', 'amount', 'status', 'detail'];
  protected readonly statuses = Object.keys(PAYMENT_STATUS_LABELS) as PaymentStatus[];

  ngOnInit(): void {
    void this.store.load(null, 0);
    void this.accounts.load(0, 100);
  }

  protected accountName(id: string): string {
    return this.accounts.accounts().find((a) => a.id === id)?.name ?? id.slice(0, 8);
  }

  protected filter(event: MatSelectChange): void {
    void this.store.load(event.value as PaymentStatus | null, 0);
  }

  protected changePage(event: PageEvent): void {
    void this.store.load(this.store.status(), event.pageIndex, event.pageSize);
  }

  protected create(): void {
    this.dialog
      .open(NewPaymentDialog)
      .afterClosed()
      .subscribe((payment?: { id: string }) => payment && void this.router.navigate(['/payments', payment.id]));
  }
}
