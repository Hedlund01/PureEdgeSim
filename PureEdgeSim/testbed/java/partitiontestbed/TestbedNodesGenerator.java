package partitiontestbed;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.datacentersmanager.DefaultComputingNodesGenerator;
import com.mechalikh.pureedgesim.locationmanager.Location;
import com.mechalikh.pureedgesim.locationmanager.MobilityModel;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters.TYPES;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;

/**
 * Generates as many edge nodes as {@code edge_nodes} asks, instead of the fixed
 * list of {@code edge_datacenters.xml}.
 * <p>
 * The edge data centers file holds one {@code <datacenter>}, the template every
 * edge server is drawn from: its hardware may be a distribution, so the nodes
 * need not be identical. The nodes are named {@code dc1} to {@code dcN}, placed
 * uniformly on the map, and given their role by the world. Each device is
 * then placed within {@code devices.radius_m} of an access node, the access
 * nodes taking the devices in turn, and is attached to that node: the device
 * belongs to the partition of its access node.
 *
 * @see TestbedTopologyCreator
 */
public class TestbedNodesGenerator extends DefaultComputingNodesGenerator {

	private final TestbedWorld world;
	/** Access node of each device, in the order the devices are created. */
	private final List<EdgeServer> homes = new ArrayList<>();

	/**
	 * @param simulationManager  the testbed simulation manager
	 * @param mobilityModelClass the mobility model of the devices
	 * @param computingNodeClass the class of the computing nodes
	 */
	public TestbedNodesGenerator(SimulationManager simulationManager,
			Class<? extends MobilityModel> mobilityModelClass, Class<? extends ComputingNode> computingNodeClass) {
		super(simulationManager, mobilityModelClass, computingNodeClass);
		this.world = ((TestbedSimulationManager) simulationManager).getWorld();
	}

	@Override
	public void generateDatacentersAndDevices() {
		generateDataCenters(SimulationParameters.cloudDataCentersFile, TYPES.CLOUD);
		world.cloud = cloudOnlyList.get(0);
		generateEdgeNodes();
		world.createServers(edgeOnlyList);
		generateEdgeDevices();
		for (int i = 0; i < mistOnlyList.size(); i++)
			world.attach(mistOnlyList.get(i), homes.get(i));
		getSimulationManager().getSimulationLogger().print("%s - %d edge servers and %d devices were generated",
				getClass().getSimpleName(), edgeOnlyList.size(), mistOnlyList.size());
	}

	/**
	 * Draws the edge nodes from the template of the edge data centers file.
	 */
	protected void generateEdgeNodes() {
		Element template = readTemplate();
		Random random = world.random;
		for (int i = 0; i < world.config.edgeNodes; i++) {
			Element node = (Element) template.cloneNode(true);
			Element location = (Element) node.getElementsByTagName("location").item(0);
			setValue(location, "x_pos", random.nextDouble() * SimulationParameters.simulationMapWidth);
			setValue(location, "y_pos", random.nextDouble() * SimulationParameters.simulationMapLength);
			ComputingNode edgeNode;
			try {
				edgeNode = createComputingNode(node, TYPES.EDGE_DATACENTER);
			} catch (ReflectiveOperationException | SecurityException | IllegalArgumentException
					| java.security.NoSuchAlgorithmException e) {
				throw new IllegalStateException(getClass().getSimpleName() + " - cannot create edge node " + (i + 1)
						+ " from the template of \"" + SimulationParameters.edgeDataCentersFile + "\": "
						+ e.getMessage(), e);
			}
			edgeNode.setName("dc" + (i + 1));
			edgeOnlyList.add(edgeNode);
			mistAndEdgeListSensorsExcluded.add(edgeNode);
			if (SimulationParameters.enableOrchestrators && "EDGE".equals(SimulationParameters.deployOrchestrators))
				orchestratorsList.add(edgeNode);
			allNodesList.add(edgeNode);
			allNodesListSensorsExcluded.add(edgeNode);
			edgeAndCloudList.add(edgeNode);
		}
		// The framework counted the data centers of the file, which is the one
		// template, and averages the edge statistics over that count.
		SimulationParameters.numberOfEdgeDataCenters = edgeOnlyList.size();
	}

	/**
	 * Creates the devices of one type of {@code edge_devices.xml}, and records the
	 * type and the application of each. A type is named by the {@code name}
	 * attribute of its {@code <device>} element and runs the application its
	 * {@code application} attribute names.
	 */
	@Override
	protected void generateDevicesInstances(Element type, int instances) {
		String name = type.getAttribute("name").trim();
		String application = type.getAttribute("application").trim();
		List<String> applications = Service.applicationNames();
		int index = applications.indexOf(application);
		if (index < 0)
			throw new IllegalArgumentException(getClass().getSimpleName() + " - the device type \"" + name
					+ "\" of \"" + SimulationParameters.edgeDevicesFile + "\" must name its application with "
					+ "application=\"...\", one of " + applications + "; found \"" + application + "\"");
		int first = mistOnlyList.size();
		super.generateDevicesInstances(type, instances);
		for (int i = first; i < mistOnlyList.size(); i++)
			world.setDeviceType(mistOnlyList.get(i), name.isEmpty() ? application + " device" : name, index);
	}

	/**
	 * Places a device next to the access node whose turn it is, rather than
	 * anywhere on the map.
	 */
	@Override
	protected Location getDeviceLocation(Element deviceElement, Random random) {
		List<EdgeServer> access = world.accessServers();
		EdgeServer home = access.get(homes.size() % access.size());
		homes.add(home);
		Location center = home.node.getMobilityModel().getCurrentLocation();
		double angle = world.random.nextDouble() * 2 * Math.PI;
		double radius = Math.sqrt(world.random.nextDouble()) * world.config.devicesRadius;
		double x = clamp(center.getXPos() + radius * Math.cos(angle), SimulationParameters.simulationMapWidth);
		double y = clamp(center.getYPos() + radius * Math.sin(angle), SimulationParameters.simulationMapLength);
		return new Location(x, y);
	}

	private static double clamp(double value, double max) {
		return Math.max(0, Math.min(max, value));
	}

	private static void setValue(Element parent, String tag, double value) {
		Element element = (Element) parent.getElementsByTagName(tag).item(0);
		// A plain value replaces a distribution the template may declare there.
		while (element.getAttributes().getLength() > 0)
			element.removeAttribute(element.getAttributes().item(0).getNodeName());
		element.setTextContent(String.valueOf(value));
	}

	private Element readTemplate() {
		try (InputStream input = new FileInputStream(SimulationParameters.edgeDataCentersFile)) {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document document = builder.parse(input);
			Element template = (Element) document.getElementsByTagName("datacenter").item(0);
			if (template == null)
				throw new IllegalArgumentException(getClass().getSimpleName() + " - \""
						+ SimulationParameters.edgeDataCentersFile + "\" must hold one <datacenter> template");
			return template;
		} catch (IOException | ParserConfigurationException | SAXException e) {
			throw new IllegalStateException(getClass().getSimpleName() + " - cannot read \""
					+ SimulationParameters.edgeDataCentersFile + "\": " + e.getMessage(), e);
		}
	}
}
