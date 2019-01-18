--liquibase formatted sql

--changeset izanami:1
 create table izanami_configuration (
   id varchar(100) primary key,
   payload jsonb not null
 );
--rollback drop table izanami_configuration