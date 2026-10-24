package io.github.psydic.shop.db;

import java.sql.Connection;
import java.sql.SQLException;

/** A unit of database work that runs on a borrowed connection. */
@FunctionalInterface
public interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
}
