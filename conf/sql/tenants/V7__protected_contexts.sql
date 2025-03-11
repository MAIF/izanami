ALTER TABLE global_feature_contexts ADD COLUMN protected BOOLEAN DEFAULT false;
ALTER TABLE feature_contexts ADD COLUMN protected BOOLEAN DEFAULT false;