package ortus.boxlang.bxdebugger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayDeque;
import java.util.Queue;

import org.eclipse.lsp4j.debug.StoppedEventArguments;

public class TestDebugClient implements IBoxLangDebugClient {

	private CompletableFuture<StoppedEventArguments>	stoppedEventFuture		= null;
	private final Queue<StoppedEventArguments>			stoppedEvents			= new ArrayDeque<>();
	private final CompletableFuture<Void>				initializedEventFuture	= new CompletableFuture<>();
	private final List<BoxLangDumpEventBody>			dumpEvents				= new ArrayList<>();
	private CompletableFuture<BoxLangDumpEventBody>		dumpEventFuture			= null;

	@Override
	public synchronized void stopped( StoppedEventArguments args ) {
		if ( stoppedEventFuture != null && !stoppedEventFuture.isDone() ) {
			var waiter = stoppedEventFuture;
			stoppedEventFuture = null;
			waiter.complete( args );
		} else {
			stoppedEvents.add( args );
		}
	}

	public synchronized CompletableFuture<StoppedEventArguments> waitForStoppedEvent() {
		StoppedEventArguments already = stoppedEvents.poll();
		if ( already != null ) {
			return CompletableFuture.completedFuture( already );
		}
		if ( stoppedEventFuture == null || stoppedEventFuture.isDone() ) {
			stoppedEventFuture = new CompletableFuture<>();
		}
		return stoppedEventFuture;
	}

	@Override
	public void initialized() {
		initializedEventFuture.complete( null );
	}

	public CompletableFuture<Void> waitForInitializedEvent() {
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
