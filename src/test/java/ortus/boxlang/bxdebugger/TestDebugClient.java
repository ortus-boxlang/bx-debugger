package ortus.boxlang.bxdebugger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.lsp4j.debug.StoppedEventArguments;

public class TestDebugClient implements IBoxLangDebugClient {

	private CompletableFuture<StoppedEventArguments>		stoppedEventFuture		= null;
	private final AtomicReference<StoppedEventArguments>	lastStoppedEvent		= new AtomicReference<>();
	private CompletableFuture<Void>							initializedEventFuture	= null;
	private final List<BoxLangDumpEventBody>				dumpEvents				= new ArrayList<>();
	private CompletableFuture<BoxLangDumpEventBody>			dumpEventFuture			= null;

	@Override
	public void stopped( StoppedEventArguments args ) {
		lastStoppedEvent.set( args );
		if ( stoppedEventFuture != null && !stoppedEventFuture.isDone() ) {
			stoppedEventFuture.complete( args );
		}
	}

	public CompletableFuture<StoppedEventArguments> waitForStoppedEvent() {
		stoppedEventFuture = new CompletableFuture<>();
		// Complete immediately if the event already arrived before we started waiting
		StoppedEventArguments already = lastStoppedEvent.getAndSet( null );
		if ( already != null ) {
			stoppedEventFuture.complete( already );
		}
		return stoppedEventFuture;
	}

	@Override
	public void initialized() {
		initializedEventFuture.complete( null );
	}

	public CompletableFuture<Void> waitForInitializedEvent() {
		initializedEventFuture = new CompletableFuture<>();

		return initializedEventFuture;
	}

	@Override
	public void boxlangDump( BoxLangDumpEventBody body ) {
		dumpEvents.add( body );
		if ( dumpEventFuture != null && !dumpEventFuture.isDone() ) {
			dumpEventFuture.complete( body );
		}
	}

	public List<BoxLangDumpEventBody> getDumpEvents() {
		return dumpEvents;
	}

	public CompletableFuture<BoxLangDumpEventBody> waitForDumpEvent() {
		dumpEventFuture = new CompletableFuture<>();
		return dumpEventFuture;
	}
}
