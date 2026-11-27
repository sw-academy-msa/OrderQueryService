package com.msa.orderquery.domain.projection.exception;

/**
 * DB 장애 같은 재시도 대상 오류를 원문 없이 감쌉니다.
 *
 * <p>SQL 오류 메시지에는 저장하려던 행(배송지 포함)이 들어갈 수 있으므로 예외 클래스 이름과 SQLState만 남기고 원인 객체를 버립니다.
 */
public class ProjectionRetryException extends RuntimeException {
    public ProjectionRetryException(Throwable failure) {
        super("주문 조회 이벤트 반영 재시도: " + summary(failure), null, false, false);
    }

    private static String summary(Throwable failure) {
        var names = new StringBuilder();
        String sqlState = null;
        for (Throwable t = failure; t != null && names.length() < 300; t = t.getCause()) {
            if (!names.isEmpty()) names.append(" <- ");
            names.append(t.getClass().getSimpleName());
            if (t instanceof java.sql.SQLException sql && sqlState == null) sqlState = sql.getSQLState();
            if (t.getCause() == t) break;
        }
        return sqlState == null ? names.toString() : names + " sqlState=" + sqlState;
    }
}
