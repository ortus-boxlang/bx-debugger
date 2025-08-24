package ortus.boxlang.moduleslug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.debug.Source;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

public class BreakpointContext {

	private static final Logger	LOGGER			= Logger.getLogger( BreakpointContext.class.getName() );

	private static int			stackFrameId	= 0;
	private static int			variableId		= 0;

	private int					breakpointId;
	private ThreadReference		threadReference;
	private ObjectReference		context;
	private List<FrameTuple>	stackFrames;
	private Map<Integer, Value>	variables		= new WeakHashMap<>();

	public BreakpointContext( int breakpointId, ThreadReference threadReference ) {
		this.breakpointId		= breakpointId;
		this.threadReference	= threadReference;
		this.stackFrames		= new ArrayList<>();

		this.transformStackFrames();
	}

	private record FrameTuple( int id, org.eclipse.lsp4j.debug.StackFrame dapFrame, StackFrame jdiFrame ) {
	}

	public static int getNextStackFrameId() {
		return stackFrameId++;
	}

	public boolean hasStackFrameId( int stackframeId ) {
		return stackFrames.stream()
		    .anyMatch( frame -> frame.id == stackframeId );
	}

	public int getBreakpointId() {
		return breakpointId;
	}

	public ThreadReference getThreadReference() {
		return threadReference;
	}

	public ObjectReference getContext() {
		return context;
	}

	public void resume() {
		threadReference.resume();
	}

	public List<org.eclipse.lsp4j.debug.StackFrame> getStackFrames() {
		return stackFrames.stream()
		    .map( FrameTuple::dapFrame )
		    .collect( Collectors.toList() );
	}

	public CompletableFuture<List<Value>> getVisibleScopes( ThreadReference invokeThread, int frameId ) {
		var frameTuple = stackFrames.stream()
		    .filter( frame -> frame.id == frameId )
		    .findFirst();

		if ( !frameTuple.isPresent() ) {
			return CompletableFuture.completedFuture( new ArrayList<>() );
		}

		var context = findNearestContextByFrameId( frameId );

		return context.map( ctx -> invokeGetVisibleScopes( invokeThread, ctx ) )
		    .orElse( CompletableFuture.completedFuture( new ArrayList<Value>() ) );
	}

	private CompletableFuture<List<Value>> invokeGetVisibleScopes( ThreadReference invokeThread, ObjectReference boxContext ) {
		return Util.invokeAsync( invokeThread, boxContext, "getVisibleScopes", "()Lortus/boxlang/runtime/types/IStruct;", new ArrayList<Value>() )
		    .handle( ( result, error ) -> {
			    if ( error != null ) {
				    return new ArrayList<>();
			    }

			    return Util.invokeAsync(
			        invokeThread,
			        ( ObjectReference ) result,
			        "get",
			        "(Ljava/lang/String;)Ljava/lang/Object;",
			        Arrays.asList( invokeThread.virtualMachine().mirrorOf( "contextual" ) )
			    ).join();
		    } )
		    .thenCompose( scopeStruct -> Util.invokeAsync(
		        invokeThread,
		        ( ObjectReference ) scopeStruct,
		        "values",
		        "()Ljava/util/Collection;",
		        Arrays.asList()
		    ) )
		    .thenCompose( scopeList -> Util.invokeAsync(
		        invokeThread,
		        ( ObjectReference ) scopeList,
		        "toArray",
		        "()[Ljava/lang/Object;",
		        Arrays.asList()
		    ) )
		    .thenApply( arrayOfScopes -> {

			    if ( arrayOfScopes instanceof ArrayReference ref ) {
				    return ref.getValues();
			    }

			    return new ArrayList<>();
		    } );
	}

	private Optional<ObjectReference> findNearestContextByFrameId( int frameId ) {

		Optional<FrameTuple>	frameTuple	= stackFrames.stream()
		    .filter( frame -> frame.id == frameId )
		    .findFirst();

		var						frameIndex	= stackFrames.indexOf( frameTuple.get() );

		List<StackFrame>		toSearch	= stackFrames.stream()
		    .skip( frameIndex )
		    .map( ft -> ft.jdiFrame() )
		    .collect( Collectors.toList() );

		return Util.findNearestContext( toSearch );
	}

	/**
	 * Get stack frames for the specified thread
	 */
	private void transformStackFrames() {
		try {
			this.stackFrames = threadReference.frames()
			    .stream()
			    .map( frame -> {
				    org.eclipse.lsp4j.debug.StackFrame dapFrame = new org.eclipse.lsp4j.debug.StackFrame();
				    // Set frame ID (using index)
				    dapFrame.setId( getNextStackFrameId() );

				    // Set frame name (method name)
				    String frameName = frame.location().method().name();
				    if ( frameName != null ) {
					    dapFrame.setName( frameName );
				    } else {
					    dapFrame.setName( "<unknown>" );
				    }

				    // Set line number
				    try {
					    int lineNumber = frame.location().lineNumber();
					    dapFrame.setLine( lineNumber );
				    } catch ( Exception e ) {
					    dapFrame.setLine( 0 );
				    }

				    // Set column (default to 0)
				    dapFrame.setColumn( 0 );

				    // Set source information
				    try {
					    Location location	= frame.location();
					    String	sourceName	= location.sourceName();
					    String	sourcePath	= location.sourcePath();

					    Source	source		= new Source();
					    source.setName( sourceName );

					    // Try to construct full path
					    if ( sourcePath != null ) {
						    source.setPath( sourcePath );
					    }

					    dapFrame.setSource( source );
				    } catch ( AbsentInformationException e ) {
					    // Source information not available
					    LOGGER.fine( "Source information not available for frame: " + frameName );
				    }

				    return new FrameTuple( dapFrame.getId(), dapFrame, frame );
			    } ).collect( Collectors.toList() );
		} catch ( IncompatibleThreadStateException e ) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
