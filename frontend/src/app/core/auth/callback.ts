import { Component, inject, signal } from '@angular/core';
import { MatProgressSpinner } from '@angular/material/progress-spinner';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';

@Component({
  selector: 'ff-callback',
  imports: [MatProgressSpinner],
  template: `
    @if (error()) {
      <p class="error" role="alert">La connexion a échoué : {{ error() }}</p>
    } @else {
      <mat-spinner diameter="40" />
    }
  `,
  styles: `
    :host {
      display: grid;
      place-items: center;
      padding: 64px;
    }
    .error {
      color: var(--mat-sys-error);
    }
  `,
})
export class Callback {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly error = signal('');

  constructor() {
    this.auth
      .completeLogin()
      .then((target) => this.router.navigateByUrl(target, { replaceUrl: true }))
      .catch((e: unknown) => this.error.set(e instanceof Error ? e.message : 'réponse invalide'));
  }
}
