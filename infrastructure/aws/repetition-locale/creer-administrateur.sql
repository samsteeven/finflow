-- Reproduit le rôle d'administration de RDS : il peut créer des rôles et des bases, mais n'est pas super-utilisateur.
CREATE ROLE finflow LOGIN CREATEROLE CREATEDB PASSWORD 'administrateur_essai';
