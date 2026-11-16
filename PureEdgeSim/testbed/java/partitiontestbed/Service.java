package partitiontestbed;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;

/**
 * A service the end devices call: one per application of {@code applications.xml},
 * in the same order. Its tasks are stateless, so any server that satisfies its
 * required traits, or the cloud, can answer them.
 */
public final class Service {

	/** Index of the service, which is the index of its application. */
	public final int id;
	/** Name of the application. */
	public final String name;
	/** Traits a server must advertise to run it. */
	public final Set<Trait> required;
	/** Whether its tasks bring a result back to the device, or are fire and forget. */
	public final boolean returnsResults;
	/**
	 * Whether a stage of its subtask graph hands its output to the next stage on
	 * the edge, from host to host, rather than back through the device.
	 */
	public final boolean edgePipeline;

	Service(int id, String name, Set<Trait> required, boolean returnsResults, boolean edgePipeline) {
		this.id = id;
		this.name = name;
		this.required = Collections.unmodifiableSet(required);
		this.returnsResults = returnsResults;
		this.edgePipeline = edgePipeline;
	}

	/**
	 * @param traits the traits of a server
	 * @return whether a server with these traits may run this service
	 */
	public boolean fits(Set<Trait> traits) {
		return traits.containsAll(required);
	}

	@Override
	public String toString() {
		return name;
	}

	/**
	 * Builds one service per application, with the traits the configuration
	 * requires for it, or traits drawn at random where it leaves them out.
	 *
	 * @param config the testbed configuration
	 * @param random the random source of the testbed
	 * @return the services, in the order of {@code applications.xml}
	 */
	static List<Service> fromApplications(TestbedConfig config, Random random) {
		List<String> names = applicationNames();
		List<Service> services = new ArrayList<>();
		for (int s = 0; s < SimulationParameters.applicationList.size(); s++) {
			Set<Trait> required = config.serviceTraits(s);
			if (required == null) {
				required = EnumSet.noneOf(Trait.class);
				for (Trait trait : Trait.values())
					if (random.nextDouble() < config.serviceTraitProbability)
						required.add(trait);
			}
			services.add(new Service(s, s < names.size() ? names.get(s) : "S" + s, required, config.returnsResults(s),
					config.edgePipeline(s)));
		}
		return services;
	}

	/**
	 * The framework keeps the type of an application but not its name, and the
	 * type is the same for many of them, so the names are read from the file.
	 *
	 * @return the {@code name} attributes of {@code applications.xml}, in order,
	 *         or an empty list when the file cannot be read
	 */
	public static List<String> applicationNames() {
		List<String> names = new ArrayList<>();
		try (InputStream input = new FileInputStream(SimulationParameters.applicationFile)) {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			NodeList applications = factory.newDocumentBuilder().parse(input).getElementsByTagName("application");
			for (int i = 0; i < applications.getLength(); i++) {
				String name = ((Element) applications.item(i)).getAttribute("name").trim();
				names.add(name.isEmpty() ? "S" + i : name);
			}
		} catch (IOException | ParserConfigurationException | SAXException e) {
			return new ArrayList<>();
		}
		return names;
	}
}
