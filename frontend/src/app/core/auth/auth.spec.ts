import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { User, UserManager } from 'oidc-client-ts';
import { describe, expect, it, vi } from 'vitest';
import { config } from '../config';
import { authInterceptor } from './auth.interceptor';
import { AuthService, safeReturnUrl, USER_MANAGER } from './auth.service';

function accessToken(claims: object): string {
  const part = (o: object) => btoa(JSON.stringify(o)).replace(/=+$/, '');
  return `${part({ alg: 'none' })}.${part(claims)}.sig`;
}

function userWith(claims: object, expired = false, state?: unknown): User {
  return { access_token: accessToken(claims), expired, state } as unknown as User;
}

function fakeManager(user: User | null) {
  const handlers: Record<string, (u?: User) => void> = {};
  return {
    getUser: vi.fn().mockResolvedValue(user),
    signinRedirect: vi.fn().mockResolvedValue(undefined),
    signinRedirectCallback: vi.fn().mockResolvedValue(user),
    signoutRedirect: vi.fn().mockResolvedValue(undefined),
    events: {
      addUserLoaded: (h: (u: User) => void) => (handlers['loaded'] = h as (u?: User) => void),
      addUserUnloaded: (h: () => void) => (handlers['unloaded'] = h),
      addAccessTokenExpired: (h: () => void) => (handlers['expired'] = h),
    },
    handlers,
  };
}

function setup(manager: ReturnType<typeof fakeManager>) {
  TestBed.configureTestingModule({
    providers: [
      { provide: USER_MANAGER, useValue: manager as unknown as UserManager },
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
    ],
  });
  return TestBed.inject(AuthService);
}

describe('AuthService', () => {
  it('déduit rôle, organisation et nom du jeton', async () => {
    const auth = setup(
      fakeManager(
        userWith({ name: 'Alice Alpha', tenant_id: 'a1', realm_access: { roles: ['tenant-admin'] } }),
      ),
    );
    await auth.init();

    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.isAdmin()).toBe(true);
    expect(auth.tenantId()).toBe('a1');
    expect(auth.username()).toBe('Alice Alpha');
  });

  it('un membre n\'est pas administrateur', async () => {
    const auth = setup(fakeManager(userWith({ realm_access: { roles: ['tenant-member'] } })));
    await auth.init();

    expect(auth.isAdmin()).toBe(false);
  });

  it('un jeton expiré ne compte plus comme une session et n\'est jamais renvoyé', async () => {
    const auth = setup(fakeManager(userWith({ tenant_id: 'a1' }, true)));
    await auth.init();

    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.accessToken()).toBeNull();
  });

  it('suit le renouvellement et la fin du jeton', async () => {
    const manager = fakeManager(null);
    const auth = setup(manager);
    await auth.init();
    expect(auth.isAuthenticated()).toBe(false);

    manager.handlers['loaded'](userWith({ tenant_id: 'a1' }));
    expect(auth.isAuthenticated()).toBe(true);

    manager.handlers['expired']();
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('la connexion renvoie à la page demandée au départ', async () => {
    const auth = setup(fakeManager(userWith({}, false, { returnUrl: '/payments' })));

    await expect(auth.completeLogin()).resolves.toBe('/payments');
  });
});

describe('safeReturnUrl', () => {
  it.each([
    ['/payments/42', '/payments/42'],
    [undefined, '/'],
    ['https://evil.example', '/'],
    ['//evil.example', '/'],
    ['/\\evil.example', '/'],
    ['javascript:alert(1)', '/'],
  ])('%j devient %j', (input, expected) => {
    expect(safeReturnUrl(input)).toBe(expected);
  });
});

describe('authInterceptor', () => {
  it('ajoute le jeton aux appels de la Gateway, et à eux seuls', async () => {
    const auth = setup(fakeManager(userWith({ tenant_id: 'a1' })));
    await auth.init();
    const http = TestBed.inject(HttpClient);
    const backend = TestBed.inject(HttpTestingController);

    http.get(`${config.apiUrl}/api/v1/accounts`).subscribe();
    http.get('https://autre.example/api').subscribe();

    const interne = backend.expectOne(`${config.apiUrl}/api/v1/accounts`);
    expect(interne.request.headers.get('Authorization')).toMatch(/^Bearer .+/);
    interne.flush([]);
    const externe = backend.expectOne('https://autre.example/api');
    expect(externe.request.headers.has('Authorization')).toBe(false);
    externe.flush([]);
  });

  it('un 401 renvoie vers Keycloak en gardant la page courante', async () => {
    const manager = fakeManager(userWith({ tenant_id: 'a1' }));
    const auth = setup(manager);
    await auth.init();
    vi.spyOn(TestBed.inject(Router), 'url', 'get').mockReturnValue('/payments');
    const http = TestBed.inject(HttpClient);
    const backend = TestBed.inject(HttpTestingController);

    http.get(`${config.apiUrl}/api/v1/payments`).subscribe({ error: () => undefined });
    backend.expectOne(`${config.apiUrl}/api/v1/payments`).flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(manager.signinRedirect).toHaveBeenCalledWith({ state: { returnUrl: '/payments' } });
  });
});
