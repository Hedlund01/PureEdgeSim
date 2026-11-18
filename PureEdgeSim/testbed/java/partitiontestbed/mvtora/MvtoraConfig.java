package partitiontestbed.mvtora;

import partitiontestbed.PropertiesFile;

/**
 * The knobs of MVTORA, read from the {@code mvtora.*} keys of the common file,
 * and from {@code mvtora.properties} of the scenario when it has one. Any key can
 * be overridden with {@code -Dmvtora.<key>=<value>}. A key left out takes the
 * value of Table II of the paper, or the one stated here.
 *
 * @see MvtoraStrategy
 */
public class MvtoraConfig extends PropertiesFile {

	/** Prefix of the command line overrides of this file. */
	public static final String PREFIX = "mvtora.";

	/** Where a device may divide its task: the vehicles it hears, or every edge server it hears. */
	public enum FogNodes {
		/** The moving edge servers, built into vehicles: the vehicle fog layer of the paper. */
		MOBILE,
		/** Every edge server the device hears by radio, other than its controller. */
		ANY
	}

	/** How the controller estimates the rate of a radio link. */
	public enum RateModel {
		/** The nominal bandwidth of the device's radio, as configured: CSI known exactly. */
		NOMINAL,
		/** The probabilistic LoS model of the paper (eq. 6 to 10), from the reported positions. */
		CHANNEL
	}

	// Protocol
	/** Time the controller waits after the first request of a round for the others, in seconds. */
	public final double collectWindow;
	/** Time a device waits for its decision before it computes locally, in seconds. */
	public final double decisionTimeout;
	/** Period of the beacons of the fog nodes, in seconds. */
	public final double beaconInterval;
	/** Age beyond which a device ignores a beacon, in seconds. */
	public final double beaconMaxAge;
	/** Which servers are fog nodes. */
	public final FogNodes fogNodes;
	/** K_n: the most fog nodes a task is divided over. */
	public final int maxFogNodes;

	// Utility, eq. 17 to 21
	/** alpha_n: weight of the completion delay. */
	public final double alpha;
	/** beta_n: weight of the energy. */
	public final double energyWeight;
	/** beta: the constant that keeps the revenue non-negative. */
	public final double beta;
	/** rho_0: price of a GHz of the controller, per GHz. */
	public final double pricePerGhz;
	/** k: effective switched capacitance of a device's processor. */
	public final double capacitance;
	/** P_{n,m}, P_{n,u}: transmit power of a device, in watts. */
	public final double transmitPower;

	// Algorithms 1, 2 and 4
	/** epsilon: accuracy of the bisection on gamma. */
	public final double bisectionAccuracy;
	/** gamma^bound: upper bound of the bisection on gamma. */
	public final double gammaBound;
	/** The most iterations of the offloading game, in case it does not settle. */
	public final int maxGameIterations;
	/** G: generations of the genetic algorithm. */
	public final int generations;
	/** L: size of its population. */
	public final int population;
	/** pc: crossover probability. */
	public final double crossover;
	/** pm: mutation probability. */
	public final double mutation;

	// Channel
	/** How rates are estimated. */
	public final RateModel rateModel;
	/** H: altitude of a device, as the paper's C-UAVs fly, in metres. */
	public final double deviceAltitude;
	/** H_u: altitude of the controller, the paper's E-UAV, in metres. */
	public final double controllerAltitude;
	/** B: bandwidth of a sub-channel, in hertz. */
	public final double subchannelBandwidth;
	/** K_n: sub-channels of a device. */
	public final int subchannels;
	/** beta_0: path loss at 1 m. */
	public final double pathLossReference;
	/** kappa: additional attenuation of NLoS. */
	public final double nlosAttenuation;
	/** a: parameter of the LoS probability. */
	public final double losA;
	/** b: parameter of the LoS probability. */
	public final double losB;
	/** mu: path loss exponent. */
	public final double pathLossExponent;
	/** sigma^2: noise power spectral density, in watts per hertz. */
	public final double noiseDensity;
	/** Psi: half-power beamwidth, in radians. */
	public final double halfBeamwidth;
	/** G_0: antenna gain within the beam. */
	public final double antennaGain;

	// Message sizes, in bytes
	public final int requestBytes;
	public final int fogEntryBytes;
	public final int decisionBytes;
	public final int beaconBytes;

	/**
	 * Reads and validates the MVTORA file.
	 *
	 * @param defaultFile the file to read unless {@code -Dmvtora.config} names
	 *                    another
	 */
	public MvtoraConfig(String defaultFile) {
		super(PREFIX, defaultFile);
		collectWindow = number("round.collect_ms", 0, 10_000, 50) / 1000;
		decisionTimeout = number("round.decision_timeout_ms", 1, 60_000, 500) / 1000;
		beaconInterval = number("beacon.interval_s", 0.01, 3600, 1);
		beaconMaxAge = number("beacon.max_age_s", 0, 3600, 3);
		fogNodes = FogNodes.valueOf(choice("vfc.fog_nodes", "mobile", "mobile", "any").toUpperCase());
		maxFogNodes = integer("vfc.max_fog_nodes", 1, 64, 5);

		alpha = number("utility.alpha", 0, 1, 0.9);
		energyWeight = number("utility.energy_weight", 0, 1, 0.1);
		beta = number("utility.beta", Double.MIN_VALUE, Double.MAX_VALUE, 1);
		pricePerGhz = number("utility.price_per_ghz", 0, Double.MAX_VALUE, 0.001);
		capacitance = number("energy.capacitance", 0, 1, 1e-28);
		transmitPower = dbmToWatts(number("energy.transmit_power_dbm", -100, 100, 20));

		bisectionAccuracy = number("bisection.accuracy", Double.MIN_VALUE, 1, 1e-6);
		gammaBound = number("bisection.gamma_bound", Double.MIN_VALUE, Double.MAX_VALUE, 1e4);
		maxGameIterations = integer("game.max_iterations", 1, 10_000, 100);
		generations = integer("ga.generations", 1, 100_000, 200);
		population = integer("ga.population", 2, 100_000, 50);
		crossover = number("ga.crossover", 0, 1, 0.8);
		mutation = number("ga.mutation", 0, 1, 0.1);

		rateModel = RateModel.valueOf(choice("rate.model", "nominal", "nominal", "channel").toUpperCase());
		deviceAltitude = number("channel.device_altitude_m", 0, 1e5, 100);
		controllerAltitude = number("channel.controller_altitude_m", 0, 1e5, 300);
		subchannelBandwidth = number("channel.subchannel_bandwidth_hz", 1, 1e12, 0.2e6);
		subchannels = integer("channel.subchannels", 1, 1024, 5);
		pathLossReference = number("channel.path_loss_reference", Double.MIN_VALUE, 1, 1.42e-4);
		nlosAttenuation = number("channel.nlos_attenuation", 0, 1, 0.2);
		losA = number("channel.los_a", 0, 1e6, 10);
		losB = number("channel.los_b", 0, 1e6, 0.6);
		pathLossExponent = number("channel.path_loss_exponent", 1, 10, 2.3);
		noiseDensity = dbmToWatts(number("channel.noise_dbm_per_hz", -300, 0, -174));
		halfBeamwidth = number("channel.half_beamwidth_rad", 1e-6, Math.PI / 2, Math.PI / 4);
		antennaGain = number("channel.antenna_gain", Double.MIN_VALUE, 1e6, 2.2846);

		requestBytes = integer("messages.request_bytes", 0, Integer.MAX_VALUE, 200);
		fogEntryBytes = integer("messages.fog_entry_bytes", 0, Integer.MAX_VALUE, 40);
		decisionBytes = integer("messages.decision_bytes", 0, Integer.MAX_VALUE, 100);
		beaconBytes = integer("messages.beacon_bytes", 0, Integer.MAX_VALUE, 80);
	}

	private static double dbmToWatts(double dbm) {
		return Math.pow(10, dbm / 10) / 1000;
	}
}
