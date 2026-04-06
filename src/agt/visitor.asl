// Museum Complex - Visitor Agent
// Each day: earn small income, then probabilistically decide to visit.
// Visit probability = (A + I - W/2) * SF / 200
// Winter ~15%, Summer ~50%

/* Initial beliefs */
my_budget(0).

/* Initial goal */
!init_visitor.

+!init_visitor
   <- .random(R);
      -+my_budget(200 + R * 300).

/* React to simulation step */
+step(Day) : true
   <- !earn_daily;
      !do_one_action.

/* Daily passive income (+10..+30) */
+!earn_daily
   <- .random(R);
      ?my_budget(Old);
      -+my_budget(Old + 10 + R * 20).

/* Has money, museum has space -> roll desire check */
+!do_one_action
   :  season_factor(SF) & attractiveness(A) & infrastructure(I) &
      wear(W) & museum_price(P) & my_budget(B) & museum_slots_free(F) &
      F > 0 & B >= P
   <- .random(Desire);
      VisitChance = (A + I - W / 2) * SF / 200;
      if (Desire < VisitChance) {
         !try_visit;
      } else {
         refuse_visit;
      }.

/* Can't afford or no space */
+!do_one_action <- refuse_visit.

/* Visit with potential hotel stay */
+!try_visit
   :  hotel_rooms_free(H) & hotel_price(HP) & museum_price(P) & my_budget(B) &
      H > 0 & B >= P + HP
   <- .random(R);
      if (R < 0.3) {
         visit(yes);
         -+my_budget(B - P - HP);
      } else {
         visit(no);
         -+my_budget(B - P);
      }.

+!try_visit
   :  museum_price(P) & my_budget(B) & B >= P
   <- visit(no);
      -+my_budget(B - P).

+!try_visit <- refuse_visit.

/* Failure handlers */
-!do_one_action <- refuse_visit.
-!try_visit <- refuse_visit.
-!earn_daily.
-!init_visitor <- .print("init failed").
