#!/usr/bin/env python3
"""Panne du Ledger provoquée avec Toxiproxy : le circuit s'ouvre, aucun paiement n'est perdu ni doublé.

Prérequis : docker compose --profile kafka --profile chaos up -d --wait, puis Payment lancé *derrière*
Toxiproxy (il joint le Ledger par le proxy du port 28084) :
    LEDGER_SERVICE_URL=http://localhost:28084 scripts/lancer-services.sh start payment
Les autres services sont lancés normalement (scripts/lancer-services.sh start).

Scénario : 1) un paiement passe ; 2) le Ledger devient muet (jouet « timeout », connexions ouvertes sans
réponse) : les premiers appels attendent le délai de lecture, puis le circuit s'ouvre et les suivants
échouent aussitôt, sans solliciter le Ledger ; 3) le Ledger revient : le worker de reprise termine tous les
paiements restés en échec technique ; 4) conservation de l'argent : chaque paiement est écrit une seule fois.
Sans dépendance (bibliothèque standard uniquement). Code de sortie non nul si une vérification échoue.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

GATEWAY = os.environ.get("GATEWAY_URL", "http://localhost:8080")
KEYCLOAK = os.environ.get("KEYCLOAK_URL", "http://localhost:8180")
TOXIPROXY = os.environ.get("TOXIPROXY_URL", "http://localhost:8474")
PAYMENT = os.environ.get("PAYMENT_URL", "http://localhost:8083")
PAIEMENTS = 12
MONTANT = 1000
echecs = 0


def appel(methode, url, corps=None, entetes=None, formulaire=None):
    donnees = None
    entetes = dict(entetes or {})
    if corps is not None:
        donnees = json.dumps(corps).encode()
        entetes["Content-Type"] = "application/json"
    if formulaire is not None:
        donnees = urllib.parse.urlencode(formulaire).encode()
    requete = urllib.request.Request(url, data=donnees, method=methode, headers=entetes)
    try:
        with urllib.request.urlopen(requete, timeout=30) as reponse:
            texte = reponse.read().decode()
            return reponse.status, (json.loads(texte) if texte.startswith(("{", "[")) else texte)
    except urllib.error.HTTPError as erreur:
        return erreur.code, erreur.read().decode()


def verifier(description, condition, detail=""):
    global echecs
    print(f"  {'ok     ' if condition else 'ÉCHEC  '} {description} {detail}".rstrip())
    if not condition:
        echecs += 1


def jeton(**formulaire):
    statut, corps = appel("POST", f"{KEYCLOAK}/realms/finflow/protocol/openid-connect/token", formulaire=formulaire)
    assert statut == 200, corps
    return corps["access_token"]


def api(methode, chemin, token, corps=None, cle=None):
    entetes = {"Authorization": f"Bearer {token}"}
    if cle:
        entetes["Idempotency-Key"] = cle
    return appel(methode, f"{GATEWAY}{chemin}", corps, entetes)


def etat_circuit(token_metrics, nom):
    statut, texte = appel("GET", f"{PAYMENT}/actuator/prometheus", entetes={"Authorization": f"Bearer {token_metrics}"})
    for ligne in texte.splitlines():
        if ligne.startswith("resilience4j_circuitbreaker_state{") and f'name="{nom}"' in ligne and ligne.endswith(" 1.0"):
            return ligne.split('state="')[1].split('"')[0]
    return "inconnu"


def solde(token, compte):
    return api("GET", f"/api/v1/ledger/accounts/{compte}/balance", token)[1]["balanceCents"]


alice = jeton(grant_type="password", client_id="finflow-dev-cli", username="alice", password="finflow-dev")
metrics = jeton(grant_type="client_credentials", client_id="prometheus", client_secret="prometheus-dev-secret")
suffixe = str(int(time.time()))

print("Préparation")
ouvrir = lambda nom: api("POST", "/api/v1/accounts", alice, {
    "name": f"{nom}-{suffixe}", "type": "CURRENT", "perTransactionLimitCents": 1000000, "dailyLimitCents": 10**11})[1]["id"]
a, b = ouvrir("chaos-a"), ouvrir("chaos-b")
DEPOT = 100000
api("POST", "/api/v1/ledger/deposits", alice, {"accountId": a, "amountCents": DEPOT}, f"chaos-depot-{suffixe}")


def payer(cle):
    debut = time.time()
    statut, paiement = api("POST", "/api/v1/payments", alice, {
        "sourceAccountId": a, "destinationAccountId": b, "amountCents": MONTANT, "currency": "EUR"}, cle)
    return statut, paiement, time.time() - debut


def statut_paiement(id_):
    return api("GET", f"/api/v1/payments/{id_}", alice)[1]["status"]


print("1. Fonctionnement normal")
_, premier, _ = payer(f"chaos-0-{suffixe}")
verifier("le paiement de référence est terminé", premier["status"] == "COMPLETED")
verifier("le circuit du Ledger est fermé", etat_circuit(metrics, "ledger") == "closed")

print("2. Le Ledger devient muet (Toxiproxy : timeout)")
appel("POST", f"{TOXIPROXY}/proxies/ledger/toxics", {
    "name": "coupure", "type": "timeout", "stream": "downstream", "toxicity": 1.0, "attributes": {"timeout": 0}})
ids, durees = [], []
for i in range(PAIEMENTS):
    statut, paiement, duree = payer(f"chaos-{i + 1}-{suffixe}")
    ids.append(paiement["id"])
    durees.append(duree)
    print(f"     paiement {i + 1:2d} : {paiement['status']:<10} en {duree:5.2f} s")
verifier("tous les paiements sont en échec technique, aucun rejeté ni perdu",
         all(statut_paiement(i) == "FAILED" for i in ids))
verifier("le circuit s'est ouvert", etat_circuit(metrics, "ledger") == "open")
verifier("les derniers appels échouent aussitôt (circuit ouvert)", max(durees[-3:]) < 1.0,
         f"(max {max(durees[-3:]):.2f} s, contre {max(durees[:3]):.2f} s au début)")

print("3. Le Ledger revient, la saga reprend les paiements en échec")
appel("DELETE", f"{TOXIPROXY}/proxies/ledger/toxics/coupure")
limite = time.time() + 90
while time.time() < limite and not all(statut_paiement(i) == "COMPLETED" for i in ids):
    time.sleep(2)
statuts = sorted({statut_paiement(i) for i in ids})
verifier("tous les paiements sont repris et terminés", statuts == ["COMPLETED"], f"({', '.join(statuts)})")
verifier("le circuit s'est refermé", etat_circuit(metrics, "ledger") == "closed")

print("4. Conservation de l'argent")
total = PAIEMENTS + 1
verifier("la source est débitée une seule fois par paiement", solde(alice, a) == DEPOT - total * MONTANT,
         f"({solde(alice, a)})")
verifier("la destination est créditée une seule fois par paiement", solde(alice, b) == total * MONTANT,
         f"({solde(alice, b)})")

print("\nÉchecs :", echecs)
sys.exit(1 if echecs else 0)
