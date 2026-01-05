package ortus.boxlang.moduleslug;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.debug.Source;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.ClassType;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

public class BreakpointContext {

	private static final Logger	LOGGER			= Logger.getLogger( BreakpointContext.class.getName() );

	private static int			stackFrameId	= 0;

	private int					breakpointId;
	private ThreadReference		stoppedThread;
	private VMController		vmController;
	private ObjectReference		context;
	private List<FrameTuple>	stackFrames;

	public BreakpointContext( int breakpointId, ThreadReference stoppedThread, VMController vmController ) {
		this.breakpointId	= breakpointId;
		this.stoppedThread	= stoppedThread;
		this.vmController	= vmController;
		this.stackFrames	= new ArrayList<>();

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
		return stoppedThread;
	}

	public ObjectReference getContext() {
		if ( context == null ) {
			context = findContextForFrame( stackFrames.get( 0 ).jdiFrame() );
		}
		return context;
	}

	public void resume() {
		stoppedThread.resume();
	}

	public List<org.eclipse.lsp4j.debug.StackFrame> getStackFrames() {
		return stackFrames.stream()
		    .map( FrameTuple::dapFrame )
		    .collect( Collectors.toList() );
	}

	public CompletableFuture<List<Value>> getVisibleScopes( int frameId ) {
		var frameTuple = stackFrames.stream()
		    .filter( frame -> frame.id == frameId )
		    .findFirst();

		if ( !frameTuple.isPresent() ) {
			return CompletableFuture.completedFuture( new ArrayList<>() );
		}

		var context = findNearestContextByFrameId( frameId );

		return context.map( ctx -> invokeGetVisibleScopes( ctx ) )
		    .orElse( CompletableFuture.completedFuture( new ArrayList<Value>() ) );
	}

	private CompletableFuture<List<Value>> invokeGetVisibleScopes( ObjectReference boxContext ) {
		return vmController.invoke( boxContext, "getVisibleScopes", new ArrayList<>(), new ArrayList<Value>() )
		    .thenCompose( ( result ) -> {
			    return vmController.invoke(
			        ( ObjectReference ) result,
			        "get",
			        List.of( "java.lang.String" ),
			        List.of( vmController.vm.mirrorOf( "contextual" ) )
			    );
		    } )
		    .thenApply( scopeStruct -> {

			    return scopeStruct;
		    } )
		    .thenCompose( scopeStruct -> {
			    return vmController.invoke(
			        ( ObjectReference ) scopeStruct,
			        "values",
			        List.of(),
			        List.of()
			    );
		    } )
		    .thenApply( scopeStruct -> {

			    return scopeStruct;
		    } )
		    .thenCompose( scopeList -> vmController.invoke(
		        ( ObjectReference ) scopeList,
		        "toArray",
		        List.of(),
		        List.of()
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

		return findNearestContext( toSearch );
	}

	/**
	 * Get stack frames for the specified thread
	 */
	private void transformStackFrames() {
		try {
			this.stackFrames = stoppedThread.frames()
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

	private ObjectReference findContextForFrame( StackFrame frame ) {
		try {
			for ( var visibleVariable : frame.visibleVariables() ) {

				if ( isBoxContext( visibleVariable ) ) {
					return ( ObjectReference ) frame.getValue( visibleVariable );
				}
			}
		} catch ( AbsentInformationException e ) {
			LOGGER.severe( "Unable to get context for frame: " + e.getMessage() );
		}
		return null;
	}

	private boolean isBoxContext( LocalVariable variable ) {
		try {

			var type = variable.type();

			if ( type.name().equalsIgnoreCase( "ortus.boxlang.runtime.context.IBoxContext" ) ) {
				return true;
			}

			if ( ! ( type instanceof ClassType ) ) {
				return false;
			}

			var isBoxContext = ( ( ClassType ) type ).allInterfaces()
			    .stream()
			    .anyMatch( iname -> iname.name().equalsIgnoreCase( "ortus.boxlang.runtime.context.IBoxContext" ) );

			return isBoxContext;
		} catch ( ClassNotLoadedException exception ) {
			LOGGER.severe( "Class not loaded: " + exception.getMessage() );
		}

		return false;
	}

	private Optional<ObjectReference> findNearestContext( List<StackFrame> frames ) {
		for ( var frame : frames ) {
			var context = findContextForFrame( frame );
			if ( context != null ) {
				return Optional.of( context );
			}
		}

		return Optional.empty();
	}

}
