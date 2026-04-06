// Museum Complex - Restorer Agent
// Performs daily repair (do_repair) while repairing(yes) and wear > 20.
// Otherwise skips. RULE: exactly ONE env action per step.

/* When repairing is active and wear still above threshold — work */
+step(_) : repairing(yes) & wear(W) & W > 20
   <- do_repair.

/* Nothing to do */
+step(_) : true
   <- skip.

/* Manager sends achieve repair — acknowledged */
+!repair
   <- .print("Repair order received, starting daily work.").

/* Failure handlers */
-!repair <- .print("Failed to acknowledge repair order.").
