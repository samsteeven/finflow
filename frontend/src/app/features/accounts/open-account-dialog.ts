import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MatDialogActions, MatDialogContent, MatDialogRef, MatDialogTitle } from '@angular/material/dialog';
import { MatFormField, MatLabel, MatError } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatOption, MatSelect } from '@angular/material/select';
import { OpenAccount } from '../../core/api/models';
import { messageOf } from '../../core/util/problem';
import { parseEuros } from '../../core/util/money';
import { AccountsStore } from './accounts.store';

@Component({
  selector: 'ff-open-account-dialog',
  imports: [
    ReactiveFormsModule,
    MatButton,
    MatDialogActions,
    MatDialogContent,
    MatDialogTitle,
    MatFormField,
    MatLabel,
    MatError,
    MatInput,
    MatSelect,
    MatOption,
  ],
  template: `
    <h2 mat-dialog-title>Ouvrir un compte</h2>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="form">
        <mat-form-field>
          <mat-label>Nom du compte</mat-label>
          <input matInput formControlName="name" maxlength="120" />
          @if (form.controls.name.hasError('required')) {
            <mat-error>Le nom est obligatoire</mat-error>
          }
        </mat-form-field>
        <mat-form-field>
          <mat-label>Type</mat-label>
          <mat-select formControlName="type">
            <mat-option value="CURRENT">Compte courant</mat-option>
            <mat-option value="SAVINGS">Épargne</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Plafond par virement (€)</mat-label>
          <input matInput formControlName="perTransaction" inputmode="decimal" />
          @if (form.controls.perTransaction.invalid) {
            <mat-error>Montant invalide (ex. 1500 ou 1500,50)</mat-error>
          }
        </mat-form-field>
        <mat-form-field>
          <mat-label>Plafond journalier (€)</mat-label>
          <input matInput formControlName="daily" inputmode="decimal" />
          @if (form.controls.daily.invalid) {
            <mat-error>Montant invalide (ex. 5000)</mat-error>
          }
        </mat-form-field>
        @if (error()) {
          <p class="error" role="alert">{{ error() }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" (click)="ref.close()">Annuler</button>
        <button mat-flat-button type="submit" [disabled]="form.invalid || busy()">Ouvrir</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .form {
      display: grid;
      gap: 4px;
      min-width: 340px;
    }
    .error {
      color: var(--mat-sys-error);
    }
  `,
})
export class OpenAccountDialog {
  protected readonly ref = inject(MatDialogRef<OpenAccountDialog>);
  private readonly store = inject(AccountsStore);
  private readonly fb = inject(FormBuilder);
  protected readonly busy = signal(false);
  protected readonly error = signal('');

  private readonly amount = (control: { value: string }) =>
    parseEuros(control.value ?? '') === null ? { amount: true } : null;

  protected readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    type: ['CURRENT' as OpenAccount['type']],
    perTransaction: ['1000', [this.amount]],
    daily: ['5000', [this.amount]],
  });

  protected async submit(): Promise<void> {
    if (this.form.invalid) {
      return;
    }
    const value = this.form.getRawValue();
    this.busy.set(true);
    this.error.set('');
    try {
      const account = await this.store.open({
        name: value.name.trim(),
        type: value.type,
        perTransactionLimitCents: parseEuros(value.perTransaction)!,
        dailyLimitCents: parseEuros(value.daily)!,
      });
      this.ref.close(account);
    } catch (e) {
      this.error.set(messageOf(e));
    } finally {
      this.busy.set(false);
    }
  }
}
