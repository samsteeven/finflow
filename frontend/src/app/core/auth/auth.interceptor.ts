import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { config } from '../config';
import { AuthService } from './auth.service';

/** Ajoute le jeton aux seuls appels de la Gateway ; un 401 renvoie vers Keycloak. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith(config.apiUrl)) {
    return next(request);
  }
  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.accessToken();
  const authorized = token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request;
  return next(authorized).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        void auth.login(router.url);
      }
      return throwError(() => error);
    }),
  );
};
