CREATE TABLE feature_calls (
  feature TEXT REFERENCES features (id),
  apikey TEXT REFERENCES apikeys (clientId),
  date TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  value JSONB
);