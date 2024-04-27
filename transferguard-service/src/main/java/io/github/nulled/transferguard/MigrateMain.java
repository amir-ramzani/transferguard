package io.github.nulled.transferguard;

import org.flywaydb.core.Flyway;

/**
 * Runs the Flyway migrations as the migrator role and exits. Used by the Helm pre-install and
 * pre-upgrade Job, so application pods never hold schema-changing credentials.
 *
 * <pre>java -cp @/app/jib-classpath-file io.github.nulled.transferguard.MigrateMain</pre>
 */
public final class MigrateMain {

    private MigrateMain() {}

    public static void main(String[] args) {
        String url = require("DB_URL");
        String user = require("DB_MIGRATOR_USER");
        String password = require("DB_MIGRATOR_PASSWORD");
        var result = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        System.out.println("Applied " + result.migrationsExecuted + " migrations; schema at "
                + result.targetSchemaVersion);
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }
}
