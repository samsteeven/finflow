"""Génère le tableau de bord Grafana de FinFlow (grafana/dashboards/finflow.json).

Écrire le JSON à la main est illisible : on décrit les panneaux ici, et le fichier est régénéré.
Usage : python infrastructure/observability/generer-tableau-de-bord.py
"""
import json
from pathlib import Path

PROM = {"type": "prometheus", "uid": "prometheus"}
NOT_ACTUATOR = 'uri!~"/actuator.*"'
panels = []
next_id = [1]


def panel(kind, title, targets, x, y, w, h, unit=None, description=None, extra=None):
    p = {
        "id": next_id[0], "type": kind, "title": title, "datasource": PROM,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "targets": [{"datasource": PROM, "refId": chr(65 + i), "expr": expr, "legendFormat": legend}
                    for i, (expr, legend) in enumerate(targets)],
        "fieldConfig": {"defaults": {"unit": unit} if unit else {}, "overrides": []},
        "options": {},
    }
    if description:
        p["description"] = description
    if extra:
        p.update(extra)
    next_id[0] += 1
    panels.append(p)


def row(title, y):
    panels.append({"id": next_id[0], "type": "row", "title": title, "collapsed": False,
                   "gridPos": {"x": 0, "y": y, "w": 24, "h": 1}, "panels": []})
    next_id[0] += 1


def stat(title, expr, x, w=6, unit=None, thresholds=None, description=None):
    extra = {"options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "background"}}
    p_thresholds = thresholds or [("green", None)]
    panel("stat", title, [(expr, "")], x, 1, w, 4, unit, description, extra)
    panels[-1]["fieldConfig"]["defaults"]["thresholds"] = {
        "mode": "absolute", "steps": [{"color": c, "value": v} for c, v in p_thresholds]}


# --- Vue d'ensemble -----------------------------------------------------------------------------
row("Vue d'ensemble", 0)
stat("Services collectés", 'count(up{job="finflow"} == 1)', 0,
     thresholds=[("red", None), ("orange", 4), ("green", 5)], description="Cinq services attendus")
stat("Paiements terminés (5 min)", 'sum(increase(finflow_payment_events_total{type="PaymentCompleted"}[5m]))', 6)
stat("Sagas bloquées", "sum(finflow_saga_stuck)", 12, thresholds=[("green", None), ("red", 1)],
     description="Paiements dont les reprises sont épuisées : doit rester à zéro")
stat("Événements en attente d'envoi", "sum(finflow_outbox_pending)", 18,
     thresholds=[("green", None), ("orange", 20), ("red", 200)], description="Outbox non publiée sur Kafka")

# --- Trafic ---------------------------------------------------------------------------------------
row("Trafic HTTP", 5)
panel("timeseries", "Requêtes par seconde", [
    (f'sum by (application) (rate(http_server_requests_seconds_count{{{NOT_ACTUATOR}}}[1m]))', "{{application}}")],
      0, 6, 8, 8, "reqps")
panel("timeseries", "Latence p95", [
    (f'histogram_quantile(0.95, sum by (le, application) (rate(http_server_requests_seconds_bucket{{{NOT_ACTUATOR}}}[1m])))',
     "{{application}}")], 8, 6, 8, 8, "s")
panel("timeseries", "Réponses 5xx par seconde", [
    (f'sum by (application) (rate(http_server_requests_seconds_count{{status=~"5..", {NOT_ACTUATOR}}}[1m]))',
     "{{application}}")], 16, 6, 8, 8, "reqps")

# --- Paiements et saga ----------------------------------------------------------------------------
row("Paiements et saga", 14)
panel("timeseries", "Événements de paiement par type", [
    ('sum by (type) (rate(finflow_payment_events_total[1m]))', "{{type}}")], 0, 15, 8, 8, "ops",
      "Écrits dans l'event store, comptés après le commit")
panel("timeseries", "Reprises de la saga", [
    ('sum by (result) (rate(finflow_saga_recoveries_total[1m]))', "{{result}}"),
    ('sum by (status) (finflow_saga_pending)', "en suspens {{status}}")], 8, 15, 8, 8, None,
      "Reprises par le worker, et paiements en attente de reprise")
panel("timeseries", "Rejeux d'Idempotency-Key", [
    ('sum(rate(finflow_payment_idempotent_replays_total[1m]))', "rejeux")], 16, 15, 8, 8, "ops",
      "Une demande répétée renvoie le même paiement, sans second débit")

# --- Kafka et outbox ------------------------------------------------------------------------------
row("Outbox, Kafka et consommateurs", 23)
panel("timeseries", "Outbox : en attente et âge du plus ancien", [
    ('sum by (application) (finflow_outbox_pending)', "en attente {{application}}"),
    ('max by (application) (finflow_outbox_oldest_age_seconds)', "âge max (s) {{application}}")], 0, 24, 8, 8)
panel("timeseries", "Publications du relais", [
    ('sum by (application) (rate(finflow_outbox_published_total[1m]))', "publiés {{application}}"),
    ('sum by (application) (rate(finflow_outbox_failures_total[1m]))', "échecs {{application}}")], 8, 24, 8, 8, "ops")
panel("timeseries", "Consommateurs : livraisons et file morte", [
    ('sum by (outcome) (rate(finflow_inbox_deliveries_total[1m]))', "{{outcome}}"),
    ('sum by (topic) (rate(finflow_consumer_dead_letters_total[1m]))', "file morte {{topic}}")], 16, 24, 8, 8, "ops")

# --- Résilience -----------------------------------------------------------------------------------
row("Résilience (Resilience4j)", 32)
panel("timeseries", "État des coupe-circuits (1 = actif)", [
    ('max by (name, state) (resilience4j_circuitbreaker_state == 1)', "{{name}} {{state}}")], 0, 33, 8, 8)
panel("timeseries", "Appels sortants par issue", [
    ('sum by (name, kind) (rate(resilience4j_circuitbreaker_calls_seconds_count[1m]))', "{{name}} {{kind}}")],
      8, 33, 8, 8, "ops")
panel("timeseries", "Écritures du Ledger par type", [
    ('sum by (kind) (rate(finflow_ledger_postings_total[1m]))', "{{kind}}")], 16, 33, 8, 8, "ops")

# --- Ressources -----------------------------------------------------------------------------------
row("Ressources", 41)
panel("timeseries", "Mémoire du tas (Mo)", [
    ('sum by (application) (jvm_memory_used_bytes{area="heap"}) / 1024 / 1024', "{{application}}")], 0, 42, 12, 8)
panel("timeseries", "Connexions PostgreSQL actives", [
    ('sum by (application) (hikaricp_connections_active)', "{{application}}")], 12, 42, 12, 8)

dashboard = {
    "uid": "finflow", "title": "FinFlow", "tags": ["finflow"], "timezone": "browser",
    "schemaVersion": 39, "version": 1, "refresh": "5s", "time": {"from": "now-15m", "to": "now"},
    "panels": panels,
    "links": [{"title": "Traces (Jaeger)", "type": "link", "url": "http://localhost:16686", "targetBlank": True}],
}

target = Path(__file__).with_name("grafana") / "dashboards" / "finflow.json"
target.write_text(json.dumps(dashboard, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
print(f"écrit : {target}")
