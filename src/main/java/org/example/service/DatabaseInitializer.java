package org.example.service;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer implements CommandLineRunner {
    private final JdbcTemplate jdbc;

    public DatabaseInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, email TEXT);");
        jdbc.execute("CREATE TABLE IF NOT EXISTS products (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, price REAL);");
        jdbc.execute("CREATE TABLE IF NOT EXISTS orders (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, product_id INTEGER, quantity INTEGER);");

        Integer usersCount = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (usersCount == 0) {
            jdbc.update("INSERT INTO users (name, email) VALUES (?,?)", "Alice", "alice@example.com");
            jdbc.update("INSERT INTO users (name, email) VALUES (?,?)", "Bob", "bob@example.com");
        }

        Integer productsCount = jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class);
        if (productsCount == 0) {
            jdbc.update("INSERT INTO products (name, price) VALUES (?,?)", "Widget", 9.99);
            jdbc.update("INSERT INTO products (name, price) VALUES (?,?)", "Gadget", 19.99);
        }

        Integer ordersCount = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
        if (ordersCount == 0) {
            jdbc.update("INSERT INTO orders (user_id, product_id, quantity) VALUES (?,?,?)", 1, 1, 2);
            jdbc.update("INSERT INTO orders (user_id, product_id, quantity) VALUES (?,?,?)", 2, 2, 1);
        }
    }
}
