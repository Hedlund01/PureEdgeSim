package zip.hedlund.pureedgesim.edgesdk.engine;

import com.mechalikh.pureedgesim.energy.EnergyModelComputingNode;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;

import zip.hedlund.pureedgesim.edgesdk.model.EnergySpec;

/**
 * The PureEdgeSim node energy model, plus a constant propulsion drain and
 * network energy charged in joules by the SDK channels.
 */
public final class SdkEnergyModel extends EnergyModelComputingNode {

	private final double propulsionW;

	public SdkEnergyModel(EnergySpec spec) {
		super(spec.maxW(), spec.idleW());
		this.propulsionW = spec.propulsionW();
		setBattery(spec.battery());
		if (spec.battery()) {
			setBatteryCapacity(spec.capacityWh());
			setIntialBatteryPercentage(100);
		}
	}

	@Override
	public void updateStaticEnergyConsumption() {
		super.updateStaticEnergyConsumption();
		cpuEnergyConsumption += propulsionW / 3600 * SimulationParameters.updateInterval;
	}

	/** Charges network energy, in joules. */
	public void addNetworkJoules(double joules) {
		networkEnergyConsumption += joules / 3600.0;
	}

	public double networkWh() {
		return networkEnergyConsumption;
	}
}
