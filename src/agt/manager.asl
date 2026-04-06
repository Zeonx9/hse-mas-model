// Museum Complex - Manager Agent
// RULE: exactly ONE environment action per step (invest/order_repair/skip)
// Priority: repair > attractiveness > infrastructure > skip

/* React to new simulation step */
+step(Day) : true
   <- !do_one_action(Day).

/* Priority 1: Order repair when wear is high AND no repair in progress */
+!do_one_action(Day)
   :  wear(W) & W > 60 & repairing(no) & budget(B) & B >= 300
   <- .print("Day ", Day, ": Ordering repair, wear=", W, "%, budget=", B);
      .send(restorer, achieve, repair);
      order_repair.

/* Priority 2: Invest in attractiveness when it drops too low */
+!do_one_action(Day)
   :  attractiveness(A) & A < 40 & budget(B) & B >= 500
   <- .print("Day ", Day, ": Investing in attractiveness (", A, "), budget=", B);
      invest_attractiveness.

/* Priority 3: Invest in infrastructure when it drops too low */
+!do_one_action(Day)
   :  infrastructure(I) & I < 40 & budget(B) & B >= 500
   <- .print("Day ", Day, ": Investing in infrastructure (", I, "), budget=", B);
      invest_infrastructure.

/* Nothing to do — still must act to satisfy TSE */
+!do_one_action(_)
   <- skip.

/* Failure handler */
-!do_one_action(_) <- skip.
