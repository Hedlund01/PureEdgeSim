package zip.hedlund.pureedgesim.edgesdk.metrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.style.Styler;

import zip.hedlund.pureedgesim.edgesdk.api.RunResult;

/**
 * PNG charts of an experiment, drawn with XChart (a PureEdgeSim dependency):
 * one bar chart per metric (candidates side by side, one series per condition,
 * 95 % CI whiskers), and a timeline of the windowed task success rate per
 * candidate with the partition periods drawn as a step series.
 */
public final class ComparisonCharts {

	private ComparisonCharts() {
	}

	/** Writes the charts; failures (e.g. no graphics support) are reported and ignored. */
	public static void write(Path dir, List<RunResult> runs, ComparisonReport report, List<Metric> metrics) {
		System.setProperty("java.awt.headless", "true");
		try {
			Files.createDirectories(dir);
			for (Metric m : metrics)
				bar(dir, report, m);
			timeline(dir, runs);
		} catch (Exception | Error e) {
			System.err.println("[edgesdk] charts skipped: " + e);
		}
	}

	private static void bar(Path dir, ComparisonReport report, Metric m) throws Exception {
		List<String> candidates = report.candidates();
		CategoryChart chart = new CategoryChartBuilder().width(900).height(500).title(m.name())
				.xAxisTitle("candidate").yAxisTitle(m.name() + (m.unit().isEmpty() ? "" : " (" + m.unit() + ")"))
				.build();
		chart.getStyler().setLegendPosition(Styler.LegendPosition.OutsideS);
		chart.getStyler().setErrorBarsColor(java.awt.Color.DARK_GRAY);
		boolean any = false;
		for (String cond : report.conditions()) {
			List<Double> y = new ArrayList<>();
			List<Double> err = new ArrayList<>();
			for (String c : candidates) {
				var row = report.row(cond, c, m.name());
				double mean = row.map(ComparisonReport.Row::mean).orElse(Double.NaN);
				double ci = row.map(ComparisonReport.Row::ci95).orElse(Double.NaN);
				y.add(Double.isNaN(mean) ? 0 : mean);
				err.add(Double.isNaN(ci) ? 0 : ci);
				any |= !Double.isNaN(mean);
			}
			chart.addSeries(cond, candidates, y, err);
		}
		if (any)
			BitmapEncoder.saveBitmap(chart, dir.resolve(m.name()).toString(), BitmapEncoder.BitmapFormat.PNG);
	}

	private static void timeline(Path dir, List<RunResult> runs) throws Exception {
		// first condition and replication, one line per candidate
		if (runs.isEmpty())
			return;
		String cond = ComparisonReport.conditionOf(runs.get(0).data());
		XYChart chart = new XYChartBuilder().width(1000).height(500).title("Task success over time (" + cond + ")")
				.xAxisTitle("time (s)").yAxisTitle("success in window (%)").build();
		chart.getStyler().setLegendPosition(Styler.LegendPosition.OutsideS);
		Map<String, RunResult> first = new TreeMap<>();
		for (RunResult r : runs)
			if (r.data().replication() == 0 && ComparisonReport.conditionOf(r.data()).equals(cond))
				first.putIfAbsent(r.candidate(), r);
		RunResult any = null;
		for (Map.Entry<String, RunResult> e : first.entrySet()) {
			List<Double> x = new ArrayList<>();
			List<Double> y = new ArrayList<>();
			for (TimelineSample s : e.getValue().data().timeline())
				if (!Double.isNaN(s.successPct())) {
					x.add(s.time());
					y.add(s.successPct());
				}
			if (!x.isEmpty())
				chart.addSeries(e.getKey(), x, y);
			any = e.getValue();
		}
		if (any != null) {
			List<Double> x = new ArrayList<>();
			List<Double> y = new ArrayList<>();
			for (TimelineSample s : any.data().timeline()) {
				x.add(s.time());
				y.add(s.partitionActive() ? 100.0 : 0.0);
			}
			if (!x.isEmpty())
				chart.addSeries("partition active", x, y);
		}
		if (!chart.getSeriesMap().isEmpty())
			BitmapEncoder.saveBitmap(chart, dir.resolve("timeline_success").toString(),
					BitmapEncoder.BitmapFormat.PNG);
	}
}
