package ortus.boxlang.moduleslug;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;

public class TestDebugClient implements IDebugProtocolClient {

	private CompletableFuture<StoppedEventArguments> stoppedEventFuture = null;

	@Override
	public void stopped( StoppedEventArguments args ) {
		stoppedEventFuture.complete( args );
	}

	public CompletableFuture<StoppedEventArguments> waitForStoppedEvent() {
		stoppedEventFuture = new CompletableFuture<>();

		return stoppedEventFuture;
	}
}
