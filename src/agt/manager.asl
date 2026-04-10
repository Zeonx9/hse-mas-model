// Museum Complex - Manager Agent (Negotiation State Machine)
// RULE: exactly ONE environment action per step.

/* Initial beliefs */
negotiating(no).
negotiating_since(0).
budget_reserve(5000). /* monthly fixed expenses */
repair_fund(6000).    /* target savings for repair */

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

/* Priority 1: critical wear (>60), idle → mandatory repair request */
+!do_one_action(Day)
   : wear(W) & W > 60 & repairing(no) & negotiating(no) & budget(B) & B >= 300
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
   : negotiating(yes) & negotiating_since(Since) & repair_quote(Price, Delay, Since) &
     budget(B) & budget_reserve(R)
   <- Threshold = B - R;
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

/* Priority 3: Low reviews + wear > 20 → probabilistic repair vs invest */
+!do_one_action(Day)
   : avg_review(AR) & AR < 50 & wear(W) & W > 20 & W <= 60 &
     repairing(no) & negotiating(no) &
     budget(B) & budget_reserve(R) & B >= R * 2 + 500 &
     mobile_network(MN) & payment_system(PS) & transport_access(TA) &
     internet_quality(IQ) & navigation_access(NA) & service_availability(SA)
   <- InfraAvg = (MN + PS + TA + IQ + NA + SA) / 6;
      PRepair = W / (W + (100 - InfraAvg));
      .random(R1);
      .print("Day ", Day, ": Reviews low (avg=", AR, "), wear=", W,
             "%, infra=", InfraAvg, ", P(repair)=", PRepair);
      if (R1 < PRepair) {
         .print("Day ", Day, ": → early repair request.");
         -repair_refused(_);
         -repair_quote(_, _, _);
         -repair_price(_);
         -+negotiating(yes);
         -+negotiating_since(Day);
         .send(restorer, achieve, quote_request(Day));
         skip;
      } else {
         .print("Day ", Day, ": → investing in infrastructure.");
         !try_invest(Day);
      }.

/* Priority 4: Probabilistic investment */
+!do_one_action(Day)
   : budget(B) & budget_reserve(R) & B >= R * 2 + 500 &
     wear(W) & repair_fund(RF) & invest_min_prob(MinP)
   <- Available = B - R;
      if (Available < RF & W > 20) {
         /* Tight on repair fund + noticeable wear → may skip invest */
         InvestProb = math.max(MinP, 1 - W / 100);
         .random(R2);
         if (R2 < InvestProb) {
            !try_invest(Day);
         } else {
            .print("Day ", Day, ": Saving for repairs (wear=", W, "%, available=", Available, ").");
            skip;
         };
      } else {
         /* Enough money or low wear → always invest */
         !try_invest(Day);
      }.

/* Default */
+!do_one_action(_) <- skip.
-!do_one_action(_) <- skip.

/* === Investment sub-goal === */

+!try_invest(Day)
   : attractiveness(A) & A < 40 &
     budget(B) & budget_reserve(R) & B >= R * 2 + 500
   <- .print("Day ", Day, ": Investing in attractiveness (", A, "), budget=", B);
      invest_attractiveness.

+!try_invest(Day)
   : mobile_network(MN) & payment_system(PS) & transport_access(TA) &
     internet_quality(IQ) & navigation_access(NA) & service_availability(SA) &
     budget(B) & budget_reserve(R) & B >= R * 2 + 500
   <- !find_weakest_infra(MN, PS, TA, IQ, NA, SA, Day).

+!try_invest(_) <- skip.
-!try_invest(_) <- skip.

/* === Find weakest infrastructure factor === */

/* mobile_network is weakest */
+!find_weakest_infra(MN, PS, TA, IQ, NA, SA, Day)
   : PS >= MN & TA >= MN & IQ >= MN & NA >= MN & SA >= MN & MN < 60
   <- .print("Day ", Day, ": Investing in mobile_network (", MN, ")");
      invest_infra(mobile_network).

/* payment_system is weakest */
+!find_weakest_infra(MN, PS, TA, IQ, NA, SA, Day)
   : MN >= PS & TA >= PS & IQ >= PS & NA >= PS & SA >= PS & PS < 60
   <- .print("Day ", Day, ": Investing in payment_system (", PS, ")");
      invest_infra(payment_system).

/* transport_access is weakest */
+!find_weakest_infra(MN, PS, TA, IQ, NA, SA, Day)
   : MN >= TA & PS >= TA & IQ >= TA & NA >= TA & SA >= TA & TA < 60
   <- .print("Day ", Day, ": Investing in transport_access (", TA, ")");
      invest_infra(transport_access).

/* internet_quality is weakest */
+!find_weakest_infra(MN, PS, TA, IQ, NA, SA, Day)
   : MN >= IQ & PS >= IQ & TA >= IQ & NA >= IQ & SA >= IQ & IQ < 60
   <- .print("Day ", Day, ": Investing in internet_quality (", IQ, ")");
      invest_infra(internet_quality).

/* navigation_access is weakest */
+!find_weakest_infra(MN, PS, TA, IQ, NA, SA, Day)
   : MN >= NA & PS >= NA & TA >= NA & IQ >= NA & SA >= NA & NA < 60
   <- .print("Day ", Day, ": Investing in navigation_access (", NA, ")");
      invest_infra(navigation_access).

/* service_availability is weakest */
+!find_weakest_infra(MN, PS, TA, IQ, NA, SA, Day)
   : MN >= SA & PS >= SA & TA >= SA & IQ >= SA & NA >= SA & SA < 60
   <- .print("Day ", Day, ": Investing in service_availability (", SA, ")");
      invest_infra(service_availability).

/* all above 60 — nothing to invest */
+!find_weakest_infra(_, _, _, _, _, _, _) <- skip.
-!find_weakest_infra(_, _, _, _, _, _, _) <- skip.

/* Stale beliefs cleanup — drop responses that don't match current negotiation */
+repair_quote(_, _, _)   <- -repair_quote(_, _, _).
+repair_refused(_)       <- -repair_refused(_).
