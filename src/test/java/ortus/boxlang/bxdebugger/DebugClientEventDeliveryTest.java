package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.junit.jupiter.api.Test;

class DebugClientEventDeliveryTest {

	@Test
	void earlyStopsAreDeliveredOnceInArrivalOrder() throws Exception {
		TestDebugClient			client	= new TestDebugClient();
		StoppedEventArguments	first	= new StoppedEventArguments();
		StoppedEventArguments	second	= new StoppedEventArguments();
		client.stopped( first );
		client.stopped( second );
		assertSame( first, client.waitForStoppedEvent().get( 1, TimeUnit.SECONDS ) );
		assertSame( second, client.waitForStoppedEvent().get( 1, TimeUnit.SECONDS ) );
		assertFalse( client.waitForStoppedEvent().isDone() );
	}

	@Test
	void deliveredStopIsNotReplayedToTheNextWaiter() throws Exception {
		TestDebugClient			client		= new TestDebugClient();
		StoppedEventArguments	first		= new StoppedEventArguments();
		StoppedEventArguments	second		= new StoppedEventArguments();
		var						firstWaiter	= client.waitForStoppedEvent();
		client.stopped( first );
		assertSame( first, firstWaiter.get( 1, TimeUnit.SECONDS ) );

		var secondWaiter = client.waitForStoppedEvent();
		assertFalse( secondWaiter.isDone(), "Must wait for a new stop, not replay the previous one" );
		client.stopped( second );
		assertSame( second, secondWaiter.get( 1, TimeUnit.SECONDS ) );
	}
}
