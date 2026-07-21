package org.example.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NlpServiceTest {

    private final NlpService service = new NlpService(null);

    @Test
    void appendsDefaultLimitToValidSelect() {
        String sql = service.validateAndSanitizeSql("SELECT id, name FROM users", schema());

        assertEquals("SELECT id, name FROM users LIMIT 100", sql);
    }

    @Test
    void rejectsUnknownTable() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.validateAndSanitizeSql("SELECT * FROM missing", schema())
        );
    }

    @Test
    void rejectsSemicolon() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.validateAndSanitizeSql("SELECT * FROM users;", schema())
        );
    }

    private Map<String, Set<String>> schema() {
        Map<String, Set<String>> schema = new LinkedHashMap<>();
        schema.put("users", Set.of("id", "name", "email"));
        schema.put("orders", Set.of("id", "user_id", "product_id", "quantity"));
        schema.put("products", Set.of("id", "name", "price"));
        return schema;
    }
}
