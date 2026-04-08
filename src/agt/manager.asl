// Museum Complex - Manager Agent (AI-Powered)
// Mechanical actions (priority 0/0.5) stay hardcoded.
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
   <- -repair_refused(_);
      -repair_quote(_, _, _);
      -repair_price(_);
      -+negotiating(yes);
      -+negotiating_since(Day);
      .send(restorer, achieve, quote_request(Day));
      skip.

+!execute_decision(Day, "accept_quote", _)
   : negotiating(yes) & negotiating_since(Since) & repair_quote(Price, Delay, Since)
   <- .print("Day ", Day, ": Accepting quote price=", Price, " delay=", Delay);
      +repair_pending_delay(Delay);
      +repair_price(Price);
      -+negotiating(no);
      -repair_quote(Price, Delay, Since);
      skip.

+!execute_decision(Day, "reject_quote", _)
   : negotiating(yes) & negotiating_since(Since) & repair_quote(Price, Delay, Since)
   <- .print("Day ", Day, ": Rejecting quote price=", Price);
      -+negotiating(no);
      -repair_quote(Price, Delay, Since);
      skip.

/* Fallback — invalid/impossible AI action */
+!execute_decision(_, _, _) <- skip.
-!execute_decision(_, _, _) <- skip.

/* Default */
+!do_one_action(_) <- skip.
-!do_one_action(_) <- skip.

/* Stale beliefs cleanup */
+repair_quote(_, _, _) <- -repair_quote(_, _, _).
+repair_refused(_) <- -repair_refused(_).
