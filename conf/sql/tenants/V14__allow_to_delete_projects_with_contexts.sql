ALTER TABLE new_contexts DROP CONSTRAINT new_contexts_project_fkey;

ALTER TABLE new_contexts ADD CONSTRAINT new_contexts_project_fkey FOREIGN KEY (project) REFERENCES projects(name) ON DELETE CASCADE ON UPDATE CASCADE;