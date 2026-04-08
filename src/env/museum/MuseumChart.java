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
    private final List<Double> budgetHistory       = new ArrayList<>();
    private final List<Double> seasonHistory       = new ArrayList<>();

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
        // 0 Revenue — blue
        renderer.setSeriesStroke(0, new BasicStroke(2.0f));
        renderer.setSeriesPaint(0, new Color(31, 119, 180));
        // 1 Satisfaction — green
        renderer.setSeriesStroke(1, new BasicStroke(2.0f));
        renderer.setSeriesPaint(1, new Color(44, 160, 44));
        // 2 Heritage — orange
        renderer.setSeriesStroke(2, new BasicStroke(2.0f));
        renderer.setSeriesPaint(2, new Color(255, 127, 14));
        // 3 Costs — red
        renderer.setSeriesStroke(3, new BasicStroke(1.5f));
        renderer.setSeriesPaint(3, new Color(214, 39, 40));
        // 4 Utility — purple (thick)
        renderer.setSeriesStroke(4, new BasicStroke(2.5f));
        renderer.setSeriesPaint(4, new Color(148, 103, 189));
        // 5 Budget — teal
        renderer.setSeriesStroke(5, new BasicStroke(2.0f));
        renderer.setSeriesPaint(5, new Color(0, 172, 178));
        // 6 SeasonFactor — gray dashed
        renderer.setSeriesStroke(6, new BasicStroke(
                1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10.0f, new float[]{6.0f, 4.0f}, 0.0f));
        renderer.setSeriesPaint(6, new Color(140, 140, 140));

        plot.setRenderer(renderer);

        ChartFrame frame = new ChartFrame("Museum Complex: Utility & Metrics", chart);
        frame.pack();
        frame.setSize(900, 500);
        frame.setVisible(true);
    }

    public synchronized void addDataPoint(double revenue, double satisfaction,
                                          double heritage, double costs, double utility,
                                          double budget, double season) {
        revenueHistory.add(revenue);
        satisfactionHistory.add(satisfaction);
        heritageHistory.add(heritage);
        costsHistory.add(costs);
        utilityHistory.add(utility);
        budgetHistory.add(budget);
        seasonHistory.add(season);

        dataset.addSeries("Revenue",              toData(revenueHistory));
        dataset.addSeries("VisitorSatisfaction",  toData(satisfactionHistory));
        dataset.addSeries("HeritagePreservation", toData(heritageHistory));
        dataset.addSeries("Costs",                toData(costsHistory));
        dataset.addSeries("Utility",              toData(utilityHistory));
        dataset.addSeries("Budget",               toData(budgetHistory));
        dataset.addSeries("SeasonFactor",          toData(seasonHistory));
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
