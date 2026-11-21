const euro = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' });

/** Affichage d'un montant que le serveur renvoie en centimes. */
export function formatCents(cents: number): string {
  return euro.format(cents / 100);
}

/**
 * Saisie d'un montant en euros ("12", "12,5", "12.50") vers des centimes entiers.
 * Renvoie null pour toute saisie invalide, sans passer par un nombre à virgule.
 */
export function parseEuros(input: string): number | null {
  const match = /^(\d{1,9})(?:[.,](\d{1,2}))?$/.exec(input.trim());
  if (!match) {
    return null;
  }
  const cents = Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'));
  return cents > 0 ? cents : null;
}
