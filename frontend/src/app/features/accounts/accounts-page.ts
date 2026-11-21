import { Component, computed, inject, OnInit } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatProgressBar } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { ACCOUNT_STATUS_LABELS, ACCOUNT_TYPE_LABELS, EurosPipe } from '../../shared/labels';
import { AccountsStore } from './accounts.store';
import { OpenAccountDialog } from './open-account-dialog';

@Component({
  selector: 'ff-accounts-page',
  imports: [MatTableModule, MatPaginator, MatButton, MatProgressBar, RouterLink, EurosPipe],
  template: `
    <header class="head">
      <h1>Comptes</h1>
      @if (auth.isAdmin()) {
        <button mat-flat-button data-testid="open-account" (click)="open()">Ouvrir un compte</button>
      }
    </header>

    @if (store.loading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    @if (store.error()) {
      <p class="error" role="alert">{{ store.error() }}</p>
    }

    <table mat-table [dataSource]="store.accounts()" data-testid="accounts-table">
      <ng-container matColumnDef="name">
        <th mat-header-cell *matHeaderCellDef>Compte</th>
        <td mat-cell *matCellDef="let a">
          <a [routerLink]="['/accounts', a.id]">{{ a.name }}</a>
        </td>
      </ng-container>
      <ng-container matColumnDef="type">
        <th mat-header-cell *matHeaderCellDef>Type</th>
        <td mat-cell *matCellDef="let a">{{ types[a.type] }}</td>
      </ng-container>
      <ng-container matColumnDef="status">
        <th mat-header-cell *matHeaderCellDef>Statut</th>
        <td mat-cell *matCellDef="let a">
          <span class="chip" [class]="'chip ' + a.status.toLowerCase()">{{ statuses[a.status] }}</span>
        </td>
      </ng-container>
      <ng-container matColumnDef="limits">
        <th mat-header-cell *matHeaderCellDef>Plafonds (virement / jour)</th>
        <td mat-cell *matCellDef="let a">
          {{ a.limits.perTransactionCents | euros }} / {{ a.limits.dailyCents | euros }}
        </td>
      </ng-container>
      <ng-container matColumnDef="balance">
        <th mat-header-cell *matHeaderCellDef class="num">Solde</th>
        <td mat-cell *matCellDef="let a" class="num" [attr.data-testid]="'balance-' + a.name">
          {{ store.balances()[a.id] | euros }}
        </td>
      </ng-container>
      <tr mat-header-row *matHeaderRowDef="columns"></tr>
      <tr mat-row *matRowDef="let row; columns: columns"></tr>
      <tr class="mat-row" *matNoDataRow>
        <td class="mat-cell empty" [attr.colspan]="columns.length">
          @if (!store.loading()) {
            Aucun compte dans cette organisation.
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
})
export class AccountsPage implements OnInit {
  protected readonly store = inject(AccountsStore);
  protected readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);

  protected readonly columns = ['name', 'type', 'status', 'limits', 'balance'];
  protected readonly types = ACCOUNT_TYPE_LABELS;
  protected readonly statuses = ACCOUNT_STATUS_LABELS;
  protected readonly hasAccounts = computed(() => this.store.accounts().length > 0);

  ngOnInit(): void {
    void this.store.load(0);
  }

  protected open(): void {
    this.dialog.open(OpenAccountDialog);
  }

  protected changePage(event: PageEvent): void {
    void this.store.load(event.pageIndex, event.pageSize);
  }
}
