import { Component, inject, OnInit, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MatDialogActions, MatDialogContent, MatDialogRef, MatDialogTitle } from '@angular/material/dialog';
import { MatError, MatFormField, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatOption, MatSelect } from '@angular/material/select';
import { Payment } from '../../core/api/models';
import { parseEuros } from '../../core/util/money';
import { messageOf } from '../../core/util/problem';
import { AccountsStore } from '../accounts/accounts.store';
import { PaymentsStore } from './payments.store';

const amount = (control: AbstractControl<string>): ValidationErrors | null =>
  parseEuros(control.value ?? '') === null ? { amount: true } : null;

@Component({
  selector: 'ff-new-payment-dialog',
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
    <h2 mat-dialog-title>Nouveau virement</h2>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="form">
        <mat-form-field>
          <mat-label>Compte à débiter</mat-label>
          <mat-select formControlName="source" data-testid="payment-source">
            @for (a of accounts.active(); track a.id) {
              <mat-option [value]="a.id">{{ a.name }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Compte à créditer</mat-label>
          <mat-select formControlName="destination" data-testid="payment-destination">
            @for (a of accounts.active(); track a.id) {
              <mat-option [value]="a.id">{{ a.name }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        @if (form.hasError('sameAccount')) {
          <p class="error" role="alert" data-testid="same-account">Les deux comptes doivent être différents</p>
        }
        <mat-form-field>
          <mat-label>Montant (€)</mat-label>
          <input matInput formControlName="amount" inputmode="decimal" data-testid="payment-amount" />
          @if (form.controls.amount.invalid) {
            <mat-error>Montant invalide (ex. 120 ou 120,50)</mat-error>
          }
        </mat-form-field>
        <mat-form-field>
          <mat-label>Libellé (facultatif)</mat-label>
          <input matInput formControlName="description" maxlength="140" data-testid="payment-description" />
        </mat-form-field>
        @if (error()) {
          <p class="error" role="alert" data-testid="payment-error">{{ error() }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" (click)="ref.close()">Annuler</button>
        <button mat-flat-button type="submit" [disabled]="form.invalid || busy()" data-testid="payment-submit">
          Envoyer
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .form {
      display: grid;
      gap: 4px;
      min-width: 360px;
    }
    .error {
      color: var(--mat-sys-error);
    }
  `,
})
export class NewPaymentDialog implements OnInit {
  protected readonly ref = inject(MatDialogRef<NewPaymentDialog, Payment>);
  protected readonly accounts = inject(AccountsStore);
  private readonly payments = inject(PaymentsStore);
  // La même clé sert à chaque envoi de ce formulaire : réessayer après une coupure ne débite pas deux fois.
  private readonly idempotencyKey = crypto.randomUUID();
  protected readonly busy = signal(false);
  protected readonly error = signal('');

  protected readonly form = inject(FormBuilder).nonNullable.group(
    {
      source: ['', Validators.required],
      destination: ['', Validators.required],
      amount: ['', [amount]],
      description: ['', Validators.maxLength(140)],
    },
    {
      validators: (g: AbstractControl): ValidationErrors | null =>
        g.get('source')?.value && g.get('source')?.value === g.get('destination')?.value
          ? { sameAccount: true }
          : null,
    },
  );

  ngOnInit(): void {
    void this.accounts.load(0, 100);
  }

  protected async submit(): Promise<void> {
    const value = this.form.getRawValue();
    const cents = parseEuros(value.amount);
    if (this.form.invalid || cents === null) {
      return;
    }
    this.busy.set(true);
    this.error.set('');
    try {
      const created = await this.payments.initiate(this.idempotencyKey, {
        sourceAccountId: value.source,
        destinationAccountId: value.destination,
        amountCents: cents,
        description: value.description.trim() || undefined,
      });
      this.ref.close(created);
    } catch (e) {
      this.error.set(messageOf(e));
    } finally {
      this.busy.set(false);
    }
  }
}
