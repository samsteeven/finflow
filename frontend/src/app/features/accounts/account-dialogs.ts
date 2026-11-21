import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialogActions,
  MatDialogContent,
  MatDialogRef,
  MatDialogTitle,
} from '@angular/material/dialog';
import { MatError, MatFormField, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { FinflowApi } from '../../core/api/finflow-api';
import { Account } from '../../core/api/models';
import { parseEuros } from '../../core/util/money';
import { messageOf } from '../../core/util/problem';

const amount = (control: AbstractControl<string>): ValidationErrors | null =>
  parseEuros(control.value ?? '') === null ? { amount: true } : null;

const SHARED_STYLES = `
  .form { display: grid; gap: 4px; min-width: 320px; }
  .error { color: var(--mat-sys-error); }
`;

const IMPORTS = [
  ReactiveFormsModule,
  MatButton,
  MatDialogActions,
  MatDialogContent,
  MatDialogTitle,
  MatFormField,
  MatLabel,
  MatError,
  MatInput,
];

/** Dépôt simulé sur un compte : alimente le Ledger depuis le compte de compensation. */
@Component({
  selector: 'ff-deposit-dialog',
  imports: IMPORTS,
  template: `
    <h2 mat-dialog-title>Déposer sur « {{ data.name }} »</h2>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="form">
        <mat-form-field>
          <mat-label>Montant (€)</mat-label>
          <input matInput formControlName="amount" inputmode="decimal" data-testid="deposit-amount" />
          @if (form.controls.amount.invalid) {
            <mat-error>Montant invalide (ex. 250 ou 250,50)</mat-error>
          }
        </mat-form-field>
        @if (error()) {
          <p class="error" role="alert">{{ error() }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" (click)="ref.close()">Annuler</button>
        <button mat-flat-button type="submit" [disabled]="form.invalid || busy()" data-testid="deposit-submit">
          Déposer
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: SHARED_STYLES,
})
export class DepositDialog {
  protected readonly data = inject<Account>(MAT_DIALOG_DATA);
  protected readonly ref = inject(MatDialogRef<DepositDialog, boolean>);
  private readonly api = inject(FinflowApi);
  // Une clé par ouverture du dialogue : un double clic ne crédite pas deux fois.
  private readonly idempotencyKey = crypto.randomUUID();
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly form = inject(FormBuilder).nonNullable.group({ amount: ['', [amount]] });

  protected async submit(): Promise<void> {
    const cents = parseEuros(this.form.getRawValue().amount);
    if (cents === null) {
      return;
    }
    this.busy.set(true);
    this.error.set('');
    try {
      await new Promise<void>((resolve, reject) =>
        this.api.deposit(this.idempotencyKey, this.data.id, cents, 'Dépôt').subscribe({
          next: () => resolve(),
          error: reject,
        }),
      );
      this.ref.close(true);
    } catch (e) {
      this.error.set(messageOf(e));
    } finally {
      this.busy.set(false);
    }
  }
}

@Component({
  selector: 'ff-limits-dialog',
  imports: IMPORTS,
  template: `
    <h2 mat-dialog-title>Plafonds de « {{ data.name }} »</h2>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="form">
        <mat-form-field>
          <mat-label>Plafond par virement (€)</mat-label>
          <input matInput formControlName="perTransaction" inputmode="decimal" data-testid="limit-per-transaction" />
          @if (form.controls.perTransaction.invalid) {
            <mat-error>Montant invalide</mat-error>
          }
        </mat-form-field>
        <mat-form-field>
          <mat-label>Plafond journalier (€)</mat-label>
          <input matInput formControlName="daily" inputmode="decimal" data-testid="limit-daily" />
          @if (form.controls.daily.invalid) {
            <mat-error>Montant invalide</mat-error>
          }
        </mat-form-field>
        @if (error()) {
          <p class="error" role="alert">{{ error() }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" (click)="ref.close()">Annuler</button>
        <button mat-flat-button type="submit" [disabled]="form.invalid || busy()" data-testid="limits-submit">
          Enregistrer
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: SHARED_STYLES,
})
export class LimitsDialog {
  protected readonly data = inject<Account>(MAT_DIALOG_DATA);
  protected readonly ref = inject(MatDialogRef<LimitsDialog, Account>);
  private readonly api = inject(FinflowApi);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly form = inject(FormBuilder).nonNullable.group({
    perTransaction: [String(this.data.limits.perTransactionCents / 100), [amount]],
    daily: [String(this.data.limits.dailyCents / 100), [amount]],
  });

  protected async submit(): Promise<void> {
    const { perTransaction, daily } = this.form.getRawValue();
    const perTransactionCents = parseEuros(perTransaction);
    const dailyCents = parseEuros(daily);
    if (perTransactionCents === null || dailyCents === null) {
      return;
    }
    this.busy.set(true);
    this.error.set('');
    try {
      const account = await new Promise<Account>((resolve, reject) =>
        this.api.changeLimits(this.data.id, perTransactionCents, dailyCents).subscribe({ next: resolve, error: reject }),
      );
      this.ref.close(account);
    } catch (e) {
      this.error.set(messageOf(e));
    } finally {
      this.busy.set(false);
    }
  }
}
