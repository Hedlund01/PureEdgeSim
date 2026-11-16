package partitiontestbed;

import java.awt.BasicStroke;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.XYSeries.XYSeriesRenderStyle;
import org.knowm.xchart.style.Styler.ChartTheme;
import org.knowm.xchart.style.Styler.LegendPosition;
import org.knowm.xchart.style.markers.Marker;
import org.knowm.xchart.style.markers.SeriesMarkers;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.locationmanager.Location;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.simulationvisualizer.Chart;


/**
 * A map of the deployment of the strategy under test, drawn with the charts of the
 * framework.
 * <p>
 * It shows the structure the other charts cannot: the mesh between the edge
 * nodes, with the links a failure or a partition has cut in red, the uplinks of
 * the gateways, and, for each component, a line from every access node to each
 * node that runs the component for its devices. The cloud sits above the map, so
 * a line that leaves the map upwards is a component deployed on the cloud. The
 * nodes are marked by role, the nodes that run at least one instance in green,
 * and while a partition lasts every node is circled in the colour of its group.
 *
 * @see TestbedVisualizer
 */
public class DeploymentChart extends Chart {

	/** Colours of the components, in the order of {@code applications.xml}. */
	private static final Color[] COMPONENT_COLORS = { new Color(31, 119, 180), new Color(148, 103, 189),
			new Color(140, 86, 75), new Color(227, 119, 194), new Color(127, 127, 127), new Color(0, 0, 128) };
	/** Colours of the groups of a partition, clear of the red and green of the map. */
	private static final Color[] GROUP_COLORS = { new Color(255, 127, 14), new Color(23, 190, 207),
			new Color(188, 189, 34), new Color(220, 0, 220), new Color(120, 70, 20), new Color(0, 90, 160) };
	/** Size of the drawing, in pixels: larger than the other charts, for the legend. */
	private static final int WIDTH = 900;
	private static final int HEIGHT = 620;
	private static final Color MESH = new Color(200, 200, 200);
	private static final Color DOWN = new Color(230, 30, 30);
	private static final Color UPLINK = new Color(160, 200, 240);
	/** Markers and colours of the device types, in the order of {@code edge_devices.xml}. */
	private static final Marker[] DEVICE_MARKERS = { SeriesMarkers.PLUS, SeriesMarkers.TRIANGLE_DOWN,
			SeriesMarkers.OVAL, SeriesMarkers.TRAPEZOID, SeriesMarkers.RECTANGLE };
	private static final Color[] DEVICE_COLORS = { new Color(140, 140, 140), new Color(150, 110, 60),
			new Color(90, 140, 170), new Color(170, 90, 140), new Color(90, 90, 90) };
	private static final Color HOST = new Color(0, 150, 60);

	private final TestbedWorld world;
	private final Location cloud;
	private final int groupSeries;
	/** Radius of the ring drawn around a node of a partition group, in metres. */
	private final double ring;

	/**
	 * @param simulationManager the testbed simulation manager
	 */
	public DeploymentChart(SimulationManager simulationManager) {
		super("Deployment", "Width (meters)", "Length (meters)", simulationManager);
		chart = new XYChartBuilder().width(WIDTH).height(HEIGHT).theme(ChartTheme.Matlab).title("Deployment")
				.xAxisTitle("Width (meters)").yAxisTitle("Length (meters)").build();
		chart.getStyler().setLegendPosition(LegendPosition.OutsideE);
		this.world = ((TestbedSimulationManager) simulationManager).getWorld();
		double width = SimulationParameters.simulationMapWidth;
		double length = SimulationParameters.simulationMapLength;
		this.cloud = new Location(width / 2, length * 1.08);
		updateSize(0.0, width, 0.0, length * 1.12);
		getChart().getStyler().setMarkerSize(6);
		int groups = 0;
		for (TestbedConfig.PartitionSpec spec : world.config.partitions)
			groups = Math.max(groups, spec.groupCount + (spec.mode == TestbedConfig.GroupMode.EXPLICIT ? 1 : 0));
		this.groupSeries = Math.min(groups, GROUP_COLORS.length);
		this.ring = Math.max(width, length) / 40;
	}

	/**
	 * Redraws the map from the state of the run.
	 */
	@Override
	public void update() {
		// Lines first, so that the markers are drawn over them.
		updateMesh();
		updateDeployments();
		updateGroups();
		updateNodes();

		String title = "Deployment - " + simulationManager.getScenario().getStringOrchAlgorithm();
		for (TestbedWorld.ActivePartition partition : world.activePartitions())
			title += " - partition " + partition.spec.index + " (" + partition.groups.size() + " groups)";
		getChart().setTitle(title);
	}

	private void updateMesh() {
		Lines up = new Lines();
		Lines down = new Lines();
		for (ComputingNode[] pair : world.meshPairs)
			if (inUse(pair[0], pair[1]))
				(isDown(pair[0], pair[1]) ? down : up).add(location(pair[0]), location(pair[1]));
		Lines uplinks = new Lines();
		for (EdgeServer server : world.servers())
			if (server.gateway)
				(isDown(server.node, world.cloud()) ? down : uplinks).add(location(server.node), cloud);
		line("Mesh links", up, MESH, 1);
		line("Cloud uplinks", uplinks, UPLINK, 1);
		line("Links down", down, DOWN, 1.5f);
	}

	private void updateDeployments() {
		List<Service> services = world.services();
		for (int c = 0; c < services.size(); c++) {
			Service service = services.get(c);
			// One line per access node and host, however many devices share it.
			Set<List<ComputingNode>> pairs = new LinkedHashSet<>();
			for (ComputingNode device : world.homes().keySet()) {
				TestbedWorld.Placement placement = world.placementOf(device);
				if (placement == null || !device.isApplicationPlaced()
						|| placement.service != service)
					continue;
				pairs.add(List.of(placement.home.node, placement.node(world)));
			}
			Lines lines = new Lines();
			for (List<ComputingNode> pair : pairs)
				if (pair.get(0) != pair.get(1))
					lines.add(location(pair.get(0)), location(pair.get(1)));
			line("Deployed: " + service.name, lines, COMPONENT_COLORS[c % COMPONENT_COLORS.length], 2);
		}
	}

	/**
	 * Rings every node in the colour of its group while a partition lasts. A ring
	 * is a line, so the markers of the node stay visible inside it.
	 */
	private void updateGroups() {
		List<Lines> rings = new ArrayList<>();
		for (int g = 0; g < groupSeries; g++)
			rings.add(new Lines());
		List<TestbedWorld.ActivePartition> active = world.activePartitions();
		if (!active.isEmpty()) {
			TestbedWorld.ActivePartition latest = active.get(active.size() - 1);
			for (EdgeServer server : world.servers()) {
				Integer group = latest.groupOf.get(server);
				if (group != null && group < groupSeries)
					rings.get(group).ring(location(server.node), ring);
			}
		}
		for (int g = 0; g < groupSeries; g++)
			line("Partition group " + g, rings.get(g), GROUP_COLORS[g], 2.5f);
	}

	private void updateNodes() {
		List<TestbedWorld.DeviceType> types = world.deviceTypes();
		for (int t = 0; t < types.size(); t++) {
			List<Double> deviceX = new ArrayList<>();
			List<Double> deviceY = new ArrayList<>();
			for (ComputingNode device : world.homes().keySet())
				if (world.deviceTypeOf(device) == types.get(t)) {
					deviceX.add(location(device).getXPos());
					deviceY.add(location(device).getYPos());
				}
			points(types.get(t).name, deviceX, deviceY, DEVICE_MARKERS[t % DEVICE_MARKERS.length],
					DEVICE_COLORS[t % DEVICE_COLORS.length]);
		}

		List<Double> nodeX = new ArrayList<>();
		List<Double> nodeY = new ArrayList<>();
		List<Double> accessX = new ArrayList<>();
		List<Double> accessY = new ArrayList<>();
		List<Double> gatewayX = new ArrayList<>();
		List<Double> gatewayY = new ArrayList<>();
		List<Double> hostX = new ArrayList<>();
		List<Double> hostY = new ArrayList<>();
		Set<EdgeServer> hosts = new HashSet<>();
		for (TestbedWorld.Placement placement : world.placements())
			if (placement.host != null && placement.device.isApplicationPlaced())
				hosts.add(placement.host);
		for (EdgeServer server : world.servers()) {
			Location at = location(server.node);
			(server.access ? accessX : nodeX).add(at.getXPos());
			(server.access ? accessY : nodeY).add(at.getYPos());
			if (server.gateway) {
				gatewayX.add(at.getXPos());
				gatewayY.add(at.getYPos());
			}
			if (hosts.contains(server)) {
				hostX.add(at.getXPos());
				hostY.add(at.getYPos());
			}
		}
		points("Edge servers", nodeX, nodeY, SeriesMarkers.CROSS, Color.BLACK);
		points("Access edge servers", accessX, accessY, SeriesMarkers.SQUARE, Color.BLACK);
		points("Servers running a service", hostX, hostY, SeriesMarkers.DIAMOND, HOST);
		points("Gateways", gatewayX, gatewayY, SeriesMarkers.TRIANGLE_UP, UPLINK.darker());
		points("Cloud data center", List.of(cloud.getXPos()), List.of(cloud.getYPos()), SeriesMarkers.SQUARE,
				anyGatewayUp() ? UPLINK.darker() : DOWN);
	}

	private boolean anyGatewayUp() {
		for (EdgeServer server : world.servers())
			if (server.gateway && !isDown(server.node, world.cloud()))
				return true;
		return false;
	}

	/** A link to a server that moved out of range is not drawn. */
	private boolean inUse(ComputingNode a, ComputingNode b) {
		for (com.mechalikh.pureedgesim.network.NetworkLink link : world.topology().linksBetween(a, b))
			if (world.topology().isUp(link) && !world.topology().carries(link))
				return false;
		return true;
	}

	private boolean isDown(ComputingNode a, ComputingNode b) {
		for (com.mechalikh.pureedgesim.network.NetworkLink link : world.topology().linksBetween(a, b))
			if (!world.topology().isUp(link))
				return true;
		return false;
	}

	/** The cloud has no place on the map: it is drawn above it. */
	private Location location(ComputingNode node) {
		return node == world.cloud() ? cloud : node.getMobilityModel().getCurrentLocation();
	}

	// ----------------------------------------------------------- drawing

	/** Line segments in one series, separated by gaps. */
	private static final class Lines {
		final List<Double> x = new ArrayList<>();
		final List<Double> y = new ArrayList<>();

		void add(Location from, Location to) {
			x.add(from.getXPos());
			y.add(from.getYPos());
			x.add(to.getXPos());
			y.add(to.getYPos());
			gap(to.getXPos());
		}

		void ring(Location center, double radius) {
			for (int i = 0; i <= 16; i++) {
				double angle = 2 * Math.PI * i / 16;
				x.add(center.getXPos() + radius * Math.cos(angle));
				y.add(center.getYPos() + radius * Math.sin(angle));
			}
			gap(center.getXPos());
		}

		/** XChart breaks a line where a value is not a number. */
		private void gap(double at) {
			x.add(at);
			y.add(Double.NaN);
		}
	}

	private void line(String name, Lines lines, Color color, float width) {
		double[] x = lines.x.isEmpty() ? new double[] { 0, 0 } : toArray(lines.x);
		double[] y = lines.y.isEmpty() ? new double[] { Double.NaN, Double.NaN } : toArray(lines.y);
		XYSeries series = series(name, x, y);
		series.setXYSeriesRenderStyle(XYSeriesRenderStyle.Line);
		series.setMarker(SeriesMarkers.NONE);
		series.setLineColor(color);
		series.setLineStyle(new BasicStroke(width));
	}

	private void points(String name, List<Double> xs, List<Double> ys, Marker marker, Color color) {
		// An empty series is one point that is not a number, which XChart skips.
		XYSeries series = series(name, xs.isEmpty() ? new double[] { 0 } : toArray(new ArrayList<>(xs)),
				ys.isEmpty() ? new double[] { Double.NaN } : toArray(new ArrayList<>(ys)));
		series.setXYSeriesRenderStyle(XYSeriesRenderStyle.Scatter);
		series.setMarker(marker);
		series.setMarkerColor(color);
	}

	private XYSeries series(String name, double[] x, double[] y) {
		if (getChart().getSeriesMap().containsKey(name))
			return getChart().updateXYSeries(name, x, y, null);
		return getChart().addSeries(name, x, y);
	}
}
