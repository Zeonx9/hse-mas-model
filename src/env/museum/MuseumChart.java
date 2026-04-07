package museum;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartFrame;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.DefaultXYDataset;

import java.awt.BasicStroke;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class MuseumChart {

    private final DefaultXYDataset dataset = new DefaultXYDataset();

    private final List<Double> revenueHistory      = new ArrayList<>();
    private final List<Double> satisfactionHistory = new ArrayList<>();
    private final List<Double> heritageHistory     = new ArrayList<>();
    private final List<Double> costsHistory        = new ArrayList<>();
    private final List<Double> utilityHistory      = new ArrayList<>();

    public MuseumChart() {
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Museum Complex Metrics",
                "Day",
                "Value (0–100)",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );
        chart.setBackgroundPaint(Color.WHITE);
        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(new Color(245, 245, 245));
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);

        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
        renderer.setSeriesStroke(0, new BasicStroke(2.0f));  // Revenue      — blue
        renderer.setSeriesStroke(1, new BasicStroke(2.0f));  // Satisfaction — green
        renderer.setSeriesStroke(2, new BasicStroke(2.0f));  // Heritage     — orange
        renderer.setSeriesStroke(3, new BasicStroke(1.5f));  // Costs        — red
        renderer.setSeriesStroke(4, new BasicStroke(2.5f));  // Utility      — purple (thick)
        renderer.setSeriesPaint(0, new Color(31,  119, 180));
        renderer.setSeriesPaint(1, new Color(44,  160,  44));
        renderer.setSeriesPaint(2, new Color(255, 127,  14));
        renderer.setSeriesPaint(3, new Color(214,  39,  40));
        renderer.setSeriesPaint(4, new Color(148,  103, 189));
        plot.setRenderer(renderer);

        ChartFrame frame = new ChartFrame("Museum Complex: Utility & Metrics", chart);
        frame.pack();
        frame.setSize(900, 500);
        frame.setVisible(true);
    }

    /**
     * Add one data point (one day) to the chart.
     * All values should be in the 0–100 range.
     *
     * @param revenue      normalized daily revenue (0–100)
     * @param satisfaction visitor satisfaction (0–100)
     * @param heritage     heritage preservation = 100 – wear (0–100)
     * @param costs        normalized daily costs (0–100)
     * @param utility      U = w1*Revenue + w2*Satisfaction + w3*Heritage – w4*Costs
     */
    public synchronized void addDataPoint(double revenue, double satisfaction,
                                          double heritage, double costs, double utility) {
        revenueHistory.add(revenue);
        satisfactionHistory.add(satisfaction);
        heritageHistory.add(heritage);
        costsHistory.add(costs);
        utilityHistory.add(utility);

        dataset.addSeries("Revenue",              toData(revenueHistory));
        dataset.addSeries("VisitorSatisfaction",  toData(satisfactionHistory));
        dataset.addSeries("HeritagePreservation", toData(heritageHistory));
        dataset.addSeries("Costs",                toData(costsHistory));
        dataset.addSeries("Utility",              toData(utilityHistory));
    }

    private double[][] toData(List<Double> values) {
        double[][] r = new double[2][values.size()];
        for (int i = 0; i < values.size(); i++) {
            r[0][i] = i;
            r[1][i] = values.get(i);
        }
        return r;
    }
}
