# Supabase only provides Google Login identity

Supabase Auth provides Google OAuth identity only; Spring Boot verifies the Supabase token, then creates or links the local User and issues the existing local JWT and Refresh Session. This preserves the system's User, role, session-revocation, Invitation, password, and authorization rules while avoiding a migration of the existing Email／Password authentication.
