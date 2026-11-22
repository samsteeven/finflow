"""Génère finflow-realm.json, le realm Keycloak importé au démarrage de la pile locale.

Le fichier JSON est versionné ; ce script n'existe que pour éviter d'écrire à la main la
configuration du profil utilisateur, qui est une chaîne JSON imbriquée dans le JSON du realm.
Usage : python infrastructure/keycloak/generer-realm.py          realm de développement (finflow-realm.json)
        python infrastructure/keycloak/generer-realm.py --cloud  realm AWS (infrastructure/aws/keycloak/finflow-realm-cloud.json, ADR-018)

Le realm AWS n'a ni client de scripts locaux, ni client Prometheus, ni mot de passe ou adresse écrits en dur :
Keycloak remplace au démarrage les ${VARIABLES} d'environnement du fichier importé (FINFLOW_PUBLIC_URL,
PAYMENT_CLIENT_SECRET, DEMO_PASSWORD), fournies par Terraform et Secrets Manager.
"""

import json
import sys
from pathlib import Path

CLOUD = "--cloud" in sys.argv
WEB_URL = "${FINFLOW_PUBLIC_URL}" if CLOUD else "http://localhost:4200"
DEMO_PASSWORD = "${DEMO_PASSWORD}" if CLOUD else "finflow-dev"
PAYMENT_SECRET = "${PAYMENT_CLIENT_SECRET}" if CLOUD else "payment-service-dev-secret"

ALPHA = "a1a1a1a1-0000-4000-8000-000000000001"
BETA = "b2b2b2b2-0000-4000-8000-000000000002"
UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"

EVERYONE = {"view": ["admin", "user"], "edit": ["admin", "user"]}

user_profile = {
    "attributes": [
        {"name": "username", "displayName": "${username}", "multivalued": False, "permissions": EVERYONE,
         "validations": {"length": {"min": 3, "max": 255}, "username-prohibited-characters": {},
                         "up-username-not-idn-homograph": {}}},
        {"name": "email", "displayName": "${email}", "multivalued": False, "permissions": EVERYONE,
         "required": {"roles": ["user"]}, "validations": {"email": {}, "length": {"max": 255}}},
        {"name": "firstName", "displayName": "${firstName}", "multivalued": False, "permissions": EVERYONE,
         "required": {"roles": ["user"]},
         "validations": {"length": {"max": 255}, "person-name-prohibited-characters": {}}},
        {"name": "lastName", "displayName": "${lastName}", "multivalued": False, "permissions": EVERYONE,
         "required": {"roles": ["user"]},
         "validations": {"length": {"max": 255}, "person-name-prohibited-characters": {}}},
        # L'organisation : visible et modifiable par un administrateur seulement. L'utilisateur ne
        # peut pas se l'attribuer depuis son compte (ADR-004).
        {"name": "tenant_id", "displayName": "Organisation (identifiant du tenant)", "multivalued": False,
         "permissions": {"view": ["admin"], "edit": ["admin"]},
         "validations": {"pattern": {"pattern": UUID_PATTERN, "error-message": "Un UUID est attendu"}}},
    ],
    "groups": [{"name": "user-metadata", "displayHeader": "User metadata",
                "displayDescription": "Attributes, which refer to user metadata"}],
}


def mappers():
    return [
        {"name": "audience finflow-api", "protocol": "openid-connect",
         "protocolMapper": "oidc-audience-mapper", "consentRequired": False,
         "config": {"included.custom.audience": "finflow-api", "access.token.claim": "true",
                    "id.token.claim": "false", "introspection.token.claim": "true"}},
        {"name": "tenant_id", "protocol": "openid-connect",
         "protocolMapper": "oidc-usermodel-attribute-mapper", "consentRequired": False,
         "config": {"user.attribute": "tenant_id", "claim.name": "tenant_id", "jsonType.label": "String",
                    "access.token.claim": "true", "id.token.claim": "true",
                    "introspection.token.claim": "true", "userinfo.token.claim": "true",
                    "multivalued": "false"}},
    ]


def user(username, first, last, roles, tenant=None):
    attributes = {"tenant_id": [tenant]} if tenant else {}
    return {"username": username, "email": f"{username}@finflow.test", "emailVerified": True,
            "firstName": first, "lastName": last, "enabled": True, "attributes": attributes,
            "realmRoles": ["default-roles-finflow", *roles],
            "credentials": [{"type": "password", "value": DEMO_PASSWORD, "temporary": False}]}


realm = {
    "realm": "finflow",
    "displayName": "FinFlow",
    "enabled": True,
    "sslRequired": "external",
    "registrationAllowed": False,
    "loginWithEmailAllowed": True,
    "bruteForceProtected": True,
    "accessTokenLifespan": 300,
    "ssoSessionIdleTimeout": 1800,
    "internationalizationEnabled": True,
    "supportedLocales": ["fr", "en"],
    "defaultLocale": "fr",
    "roles": {"realm": [
        {"name": "platform-admin", "description": "Administre la plateforme : crée les organisations"},
        {"name": "tenant-admin", "description": "Administre son organisation"},
        {"name": "tenant-member", "description": "Membre d'une organisation"},
        {"name": "service", "description": "Compte technique d'un service (reprise des sagas, ADR-015)"},
        {"name": "metrics", "description": "Lecture des métriques Prometheus (ADR-017)"},
    ]},
    "clients": [
        {
            # Front Angular : client public, authorization code + PKCE S256 uniquement.
            "clientId": "finflow-web", "name": "FinFlow (front)", "enabled": True, "publicClient": True,
            "protocol": "openid-connect", "standardFlowEnabled": True, "implicitFlowEnabled": False,
            "directAccessGrantsEnabled": False, "serviceAccountsEnabled": False,
            "redirectUris": [WEB_URL + "/*"], "webOrigins": [WEB_URL],
            "attributes": {"pkce.code.challenge.method": "S256",
                           "post.logout.redirect.uris": WEB_URL + "/*"},
            "protocolMappers": mappers(),
        },
        {
            # Développement local seulement : jetons par mot de passe pour les scripts de
            # vérification (scripts/verifier-pile.sh). N'existe pas dans un realm de production.
            "clientId": "finflow-dev-cli", "name": "FinFlow (scripts locaux)", "enabled": True,
            "publicClient": True, "protocol": "openid-connect", "standardFlowEnabled": False,
            "implicitFlowEnabled": False, "directAccessGrantsEnabled": True,
            "protocolMappers": mappers(),
        },
        {
            # Compte technique de Payment : jeton par client credentials, sans organisation. Il ne
            # sert qu'à reprendre une saga hors d'une requête utilisateur (ADR-015). Le secret
            # ci-dessous est un secret de développement ; en production il vient d'un coffre.
            "clientId": "payment-service", "name": "FinFlow (payment-service)", "enabled": True,
            "publicClient": False, "secret": PAYMENT_SECRET, "protocol": "openid-connect",
            "standardFlowEnabled": False, "implicitFlowEnabled": False, "directAccessGrantsEnabled": False,
            "serviceAccountsEnabled": True,
            "protocolMappers": mappers(),
        },
        {
            # Compte technique de Prometheus : il présente ce jeton à chaque collecte (ADR-017).
            "clientId": "prometheus", "name": "FinFlow (Prometheus)", "enabled": True,
            "publicClient": False, "secret": "prometheus-dev-secret", "protocol": "openid-connect",
            "standardFlowEnabled": False, "implicitFlowEnabled": False, "directAccessGrantsEnabled": False,
            "serviceAccountsEnabled": True,
            "protocolMappers": mappers(),
        },
    ],
    "users": [
        {"username": "service-account-payment-service", "enabled": True,
         "serviceAccountClientId": "payment-service", "realmRoles": ["service"]},
        {"username": "service-account-prometheus", "enabled": True,
         "serviceAccountClientId": "prometheus", "realmRoles": ["metrics"]},
        user("plateforme", "Paula", "Plateforme", ["platform-admin"]),
        user("alice", "Alice", "Alpha", ["tenant-admin"], ALPHA),
        user("bruno", "Bruno", "Beta", ["tenant-admin"], BETA),
        user("clara", "Clara", "Alpha", ["tenant-member"], ALPHA),
    ],
    "components": {
        "org.keycloak.userprofile.UserProfileProvider": [{
            "providerId": "declarative-user-profile", "subComponents": {},
            "config": {"kc.user.profile.config": [json.dumps(user_profile, ensure_ascii=False)]},
        }]
    },
}

if CLOUD:
    # Ni les scripts locaux (mot de passe direct), ni Prometheus (pas de supervision Prometheus sur AWS).
    realm["clients"] = [c for c in realm["clients"] if c["clientId"] not in ("finflow-dev-cli", "prometheus")]
    realm["users"] = [u for u in realm["users"] if u["username"] != "service-account-prometheus"]

# Le realm AWS vit à part : le répertoire de ce script est monté tel quel dans Keycloak en local (docker-compose).
target = (Path(__file__).parent.parent / "aws" / "keycloak" / "finflow-realm-cloud.json") if CLOUD     else Path(__file__).with_name("finflow-realm.json")
target.write_text(json.dumps(realm, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
print(f"écrit : {target}")
