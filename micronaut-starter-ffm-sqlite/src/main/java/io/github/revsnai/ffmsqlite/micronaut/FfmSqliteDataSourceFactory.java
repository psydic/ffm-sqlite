package io.github.revsnai.ffmsqlite.micronaut;

import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.Factory;

import javax.sql.DataSource;

/**
 * Exposes each database's pool as a {@link DataSource} bean with the same name, so {@code @Inject DataSource},
 * {@code @Named("reports") DataSource}, Micronaut Data JDBC and its transaction manager use it unchanged.
 */
@Factory
public class FfmSqliteDataSourceFactory {

    @EachBean(FfmSqliteDatabase.class)
    public DataSource dataSource(FfmSqliteDatabase database) {
        return database.getDataSource();
    }
}
