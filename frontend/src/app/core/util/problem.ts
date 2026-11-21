import { HttpErrorResponse } from '@angular/common/http';

/** Message lisible pour un utilisateur, tiré d'une réponse d'erreur RFC 9457 des services. */
export function messageOf(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'Le serveur est injoignable.';
    }
    const body = error.error as { title?: string; detail?: string } | null;
    if (body?.detail) {
      return body.title ? `${body.title} : ${body.detail}` : body.detail;
    }
    if (error.status === 403) {
      return "Vous n'avez pas le droit d'effectuer cette action.";
    }
    return `Erreur ${error.status}`;
  }
  return error instanceof Error ? error.message : 'Erreur inattendue';
}
