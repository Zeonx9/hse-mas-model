package museum;

import jason.asSyntax.*;
import jason.environment.TimeSteppedEnvironment;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.Random;
import java.util.logging.Logger;

public class MuseumEnv extends TimeSteppedEnvironment {

    private static final Logger logger = Logger.getLogger(MuseumEnv.class.getName());

    private int museumCapacity = 15;
    private int hotelCapacity = 5;
    private int ticketPrice = 100;
    private int hotelPrice = 50;
    private int monthlyExpenditures = 7000;
    private int maxDays = 0;
    private String csvFile = "";
    private boolean experimentMode = false;
    private int numVisitors = 0;
    /** Minimum probability floor for probabilistic investment (manager Priority 4). */
    private double investMinProb = 0.1;

    private int day = 0;
    private String season = "winter";
    private double seasonFactor = 0.3;
    private double wear = 0.0;
    private double attractiveness = 50.0;
    private double mobileNetwork       = 50.0;
    private double paymentSystem       = 50.0;
    private double transportAccess     = 50.0;
    private double internetQuality     = 50.0;
    private double navigationAccess    = 50.0;
    private double serviceAvailability = 50.0;
    private double budget = 5000.0;
    private double initialBudget = 5000.0;
    private double reviewSum = 0;
    private int    reviewCount = 0;
    private static final double REVIEW_WEIGHT = 0.2;
    private double lastAvgReview = 50.0;
    private boolean repairing = false;
    private double repairRemainingPayment = 0;

    private int museumSlotsFree;
    private int hotelRoomsFree;

    private int totalVisits = 0;
    private int totalHotelStays = 0;
    private int totalRepairs = 0;
    private int totalRefusals = 0;
    private volatile int todayVisits = 0;
    private volatile int todayHotelStays = 0;
    private volatile int todayRefusals = 0;

    // Daily revenue and cost accumulators (reset each step)
    private volatile double todayRevenue = 0.0;
    private volatile double todayCost    = 0.0;

    private static final double LOAD_DEGRADE_RATE = 0.15;
    private static final double TIME_DEGRADE_RATE = 0.03;
    private static final double BASE_WEAR             = 0.05;
    private static final double VISITOR_WEAR          = 0.02;
    private static final double RANDOM_DAMAGE_CHANCE  = 0.05;
    private static final double RANDOM_DAMAGE_MIN     = 0.5;
    private static final double RANDOM_DAMAGE_MAX     = 2.0;

    // Utility weights: U = w1*Revenue + w2*Satisfaction + w3*Heritage - w4*Costs
    private static final double W1 = 0.3;
    private static final double W2 = 0.3;
    private static final double W3 = 0.3;
    private static final double W4 = 0.1;
   
    private final Random random = new Random();
    private MuseumChart chart = null;

    @Override
    public void init(String[] args) {
        if (args.length >= 4) {
            museumCapacity = Integer.parseInt(args[0]);
            hotelCapacity  = Integer.parseInt(args[1]);
            ticketPrice    = Integer.parseInt(args[2]);
            hotelPrice     = Integer.parseInt(args[3]);
        }
        if (args.length >= 5) {
            monthlyExpenditures = Integer.parseInt(args[4]);
        }
        if (args.length >= 6) {
            investMinProb = Double.parseDouble(args[5]);
        }
        if (args.length >= 7) {
            maxDays = Integer.parseInt(args[6]);
        }
        if (args.length >= 8) {
            csvFile = args[7].replace("\"", "");
        }
        if (args.length >= 9) {
            numVisitors = Integer.parseInt(args[8]);
        }
        experimentMode = maxDays > 0 && !csvFile.isEmpty();

        initialBudget = budget;
        museumSlotsFree = museumCapacity;
        hotelRoomsFree  = hotelCapacity;

        super.init(new String[]{"1500"});
        setOverActionsPolicy(OverActionsPolicy.ignoreSecond);
        
        if (!experimentMode) {
          chart = new MuseumChart();
        }

        logger.info("=== Museum Complex Simulation Started ===");
        logger.info("Museum cap: " + museumCapacity
                + " | Hotel cap: " + hotelCapacity
                + " | Ticket: " + ticketPrice + " | Hotel price: " + hotelPrice
                + " | Monthly expenses: " + monthlyExpenditures
                + " | investMinProb: " + investMinProb
                + (experimentMode ? " | EXPERIMENT: " + maxDays + " days -> " + csvFile : ""));
    }

    @Override
    protected void stepStarted(int step) {
        if (step > 0) {
            String repairTag = repairing ? " [REPAIR]" : "";
            String reviewTag = reviewCount > 0
                    ? String.format("| Rev: %d (avg %.0f) ", reviewCount, reviewSum / reviewCount)
                    : "| Rev: 0 ";
            logger.info(String.format(
                    "[Day %d | %s] Visits: %d | Hotel: %d | Refused: %d | Wear: %.1f%% | Attr: %.0f | Mob: %.0f | Pay: %.0f | Trn: %.0f | Int: %.0f | Nav: %.0f | Svc: %.0f %s| Budget: %.0f%s",
                    day, season, todayVisits, todayHotelStays, todayRefusals,
                    wear, attractiveness,
                    mobileNetwork, paymentSystem, transportAccess,
                    internetQuality, navigationAccess, serviceAvailability,
                    reviewTag, budget, repairTag));

            updateChart();

            // Yearly snapshot
            if (step % 365 == 0) {
                logger.info(String.format(Locale.US,
                        "=== YEAR %d SNAPSHOT ===\n%s\n%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
                        step / 365, CSV_HEADER,
                        museumCapacity, hotelCapacity, ticketPrice, hotelPrice,
                        monthlyExpenditures, numVisitors, maxDays,
                        totalVisits, totalHotelStays, totalRefusals, totalRepairs,
                        wear, attractiveness, budget,
                        mobileNetwork, paymentSystem, transportAccess,
                        internetQuality, navigationAccess, serviceAvailability,
                        lastAvgReview));
            }
        }

        if (experimentMode && step >= maxDays) {
            writeCsvRow();
            new Thread(() -> {
                try {
                    Thread.sleep(400);
                    getEnvironmentInfraTier().getRuntimeServices().stopMAS();
                } catch (Exception e) { e.printStackTrace(); }
            }).start();
            return;
        }

        day = step;
        updateSeason();
        applyMonthlyExpenditures();
        applyInfrastructureDegradation();
        applyReviews();
        resetDailyCounters();
        applyWear();
        updatePercepts();
        if (!experimentMode) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }

    @Override
    public boolean executeAction(String ag, Structure action) {
        String act = action.getFunctor();

        switch (act) {
            case "visit":
                handleVisit(ag, action);
                break;
            case "refuse_visit":
                handleRefuseVisit(ag);
                break;
            case "invest_attractiveness":
                handleInvestAttractiveness(ag);
                break;
            case "invest_infra":
                handleInvestInfra(ag, action);
                break;
            case "order_repair":
                handleOrderRepair(ag, action);
                break;
            case "do_repair":
                handleDoRepair(ag);
                break;
            case "skip":
                break;
            default:
                logger.warning(ag + " attempted unknown action: " + act);
                break;
        }
        return true;
    }

    private synchronized void handleVisit(String ag, Structure action) {
        if (museumSlotsFree <= 0) {
            totalRefusals++;
            todayRefusals++;
            return;
        }

        museumSlotsFree--;
        budget += ticketPrice;
        todayRevenue += ticketPrice;
        totalVisits++;
        todayVisits++;

        boolean wantHotel = action.getArity() > 0
                && action.getTerm(0).toString().equals("yes");

        if (wantHotel && hotelRoomsFree > 0) {
            hotelRoomsFree--;
            budget += hotelPrice;
            todayRevenue += hotelPrice;
            totalHotelStays++;
            todayHotelStays++;
        }

        if (action.getArity() >= 2) {
            try {
                double review = ((NumberTerm) action.getTerm(1)).solve();
                reviewSum += review;
                reviewCount++;
            } catch (Exception ignored) {}
        }
    }

    private synchronized void handleRefuseVisit(String ag) {
        totalRefusals++;
        todayRefusals++;
    }

    private synchronized void handleInvestAttractiveness(String ag) {
        double cost = 500;
        if (budget < cost) return;
        budget -= cost;
        todayCost += cost;
        attractiveness = Math.min(100, attractiveness + 10);
        logger.info("Manager invested in attractiveness -> " + String.format("%.0f", attractiveness));
    }

    private synchronized void handleInvestInfra(String ag, Structure action) {
        double cost = 500;
        if (budget < cost || action.getArity() < 1) return;
        String factor = action.getTerm(0).toString();
        budget -= cost;
        todayCost += cost;
        switch (factor) {
            case "mobile_network":       mobileNetwork = Math.min(100, mobileNetwork + 10); break;
            case "payment_system":       paymentSystem = Math.min(100, paymentSystem + 10); break;
            case "transport_access":     transportAccess = Math.min(100, transportAccess + 10); break;
            case "internet_quality":     internetQuality = Math.min(100, internetQuality + 10); break;
            case "navigation_access":    navigationAccess = Math.min(100, navigationAccess + 10); break;
            case "service_availability": serviceAvailability = Math.min(100, serviceAvailability + 10); break;
            default: budget += cost; return;
        }
        logger.info("Manager invested in " + factor);
    }

    private synchronized void handleOrderRepair(String ag, Structure action) {
        double price = 0;
        if (action.getArity() > 0) {
            try {
                price = ((NumberTerm) action.getTerm(0)).solve();
            } catch (Exception e) {
                logger.warning("order_repair: could not parse price, defaulting to 0");
            }
        }
        double upfront = Math.round(price / 2.0);
        repairRemainingPayment = price - upfront;
        budget -= upfront;
        todayCost += upfront;
        repairing = true;
        logger.info(String.format(
            "Manager ordered repair (wear=%.1f%%) | negotiated=%.0f, upfront=%.0f, remaining=%.0f, budget=%.0f",
            wear, price, upfront, repairRemainingPayment, budget));
    }

    private synchronized void handleDoRepair(String ag) {
        wear = Math.max(0, wear - 1);
        totalRepairs++;
        if (wear <= 20) {
            repairing = false;
            budget -= repairRemainingPayment;
            todayCost += repairRemainingPayment;
            logger.info(String.format(
                "Repair completed -> wear=%.1f%% | final payment=%.0f, budget=%.0f",
                wear, repairRemainingPayment, budget));
            repairRemainingPayment = 0;
        }
    }

    private void applyInfrastructureDegradation() {
        double loadRatio = (double) todayVisits / museumCapacity;
        // Load-sensitive
        mobileNetwork      = Math.max(0, mobileNetwork      - LOAD_DEGRADE_RATE * loadRatio);
        paymentSystem      = Math.max(0, paymentSystem      - LOAD_DEGRADE_RATE * loadRatio);
        // Time-based
        transportAccess    = Math.max(0, transportAccess    - TIME_DEGRADE_RATE);
        internetQuality    = Math.max(0, internetQuality    - TIME_DEGRADE_RATE);
        navigationAccess   = Math.max(0, navigationAccess   - TIME_DEGRADE_RATE);
        serviceAvailability = Math.max(0, serviceAvailability - TIME_DEGRADE_RATE);
    }

    private void applyMonthlyExpenditures() {
        if (day > 0 && day % 30 == 0) {
            budget -= monthlyExpenditures;
            todayCost += monthlyExpenditures;
            logger.info(String.format("Monthly expenditures: -%d | Budget: %.0f", monthlyExpenditures, budget));
        }
    }

    private void updateSeason() {
        int dayOfYear = day % 365;
        if (dayOfYear < 60 || dayOfYear >= 335) {
            season = "winter";
            seasonFactor = 0.3;
        } else if (dayOfYear < 152) {
            season = "spring";
            seasonFactor = 0.7;
        } else if (dayOfYear < 244) {
            season = "summer";
            seasonFactor = 1.0;
        } else {
            season = "autumn";
            seasonFactor = 0.5;
        }
    }

    private void applyReviews() {
        if (reviewCount > 0) {
            lastAvgReview = reviewSum / reviewCount;
            attractiveness = REVIEW_WEIGHT * lastAvgReview + (1 - REVIEW_WEIGHT) * attractiveness;
            attractiveness = Math.max(0, Math.min(100, attractiveness));
        }
        reviewSum = 0;
        reviewCount = 0;
    }

    private void applyWear() {
        double damage = BASE_WEAR + VISITOR_WEAR * todayVisits;
        if (random.nextDouble() < RANDOM_DAMAGE_CHANCE) {
            damage += RANDOM_DAMAGE_MIN + random.nextDouble() * (RANDOM_DAMAGE_MAX - RANDOM_DAMAGE_MIN);
        }
        wear = Math.min(100, Math.max(0, wear + damage));
    }

    private void resetDailyCounters() {
        museumSlotsFree = repairing ? museumCapacity / 2 : museumCapacity;
        hotelRoomsFree  = hotelCapacity;
        todayVisits     = 0;
        todayHotelStays = 0;
        todayRefusals   = 0;
        todayRevenue    = 0.0;
        todayCost       = 0.0;
    }

    /**
     * Compute normalized metrics for the completed day and push to chart.
     *
     * All values are normalized to 0–100 so they are comparable on one axis.
     *
     * Revenue:      (dailyRevenue / maxDailyRevenue) * 100
     * Satisfaction: actual visitor review average (0–100), falls back to lastAvgReview
     * Heritage:     100 – wear
     * Costs:        clamp((dailyCost / refCost) * 100, 0, 100)
     * Budget:       clamp(budget / (initialBudget * 10) * 100, 0, 100)
     * Season:       seasonFactor * 100  (30 winter … 100 summer)
     * Utility:      W1*Revenue + W2*Satisfaction + W3*Heritage – W4*Costs
     */
    private void updateChart() {
        if (chart == null) {
          return;
        }
        double maxDailyRevenue = museumCapacity * ticketPrice + hotelCapacity * hotelPrice;
        double revenue = Math.min(100, (todayRevenue / maxDailyRevenue) * 100);

        double satisfaction = reviewCount > 0
                ? Math.max(0, Math.min(100, reviewSum / reviewCount))
                : Math.max(0, Math.min(100, lastAvgReview));

        double heritage = Math.max(0, 100 - wear);

        double refCost = monthlyExpenditures / 30.0 + 500;
        double costs = Math.min(100, (todayCost / refCost) * 100);

        double budgetRef = Math.max(1, initialBudget * 10);
        double budgetNorm = Math.max(0, Math.min(100, budget / budgetRef * 100));

        double seasonNorm = seasonFactor * 100;

        double utility = W1 * revenue + W2 * satisfaction + W3 * heritage - W4 * costs;

        logger.info(String.format(
                "[Metrics] Rev=%.1f Sat=%.1f Her=%.1f Cost=%.1f Bud=%.1f Sea=%.0f U=%.2f",
                revenue, satisfaction, heritage, costs, budgetNorm, seasonNorm, utility));

        chart.addDataPoint(revenue, satisfaction, heritage, costs, utility,
                budgetNorm, seasonNorm);
    }

    private void updatePercepts() {
        clearPercepts();

        addPercept(Literal.parseLiteral("step(" + day + ")"));
        addPercept(Literal.parseLiteral("season(" + season + ")"));
        addPercept(Literal.parseLiteral("season_factor(" + seasonFactor + ")"));
        addPercept(Literal.parseLiteral("wear(" + String.format(Locale.US, "%.1f", wear) + ")"));
        addPercept(Literal.parseLiteral("attractiveness(" + String.format("%.0f", attractiveness) + ")"));
        addPercept(Literal.parseLiteral("mobile_network("       + String.format("%.0f", mobileNetwork) + ")"));
        addPercept(Literal.parseLiteral("payment_system("       + String.format("%.0f", paymentSystem) + ")"));
        addPercept(Literal.parseLiteral("transport_access("     + String.format("%.0f", transportAccess) + ")"));
        addPercept(Literal.parseLiteral("internet_quality("     + String.format("%.0f", internetQuality) + ")"));
        addPercept(Literal.parseLiteral("navigation_access("    + String.format("%.0f", navigationAccess) + ")"));
        addPercept(Literal.parseLiteral("service_availability(" + String.format("%.0f", serviceAvailability) + ")"));
        addPercept(Literal.parseLiteral("museum_price(" + ticketPrice + ")"));
        addPercept(Literal.parseLiteral("hotel_price(" + hotelPrice + ")"));
        addPercept(Literal.parseLiteral("museum_slots_free(" + museumSlotsFree + ")"));
        addPercept(Literal.parseLiteral("hotel_rooms_free(" + hotelRoomsFree + ")"));
        addPercept(Literal.parseLiteral("avg_review(" + String.format("%.0f", lastAvgReview) + ")"));
        addPercept(Literal.parseLiteral("repairing(" + (repairing ? "yes" : "no") + ")"));

        addPercept("manager", Literal.parseLiteral("budget(" + String.format("%.0f", budget) + ")"));
        addPercept("manager", Literal.parseLiteral(
                "invest_min_prob(" + String.format(Locale.US, "%.4f", investMinProb) + ")"));
    }

    private static final String CSV_HEADER = String.join(",",
            "museumCapacity", "hotelCapacity", "ticketPrice", "hotelPrice",
            "monthlyExpenditures", "investMinProb", "numVisitors", "maxDays",
            "totalVisits", "totalHotelStays", "totalRefusals", "totalRepairs",
            "finalWear", "finalAttractiveness", "finalBudget",
            "finalMobileNetwork", "finalPaymentSystem", "finalTransportAccess",
            "finalInternetQuality", "finalNavigationAccess", "finalServiceAvailability",
            "finalAvgReview");

    private void writeCsvRow() {
        try {
            File f = new File(csvFile);
            boolean writeHeader = !f.exists() || f.length() == 0;
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                if (writeHeader) pw.println(CSV_HEADER);
                pw.printf(Locale.US,
                        "%d,%d,%d,%d,%d,%.4f,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f%n",
                        museumCapacity, hotelCapacity, ticketPrice, hotelPrice,
                        monthlyExpenditures, investMinProb, numVisitors, maxDays,
                        totalVisits, totalHotelStays, totalRefusals, totalRepairs,
                        wear, attractiveness, budget,
                        mobileNetwork, paymentSystem, transportAccess,
                        internetQuality, navigationAccess, serviceAvailability,
                        lastAvgReview);
            }
            logger.info("CSV row written to " + csvFile);
        } catch (IOException e) {
            logger.severe("Failed to write CSV: " + e.getMessage());
        }
    }
}
