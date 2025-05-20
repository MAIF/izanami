ALTER TABLE features ADD created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW();

CREATE TABLE feature_calls (
   feature TEXT REFERENCES features (id) ON UPDATE CASCADE ON DELETE CASCADE NOT NULL,
   apikey TEXT REFERENCES apikeys (clientId) ON UPDATE CASCADE ON DELETE CASCADE NOT NULL,
   context ${extensions_schema}.ltree DEFAULT ${extensions_schema}.text2ltree(''),
   value JSONB,
   range_start TIMESTAMP WITH TIME ZONE NOT NULL,
   range_stop TIMESTAMP WITH TIME ZONE NOT NULL,
   count NUMERIC NOT NULL DEFAULT 0,
   CONSTRAINT call_ranges_consistency CHECK (
       range_start < feature_calls.range_stop
   ),
   PRIMARY KEY (feature, apikey, context, value, range_start)
);