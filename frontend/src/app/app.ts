import { Component, effect, inject, signal } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatToolbar } from '@angular/material/toolbar';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { FinflowApi } from './core/api/finflow-api';
import { AuthService } from './core/auth/auth.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatToolbar, MatButton],
  template: `
    <mat-toolbar class="bar">
      <a routerLink="/" class="brand">FinFlow</a>
      @if (auth.isAuthenticated()) {
        <nav>
          <a mat-button routerLink="/accounts" routerLinkActive="active">Comptes</a>
          <a mat-button routerLink="/payments" routerLinkActive="active">Paiements</a>
        </nav>
        <span class="spacer"></span>
        <span class="who" data-testid="who">
          <strong data-testid="tenant-name">{{ tenantName() }}</strong>
          · <span data-testid="user-name">{{ auth.username() }}</span>
          ({{ auth.isAdmin() ? 'administrateur' : 'membre' }})
        </span>
        <button mat-button data-testid="logout" (click)="auth.logout()">Se déconnecter</button>
      }
    </mat-toolbar>
    <main>
      <router-outlet />
    </main>
  `,
  styles: `
    .bar {
      gap: 8px;
      background: var(--mat-sys-surface-container);
    }
    .brand {
      font: var(--mat-sys-title-large);
      text-decoration: none;
      color: var(--mat-sys-primary);
      margin-right: 16px;
    }
    .active {
      background: var(--mat-sys-secondary-container);
    }
    .spacer {
      flex: 1;
    }
    .who {
      font: var(--mat-sys-body-medium);
    }
  `,
})
export class App {
  protected readonly auth = inject(AuthService);
  private readonly api = inject(FinflowApi);
  protected readonly tenantName = signal('');

  constructor() {
    // Le nom de l'organisation se relit à chaque changement d'utilisateur (connexion, déconnexion).
    effect(() => {
      const tenantId = this.auth.tenantId();
      if (!tenantId || !this.auth.isAuthenticated()) {
        this.tenantName.set('');
        return;
      }
      firstValueFrom(this.api.tenant())
        .then((tenant) => this.tenantName.set(tenant.name))
        .catch(() => this.tenantName.set(''));
    });
  }
}
