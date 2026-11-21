export type AccountType = 'CURRENT' | 'SAVINGS';
export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED';

export interface Account {
  id: string;
  name: string;
  type: AccountType;
  currency: string;
  status: AccountStatus;
  limits: { perTransactionCents: number; dailyCents: number };
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
}

export interface OpenAccount {
  name: string;
  type: AccountType;
  perTransactionLimitCents: number;
  dailyLimitCents: number;
}

export interface Balance {
  accountId: string;
  currency: string;
  balanceCents: number;
}

export interface Entry {
  transactionId: string;
  kind: 'TRANSFER' | 'DEPOSIT' | 'REVERSAL';
  side: 'DEBIT' | 'CREDIT';
  amountCents: number;
  postedAt: string;
}

export type PaymentStatus =
  | 'CREATED'
  | 'VALIDATING'
  | 'REJECTED'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'FAILED'
  | 'REFUND_PENDING'
  | 'REFUNDED';

export interface PaymentSummary {
  id: string;
  sourceAccountId: string;
  destinationAccountId: string;
  amountCents: number;
  currency: string;
  status: PaymentStatus;
  createdAt: string;
  updatedAt: string;
}

export interface Payment extends PaymentSummary {
  description: string | null;
  rejection: { rule: string; detail: string } | null;
  ledgerTransactionId: string | null;
  refundLedgerTransactionId: string | null;
  refundRejection: string | null;
  version: number;
}

export interface NewPayment {
  sourceAccountId: string;
  destinationAccountId: string;
  amountCents: number;
  description?: string;
}

export interface PaymentEvent {
  version: number;
  eventId: string;
  eventType: string;
  correlationId: string | null;
  causationId: string | null;
  occurredAt: string;
  payload: Record<string, unknown>;
}

export interface PaymentActivity {
  paymentId: string;
  sourceAccountId: string;
  destinationAccountId: string;
  amountCents: number;
  status: PaymentStatus;
  aggregateVersion: number;
  updatedAt: string;
}

export interface AccountActivity {
  accountId: string;
  projectedBalanceCents: number;
  payments: PaymentActivity[];
}

export interface Tenant {
  id: string;
  name: string;
  slug: string;
  status: string;
  plan: string;
}
