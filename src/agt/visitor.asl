// Museum Complex - Visitor Agent
// 3-stage decision chain: transport → payment → desire
// P(visit) = P(arrival) × P(payment) × P(desire)

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

/* Stage 1: Transport gate */
+!do_one_action
   :  transport_access(TA) & museum_price(P) & my_budget(B) &
      museum_slots_free(F) & F > 0 & B >= P
   <- ArrivalProb = TA / 100;
      .random(R1);
      if (R1 < ArrivalProb) { !attempt_payment; } else { refuse_visit; }.

+!do_one_action <- refuse_visit.

/* Stage 2: Payment gate */
+!attempt_payment : payment_system(PS)
   <- PayProb = PS / 100;
      .random(R2);
      if (R2 < PayProb) { !check_desire; } else { refuse_visit; }.
+!attempt_payment <- refuse_visit.
-!attempt_payment <- refuse_visit.

/* Stage 3: Desire check (satisfaction from infra + attractiveness) */
+!check_desire
   :  mobile_network(MN) & navigation_access(NA) & service_availability(SA) &
      internet_quality(IQ) & attractiveness(A) & wear(W) & season_factor(SF)
   <- Satisfaction = (MN + NA + SA) / 3;
      VisitChance = (A + Satisfaction + IQ / 2 - W / 2) * SF / 250;
      .random(R3);
      if (R3 < VisitChance) { !try_visit; } else { refuse_visit; }.
+!check_desire <- refuse_visit.
-!check_desire <- refuse_visit.

/* Visit with potential hotel stay */
+!try_visit
   :  hotel_rooms_free(H) & hotel_price(HP) & museum_price(P) & my_budget(B) &
      H > 0 & B >= P + HP &
      mobile_network(MN) & service_availability(SA) & navigation_access(NA) &
      internet_quality(IQ) & wear(W)
   <- .random(RN); Noise = RN * 20 - 10;
      Review0 = (MN + SA + NA + IQ) / 4 - W / 2 + Noise;
      Review = math.max(0, math.min(100, Review0));
      .random(R);
      if (R < 0.3) {
         visit(yes, Review);
         -+my_budget(B - P - HP);
      } else {
         visit(no, Review);
         -+my_budget(B - P);
      }.

+!try_visit
   :  museum_price(P) & my_budget(B) & B >= P &
      mobile_network(MN) & service_availability(SA) & navigation_access(NA) &
      internet_quality(IQ) & wear(W)
   <- .random(RN); Noise = RN * 20 - 10;
      Review0 = (MN + SA + NA + IQ) / 4 - W / 2 + Noise;
      Review = math.max(0, math.min(100, Review0));
      visit(no, Review);
      -+my_budget(B - P).

+!try_visit <- refuse_visit.

/* Failure handlers */
-!do_one_action <- refuse_visit.
-!try_visit <- refuse_visit.
-!earn_daily.
-!init_visitor <- .print("init failed").
