-- 로컬 일반 PostgreSQL에 Query 전용 DB/계정/스키마만 생성합니다. 기존 Account/Command 데이터는 변경하지 않습니다.
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'stockdrop' OR NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'account') THEN
        RAISE EXCEPTION '로컬 일반 DB가 아닙니다. 대상 컨테이너를 확인하세요.';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'orders_query'
        AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR NOT rolcanlogin)) THEN
        RAISE EXCEPTION '기존 Query 역할의 권한이 예상과 다릅니다.';
    END IF;
    -- 기존 계정의 다른 역할 멤버십으로 서비스 경계를 우회하지 못하게 합니다.
    IF EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname='orders_query') THEN
        RAISE EXCEPTION '기존 Query 역할에 다른 역할 멤버십이 있습니다.';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_database WHERE datname = 'orders_query' AND pg_get_userbyid(datdba) <> current_user) THEN
        RAISE EXCEPTION '기존 Query DB 소유권이 예상과 다릅니다.';
    END IF;
END $$;
-- 초기화 스크립트는 이 SQL 실행 후 설정 비밀번호로 TCP 로그인을 확인해야 성공을 출력합니다.
-- 기존 역할의 비밀번호는 변경하지 않습니다.
SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'orders_query') AS create_role \gset
\if :create_role
CREATE ROLE orders_query LOGIN PASSWORD :'query_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
\endif
COMMIT;
SELECT NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'orders_query') AS create_database \gset
\if :create_database
CREATE DATABASE orders_query TEMPLATE template0 ENCODING 'UTF8';
\endif
REVOKE ALL ON DATABASE orders_query FROM PUBLIC;
GRANT CONNECT ON DATABASE orders_query TO orders_query;
ALTER ROLE orders_query IN DATABASE orders_query SET search_path TO orders_query;
\connect orders_query
BEGIN;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS orders_query AUTHORIZATION orders_query;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='orders_query' AND pg_get_userbyid(nspowner)='orders_query') THEN
        RAISE EXCEPTION 'Query 스키마 소유권이 예상과 다릅니다.';
    END IF;
END $$;
REVOKE ALL ON SCHEMA orders_query FROM PUBLIC;
COMMIT;
