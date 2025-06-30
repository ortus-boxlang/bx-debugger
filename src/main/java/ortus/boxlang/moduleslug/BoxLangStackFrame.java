package ortus.boxlang.moduleslug;

import org.eclipse.lsp4j.debug.StackFrame;

/**
 * BoxLang-specific stack frame that extends the standard DAP StackFrame
 * with additional properties and context information specific to BoxLang.
 * 
 * This class handles the translation of BoxLang context objects (IBoxContext)
 * into debug-friendly representations for the DAP client.
 */
public class BoxLangStackFrame extends StackFrame {

	/**
	 * Indicates whether this frame represents BoxLang source code
	 */
	private boolean		isBoxLangFrame;

	/**
	 * The BoxLang context object associated with this frame (if any)
	 */
	private Object		boxLangContext;

	/**
	 * The original Java stack frame this BoxLang frame is based on
	 */
	private StackFrame	javaFrame;

	/**
	 * Default constructor
	 */
	public BoxLangStackFrame() {
		super();
		this.isBoxLangFrame = false;
	}

	/**
	 * Constructor from a standard StackFrame
	 * 
	 * @param javaFrame The Java stack frame to wrap
	 */
	public BoxLangStackFrame( StackFrame javaFrame ) {
		super();
		this.javaFrame		= javaFrame;
		this.isBoxLangFrame	= false;

		// Copy properties from the Java frame
		if ( javaFrame != null ) {
			setId( javaFrame.getId() );
			setName( javaFrame.getName() );
			setLine( javaFrame.getLine() );
			setColumn( javaFrame.getColumn() );
			setSource( javaFrame.getSource() );
			// the name and path values are flipped for some reason
			// so we set the path to the name
			getSource().setPath( javaFrame.getSource().getName() );
			setEndLine( javaFrame.getEndLine() );
			setEndColumn( javaFrame.getEndColumn() );
			setModuleId( javaFrame.getModuleId() );
			setPresentationHint( javaFrame.getPresentationHint() );
		}
	}

	/**
	 * Check if this frame represents BoxLang source code
	 * 
	 * @return true if this is a BoxLang frame
	 */
	public boolean isBoxLangFrame() {
		return isBoxLangFrame;
	}

	/**
	 * Set whether this frame represents BoxLang source code
	 * 
	 * @param isBoxLangFrame true if this is a BoxLang frame
	 */
	public void setBoxLangFrame( boolean isBoxLangFrame ) {
		this.isBoxLangFrame = isBoxLangFrame;
	}

	/**
	 * Get the BoxLang context object associated with this frame
	 * 
	 * @return the BoxLang context object, or null if not available
	 */
	public Object getBoxLangContext() {
		return boxLangContext;
	}

	/**
	 * Set the BoxLang context object for this frame
	 * 
	 * @param boxLangContext the BoxLang context object
	 */
	public void setBoxLangContext( Object boxLangContext ) {
		this.boxLangContext = boxLangContext;
	}

	/**
	 * Get the original Java stack frame this BoxLang frame is based on
	 * 
	 * @return the original Java frame, or null if this is a pure BoxLang frame
	 */
	public StackFrame getJavaFrame() {
		return javaFrame;
	}

	/**
	 * Set the original Java stack frame
	 * 
	 * @param javaFrame the Java frame
	 */
	public void setJavaFrame( StackFrame javaFrame ) {
		this.javaFrame = javaFrame;
	}

	/**
	 * Determine if the given frame represents BoxLang source code based on source path
	 * 
	 * @param frame the frame to check
	 * 
	 * @return true if the frame appears to be from BoxLang source
	 */
	public static boolean isBoxLangSourceFrame( StackFrame frame ) {
		if ( frame == null || frame.getSource() == null ) {
			return false;
		}

		String sourcePath = frame.getSource().getPath();
		if ( sourcePath == null ) {
			return false;
		}

		// Check for BoxLang file extensions
		return sourcePath.endsWith( ".bx" ) ||
		    sourcePath.endsWith( ".bxs" ) ||
		    sourcePath.endsWith( ".bxm" ) ||
		    sourcePath.endsWith( ".cf" ) ||
		    sourcePath.endsWith( ".cfs" ) ||
		    sourcePath.endsWith( ".cfm" ) ||
		    sourcePath.endsWith( ".cfml" );
	}

	/**
	 * Create a BoxLang-specific frame from a standard frame
	 * 
	 * @param javaFrame the Java frame to convert
	 * 
	 * @return a BoxLangStackFrame with appropriate properties set
	 */
	public static BoxLangStackFrame fromJavaFrame( StackFrame javaFrame ) {
		BoxLangStackFrame boxFrame = new BoxLangStackFrame( javaFrame );
		boxFrame.setBoxLangFrame( isBoxLangSourceFrame( javaFrame ) );
		return boxFrame;
	}

	@Override
	public String toString() {
		return String.format( "BoxLangStackFrame{id=%d, name='%s', isBoxLang=%s, line=%d, source=%s}",
		    getId(),
		    getName(),
		    isBoxLangFrame,
		    getLine(),
		    getSource() != null ? getSource().getPath() : "null"
		);
	}
}
