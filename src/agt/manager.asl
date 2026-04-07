// Museum Complex - Manager Agent (Negotiation State Machine)
// RULE: exactly ONE environment action per step.

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

/* Priority 1: high wear, idle → request quote (clear stale beliefs first) */
+!do_one_action(Day)
   : wear(W) & W > 30 & repairing(no) & negotiating(no) & budget(B) & B >= 300
   <- .print("Day ", Day, ": Wear=", W, "%, requesting quote from restorer.");
      -repair_refused(_);
      -repair_quote(_, _, _);
      -repair_price(_);
      -+negotiating(yes);
      -+negotiating_since(Day);
      .send(restorer, achieve, quote_request(Day));
      skip.

/* Priority 2a: matching quote arrived → process it */
+!do_one_action(Day)
   : negotiating(yes) & negotiating_since(Since) & repair_quote(Price, Delay, Since) & budget(B)
   <- Threshold = B - 1000;
      .print("Day ", Day, ": Quote: price=", Price, ", delay=", Delay,
             " | budget=", B, ", threshold=", Threshold);
      if (Threshold >= Price) {
         .print("Quote accepted.");
         +repair_pending_delay(Delay);
         +repair_price(Price);
         -+negotiating(no);
      } else {
         .print("Quote too expensive (", Price, " > ", Threshold, ").");
         -+negotiating(no);
      };
      -repair_quote(Price, Delay, Since);
      skip.

/* Priority 2b: matching refusal arrived → reset */
+!do_one_action(Day)
   : negotiating(yes) & negotiating_since(Since) & repair_refused(Since)
   <- .print("Day ", Day, ": Restorer refused, will retry next cycle.");
      -repair_refused(Since);
      -+negotiating(no);
      skip.

/* Priority 2c: timeout — no response for 3+ steps → reset */
+!do_one_action(Day)
   : negotiating(yes) & negotiating_since(Since) & Day > Since + 3
   <- .print("Day ", Day, ": Quote timeout (sent day ", Since, "), resetting.");
      -+negotiating(no);
      skip.

/* Priority 2d: still waiting */
+!do_one_action(Day)
   : negotiating(yes)
   <- .print("Day ", Day, ": Awaiting repair quote...");
      skip.

/* Priority 3: invest attractiveness */
+!do_one_action(Day)
   : attractiveness(A) & A < 40 & budget(B) & B >= 500
   <- .print("Day ", Day, ": Investing in attractiveness (", A, "), budget=", B);
      invest_attractiveness.

/* Priority 4: invest infrastructure */
+!do_one_action(Day)
   : infrastructure(I) & I < 40 & budget(B) & B >= 500
   <- .print("Day ", Day, ": Investing in infrastructure (", I, "), budget=", B);
      invest_infrastructure.

/* Default */
+!do_one_action(_) <- skip.
-!do_one_action(_) <- skip.

/* Stale beliefs cleanup — drop responses that don't match current negotiation */
+repair_quote(_, _, _)   <- -repair_quote(_, _, _).
+repair_refused(_)       <- -repair_refused(_).
