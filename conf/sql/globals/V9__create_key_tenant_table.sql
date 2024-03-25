CREATE TABLE key_tenant (
    client_id TEXT,
    tenant TEXT REFERENCES tenants(name) ON DELETE CASCADE ON UPDATE CASCADE,
    PRIMARY KEY (client_id, tenant)
)