/*
 *(c) Copyright QNX Software Systems Ltd. 2002.
 * All Rights Reserved.
 * 
 */
package org.eclipse.cdt.debug.internal.core.breakpoints;

import org.eclipse.cdt.debug.core.ICFunctionBreakpoint;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.CoreException;

/**
 * 
 * Enter type comment.
 * 
 * @since Aug 21, 2002
 */
public class CFunctionBreakpoint extends CBreakpoint implements ICFunctionBreakpoint
{
	/**
	 * Breakpoint attribute storing the function this breakpoint suspends 
	 * execution in (value <code>"org.eclipse.cdt.debug.core.function"</code>).
	 * This attribute is a <code>String</code>.
	 */
	protected static final String FUNCTION = "org.eclipse.cdt.debug.core.function"; //$NON-NLS-1$	

	/**
	 * Constructor for CFunctionBreakpoint.
	 */
	public CFunctionBreakpoint()
	{
		super();
	}
	/* (non-Javadoc)
	 * @see org.eclipse.cdt.debug.core.ICFunctionBreakpoint#getFunction()
	 */
	public String getFunction() throws CoreException
	{
		return ensureMarker().getAttribute( FUNCTION, null );
	}

	/* (non-Javadoc)
	 * @see org.eclipse.cdt.debug.core.ICFunctionBreakpoint#setFunction(String)
	 */
	public void setFunction( String function ) throws CoreException
	{
		setAttribute( FUNCTION, function );
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.ILineBreakpoint#getLineNumber()
	 */
	public int getLineNumber() throws CoreException
	{
		return ensureMarker().getAttribute( IMarker.LINE_NUMBER, -1 );
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.ILineBreakpoint#getCharStart()
	 */
	public int getCharStart() throws CoreException
	{
		return ensureMarker().getAttribute( IMarker.CHAR_START, -1 );
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.ILineBreakpoint#getCharEnd()
	 */
	public int getCharEnd() throws CoreException
	{
		return ensureMarker().getAttribute( IMarker.CHAR_END, -1 );
	}
}
