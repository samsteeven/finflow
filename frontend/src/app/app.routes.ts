import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { Callback } from './core/auth/callback';

export const routes: Routes = [
  { path: 'callback', component: Callback },
  {
    path: 'accounts',
    canActivate: [authGuard],
    loadComponent: () => import('./features/accounts/accounts-page').then((m) => m.AccountsPage),
  },
  {
    path: 'accounts/:id',
    canActivate: [authGuard],
    loadComponent: () => import('./features/accounts/account-detail-page').then((m) => m.AccountDetailPage),
  },
  {
    path: 'payments',
    canActivate: [authGuard],
    loadComponent: () => import('./features/payments/payments-page').then((m) => m.PaymentsPage),
  },
  {
    path: 'payments/:id',
    canActivate: [authGuard],
    loadComponent: () => import('./features/payments/payment-detail-page').then((m) => m.PaymentDetailPage),
  },
  { path: '', pathMatch: 'full', redirectTo: 'accounts' },
  { path: '**', redirectTo: 'accounts' },
];
