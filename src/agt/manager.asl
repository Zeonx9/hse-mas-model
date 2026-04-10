// Museum Complex - Manager Agent (AI-Powered)
// Mechanical actions (priority 0/0.5/1/1.5) stay hardcoded.
// All strategic decisions delegated to AI via OpenRouter.

/* Initial beliefs */
negotiating(no).
negotiating_since(0).

+step(Day) : true
   <- !do_one_action(Day).

/* Priority 0: repair approved, delay expired → execute */
+!do_one_action(Day)
   : repair_pending_delay(0) & repair_price(Price) & repairing(no)
   <- .print("Day ", Day, ": Executing approved repair (price=", Price, ").");
      -repair_pending_delay(0);
      -repair_price(Price);
      -+negotiating(no);
      order_repair(Price).

/* Priority 0.5: repair approved, counting down one step */
+!do_one_action(Day)
   : repair_pending_delay(D) & D > 0
   <- .print("Day ", Day, ": Repair pending, ", D, " step(s) remaining.");
      -repair_pending_delay(D);
      +repair_pending_delay(0);
      skip.

/* Priority 1: pending quote → auto-accept */
+!do_one_action(Day)
   : repair_quote(Price, Delay, _)
   <- .print("Day ", Day, ": Auto-accepting quote price=", Price, " delay=", Delay);
      +repair_pending_delay(Delay);
      +repair_price(Price);
      -+negotiating(no);
      .abolish(repair_quote(_, _, _));
      skip.

/* Priority 1.5: negotiating timeout (7+ days) → reset and let AI retry */
+!do_one_action(Day)
   : negotiating(yes) & negotiating_since(Since) & Day >= Since + 7
   <- .print("Day ", Day, ": Negotiation timeout (sent day ", Since, "), resetting.");
      -+negotiating(no);
      .abolish(repair_quote(_, _, _));
      .abolish(repair_refused(_));
      skip.

/* All other decisions → AI */
+!do_one_action(Day)
   <- ai.decide(Action, Target);
      .print("Day ", Day, ": AI decision -> ", Action, " ", Target);
      !execute_decision(Day, Action, Target).

/* Dispatch AI decision to env action */
+!execute_decision(Day, "skip", _) <- skip.

+!execute_decision(Day, "invest_attractiveness", _) <- invest_attractiveness.

+!execute_decision(Day, "invest_infra", Target)
   <- .term2string(T, Target); invest_infra(T).

+!execute_decision(Day, "request_repair", _)
   : repairing(no) & negotiating(no)
   <- .abolish(repair_refused(_));
      .abolish(repair_quote(_, _, _));
      -repair_price(_);
      -+negotiating(yes);
      -+negotiating_since(Day);
      .print("Day ", Day, ": Sending quote request to restorer.");
      .send(restorer, achieve, quote_request(Day));
      skip.

+!execute_decision(Day, "accept_quote", _)
   : repair_quote(Price, Delay, _)
   <- .print("Day ", Day, ": Accepting quote price=", Price, " delay=", Delay);
      +repair_pending_delay(Delay);
      +repair_price(Price);
      -+negotiating(no);
      .abolish(repair_quote(_, _, _));
      skip.

+!execute_decision(Day, "reject_quote", _)
   : repair_quote(Price, Delay, _)
   <- .print("Day ", Day, ": Rejecting quote price=", Price);
      -+negotiating(no);
      .abolish(repair_quote(_, _, _));
      skip.

/* Fallback — invalid/impossible AI action */
+!execute_decision(_, _, _) <- skip.
-!execute_decision(_, _, _) <- skip.

/* Default */
+!do_one_action(_) <- skip.
-!do_one_action(_) <- skip.

/* When restorer refuses — reset negotiation state */
+repair_refused(_)
   <- .print("Restorer refused, resetting negotiation.");
      -+negotiating(no);
      .abolish(repair_refused(_)).
