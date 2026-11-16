package partitiontestbed;

import java.util.List;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

/**
 * What a strategy did when asked to deploy the service of a device: where the
 * service now runs, if anywhere, and how long the decision took. The requests of
 * the device wait for that time before they are sent to the host.
 * <p>
 * A strategy can subclass it to keep its own figures, and returns them as
 * {@link #details()} for the deployments file.
 *
 * @see PlacementStrategy#deploy(ComputingNode, EdgeServer, Service)
 */
public class Deployment {

	/** The device that asked. */
	public final ComputingNode device;
	/** The access server of the device. */
	public final EdgeServer home;
	/** The service it asked for. */
	public final Service service;
	/** How the deployment ended, in the words of the strategy, for example {@code EDGE}. */
	public String outcome;
	/** The server that runs the service, or {@code null} for the cloud or a failure. */
	public EdgeServer host;
	/** Whether the service runs on the cloud. */
	public boolean onCloud;
	/** Time the deployment took, in seconds. */
	public double delay;
	/** What the strategy needs to keep about the placement, for example the implementation. */
	public Object detail;

	/**
	 * @param device  the device that asked
	 * @param home    its access server
	 * @param service the service it asked for
	 */
	public Deployment(ComputingNode device, EdgeServer home, Service service) {
		this.device = device;
		this.home = home;
		this.service = service;
	}

	/** @return whether the service now runs somewhere */
	public boolean success() {
		return host != null || onCloud;
	}

	/**
	 * @return the values of the columns the strategy adds to the deployments file,
	 *         in the order of {@link PlacementStrategy#deploymentColumns()}
	 */
	public List<String> details() {
		return List.of();
	}
}
