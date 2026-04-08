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
    private static final String MODEL = "qwen/qwen3-coder-next";
    private static final int MAX_EXCHANGES = 30;

    private final String apiKey;
    private final HttpClient httpClient;
    private final List<String[]> conversationHistory; // [role, content]

    private static final String SYSTEM_PROMPT =
            "Ты — AI-менеджер музейного комплекса в агентной симуляции. Каждый шаг (день) ты получаешь JSON с текущим состоянием и должен выбрать ОДНО действие.\n\n"
            + "## Единицы измерения\n\n"
            + "wear, attractiveness, avg_review и все факторы инфраструктуры — доли от 0.0 до 1.0.\n"
            + "  wear=0.004 -> износ 0.4% (отлично). wear=0.6 -> износ 60% (критический).\n"
            + "  attractiveness=0.65 -> 65%. mobile_network=0.5 -> 50%.\n"
            + "budget, monthly_expenses, цены — абсолютные значения в денежных единицах.\n\n"
            + "## Управление бюджетом (ПРИОРИТЕТ #1)\n\n"
            + "Бюджет — самый важный ресурс. Уйти в минус = проиграть.\n\n"
            + "В JSON приходят поля: budget, monthly_expenses (5000), days_until_expenses.\n"
            + "Каждые 30 дней списывается monthly_expenses. Поле days_until_expenses показывает сколько дней осталось до следующего списания.\n\n"
            + "### Правило безопасного бюджета\n"
            + "safe_budget = monthly_expenses + 2000 (т.е. 7000)\n"
            + "Если budget < safe_budget → НЕЛЬЗЯ тратить, делай skip.\n"
            + "Если budget >= safe_budget но budget - 500 < safe_budget → тоже skip (вложение стоит 500).\n"
            + "Инвестируй ТОЛЬКО если после траты останется >= safe_budget.\n\n"
            + "### Прогнозирование доходов\n"
            + "Доход зависит от посетителей. Посетители зависят от: сезона, инфраструктуры, attractiveness.\n"
            + "- winter: очень мало посетителей. Копи деньги, не инвестируй без необходимости.\n"
            + "- spring: умеренный поток. Можно осторожно инвестировать.\n"
            + "- summer: пик сезона, максимум дохода. Лучшее время для инвестиций.\n"
            + "- autumn: поток снижается. Начинай копить на зиму.\n\n"
            + "### Подготовка к зиме\n"
            + "Перед зимой (autumn, day%365 > 250) накапливай запас. Зимой доход минимальный, а расходы те же.\n"
            + "Нужен запас минимум на 2-3 месяца: budget >= monthly_expenses * 3 перед зимой.\n\n"
            + "## Стратегия по износу (wear)\n\n"
            + "- wear < 0.2: отличное состояние, ремонт НЕ нужен.\n"
            + "- wear 0.2–0.4: нормально, ремонт не нужен.\n"
            + "- wear 0.4–0.6: повышенный, стоит запланировать ремонт.\n"
            + "- wear > 0.6: КРИТИЧНО! Немедленно request_repair.\n"
            + "Ремонт тоже стоит денег (50% сразу, 50% по завершении). Учитывай это в бюджете.\n\n"
            + "## Доступные действия\n\n"
            + "1. skip — пропустить ход. Используй когда бюджет низкий или нечего улучшать.\n"
//            + "2. invest_attractiveness — вложить 500, attractiveness +0.1 (макс 1.0)\n"
            + "2. invest_infra:<factor> — вложить 500, фактор +0.1 (макс 1.0)\n"
            + "   Факторы: mobile_network, payment_system, transport_access, internet_quality, navigation_access, service_availability\n"
            + "3. request_repair — запросить котировку (ТОЛЬКО при wear > 0.4)\n"
            + "4. accept_quote — принять котировку (ТОЛЬКО если pending_quote != null)\n"
            + "5. reject_quote — отклонить котировку (ТОЛЬКО если pending_quote != null)\n\n"
            + "## ВАЖНО: задержка действий (~4 дня)\n\n"
            + "Твои решения применяются с задержкой примерно 3–4 шага (дня).\n"
            + "Это значит:\n"
            + "- Если ты вложил в mobile_network, его значение вырастет не сразу, а через ~4 дня.\n"
            + "- Если значение фактора не изменилось на следующем шаге после инвестиции — это нормально, жди.\n"
            + "- НЕ инвестируй в тот же фактор повторно, если ты уже вложил в него 1–4 шага назад. Помни свои прошлые решения!\n"
            + "- Планируй наперёд: если wear=0.5 и растёт, запрашивай ремонт сейчас, не жди 0.6.\n\n"
            + "## Прочие правила\n\n"
            + "- НЕ запрашивай ремонт при wear < 0.2.\n"
            + "- Для ремонта: request_repair -> жди pending_quote -> accept_quote/reject_quote.\n"
            + "- Нельзя request_repair если repairing=true или negotiating=true.\n"
            + "- Инфраструктура деградирует ежедневно. Низкие значения отпугивают посетителей.\n"
            + "- Инвестируй в самый низкий фактор инфраструктуры — это даёт максимальный эффект.\n"
            + "- Из-за задержки чередуй инвестиции между разными факторами, не вкладывай в один и тот же подряд.\n\n"
            + "## Формат ответа\n\n"
            + "ТОЛЬКО валидный JSON, без markdown:\n"
            + "{\"action\": \"<действие>\", \"target\": \"<factor или none>\", \"reasoning\": \"<кратко>\"}\n\n"
            + "Примеры:\n"
            + "{\"action\": \"skip\", \"target\": \"none\", \"reasoning\": \"budget=6500 < safe(7000), копим\"}\n"
            + "{\"action\": \"invest_infra\", \"target\": \"mobile_network\", \"reasoning\": \"budget=12000, mobile_network=0.38 — самый низкий\"}\n"
            + "{\"action\": \"skip\", \"target\": \"none\", \"reasoning\": \"autumn, day=280, копим на зиму\"}\n"
            + "{\"action\": \"request_repair\", \"target\": \"none\", \"reasoning\": \"wear=0.62, budget=15000 — можем позволить\"}";

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
