package zip.hedlund.pureedgesim.edgesdk.fault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.network.Degradation;

/**
 * Scenario-scripted faults: a timeline of events, written in Java or in a small
 * text format.
 *
 * <pre>{@code
 * FaultScript.builder()
 *     .partition(at(120), lasting(60), "regionA", "cloud")   // groups or node patterns
 *     .crash("uav-3", at(300))                                // crash-stop
 *     .crash("edge-1", at(400), Recovery.after(30))           // crash-recovery
 *     .linkDown("truck", "cloud-eu", at(200), lasting(90))
 *     .degrade("truck", "cloud-eu", at(500), lasting(60), Degradation.latency(0.2).plusLoss(0.05))
 *     .build();
 * }</pre>
 *
 * Text format, one event per line ({@code #} starts a comment; times accept
 * {@code ms}, {@code s}, {@code m}, {@code h}):
 *
 * <pre>
 * 120s partition regionA | cloud for 60s
 * 300s crash uav-3
 * 400s crash edge-1 recover 30s
 * 200s linkdown truck cloud-eu for 90s
 * 500s degrade truck cloud-eu latency+200ms loss=5% bw*0.5 for 60s
 * </pre>
 *
 * A partition group is a declared group name, or a comma-separated list of node
 * names and {@code *} patterns.
 */
public final class FaultScript {

	private FaultScript() {
	}

	/** Readability helper: an absolute time in seconds. */
	public static double at(double seconds) {
		return seconds;
	}

	/** Readability helper: a duration in seconds. */
	public static double lasting(double seconds) {
		return seconds;
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Parses a script in the text format. */
	public static ScriptedFaultModel parse(String text) {
		Builder b = builder();
		String[] lines = text.split("\\R");
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i].replaceAll("#.*$", "").trim();
			if (line.isEmpty())
				continue;
			try {
				parseLine(b, line);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException("fault script line " + (i + 1) + " \"" + lines[i].trim() + "\": "
						+ e.getMessage(), e);
			}
		}
		return b.build();
	}

	/** Reads and parses a script file. */
	public static ScriptedFaultModel load(Path file) {
		try {
			return parse(Files.readString(file, StandardCharsets.UTF_8)).labelledScript(file.getFileName().toString());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Parses a time like {@code 120}, {@code 120s}, {@code 500ms}, {@code 2m}, {@code 1h}. */
	public static double parseTime(String token) {
		String t = token.trim().toLowerCase(Locale.ROOT);
		double factor = 1;
		if (t.endsWith("ms")) {
			factor = 0.001;
			t = t.substring(0, t.length() - 2);
		} else if (t.endsWith("s")) {
			t = t.substring(0, t.length() - 1);
		} else if (t.endsWith("m")) {
			factor = 60;
			t = t.substring(0, t.length() - 1);
		} else if (t.endsWith("h")) {
			factor = 3600;
			t = t.substring(0, t.length() - 1);
		}
		return Double.parseDouble(t) * factor;
	}

	private static void parseLine(Builder b, String line) {
		String[] tok = line.split("\\s+");
		if (tok.length < 2)
			throw new IllegalArgumentException("expected \"<time> <event> ...\"");
		double at = parseTime(tok[0]);
		String verb = tok[1].toLowerCase(Locale.ROOT);
		switch (verb) {
		case "partition" -> {
			int forIdx = indexOf(tok, "for");
			String groupsText = String.join(" ", Arrays.copyOfRange(tok, 2, forIdx));
			String[] groups = Arrays.stream(groupsText.split("\\|")).map(String::trim).filter(s -> !s.isEmpty())
					.toArray(String[]::new);
			b.partition(at, parseTime(tok[forIdx + 1]), groups);
		}
		case "crash" -> {
			require(tok, 3);
			int rec = indexOf(tok, "recover", -1);
			b.crash(tok[2], at, rec < 0 ? Recovery.never() : Recovery.after(parseTime(tok[rec + 1])));
		}
		case "linkdown", "link-down" -> {
			require(tok, 4);
			int forIdx = indexOf(tok, "for", -1);
			b.linkDown(tok[2], tok[3], at, forIdx < 0 ? Double.POSITIVE_INFINITY : parseTime(tok[forIdx + 1]));
		}
		case "degrade" -> {
			require(tok, 4);
			int forIdx = indexOf(tok, "for");
			Degradation d = Degradation.NONE;
			for (int i = 4; i < forIdx; i++) {
				String opt = tok[i].toLowerCase(Locale.ROOT);
				if (opt.startsWith("latency+"))
					d = d.plusLatency(parseTime(opt.substring("latency+".length())));
				else if (opt.startsWith("loss="))
					d = d.plusLoss(percent(opt.substring("loss=".length())));
				else if (opt.startsWith("bw*"))
					d = d.timesBandwidth(Double.parseDouble(opt.substring(3)));
				else
					throw new IllegalArgumentException("unknown degradation option \"" + tok[i] + "\"");
			}
			b.degrade(tok[2], tok[3], at, parseTime(tok[forIdx + 1]), d);
		}
		default -> throw new IllegalArgumentException("unknown event \"" + tok[1] + "\"");
		}
	}

	private static double percent(String v) {
		return v.endsWith("%") ? Double.parseDouble(v.substring(0, v.length() - 1)) / 100 : Double.parseDouble(v);
	}

	private static int indexOf(String[] tok, String word) {
		int i = indexOf(tok, word, -1);
		if (i < 0 || i + 1 >= tok.length)
			throw new IllegalArgumentException("missing \"" + word + " <duration>\"");
		return i;
	}

	private static int indexOf(String[] tok, String word, int fallback) {
		for (int i = 2; i < tok.length; i++)
			if (tok[i].equalsIgnoreCase(word))
				return i;
		return fallback;
	}

	private static void require(String[] tok, int n) {
		if (tok.length < n)
			throw new IllegalArgumentException("not enough arguments");
	}

	/** Builds a {@link ScriptedFaultModel}. */
	public static final class Builder {

		private final List<ScriptedFaultModel.Step> steps = new ArrayList<>();
		private String label = "scripted";

		/**
		 * Splits the network into groups at {@code at} for {@code duration}. Each
		 * group is a declared group name or comma-separated node patterns.
		 */
		public Builder partition(double at, double duration, String... groups) {
			if (groups.length < 1)
				throw new IllegalArgumentException("a partition needs at least one group");
			List<Select> selects = Arrays.stream(groups).map(g -> Select.names(g.split("\\s*,\\s*"))).toList();
			String text = String.join(" | ", groups);
			steps.add(step(s -> s.partition(selects, at, duration),
					fmt(at) + " partition " + text + " for " + fmt(duration)));
			return this;
		}

		/** Crash-stop of the matching nodes. */
		public Builder crash(String node, double at) {
			return crash(node, at, Recovery.never());
		}

		/** Crash of the matching nodes, with recovery. */
		public Builder crash(String node, double at, Recovery recovery) {
			steps.add(step(s -> s.crash(Select.name(node), at, recovery), fmt(at) + " crash " + node
					+ (recovery.recovers() ? " recover " + fmt(recovery.after()) : "")));
			return this;
		}

		/** Takes every link between two nodes down. */
		public Builder linkDown(String a, String b, double at, double duration) {
			steps.add(step(s -> s.linkDown(Targets.link(a, b), at, duration),
					fmt(at) + " linkdown " + a + " " + b + (duration == Double.POSITIVE_INFINITY ? ""
							: " for " + fmt(duration))));
			return this;
		}

		/** Takes the selected links down. */
		public Builder linkDown(LinkSelect links, double at, double duration) {
			steps.add(step(s -> s.linkDown(links, at, duration), fmt(at) + " linkdown <selection>"));
			return this;
		}

		/** Degrades every link between two nodes. */
		public Builder degrade(String a, String b, double at, double duration, Degradation d) {
			steps.add(step(s -> s.degrade(Targets.link(a, b), at, duration, d),
					fmt(at) + " degrade " + a + " " + b + " latency+" + fmt(d.extraLatencyS()) + " loss="
							+ (d.extraLoss() * 100) + "% bw*" + d.bandwidthFactor() + " for " + fmt(duration)));
			return this;
		}

		/** Sets the label used in reports. */
		public Builder label(String newLabel) {
			this.label = newLabel;
			return this;
		}

		public ScriptedFaultModel build() {
			return new ScriptedFaultModel(steps, label);
		}

		private static ScriptedFaultModel.Step step(java.util.function.Consumer<FaultScheduler> action, String text) {
			return new ScriptedFaultModel.Step() {
				@Override
				public void schedule(FaultScheduler scheduler) {
					action.accept(scheduler);
				}

				@Override
				public String describe() {
					return text;
				}
			};
		}

		private static String fmt(double seconds) {
			if (seconds == Math.rint(seconds))
				return (long) seconds + "s";
			return String.format(Locale.ROOT, "%.3fs", seconds);
		}
	}
}
