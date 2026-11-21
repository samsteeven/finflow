import { Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import { User, UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { config } from '../config';
import { decodeClaims } from '../util/jwt';

/** Gestionnaire OIDC injectable, remplaçable dans les tests. */
export const USER_MANAGER = new InjectionToken<UserManager>('USER_MANAGER', {
  providedIn: 'root',
  factory: () =>
    new UserManager({
      authority: config.issuer,
      client_id: config.clientId,
      redirect_uri: `${window.location.origin}/callback`,
      post_logout_redirect_uri: `${window.location.origin}/`,
      response_type: 'code', // code d'autorisation ; PKCE S256 appliqué par la bibliothèque
      scope: 'openid profile',
      // La session ne survit pas à l'onglet : jeton en sessionStorage, jamais en localStorage.
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      automaticSilentRenew: true,
    }),
});

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly manager = inject(USER_MANAGER);
  private readonly current = signal<User | null>(null);

  readonly isAuthenticated = computed(() => {
    const user = this.current();
    return user !== null && !user.expired;
  });
  private readonly claims = computed(() => decodeClaims(this.current()?.access_token));
  readonly roles = computed(() => this.claims().realm_access?.roles ?? []);
  readonly isAdmin = computed(() => this.roles().includes('tenant-admin'));
  readonly username = computed(() => this.claims().name ?? this.claims().preferred_username ?? '');
  readonly tenantId = computed(() => this.claims().tenant_id ?? null);

  /** Appelé au démarrage : relit la session de l'onglet, et suit les renouvellements du jeton. */
  async init(): Promise<void> {
    this.manager.events.addUserLoaded((user) => this.current.set(user));
    this.manager.events.addUserUnloaded(() => this.current.set(null));
    this.manager.events.addAccessTokenExpired(() => this.current.set(null));
    this.current.set(await this.manager.getUser());
  }

  accessToken(): string | null {
    return this.isAuthenticated() ? (this.current()?.access_token ?? null) : null;
  }

  login(returnUrl: string): Promise<void> {
    return this.manager.signinRedirect({ state: { returnUrl } });
  }

  /** Termine la connexion au retour de Keycloak ; renvoie la page demandée au départ. */
  async completeLogin(): Promise<string> {
    const user = await this.manager.signinRedirectCallback();
    this.current.set(user);
    const state = user.state as { returnUrl?: string } | undefined;
    return safeReturnUrl(state?.returnUrl);
  }

  logout(): Promise<void> {
    return this.manager.signoutRedirect();
  }
}

/** Seule une page de l'application est acceptée comme destination : jamais une adresse externe. */
export function safeReturnUrl(url: string | undefined): string {
  return url && url.startsWith('/') && !url.startsWith('//') && !url.startsWith('/\\') ? url : '/';
}
