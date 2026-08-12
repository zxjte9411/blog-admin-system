CREATE OR REPLACE FUNCTION prevent_password_setting_change_audit_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'password setting audit is immutable';
END;
$$;

CREATE TRIGGER password_setting_changes_immutable
BEFORE UPDATE OR DELETE ON password_setting_changes
FOR EACH ROW EXECUTE FUNCTION prevent_password_setting_change_audit_mutation();
