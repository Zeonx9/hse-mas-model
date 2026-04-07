// Museum Complex - Restorer Agent (Economic Agent)
// RULE: exactly ONE environment action per step.

/* Initial beliefs */
workload(0).
available(yes).

/* Step handler: update state + one env action */
+step(Day) : true
   <- !update_econ_state;
      !restorer_action.

+!restorer_action : repairing(yes) & wear(W) & W > 20
   <- do_repair.

+!restorer_action
   <- skip.

/* Economic state update (no env action) */
+!update_econ_state
   <- .random(Rw);
      ?workload(Wl);
      !update_workload(Wl, Rw);
      .random(Ra);
      !update_availability(Ra).

/* Workload: increase (cap 2) */
+!update_workload(Wl, Rw) : Rw < 0.3 & Wl < 2
   <- NewWl = Wl + 1; -+workload(NewWl).
+!update_workload(_, Rw) : Rw < 0.3
   <- -+workload(2).
/* Workload: decrease (floor 0) */
+!update_workload(Wl, Rw) : Rw < 0.5 & Wl > 0
   <- NewWl = Wl - 1; -+workload(NewWl).
+!update_workload(_, Rw) : Rw < 0.5
   <- -+workload(0).
/* Workload: no change */
+!update_workload(_, _) <- true.

/* Availability: 15% chance to flip */
+!update_availability(Ra) : Ra < 0.15 & available(yes) <- -+available(no).
+!update_availability(Ra) : Ra < 0.15 & available(no)  <- -+available(yes).
+!update_availability(_) <- true.

/* Quote request handler (triggered by manager's achieve message) */
+!quote_request(Day)
   : available(no)
   <- .print("Day ", Day, ": Restorer unavailable, refusing.");
      .send(manager, tell, repair_refused(Day)).

+!quote_request(Day)
   : workload(Wl) & Wl >= 2
   <- .print("Day ", Day, ": Restorer overloaded (workload=", Wl, "), refusing.");
      .send(manager, tell, repair_refused(Day)).

+!quote_request(Day)
   : wear(Wear) & workload(Wl) & available(yes)
   <- Price = Wear * 100 + Wl * 500;
      .random(Rd);
      if (Rd < 0.2) {
         Delay = 1;
      } else {
         Delay = 0;
      };
      .print("Day ", Day, ": Quote: price=", Price, ", delay=", Delay,
             " (wear=", Wear, ", workload=", Wl, ")");
      .send(manager, tell, repair_quote(Price, Delay, Day)).

/* Failure handlers */
-!restorer_action    <- skip.
-!update_econ_state.
-!quote_request(Day)
   <- .print("Day ", Day, ": Quote request plan failed.");
      .send(manager, tell, repair_refused(Day)).
