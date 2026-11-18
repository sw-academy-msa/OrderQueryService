# 생성한 Query 계정으로 TCP 인증·스키마 소유권·DDL/DML 권한을 롤백 가능한 방식으로 확인합니다.
param([string]$Container = 'stockdrop-account-postgres')
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envText = [IO.File]::ReadAllText((Join-Path $root '.env'))
# 앱과 다른 비밀번호를 검사하지 않도록 누락·중복 설정을 먼저 거절합니다.
$matches = [regex]::Matches($envText,'(?m)^DB_PASSWORD=([^\r\n]*)\r?$')
if ($matches.Count -ne 1 -or [string]::IsNullOrWhiteSpace($matches[0].Groups[1].Value)) { throw 'DB_PASSWORD는 비어 있지 않은 값으로 정확히 한 번 설정해야 합니다.' }
$match = $matches[0]
$sql = @'
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'orders_query' OR current_user <> 'orders_query' OR current_schema() <> 'orders_query' THEN
        RAISE EXCEPTION 'Query 접속 경계 불일치';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) THEN
        RAISE EXCEPTION 'Query 역할 과다 권한';
    END IF;
    IF has_schema_privilege(current_user,'public','CREATE') OR has_database_privilege(current_user,current_database(),'CREATE') THEN
        RAISE EXCEPTION '다른 스키마 생성 권한이 있음';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname IN ('account','product','orders')) THEN
        RAISE EXCEPTION '다른 서비스 원본 스키마가 Query DB에 있음';
    END IF;
END $$;
CREATE TABLE orders_query.query_permission_probe(id integer PRIMARY KEY);
INSERT INTO orders_query.query_permission_probe VALUES (1);
UPDATE orders_query.query_permission_probe SET id=2 WHERE id=1;
DELETE FROM orders_query.query_permission_probe;
ROLLBACK;
SELECT 'QUERY_PERMISSION_CHECK_PASS';
'@
$inputText = $match.Groups[1].Value + "`n" + $sql
# 컨테이너의 루프백 trust 규칙을 우회하여 앱과 같은 호스트 비밀번호 인증을 검증합니다.
# 비밀번호 줄에는 CR을 넣지 않고 외부 프로세스 입력을 UTF-8로 전달합니다.
$previousOutputEncoding = $OutputEncoding
$OutputEncoding = New-Object Text.UTF8Encoding($false)
$previous = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $output = $inputText | & docker exec -i $Container sh -c 'IFS= read -r PGPASSWORD; export PGPASSWORD; set -- $(hostname -i); exec psql -X -h "$1" -U orders_query -d orders_query -v ON_ERROR_STOP=1' 2>&1
    $code = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previous
    $OutputEncoding = $previousOutputEncoding
    $inputText = $null
    $envText = $null
}
if ($code -ne 0 -or -not ($output -match 'QUERY_PERMISSION_CHECK_PASS')) { throw 'Query DB 권한 검증 실패. 원문 비밀값은 출력하지 않습니다.' }
Write-Output 'Query TCP authentication and own-schema permissions: PASS'
