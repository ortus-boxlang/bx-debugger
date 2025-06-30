package ortus.boxlang.moduleslug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Source;

/**
 * Manages source code retrieval and caching for the BoxLang debugger.
 * Handles both file-based sources and dynamically generated content.
 */
public class SourceManager {

	private static final Logger							LOGGER			= Logger.getLogger( SourceManager.class.getName() );

	// Cache for source content by source reference
	private final ConcurrentHashMap<Integer, String>	sourceCache		= new ConcurrentHashMap<>();

	// Cache for file content by path
	private final ConcurrentHashMap<String, String>		fileCache		= new ConcurrentHashMap<>();

	// Whether to enable file caching for performance
	private final boolean								enableFileCache	= true;

	/**
	 * Retrieve source content for the given source.
	 * 
	 * @param source the source to retrieve content for
	 * 
	 * @return the source content, or empty string if not available
	 */
	public String getSourceContent( Source source ) {
		if ( source == null ) {
			LOGGER.warning( "Source is null, returning empty content" );
			return "";
		}

		// Handle source reference (dynamically generated content)
		if ( source.getSourceReference() != null && source.getSourceReference() > 0 ) {
			return getSourceContentByReference( source.getSourceReference() );
		}

		// Handle file-based source
		if ( source.getPath() != null ) {
			return getSourceContentByPath( source.getPath() );
		}

		LOGGER.warning( "Source has no path or sourceReference, returning empty content" );
		return "";
	}

	/**
	 * Get source content by source reference (for dynamically generated content).
	 * 
	 * @param sourceReference the source reference ID
	 * 
	 * @return the cached source content, or a placeholder message if not found
	 */
	private String getSourceContentByReference( int sourceReference ) {
		String content = sourceCache.get( sourceReference );
		if ( content != null ) {
			LOGGER.info( "Retrieved cached content for source reference: " + sourceReference );
			return content;
		}

		LOGGER.warning( "No cached content found for source reference: " + sourceReference );
		return "// Source content not available for reference: " + sourceReference;
	}

	/**
	 * Get source content by file path.
	 * 
	 * @param filePath the file path to read
	 * 
	 * @return the file content, or empty string if file doesn't exist or can't be read
	 */
	private String getSourceContentByPath( String filePath ) {
		// Check file cache first if enabled
		if ( enableFileCache && fileCache.containsKey( filePath ) ) {
			LOGGER.fine( "Retrieved cached content for file: " + filePath );
			return fileCache.get( filePath );
		}

		try {
			Path path = Paths.get( filePath );
			if ( !Files.exists( path ) ) {
				LOGGER.warning( "File does not exist: " + filePath );
				return "";
			}

			if ( !Files.isReadable( path ) ) {
				LOGGER.warning( "File is not readable: " + filePath );
				return "";
			}

			String content = Files.readString( path );

			// Cache the content if caching is enabled
			if ( enableFileCache ) {
				fileCache.put( filePath, content );
				LOGGER.fine( "Cached content for file: " + filePath );
			}

			LOGGER.info( "Successfully read file content: " + filePath + " (length: " + content.length() + ")" );
			return content;

		} catch ( IOException e ) {
			LOGGER.warning( "Error reading file: " + filePath + " - " + e.getMessage() );
			return "";
		}
	}

	/**
	 * Store source content for a given source reference.
	 * This is used for dynamically generated content.
	 * 
	 * @param sourceReference the source reference ID
	 * @param content         the source content to store
	 */
	public void storeSourceContent( int sourceReference, String content ) {
		sourceCache.put( sourceReference, content );
		LOGGER.info( "Stored source content for reference: " + sourceReference + " (length: " + content.length() + ")" );
	}

	/**
	 * Clear the file cache. Useful for development scenarios where files change frequently.
	 */
	public void clearFileCache() {
		fileCache.clear();
		LOGGER.info( "File cache cleared" );
	}

	/**
	 * Clear the source reference cache.
	 */
	public void clearSourceCache() {
		sourceCache.clear();
		LOGGER.info( "Source reference cache cleared" );
	}

	/**
	 * Clear all caches.
	 */
	public void clearAllCaches() {
		clearFileCache();
		clearSourceCache();
		LOGGER.info( "All source caches cleared" );
	}

	/**
	 * Get cache statistics for monitoring.
	 * 
	 * @return string with cache statistics
	 */
	public String getCacheStats() {
		return String.format( "SourceManager Cache Stats - File cache: %d entries, Source cache: %d entries",
		    fileCache.size(), sourceCache.size() );
	}

	/**
	 * Check if a source has content available.
	 * This is useful for quick checks without actually loading the content.
	 * 
	 * @param source the source to check
	 * 
	 * @return true if content is likely available, false otherwise
	 */
	public boolean hasSourceContent( Source source ) {
		if ( source == null ) {
			return false;
		}

		// Check source reference cache
		if ( source.getSourceReference() != null && source.getSourceReference() > 0 ) {
			return sourceCache.containsKey( source.getSourceReference() );
		}

		// Check file existence
		if ( source.getPath() != null ) {
			// Check file cache first
			if ( enableFileCache && fileCache.containsKey( source.getPath() ) ) {
				return true;
			}

			// Check if file exists on disk
			try {
				Path path = Paths.get( source.getPath() );
				return Files.exists( path ) && Files.isReadable( path );
			} catch ( Exception e ) {
				LOGGER.fine( "Error checking file existence: " + source.getPath() + " - " + e.getMessage() );
				return false;
			}
		}

		return false;
	}
}
