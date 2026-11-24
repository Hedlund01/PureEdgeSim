package zip.hedlund.pureedgesim.edgesdk.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The hardware, power, mobility and radios of a node. Presets are instances of
 * this record; every part can be overridden with a {@code with…} method, which
 * returns a copy.
 *
 * <pre>{@code
 * NodeSpec bigUav = DevicePresets.UAV.withCompute(c -> c.withMipsPerCore(8_000))
 *                                    .withEnergy(e -> e.withCapacityWh(150));
 * }</pre>
 *
 * @param name     base name; {@code addEndDevices(spec, 3)} names the nodes
 *                 {@code name-0, name-1, name-2}
 * @param tier     tier of the continuum
 * @param compute  CPU
 * @param memory   RAM and storage
 * @param energy   power and battery
 * @param mobility how the node moves
 * @param channels channels the node has a radio or port for; a link needs both
 *                 endpoints to support its channel
 * @param relays   whether the node forwards traffic between two other nodes
 *                 (edge servers and clouds do; end devices do not by default)
 */
public record NodeSpec(String name, Tier tier, ComputeSpec compute, MemorySpec memory, EnergySpec energy,
		MobilitySpec mobility, List<ChannelSpec> channels, boolean relays) {

	public NodeSpec {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(tier, "tier");
		Objects.requireNonNull(compute, "compute");
		Objects.requireNonNull(memory, "memory");
		Objects.requireNonNull(energy, "energy");
		Objects.requireNonNull(mobility, "mobility");
		channels = List.copyOf(channels);
		if (tier == Tier.EDGE_STATIC && mobility.isMobile())
			throw new IllegalArgumentException(name + ": a static edge node cannot move; use Tier.EDGE_MOBILE");
	}

	/** @return true when the node has a port or radio for the channel (matched by name) */
	public boolean supports(ChannelSpec channel) {
		return channels.stream().anyMatch(c -> c.name().equals(channel.name()));
	}

	public NodeSpec named(String newName) {
		return new NodeSpec(newName, tier, compute, memory, energy, mobility, channels, relays);
	}

	public NodeSpec withCompute(UnaryOperator<ComputeSpec> f) {
		return new NodeSpec(name, tier, f.apply(compute), memory, energy, mobility, channels, relays);
	}

	public NodeSpec withMemory(UnaryOperator<MemorySpec> f) {
		return new NodeSpec(name, tier, compute, f.apply(memory), energy, mobility, channels, relays);
	}

	public NodeSpec withEnergy(UnaryOperator<EnergySpec> f) {
		return new NodeSpec(name, tier, compute, memory, f.apply(energy), mobility, channels, relays);
	}

	public NodeSpec withMobility(MobilitySpec m) {
		return new NodeSpec(name, tier, compute, memory, energy, m, channels, relays);
	}

	/** Replaces the channel list. */
	public NodeSpec withChannels(ChannelSpec... newChannels) {
		return new NodeSpec(name, tier, compute, memory, energy, mobility, List.of(newChannels), relays);
	}

	/** Adds a channel, or replaces the channel of the same name. */
	public NodeSpec withChannel(ChannelSpec channel) {
		List<ChannelSpec> copy = new ArrayList<>(channels);
		copy.removeIf(c -> c.name().equals(channel.name()));
		copy.add(channel);
		return new NodeSpec(name, tier, compute, memory, energy, mobility, copy, relays);
	}

	public NodeSpec withRelaying(boolean relay) {
		return new NodeSpec(name, tier, compute, memory, energy, mobility, channels, relay);
	}

	public NodeSpec withTier(Tier newTier) {
		return new NodeSpec(name, newTier, compute, memory, energy, mobility, channels, relays);
	}
}
