package ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

public class AIClient {

    private static final Logger logger = Logger.getLogger(AIClient.class.getName());

    private static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private static final String MODEL = "openai/o4-mini";
    private static final int MAX_EXCHANGES = 30;

    private final String apiKey;
    private final HttpClient httpClient;
    private final List<String[]> conversationHistory; // [role, content]

    private static final String SYSTEM_PROMPT =
            "Ты — AI-менеджер музейного комплекса в агентной симуляции. Каждый шаг (день) ты получаешь JSON с текущим состоянием и должен выбрать ОДНО действие.\n\n"
            + "## ВАЖНО: единицы измерения\n\n"
            + "ВСЕ числовые значения в JSON уже в ПРОЦЕНТАХ (шкала 0–100). НЕ умножай на 100!\n"
            + "Примеры правильного чтения:\n"
            + "  wear=0.4 -> износ всего 0.4% (отлично, почти новое!)\n"
            + "  wear=5.0 -> износ 5% (низкий, ремонт не нужен)\n"
            + "  wear=25.0 -> износ 25% (умеренный, можно подумать о ремонте)\n"
            + "  wear=60.0 -> износ 60% (КРИТИЧЕСКИЙ, срочно нужен ремонт!)\n"
            + "  attractiveness=65 -> привлекательность 65% (хорошо)\n"
            + "  mobile_network=50 -> мобильная сеть 50% (средне)\n\n"
            + "## Стратегия по износу (wear)\n\n"
            + "- wear < 20: отличное состояние, ремонт НЕ нужен. Инвестируй в инфраструктуру.\n"
            + "- wear 20–40: нормально, ремонт пока не нужен. Можно инвестировать.\n"
            + "- wear 40–60: повышенный износ, стоит запланировать ремонт.\n"
            + "- wear > 60: КРИТИЧНО! Немедленно request_repair.\n\n"
            + "## Доступные действия\n\n"
            + "1. skip — пропустить ход (ничего не делать)\n"
            + "2. invest_attractiveness — вложить 500 в привлекательность (+10%, макс 100%)\n"
            + "3. invest_infra:<factor> — вложить 500 в инфраструктурный фактор (+10%, макс 100%)\n"
            + "   Факторы: mobile_network, payment_system, transport_access, internet_quality, navigation_access, service_availability\n"
            + "4. request_repair — запросить котировку на ремонт у реставратора (ТОЛЬКО при wear > 40!)\n"
            + "5. accept_quote — принять текущую котировку (если есть pending_quote)\n"
            + "6. reject_quote — отклонить текущую котировку\n\n"
            + "## Правила\n\n"
            + "- Каждое вложение стоит 500. Не трать если бюджет < monthly_expenses + 500.\n"
            + "- НЕ запрашивай ремонт при низком wear (< 20). Это пустая трата денег.\n"
            + "- Для ремонта: сначала request_repair, потом жди котировку (pending_quote != null), потом accept_quote или reject_quote.\n"
            + "- Нельзя request_repair если repairing=true или negotiating=true.\n"
            + "- accept_quote/reject_quote можно ТОЛЬКО при pending_quote != null.\n"
            + "- Инфраструктура деградирует каждый день. Низкие значения снижают посещаемость.\n"
            + "- attractiveness влияет на желание посетителей прийти. avg_review влияет на attractiveness.\n"
            + "- Сезоны влияют на поток: summer > spring > autumn > winter.\n"
            + "- Держи бюджет выше monthly_expenses * 2 как запас прочности.\n\n"
            + "## Формат ответа\n\n"
            + "Отвечай ТОЛЬКО валидным JSON (без markdown, без ```):\n"
            + "{\"action\": \"<действие>\", \"target\": \"<factor или none>\", \"reasoning\": \"<кратко почему>\"}\n\n"
            + "Примеры:\n"
            + "{\"action\": \"invest_infra\", \"target\": \"mobile_network\", \"reasoning\": \"wear=0.6% — отличное состояние, вкладываем в инфру\"}\n"
            + "{\"action\": \"invest_attractiveness\", \"target\": \"none\", \"reasoning\": \"attractiveness=35% слишком низкая, wear=3% — всё ок\"}\n"
            + "{\"action\": \"request_repair\", \"target\": \"none\", \"reasoning\": \"wear=62% — критический износ, нужен ремонт\"}\n"
            + "{\"action\": \"skip\", \"target\": \"none\", \"reasoning\": \"negotiating=true, ждём котировку\"}";

    public AIClient() {
        this.apiKey = System.getenv("OPENROUTER_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            logger.warning("OPENROUTER_API_KEY not set — AI manager will always skip");
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.conversationHistory = new ArrayList<>();
    }

    public String[] decide(Map<String, Object> state) {
        if (apiKey == null || apiKey.isBlank()) {
            return new String[]{"skip", "none"};
        }

        String userMessage = mapToJson(state);
        conversationHistory.add(new String[]{"user", userMessage});

        // Trim to last MAX_EXCHANGES exchanges (2 entries each)
        while (conversationHistory.size() > MAX_EXCHANGES * 2) {
            conversationHistory.remove(0);
        }

        try {
            StringBuilder messagesJson = new StringBuilder("[");
            messagesJson.append(messageJson("system", SYSTEM_PROMPT));
            for (String[] entry : conversationHistory) {
                messagesJson.append(",").append(messageJson(entry[0], entry[1]));
            }
            messagesJson.append("]");

            String body = "{\"model\":" + jsonString(MODEL)
                    + ",\"messages\":" + messagesJson
                    + ",\"temperature\":0.3"
                    + ",\"max_tokens\":200}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warning("OpenRouter API error " + response.statusCode() + ": " + response.body());
                conversationHistory.remove(conversationHistory.size() - 1);
                return new String[]{"skip", "none"};
            }

            logger.info("AI response: " + response.body());

            String content = extractContent(response.body());
            if (content == null) {
                logger.warning("Could not extract content from API response");
                conversationHistory.remove(conversationHistory.size() - 1);
                return new String[]{"skip", "none"};
            }

            conversationHistory.add(new String[]{"assistant", content});
            return parseDecision(content);

        } catch (Exception e) {
            logger.warning("AI decision failed: " + e.getMessage());
            conversationHistory.remove(conversationHistory.size() - 1);
            return new String[]{"skip", "none"};
        }
    }

    private String[] parseDecision(String content) {
        try {
            String cleaned = content.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }

            String action = extractJsonStringField(cleaned, "action");
            String target = extractJsonStringField(cleaned, "target");
            String reasoning = extractJsonStringField(cleaned, "reasoning");

            if (action == null) {
                logger.warning("No 'action' field in AI response: " + content);
                return new String[]{"skip", "none"};
            }
            if (target == null) target = "none";

            // Handle invest_infra:<factor> format
            if (action.startsWith("invest_infra:")) {
                target = action.substring("invest_infra:".length());
                action = "invest_infra";
            }

            if (reasoning != null && !reasoning.isEmpty()) {
                logger.info("AI reasoning: " + reasoning);
            }

            return new String[]{action, target};
        } catch (Exception e) {
            logger.warning("Failed to parse AI response: " + content);
            return new String[]{"skip", "none"};
        }
    }

    // --- Minimal JSON helpers (no external dependencies) ---

    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }

    private static String messageJson(String role, String content) {
        return "{\"role\":" + jsonString(role) + ",\"content\":" + jsonString(content) + "}";
    }

    @SuppressWarnings("unchecked")
    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(jsonString(e.getKey())).append(":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Map) {
                sb.append(mapToJson((Map<String, Object>) v));
            } else if (v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof Number) {
                double d = ((Number) v).doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    sb.append((long) d);
                } else {
                    sb.append(String.format(Locale.US, "%.1f", d));
                }
            } else {
                sb.append(jsonString(v.toString()));
            }
        }
        sb.append("}");
        return sb.toString();
    }

    /** Extract "content" string from OpenRouter chat completion response. */
    private static String extractContent(String responseBody) {
        // Find "choices"[0]."message"."content":"..."
        int contentIdx = responseBody.indexOf("\"content\"");
        if (contentIdx < 0) return null;

        // Walk through: find "choices" -> "message" -> "content"
        // Strategy: find the "content" that appears after "message"
        int msgIdx = responseBody.indexOf("\"message\"");
        if (msgIdx < 0) return null;
        int cIdx = responseBody.indexOf("\"content\"", msgIdx);
        if (cIdx < 0) return null;

        return extractStringValue(responseBody, cIdx);
    }

    /** Extract a string field value from a JSON object string. */
    private static String extractJsonStringField(String json, String field) {
        String search = "\"" + field + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        return extractStringValue(json, idx);
    }

    /** Given position of a "key", find the string value after the colon. */
    private static String extractStringValue(String json, int keyPos) {
        int colon = json.indexOf(':', keyPos);
        if (colon < 0) return null;

        // Skip whitespace after colon
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;

        // Parse the quoted string (handling escape sequences)
        i++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default: sb.append(next); break;
                }
                i += 2;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
                i++;
            }
        }
        return null;
    }
}
