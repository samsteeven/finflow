import { Pipe, PipeTransform } from '@angular/core';
import { PaymentStatus } from '../core/api/models';
import { formatCents } from '../core/util/money';

export const PAYMENT_STATUS_LABELS: Record<PaymentStatus, string> = {
  CREATED: 'Créé',
  VALIDATING: 'En validation',
  REJECTED: 'Refusé',
  PROCESSING: 'En cours',
  COMPLETED: 'Terminé',
  FAILED: 'Échec technique',
  REFUND_PENDING: 'Remboursement en cours',
  REFUNDED: 'Remboursé',
};

export const ACCOUNT_STATUS_LABELS: Record<string, string> = {
  ACTIVE: 'Actif',
  FROZEN: 'Gelé',
  CLOSED: 'Clôturé',
};

export const ACCOUNT_TYPE_LABELS: Record<string, string> = {
  CURRENT: 'Compte courant',
  SAVINGS: 'Épargne',
};

export const ENTRY_KIND_LABELS: Record<string, string> = {
  TRANSFER: 'Virement',
  DEPOSIT: 'Dépôt',
  REVERSAL: 'Contrepassation',
};

@Pipe({ name: 'euros' })
export class EurosPipe implements PipeTransform {
  transform(cents: number | null | undefined): string {
    return cents === null || cents === undefined ? '—' : formatCents(cents);
  }
}

@Pipe({ name: 'paymentStatus' })
export class PaymentStatusPipe implements PipeTransform {
  transform(status: PaymentStatus): string {
    return PAYMENT_STATUS_LABELS[status] ?? status;
  }
}
