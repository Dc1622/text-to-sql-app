package org.example.controller;

import org.example.service.NlpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api")
public class QueryController {
    private static final Logger logger = LoggerFactory.getLogger(QueryController.class);
    private final NlpService nlp;
    private final JdbcTemplate jdbc;

    public QueryController(NlpService nlp, JdbcTemplate jdbc) {
        this.nlp = nlp;
        this.jdbc = jdbc;
        logger.info("QueryController initialized");
    }

    @PostMapping("/nlp-to-sql")
    public ResponseEntity<Map<String, Object>> nlpToSql(@RequestBody String text) {
        logger.info("NLP to SQL request received");
        try {
            if (text == null || text.trim().isEmpty()) {
                logger.warn("Empty text provided");
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "empty_input");
                error.put("message", "Input text cannot be empty");
                return ResponseEntity.badRequest().body(error);
            }
            
            String sql = nlp.toSql(text);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("input", text);
            result.put("sql", sql);
            
            logger.info("Successfully converted NLP to SQL");
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            logger.error("LLM not available: {}", e.getMessage());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "llm_unavailable");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
        } catch (RuntimeException e) {
            logger.error("Error converting NLP to SQL: {}", e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "conversion_failed");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        } catch (Exception e) {
            logger.error("Unexpected error in NLP to SQL: {}", e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "unexpected_error");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody Map<String, String> body) {
        logger.info("Query execution request received");
        try {
            String sql = body.get("sql");
            String question = body.get("question");
            if (sql == null || sql.trim().isEmpty()) {
                logger.warn("No SQL provided in request");
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "empty_sql");
                error.put("message", "No SQL provided");
                return ResponseEntity.badRequest().body(error);
            }
            
            logger.debug("Validating SQL: {}", sql);
            String safe = nlp.validateAndSanitizeSql(sql, nlp.fetchSchema());
            
            logger.debug("Executing SQL query");
            List<Map<String, Object>> rows = jdbc.queryForList(safe);
            
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rows", rows);
            result.put("rowCount", rows.size());
            result.put("sql", safe);
            result.put("answer", nlp.summarizeResults(question, rows));
            
            logger.info("Query executed successfully, {} rows returned", rows.size());
            return ResponseEntity.ok(result);
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid SQL: {}", e.getMessage());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "invalid_sql");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            logger.error("Query execution failed: {}", e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "execution_failed");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        logger.debug("Health check requested");
        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("timestamp", String.valueOf(System.currentTimeMillis()));
        return ResponseEntity.ok(result);
    }
}
