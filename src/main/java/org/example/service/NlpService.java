package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NlpService {
    private static final Logger logger = LoggerFactory.getLogger(NlpService.class);
    private final RestTemplate rest = new RestTemplate();
    private final JdbcTemplate jdbc;
    private final String geminiKey;
    private static final String GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent";
    private static final Pattern MARKDOWN_FENCE = Pattern.compile("(?is)```(?:sql)?\\s*(.*?)\\s*```");
    private static final Pattern FIRST_SELECT = Pattern.compile("(?is)\\bselect\\b.+");

    public NlpService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.geminiKey = System.getenv("GEMINI_API_KEY");
        logger.info("NlpService initialized. Gemini API Key present: {}", (geminiKey != null && !geminiKey.isEmpty()));
    }

    public String toSql(String text) {
        logger.info("Processing NLP query: {}", text);
        
        if (text == null || text.trim().isEmpty()) {
            logger.warn("Empty input provided");
            return "-- no input provided";
        }
        
        String allowHeuristic = System.getenv("NL_ALLOW_HEURISTIC");
        if (geminiKey == null || geminiKey.isEmpty()) {
            if ("true".equalsIgnoreCase(allowHeuristic)) {
                logger.info("GEMINI_API_KEY not set, using heuristic fallback (NL_ALLOW_HEURISTIC=true)");
                return heuristic(text);
            }
            String error = "GEMINI_API_KEY is not set in environment. LLM unavailable.";
            logger.error(error);
            throw new IllegalStateException(error);
        }
        
        try {
            Map<String, Set<String>> schema = fetchSchema();
            logger.debug("Schema fetched: {} tables", schema.size());
            
            String systemPrompt = buildSystemPrompt(schema);
            String userPrompt = "Convert the following natural language to a single SQLite SELECT query that only references the provided schema and returns only data that exists in the database. Respond with ONLY the SQL and nothing else:\n\n" + text;
            
            String sql = callGeminiApi(systemPrompt, userPrompt);
            logger.info("Generated SQL: {}", sql);
            
            String validated = validateAndSanitizeSql(sql, schema);
            logger.info("SQL validated and sanitized: {}", validated);
            
            return validated;
        } catch (Exception e) {
            logger.error("Error processing NLP query", e);
            throw e;
        }
    }

    public String summarizeResults(String question, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "No matching records were found.";
        }

        if (geminiKey == null || geminiKey.isEmpty()) {
            return summarizeResultsHeuristic(question, rows);
        }

        try {
            String systemPrompt = "You are a helpful data assistant.\n"
                + "Answer the user's question using only the provided query results.\n"
                + "Respond in clear natural language only.\n"
                + "Do not include SQL, JSON, or markdown.\n"
                + "Use multiple short lines when that makes the answer easier to read.";
            String userPrompt = buildResultsPrompt(question, rows);
            String answer = callGeminiPlainText(systemPrompt, userPrompt);
            logger.info("Generated NLP answer for {} row(s)", rows.size());
            return answer;
        } catch (Exception e) {
            logger.warn("Falling back to heuristic NLP answer: {}", e.getMessage());
            return summarizeResultsHeuristic(question, rows);
        }
    }

    private String callGeminiApi(String systemPrompt, String userPrompt) {
        String raw = invokeGemini(systemPrompt, userPrompt, true);
        String sql = extractSqlFromModelOutput(raw);
        logger.info("Successfully extracted SQL from Gemini response");
        return sql;
    }

    private String callGeminiPlainText(String systemPrompt, String userPrompt) {
        String raw = invokeGemini(systemPrompt, userPrompt, false);
        return extractPlainTextFromModelOutput(raw);
    }

    @SuppressWarnings("unchecked")
    private String invokeGemini(String systemPrompt, String userPrompt, boolean sqlOutput) {
        logger.info("Calling Gemini API endpoint: {}", GEMINI_ENDPOINT);
        
        try {
            // Build the request payload for Gemini v1beta API
            Map<String, Object> userMessage = new LinkedHashMap<>();
            userMessage.put("role", "user");
            
            List<Map<String, String>> parts = new ArrayList<>();
            Map<String, String> part1 = new LinkedHashMap<>();
            part1.put("text", systemPrompt);
            parts.add(part1);
            
            Map<String, String> part2 = new LinkedHashMap<>();
            part2.put("text", userPrompt);
            parts.add(part2);
            
            userMessage.put("parts", parts);
            
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contents", List.of(userMessage));
            
            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("temperature", 0.0);
            generationConfig.put("topP", 0.1);
            generationConfig.put("topK", 5);
            generationConfig.put("maxOutputTokens", 1024);
            generationConfig.put("candidateCount", 1);
            generationConfig.put("thinkingConfig", Map.of("thinkingBudget", 0));
            payload.put("generationConfig", generationConfig);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            String urlWithKey = GEMINI_ENDPOINT + "?key=" + URLEncoder.encode(geminiKey, StandardCharsets.UTF_8);
            
            logger.debug("Sending request to Gemini API");
            Map<String, Object> response = rest.postForObject(urlWithKey, request, Map.class);
            
            if (response == null) {
                String error = "Gemini API returned null response";
                logger.error(error);
                throw new RuntimeException(error);
            }
            
            logger.debug("Gemini API response received");
            
            // Parse response according to Gemini v1beta format
            if (response.containsKey("candidates") && response.get("candidates") instanceof List) {
                List<?> candidates = (List<?>) response.get("candidates");
                if (candidates.size() > 1) {
                    logger.warn("Gemini returned {} candidates; using the first", candidates.size());
                }
                if (!candidates.isEmpty() && candidates.get(0) instanceof Map) {
                    Map<String, Object> candidate = (Map<String, Object>) candidates.get(0);
                    Object finishReason = candidate.get("finishReason");
                    if ("MAX_TOKENS".equals(finishReason)) {
                        String message = sqlOutput
                            ? "LLM response was truncated before SQL completed"
                            : "LLM response was truncated before the answer completed";
                        throw new RuntimeException(message);
                    }

                    if (candidate.containsKey("content") && candidate.get("content") instanceof Map) {
                        Map<String, Object> content = (Map<String, Object>) candidate.get("content");

                        if (content.containsKey("parts") && content.get("parts") instanceof List) {
                            List<?> partsResp = (List<?>) content.get("parts");
                            if (partsResp.size() > 1) {
                                logger.warn("Gemini returned {} text parts; joining all parts", partsResp.size());
                            }

                            StringBuilder joined = new StringBuilder();
                            for (Object partObj : partsResp) {
                                if (partObj instanceof Map<?, ?> part && part.get("text") != null) {
                                    joined.append(part.get("text").toString());
                                }
                            }

                            String raw = joined.toString().trim();
                            if (!raw.isEmpty()) {
                                return raw;
                            }
                        }
                    }
                }
            }
            
            logger.error("Could not parse Gemini response. Response: {}", response);
            throw new RuntimeException("Gemini API response format unexpected or missing required fields");
            
        } catch (RestClientException e) {
            logger.error("HTTP error calling Gemini API: {}", e.getMessage(), e);
            throw new RuntimeException("Gemini API HTTP error: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error calling Gemini API: {}", e.getMessage(), e);
            throw new RuntimeException("Gemini API call failed: " + e.getMessage(), e);
        }
    }

    String extractSqlFromModelOutput(String raw) {
        if (raw == null) {
            return "";
        }

        String text = raw.trim();
        Matcher fenceMatcher = MARKDOWN_FENCE.matcher(text);
        if (fenceMatcher.find()) {
            text = fenceMatcher.group(1).trim();
        }

        int semi = text.indexOf(';');
        if (semi >= 0) {
            logger.warn("Multiple SQL statements detected; using only the first");
            text = text.substring(0, semi).trim();
        }

        Matcher selectMatcher = FIRST_SELECT.matcher(text);
        if (selectMatcher.find()) {
            text = selectMatcher.group().trim();
        }

        return text.replaceAll("\\s+", " ").trim();
    }

    private String extractPlainTextFromModelOutput(String raw) {
        if (raw == null) {
            return "";
        }

        String text = raw.trim();
        Matcher fenceMatcher = MARKDOWN_FENCE.matcher(text);
        if (fenceMatcher.find()) {
            text = fenceMatcher.group(1).trim();
        }

        return text.trim();
    }

    private String buildResultsPrompt(String question, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        if (question == null || question.trim().isEmpty()) {
            sb.append("Summarize these query results.\n\n");
        } else {
            sb.append("Question: ").append(question.trim()).append("\n\n");
        }

        sb.append("Query returned ").append(rows.size()).append(" row(s):\n");
        int limit = Math.min(rows.size(), 25);
        for (int i = 0; i < limit; i++) {
            sb.append("- ").append(rows.get(i)).append('\n');
        }
        if (rows.size() > limit) {
            sb.append("... and ").append(rows.size() - limit).append(" more row(s) not shown.\n");
        }
        return sb.toString();
    }

    private String summarizeResultsHeuristic(String question, List<Map<String, Object>> rows) {
        if (rows.size() == 1 && rows.get(0).size() == 1) {
            Object value = rows.get(0).values().iterator().next();
            if (question != null && question.toLowerCase().matches(".*\\b(how many|count|number of)\\b.*")) {
                return "The answer is " + value + ".";
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(rows.size()).append(rows.size() == 1 ? " result:\n" : " results:\n");
        int limit = Math.min(rows.size(), 20);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = rows.get(i);
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object value = entry.getValue();
                parts.add(humanizeColumn(entry.getKey()) + ": " + (value == null ? "null" : value));
            }
            sb.append(i + 1).append(". ").append(String.join(", ", parts)).append('\n');
        }
        if (rows.size() > limit) {
            sb.append("... and ").append(rows.size() - limit).append(" more.");
        }
        return sb.toString().trim();
    }

    private String humanizeColumn(String column) {
        return column.replace('_', ' ');
    }

    public Map<String, Set<String>> fetchSchema() {
        logger.info("Fetching database schema");
        Map<String, Set<String>> schema = new HashMap<>();
        try {
            List<String> tables = jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", String.class);
            logger.info("Found {} tables in database", tables.size());
            
            for (String t : tables) {
                List<Map<String, Object>> cols = jdbc.queryForList("PRAGMA table_info(" + quoteIdentifier(t) + ")");
                Set<String> colNames = new HashSet<>();
                for (Map<String, Object> c : cols) {
                    colNames.add(c.get("name").toString());
                }
                schema.put(t, colNames);
                logger.debug("Table '{}' has {} columns", t, colNames.size());
            }
        } catch (Exception e) {
            logger.error("Error fetching schema", e);
            throw new RuntimeException("Failed to fetch database schema: " + e.getMessage(), e);
        }
        return schema;
    }

    private String heuristic(String text) {
        logger.info("Using heuristic fallback for query: {}", text);
        String lower = text == null ? "" : text.toLowerCase();
        if (lower.contains("users")) {
            logger.debug("Heuristic matched: users query");
            return "SELECT id, name, email FROM users LIMIT 100";
        }
        if (lower.contains("orders")) {
            logger.debug("Heuristic matched: orders query");
            return "SELECT * FROM orders LIMIT 100";
        }
        if (lower.contains("products")) {
            logger.debug("Heuristic matched: products query");
            return "SELECT * FROM products LIMIT 100";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\bfrom\\s+([a-zA-Z_][a-zA-Z0-9_]*)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            logger.debug("Heuristic matched: FROM clause for table {}", m.group(1));
            return "SELECT * FROM " + m.group(1) + " LIMIT 100";
        }
        logger.debug("Heuristic: no match, defaulting to users query");
        return "SELECT * FROM users LIMIT 10";
    }

    private String buildSystemPrompt(Map<String, Set<String>> schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a strict SQL generator for SQLite.\nRules:\n");
        sb.append("- Only output a single SELECT query (no explanations, no comments, no extra text).\n");
        sb.append("- Do not reference tables or columns not present in the schema.\n");
        sb.append("- Do not use DML/DCL (INSERT/UPDATE/DELETE/CREATE/DROP/ALTER/PRAGMA/ATTACH).\n");
        sb.append("- Do not include multiple statements or semicolons.\n");
        sb.append("- Prefer LIMIT 100 if not specified.\n\n");
        sb.append("Schema (table: columns):\n");
        for (Map.Entry<String, Set<String>> e : schema.entrySet()) {
            sb.append(e.getKey()).append(": ").append(String.join(", ", e.getValue())).append("\n");
        }
        String fallbackTable = schema.keySet().stream().findFirst().orElse("users");
        sb.append("\nIf the user asks for data not present in the schema, return a safe query against an existing table that returns zero rows, e.g. SELECT * FROM ")
            .append(fallbackTable)
            .append(" WHERE 1=0");
        return sb.toString();
    }

    public String validateAndSanitizeSql(String sql, Map<String, Set<String>> schema) {
        logger.info("Validating SQL: {}", sql);
        
        if (sql == null) {
            logger.error("SQL is null");
            throw new IllegalArgumentException("SQL is null");
        }
        
        String cleaned = sql.trim();
        
        // Disallow semicolons and comments
        if (cleaned.contains(";")) {
            logger.error("Semicolon detected in SQL");
            throw new IllegalArgumentException("Semicolons are not allowed in SQL");
        }
        if (cleaned.contains("--") || cleaned.contains("/*")) {
            logger.error("Comments detected in SQL");
            throw new IllegalArgumentException("Comments not allowed");
        }
        
        String upper = cleaned.toUpperCase(Locale.ROOT);
        
        // Disallow dangerous keywords
        String[] forbidden = new String[]{"INSERT","UPDATE","DELETE","DROP","ALTER","CREATE","PRAGMA","ATTACH","VACUUM","EXEC","MERGE"};
        for (String f : forbidden) {
            if (Pattern.compile("\\b" + f + "\\b").matcher(upper).find()) {
                logger.error("Forbidden keyword detected: {}", f);
                throw new IllegalArgumentException("Forbidden SQL keyword detected: " + f);
            }
        }
        
        // Must start with SELECT
        if (!upper.startsWith("SELECT")) {
            logger.error("SQL does not start with SELECT");
            throw new IllegalArgumentException("Only SELECT queries are allowed");
        }
        
        // Extract table names from FROM and JOIN
        Set<String> usedTables = new HashSet<>();
        Pattern fromPattern = Pattern.compile("(?i)\\bfrom\\s+([a-zA-Z_][a-zA-Z0-9_]*)");
        Matcher m = fromPattern.matcher(cleaned);
        while (m.find()) usedTables.add(m.group(1));
        
        Pattern joinPattern = Pattern.compile("(?i)\\bjoin\\s+([a-zA-Z_][a-zA-Z0-9_]*)");
        m = joinPattern.matcher(cleaned);
        while (m.find()) usedTables.add(m.group(1));
        
        if (usedTables.isEmpty()) {
            logger.error("No table referenced in query");
            throw new IllegalArgumentException("No table referenced in query");
        }
        
        logger.debug("Used tables: {}", usedTables);
        
        for (String t : usedTables) {
            if (!schema.containsKey(t)) {
                logger.error("Unknown table referenced: {}", t);
                throw new IllegalArgumentException("Unknown table referenced: " + t);
            }
        }
        
        // Validate selected columns
        Pattern selectPattern = Pattern.compile("(?i)^select\\s+(.*?)\\s+from\\s", Pattern.DOTALL);
        m = selectPattern.matcher(cleaned);
        if (m.find()) {
            String colsPart = m.group(1);
            // Accept wildcard
            if (!colsPart.trim().equals("*")) {
                String[] cols = colsPart.split(",");
                // Build union of allowed columns
                Set<String> allowedCols = new HashSet<>();
                for (Set<String> cs : schema.values()) allowedCols.addAll(cs);
                
                for (String colExpr : cols) {
                    String col = colExpr.trim();
                    String upperCol = col.toUpperCase(Locale.ROOT);
                    int aliasIdx = upperCol.indexOf(" AS ");
                    if (aliasIdx >= 0) {
                        col = col.substring(0, aliasIdx).trim();
                    }
                    // handle table.col
                    if (col.contains(".")) col = col.substring(col.indexOf('.')+1);
                    // remove possible functions (e.g., COUNT(col))
                    col = col.replaceAll("\\w+\\((.*?)\\)", "$1").replaceAll("[^a-zA-Z0-9_]", "");
                    if (col.isEmpty()) continue;
                    if (!allowedCols.contains(col)) {
                        logger.error("Unknown column referenced: {}", col);
                        throw new IllegalArgumentException("Unknown column referenced: " + col);
                    }
                }
            }
        } else {
            logger.error("Could not parse SELECT columns");
            throw new IllegalArgumentException("Could not parse SELECT columns");
        }
        
        // Ensure LIMIT exists; if not, append LIMIT 100
        Pattern limitPattern = Pattern.compile("(?i)\\blimit\\b");
        if (!limitPattern.matcher(cleaned).find()) {
            logger.debug("LIMIT not found, appending LIMIT 100");
            cleaned = cleaned + " LIMIT 100";
        }
        
        logger.info("SQL validation passed");
        return cleaned;
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

}
