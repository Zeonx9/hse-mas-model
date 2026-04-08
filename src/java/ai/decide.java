package ai;

import jason.asSemantics.DefaultInternalAction;
import jason.asSemantics.TransitionSystem;
import jason.asSemantics.Unifier;
import jason.asSyntax.*;
import jason.bb.BeliefBase;

import java.util.*;
import java.util.logging.Logger;

public class decide extends DefaultInternalAction {

    private static final Logger logger = Logger.getLogger(decide.class.getName());
    private static final AIClient client = new AIClient();

    private static final String[] INFRA_FACTORS = {
            "mobile_network", "payment_system", "transport_access",
            "internet_quality", "navigation_access", "service_availability"
    };

    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {
        BeliefBase bb = ts.getAg().getBB();
        Map<String, Object> state = extractState(bb);

        String[] result = client.decide(state);

        return un.unifies(args[0], new StringTermImpl(result[0]))
                && un.unifies(args[1], new StringTermImpl(result[1]));
    }

    private Map<String, Object> extractState(BeliefBase bb) {
        Map<String, Object> state = new LinkedHashMap<>();

        state.put("day", getNumericBelief(bb, "step", 0));
        state.put("season", getStringBelief(bb, "season", "winter"));
        state.put("budget", getNumericBelief(bb, "budget", 0));
        state.put("wear", getNumericBelief(bb, "wear", 0));
        state.put("attractiveness", getNumericBelief(bb, "attractiveness", 50));
        state.put("avg_review", getNumericBelief(bb, "avg_review", 50));

        Map<String, Object> infra = new LinkedHashMap<>();
        for (String factor : INFRA_FACTORS) {
            infra.put(factor, getNumericBelief(bb, factor, 50));
        }
        state.put("infrastructure", infra);

        String repairingVal = getStringBelief(bb, "repairing", "no");
        state.put("repairing", repairingVal.equals("yes"));

        String negotiatingVal = getStringBelief(bb, "negotiating", "no");
        state.put("negotiating", negotiatingVal.equals("yes"));

        // Check for pending quote
        Map<String, Object> pendingQuote = getQuoteBelief(bb);
        state.put("pending_quote", pendingQuote);

        state.put("museum_capacity", getNumericBelief(bb, "museum_slots_free", 10));
        state.put("ticket_price", getNumericBelief(bb, "museum_price", 20));
        state.put("hotel_price", getNumericBelief(bb, "hotel_price", 30));

        return state;
    }

    private double getNumericBelief(BeliefBase bb, String functor, double defaultVal) {
        Iterator<Literal> it = bb.getCandidateBeliefs(new PredicateIndicator(functor, 1));
        if (it != null && it.hasNext()) {
            Literal l = it.next();
            if (l.getArity() >= 1) {
                try {
                    return ((NumberTerm) l.getTerm(0)).solve();
                } catch (Exception ignored) {}
            }
        }
        return defaultVal;
    }

    private String getStringBelief(BeliefBase bb, String functor, String defaultVal) {
        Iterator<Literal> it = bb.getCandidateBeliefs(new PredicateIndicator(functor, 1));
        if (it != null && it.hasNext()) {
            Literal l = it.next();
            if (l.getArity() >= 1) {
                return l.getTerm(0).toString();
            }
        }
        return defaultVal;
    }

    private Map<String, Object> getQuoteBelief(BeliefBase bb) {
        Iterator<Literal> it = bb.getCandidateBeliefs(new PredicateIndicator("repair_quote", 3));
        if (it != null && it.hasNext()) {
            Literal l = it.next();
            try {
                Map<String, Object> quote = new LinkedHashMap<>();
                quote.put("price", ((NumberTerm) l.getTerm(0)).solve());
                quote.put("delay", ((NumberTerm) l.getTerm(1)).solve());
                return quote;
            } catch (Exception ignored) {}
        }
        return null;
    }
}
