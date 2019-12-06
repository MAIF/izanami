ALTER SYSTEM SET max_connections = 1000;
ALTER SYSTEM RESET shared_buffers;

CREATE DATABASE izanami;
CREATE USER izanami WITH PASSWORD 'izanami';
GRANT ALL PRIVILEGES ON DATABASE "izanami" to izanami;