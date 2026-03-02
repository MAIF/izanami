-- disable existing foreign keys
ALTER TABLE webhooks_projects DROP CONSTRAINT webhooks_projects_project_fkey;

-- migrate columns
ALTER TABLE projects ALTER COLUMN id TYPE TEXT;
ALTER TABLE tags ALTER COLUMN id TYPE TEXT;
ALTER TABLE webhooks_projects ALTER COLUMN project TYPE TEXT;

-- re-enable foreign keys
ALTER TABLE webhooks_projects ADD CONSTRAINT webhooks_projects_project_fkey FOREIGN KEY (project) REFERENCES projects(id) ON DELETE CASCADE ON UPDATE CASCADE;