package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import zip.hedlund.pureedgesim.edgesdk.model.LinkSpec;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.network.Degradation;
import zip.hedlund.pureedgesim.edgesdk.network.DownCause;
import zip.hedlund.pureedgesim.edgesdk.network.LinkState;
import zip.hedlund.pureedgesim.edgesdk.network.LiveNetwork;
import zip.hedlund.pureedgesim.edgesdk.network.SdkLink;
import zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets;

class LinkStateTest {

	private SdkLink link() {
		return new SdkLink(new LinkSpec(NodeRef.of("a"), NodeRef.of("b"), ChannelPresets.WIFI), null, null,
				new LiveNetwork());
	}

	@Test
	void causesAreReferenceCounted() {
		SdkLink l = link();
		assertEquals(LinkState.UP, l.state());
		l.addCause(DownCause.PARTITION);
		l.addCause(DownCause.FAULT);
		assertEquals(LinkState.DOWN, l.state());
		// the random failure ends during the partition: still down
		l.removeCause(DownCause.FAULT);
		assertEquals(LinkState.DOWN, l.state());
		l.removeCause(DownCause.PARTITION);
		assertEquals(LinkState.UP, l.state());
	}

	@Test
	void sameCauseTwiceNeedsTwoRemovals() {
		SdkLink l = link();
		l.addCause(DownCause.FAULT);
		l.addCause(DownCause.FAULT);
		l.removeCause(DownCause.FAULT);
		assertTrue(!l.isUp());
		l.removeCause(DownCause.FAULT);
		assertTrue(l.isUp());
		l.removeCause(DownCause.FAULT); // extra removals are ignored
		assertTrue(l.isUp());
	}

	@Test
	void degradationsCombineAndRestoreExactly() {
		SdkLink l = link();
		Object t1 = l.degrade(Degradation.latency(0.1).plusLoss(0.1));
		Object t2 = l.degrade(Degradation.loss(0.1).timesBandwidth(0.5));
		assertEquals(LinkState.DEGRADED, l.state());
		assertEquals(0.1, l.degradation().extraLatencyS(), 1e-12);
		assertEquals(0.19, l.degradation().extraLoss(), 1e-12);
		assertEquals(0.5, l.degradation().bandwidthFactor(), 1e-12);
		l.restore(t1);
		assertEquals(0, l.degradation().extraLatencyS(), 1e-12);
		l.restore(t2);
		assertEquals(LinkState.UP, l.state());
	}
}
