CREATE TABLE demo_usage_daily (
    usage_date DATE PRIMARY KEY,
    writes INTEGER NOT NULL CHECK (writes >= 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
