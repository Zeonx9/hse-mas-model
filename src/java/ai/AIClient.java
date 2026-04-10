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
            + "  wear=0.004 -> износ 0.4% (отлично). wear=0.6 -> износ 60% (критический).\n"
            + "  attractiveness=0.65 -> 65%. mobile_network=0.5 -> 50%.\n"
            + "budget, monthly_expenses, цены — абсолютные значения в денежных единицах.\n\n"
            + "## Управление бюджетом (ПРИОРИТЕТ #1)\n\n"
            + "Бюджет — самый важный ресурс. Уйти в минус = проиграть.\n\n"
            + "В JSON приходят поля: budget, monthly_expenses (5000), days_until_expenses.\n"
            + "Каждые 30 дней списывается monthly_expenses. Поле days_until_expenses показывает сколько дней осталось до следующего списания.\n\n"
            + "### Правило безопасного бюджета\n"
            + "safe_budget = monthly_expenses (чтобы точно пережить следующее списание).\n"
            + "repair_reserve = если wear > 0.2 то примерно wear * 10000, иначе 0.\n"
            + "available = budget - safe_budget - repair_reserve.\n"
            + "max_investments = available / 500 (округлить вниз, минимум 0).\n"
            + "Ты можешь держать запас больше safe_budget если считаешь нужным (зима, низкий доход), но не обязана.\n\n"
            + "### ВАЖНО: трать излишки!\n"
            + "Деньги сверх (safe_budget + repair_reserve) должны РАБОТАТЬ.\n"
            + "Инвестиции → больше посетителей → больше дохода. Копить сверх резервов бессмысленно.\n"
            + "Но НЕ трать repair_reserve на инвестиции — ремонт важнее!\n\n"
            + "### Сезонный контекст\n"
            + "Доход зависит от посетителей. Посетители зависят от: сезона, инфраструктуры, attractiveness.\n"
            + "- winter: мало посетителей, но инвестиции всё равно нужны если бюджет позволяет.\n"
            + "- spring/summer/autumn: активно инвестируй, особенно летом.\n"
            + "Запас на зиму: к day%365=335 желательно иметь budget >= monthly_expenses * 2.\n\n"
            + "## РЕМОНТ — ОБЯЗАТЕЛЬНЫЕ ПРАВИЛА (выполняй ДО инвестиций!)\n\n"
            + "### Алгоритм (выполняй СВЕРХУ ВНИЗ, первое подходящее):\n\n"
            + "1. pending_quote != null → accept_quote.\n"
            + "2. repair_refused == true → request_repair (повтор).\n"
            + "3. wear >= 0.3 И repairing==false И negotiating==false → request_repair.\n"
            + "   ПОЧЕМУ: высокий wear снижает отзывы посетителей → падает attractiveness → меньше дохода.\n"
            + "   Чем раньше починишь, тем дешевле (цена ~ wear*10000) и тем быстрее вернутся посетители.\n"
            + "4. wear >= 0.2 И все факторы инфры >= 0.9 И repairing==false И negotiating==false → request_repair.\n"
            + "   ПОЧЕМУ: инвестировать некуда (всё >= 0.9), лучше снизить wear для лучших отзывов.\n"
            + "5. negotiating==true → skip (ждём ответ, котировка придёт через 1-2 дня).\n"
            + "6. repairing==true → можно invest_infra пока ремонт идёт (если есть куда).\n"
            + "7. Есть факторы < 0.9 → invest_infra в самый низкий.\n"
            + "8. Всё >= 0.9 и wear < 0.2 → skip.\n\n"
            + "### Зачем ремонтировать?\n"
            + "- Высокий wear СНИЖАЕТ отзывы посетителей (review = infra - wear/2).\n"
            + "- Плохие отзывы снижают attractiveness → меньше посетителей → меньше дохода.\n"
            + "- При wear > 0.2 ты ТЕРЯЕШЬ деньги каждый день из-за плохих отзывов.\n"
            + "- Ремонт снижает wear до 0.2 → отзывы улучшаются → доход растёт.\n"
            + "- Цена ремонта ~ wear*10000. При wear=0.3 стоит ~3000, при wear=0.5 ~5000.\n"
            + "- Оплата: 50% сразу, 50% по завершении.\n\n"
            + "### Цикл ремонта\n"
            + "request_repair → котировка через 1-2 дня → auto-accept (хардкод) → ремонт.\n"
            + "Если реставратор отказал → повторяй request_repair.\n\n"
            + "## Доступные действия\n\n"
            + "1. accept_quote — принять котировку. ИСПОЛЬЗУЙ СРАЗУ при pending_quote != null!\n"
            + "2. request_repair — запросить котировку. ПРИОРИТЕТНО при wear >= 0.6.\n"
            + "3. invest_infra:<factor> — вложить 500, фактор +0.1 (макс 1.0). НЕ инвестируй >= 0.9.\n"
            + "   Факторы: mobile_network, payment_system, transport_access, internet_quality, navigation_access, service_availability\n"
            + "4. reject_quote — отклонить котировку (ТОЛЬКО если pending_quote != null, используй РЕДКО).\n"
            + "5. skip — пропустить ход. Когда ждёшь ответа или бюджет низкий.\n\n"
            + "## Прочие правила\n\n"
            + "- request_repair нельзя если repairing=true или negotiating=true.\n"
            + "- НЕ инвестируй в факторы >= 0.9.\n"
            + "- Инвестируй в самый низкий фактор < 0.9.\n"
            + "- Чередуй инвестиции между разными факторами.\n\n"
            + "## Формат ответа\n\n"
            + "Верни JSON-массив ровно из 7 объектов — план на 7 дней. День 1 = сегодня.\n"
            + "ТОЛЬКО валидный JSON, без markdown, без комментариев.\n"
            + "reasoning должен быть КОРОТКИМ — максимум 10 слов на каждый action! Не расписывай вычисления.\n\n"
            + "[{\"action\":\"...\",\"target\":\"...\",\"reasoning\":\"...\"}, ...ещё 6]\n\n"
            + "Пример 1 — wear низкий, инвестируем (wear=0.05, infra 0.38-0.52):\n"
            + "[{\"action\":\"invest_infra\",\"target\":\"mobile_network\",\"reasoning\":\"wear ок, min infra 0.38\"},"
            + "{\"action\":\"invest_infra\",\"target\":\"payment_system\",\"reasoning\":\"следующий 0.42\"},"
            + "{\"action\":\"invest_infra\",\"target\":\"transport_access\",\"reasoning\":\"0.45\"},"
            + "{\"action\":\"invest_infra\",\"target\":\"internet_quality\",\"reasoning\":\"0.48\"},"
            + "{\"action\":\"invest_infra\",\"target\":\"navigation_access\",\"reasoning\":\"0.50\"},"
            + "{\"action\":\"invest_infra\",\"target\":\"service_availability\",\"reasoning\":\"0.52\"},"
            + "{\"action\":\"invest_infra\",\"target\":\"mobile_network\",\"reasoning\":\"цикл, снова min\"}]\n\n"
            + "Пример 2 — wear средний, ремонт (wear=0.35, infra 0.40-0.60, negotiating=false):\n"
            + "[{\"action\":\"request_repair\",\"target\":\"none\",\"reasoning\":\"wear 0.35 портит отзывы, чиним\"},"
            + "{\"action\":\"invest_infra\",\"target\":\"mobile_network\",\"reasoning\":\"пока ждём котировку, min 0.40\"},"
            + "{\"action\":\"invest_infra\",\"target\":\"payment_system\",\"reasoning\":\"0.45\"},"
            + "{\"action\":\"invest_infra\",\"target\":\"transport_access\",\"reasoning\":\"0.48\"},"
            + "{\"action\":\"invest_infra\",\"target\":\"internet_quality\",\"reasoning\":\"0.50\"},"
            + "{\"action\":\"invest_infra\",\"target\":\"navigation_access\",\"reasoning\":\"0.55\"},"
            + "{\"action\":\"invest_infra\",\"target\":\"service_availability\",\"reasoning\":\"0.58\"}]\n\n"
            + "Пример 3 — все факторы высокие, ремонт (wear=0.3, ВСЕ infra >= 0.95):\n"
            + "[{\"action\":\"request_repair\",\"target\":\"none\",\"reasoning\":\"infra вся >=0.9, wear 0.3 портит отзывы\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"ждём котировку\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"ждём котировку\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"ждём ремонт\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"ремонт идёт\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"ремонт идёт\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"ремонт идёт\"}]\n\n"
            + "Пример 4 — критический wear (wear=0.65, negotiating=false):\n"
            + "[{\"action\":\"request_repair\",\"target\":\"none\",\"reasoning\":\"КРИТИЧНО wear 0.65, срочный ремонт\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"ждём котировку\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"ждём котировку\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"ремонт важнее всего\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"ремонт идёт\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"ремонт идёт\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"ремонт идёт\"}]\n\n"
            + "Пример 5 — все факторы высокие, ремонт не требуется (wear=0.22, ВСЕ infra >= 0.95):\n"
            + "[{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"infra вся >=0.9, wear 0.22 ок\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"копим деньги\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"копим деньги\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"копим деньги\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"копим деньги\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"копим деньги\"},"
            + "{\"action\":\"skip\",\"target\":\"none\",\"reasoning\":\"копим деньги\"}]";

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
                    + ",\"max_tokens\":1000}";

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

//            logger.info("AI response: " + response.body());

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

        // Pad incomplete plans with skip (e.g. response truncated by max_tokens)
        int parsed = plan.size();
        while (plan.size() < PLAN_DAYS) {
            plan.add(new String[]{"skip", "none"});
        }
        if (parsed < PLAN_DAYS) {
            logger.warning("AI returned only " + parsed + " actions, padded to " + PLAN_DAYS + " with skip");
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
