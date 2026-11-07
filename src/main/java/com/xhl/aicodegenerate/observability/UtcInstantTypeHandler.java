package com.xhl.aicodegenerate.observability;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;

/** 仅追踪表使用 UTC，避免改变已有业务日期的 JDBC／会话时区语义。 */
public final class UtcInstantTypeHandler extends BaseTypeHandler<Instant> {
    private Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }
    @Override public void setNonNullParameter(PreparedStatement statement, int index, Instant value, JdbcType type) throws SQLException {
        statement.setTimestamp(index, Timestamp.from(value), utc());
    }
    @Override public Instant getNullableResult(ResultSet result, String column) throws SQLException {
        return instant(result.getTimestamp(column, utc()));
    }
    @Override public Instant getNullableResult(ResultSet result, int column) throws SQLException {
        return instant(result.getTimestamp(column, utc()));
    }
    @Override public Instant getNullableResult(CallableStatement statement, int column) throws SQLException {
        return instant(statement.getTimestamp(column, utc()));
    }
    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
