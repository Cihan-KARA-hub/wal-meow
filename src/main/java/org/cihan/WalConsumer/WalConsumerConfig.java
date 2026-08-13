package org.cihan.WalConsumer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class WalConsumerConfig {

    private static final String URL      = "jdbc:postgresql://localhost:5432/db";
    private static final String USER     = "db";
    private static final String PASSWORD = "db";
    private static final String DATABASE = "db";

    public String getHost()     { return URL; }
    public String getDatabase() { return DATABASE; }

    public Connection sqlConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    public Connection replicationConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user",                    USER);
        props.setProperty("password",                PASSWORD);
        props.setProperty("replication",             "database");
        props.setProperty("preferQueryMode",         "simple");
        props.setProperty("assumeMinServerVersion",  "9.4");

        return DriverManager.getConnection(URL, props);
    }
}