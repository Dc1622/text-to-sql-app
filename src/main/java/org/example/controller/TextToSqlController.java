package org.example.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class TextToSqlController {

    private static final Pattern FROM_TABLE = Pattern.compile("\\bfrom\\s+(?:the\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)", Pattern.CASE_INSENSITIVE);

    @PostMapping(value = "/convert", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> convertTextToSql(@RequestBody String text) {
        String sql = generateSqlFromText(text);
        return Map.of("input", text == null ? "" : text, "sql", sql);
    }

    private String generateSqlFromText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "-- no input provided";
        }
        Matcher m = FROM_TABLE.matcher(text);
        if (m.find()) {
            String table = m.group(1);
            return "SELECT * FROM " + table + ";";
        }
        String lower = text.toLowerCase();
        if (lower.contains("users")) return "SELECT * FROM users;";
        if (lower.contains("orders")) return "SELECT * FROM orders;";
        if (lower.contains("products")) return "SELECT * FROM products;";
        return "SELECT * FROM your_table; -- refine with more context";
    }
}
