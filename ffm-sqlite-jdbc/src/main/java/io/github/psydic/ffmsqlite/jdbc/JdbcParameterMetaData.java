package io.github.psydic.ffmsqlite.jdbc;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;

/** Parameter metadata. SQLite parameters are untyped; types are reported as VARCHAR, as sqlite-jdbc does. */
final class JdbcParameterMetaData implements ParameterMetaData {
    private final int count;

    JdbcParameterMetaData(int count) {
        this.count = count;
    }

    private void check(int param) throws SQLException {
        if (param < 1 || param > count) {
            throw new SQLException("parameter index " + param + " out of range 1.." + count, "07009");
        }
    }

    @Override
    public int getParameterCount() {
        return count;
    }

    @Override
    public int isNullable(int param) throws SQLException {
        check(param);
        return parameterNullableUnknown;
    }

    @Override
    public boolean isSigned(int param) throws SQLException {
        check(param);
        return true;
    }

    @Override
    public int getPrecision(int param) throws SQLException {
        check(param);
        return 0;
    }

    @Override
    public int getScale(int param) throws SQLException {
        check(param);
        return 0;
    }

    @Override
    public int getParameterType(int param) throws SQLException {
        check(param);
        return Types.VARCHAR;
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
        check(param);
        return "VARCHAR";
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
        check(param);
        return String.class.getName();
    }

    @Override
    public int getParameterMode(int param) throws SQLException {
        check(param);
        return parameterModeIn;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
