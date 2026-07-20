package org.example.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class TextToSqlController {

    private static final Pattern FROM_TABLE = Pattern.compile("\\bfrom\\s+(?:the\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALID_TABLE_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    @PostMapping(value = "/convert", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> convertTextToSql(@RequestBody(required = false) String text) {
        try {
            if (text == null || text.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Input text is required", "sql", ""));
            }
            
            String sql = generateSqlFromText(text);
            return ResponseEntity.ok(Map.of("input", text, "sql", sql));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to generate SQL: " + e.getMessage(), "sql", ""));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "healthy", "message", "Service is running"));
    }

    private String generateSqlFromText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "-- no input provided";
        }
        
        Matcher m = FROM_TABLE.matcher(text);
        if (m.find()) {
            String table = m.group(1);
            if (isValidTableName(table)) {
                return "SELECT * FROM " + sanitizeTableName(table) + ";";
            }
        }
        
        String lower = text.toLowerCase();
        if (lower.contains("users")) return "SELECT * FROM users;";
        if (lower.contains("orders")) return "SELECT * FROM orders;";
        if (lower.contains("products")) return "SELECT * FROM products;";
        return "SELECT * FROM your_table; -- refine with more context";
    }

    private boolean isValidTableName(String tableName) {
        return tableName != null && VALID_TABLE_NAME.matcher(tableName).matches();
    }

    private String sanitizeTableName(String tableName) {
        // Basic sanitization to prevent SQL injection
        return tableName.replaceAll("[^a-zA-Z0-9_]", "");
    }
}
