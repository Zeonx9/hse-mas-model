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
    private static final int PLAN_DAYS = 7;

    private final String apiKey;
    private final HttpClient httpClient;
    private final List<String[]> conversationHistory; // [role, content]

    // Cached weekly plan: queue of [action, target]
    private final Deque<String[]> planQueue = new ArrayDeque<>();

    private static final String SYSTEM_PROMPT =
            "Ты — AI-менеджер музейного комплекса в агентной симуляции. Ты получаешь JSON с текущим состоянием и должен спланировать действия на следующие 7 дней.\n\n"
            + "## Единицы измерения\n\n"
            + "wear, attractiveness, avg_review и все факторы инфраструктуры — доли от 0.0 до 1.0.\n"
            + "  wear=0.004 -> износ 0.4% (отлично). wear=0.3 -> износ 60% (критический).\n"
            + "  attractiveness=0.65 -> 65%. mobile_network=0.5 -> 50%.\n"
            + "budget, monthly_expenses, цены — абсолютные значения в денежных единицах.\n\n"
            + "## Управление бюджетом (ПРИОРИТЕТ #1)\n\n"
            + "Бюджет — самый важный ресурс. Уйти в минус = проиграть.\n\n"
            + "В JSON приходят поля: budget, monthly_expenses (5000), days_until_expenses.\n"
            + "Каждые 30 дней списывается monthly_expenses. Поле days_until_expenses показывает сколько дней осталось до следующего списания.\n\n"
            + "### Правило безопасного бюджета\n"
            + "safe_budget = monthly_expenses + 2000 (т.е. 7000)\n"
            + "Посчитай max_investments = (budget - safe_budget) / 500 (округлить вниз).\n"
            + "Это максимальное число инвестиций в плане. Остальные дни — skip.\n\n"
            + "### ВАЖНО: трать излишки!\n"
            + "Деньги сверх safe_budget должны РАБОТАТЬ. Копить сверх safe_budget бессмысленно — деньги не приносят процентов.\n"
            + "Инвестиции повышают инфраструктуру → больше посетителей → больше дохода. Это окупается!\n"
            + "Если budget=15000 и safe=7000, то max_investments = (15000-7000)/500 = 16. Ты можешь вложить все 7 дней!\n"
            + "Если budget=9000 и safe=7000, то max_investments = (9000-7000)/500 = 4. Вложи 4 дня, 3 дня skip.\n"
            + "skip оправдан ТОЛЬКО если: budget слишком низкий ИЛИ все факторы инфры уже >= 0.8.\n\n"
            + "### Сезонный контекст\n"
            + "Доход зависит от посетителей. Посетители зависят от: сезона, инфраструктуры, attractiveness.\n"
            + "- winter: мало посетителей, но инвестиции всё равно нужны если бюджет позволяет.\n"
            + "- spring/summer/autumn: активно инвестируй, особенно летом.\n"
            + "Запас на зиму: к day%365=335 желательно иметь budget >= monthly_expenses * 2.\n\n"
            + "## Стратегия по износу (wear)\n\n"
            + "- wear < 0.2: отличное состояние, ремонт НЕ нужен.\n"
            + "- wear 0.2–0.4: нормально, ремонт не нужен.\n"
            + "- wear 0.4–0.6: повышенный, стоит запланировать ремонт.\n"
            + "- wear > 0.6: КРИТИЧНО! Немедленно request_repair.\n"
            + "Ремонт тоже стоит денег (50% сразу, 50% по завершении). Учитывай это в бюджете.\n\n"
            + "## Доступные действия\n\n"
            + "1. skip — пропустить ход. ТОЛЬКО если budget < safe_budget+500 или все факторы >= 0.8.\n"
//            + "2. invest_attractiveness — вложить 500, attractiveness +0.1 (макс 1.0)\n"
            + "2. invest_infra:<factor> — вложить 500, фактор +0.1 (макс 1.0)\n"
            + "   Факторы: mobile_network, payment_system, transport_access, internet_quality, navigation_access, service_availability\n"
            + "3. request_repair — запросить котировку (ТОЛЬКО при wear > 0.4)\n"
            + "4. accept_quote — принять котировку (ТОЛЬКО если pending_quote != null)\n"
            + "5. reject_quote — отклонить котировку (ТОЛЬКО если pending_quote != null)\n\n"
            + "## Прочие правила\n\n"
            + "- НЕ запрашивай ремонт при wear < 0.2.\n"
            + "- Для ремонта: request_repair -> жди pending_quote -> accept_quote/reject_quote.\n"
            + "- Нельзя request_repair если repairing=true или negotiating=true.\n"
            + "- Инфраструктура деградирует ежедневно. Низкие значения отпугивают посетителей.\n"
            + "- Инвестируй в самый низкий фактор инфраструктуры — это даёт максимальный эффект.\n"
            + "- Чередуй инвестиции между разными факторами, не вкладывай в один и тот же подряд.\n\n"
            + "## Формат ответа\n\n"
            + "Верни JSON-массив ровно из 7 объектов — план на 7 дней. День 1 = сегодня.\n"
            + "ТОЛЬКО валидный JSON, без markdown, без комментариев:\n"
            + "[{\"action\":\"...\",\"target\":\"...\",\"reasoning\":\"...\"}, ...ещё 6 объектов]\n\n"
            + "Пример (budget=12000, safe=7000, max_investments=10 → все 7 дней инвестируем):\n"
            + "[\n"
            + "  {\"action\":\"invest_infra\",\"target\":\"mobile_network\",\"reasoning\":\"самый низкий 0.38\"},\n"
            + "  {\"action\":\"invest_infra\",\"target\":\"payment_system\",\"reasoning\":\"0.42 — второй\"},\n"
            + "  {\"action\":\"invest_infra\",\"target\":\"transport_access\",\"reasoning\":\"0.45\"},\n"
            + "  {\"action\":\"invest_infra\",\"target\":\"internet_quality\",\"reasoning\":\"0.48\"},\n"
            + "  {\"action\":\"invest_infra\",\"target\":\"navigation_access\",\"reasoning\":\"0.50\"},\n"
            + "  {\"action\":\"invest_infra\",\"target\":\"service_availability\",\"reasoning\":\"0.52\"},\n"
            + "  {\"action\":\"invest_infra\",\"target\":\"mobile_network\",\"reasoning\":\"снова самый низкий после цикла\"}\n"
            + "]";

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

        // Return next action from cached plan if available
        if (!planQueue.isEmpty()) {
            String[] next = planQueue.poll();
            logger.info("AI plan step (cached): " + next[0] + " " + next[1]);
            return next;
        }

        // Plan exhausted — request new 7-day plan from API
        String userMessage = mapToJson(state);
        conversationHistory.add(new String[]{"user", userMessage});

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
                    + ",\"max_tokens\":600}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60))
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

            List<String[]> plan = parsePlan(content);
            if (plan.isEmpty()) {
                return new String[]{"skip", "none"};
            }

            // First action returned immediately, rest queued
            String[] first = plan.get(0);
            for (int i = 1; i < plan.size(); i++) {
                planQueue.add(plan.get(i));
            }

            logger.info("AI plan loaded: " + plan.size() + " actions, executing first: " + first[0] + " " + first[1]);
            return first;

        } catch (Exception e) {
            logger.warning("AI decision failed: " + e.getMessage());
            conversationHistory.remove(conversationHistory.size() - 1);
            return new String[]{"skip", "none"};
        }
    }

    /** Invalidate cached plan — called when unexpected state requires replanning. */
    public void invalidatePlan() {
        planQueue.clear();
    }

    private List<String[]> parsePlan(String content) {
        List<String[]> plan = new ArrayList<>();
        try {
            String cleaned = content.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }

            // Find the JSON array
            int arrStart = cleaned.indexOf('[');
            int arrEnd = cleaned.lastIndexOf(']');
            if (arrStart < 0 || arrEnd < 0 || arrEnd <= arrStart) {
                // Fallback: try parsing as single action
                String[] single = parseSingleAction(cleaned);
                if (single != null) plan.add(single);
                return plan;
            }

            String arrContent = cleaned.substring(arrStart + 1, arrEnd);

            // Split by "},{" — simple but effective for flat array of objects
            // First, find each {...} object
            int depth = 0;
            int objStart = -1;
            for (int i = 0; i < arrContent.length(); i++) {
                char c = arrContent.charAt(i);
                if (c == '{') {
                    if (depth == 0) objStart = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String obj = arrContent.substring(objStart, i + 1);
                        String[] action = parseSingleAction(obj);
                        if (action != null) {
                            plan.add(action);
                        }
                        objStart = -1;
                    }
                }
            }

        } catch (Exception e) {
            logger.warning("Failed to parse AI plan: " + content);
        }

        if (plan.isEmpty()) {
            plan.add(new String[]{"skip", "none"});
        }
        return plan;
    }

    private String[] parseSingleAction(String json) {
        String action = extractJsonStringField(json, "action");
        String target = extractJsonStringField(json, "target");
        String reasoning = extractJsonStringField(json, "reasoning");

        if (action == null) return null;
        if (target == null) target = "none";

        if (action.startsWith("invest_infra:")) {
            target = action.substring("invest_infra:".length());
            action = "invest_infra";
        }

        if (reasoning != null && !reasoning.isEmpty()) {
            logger.info("AI reasoning: " + reasoning);
        }

        return new String[]{action, target};
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
                    sb.append(String.format(Locale.US, "%.4f", d));
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

        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;

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
