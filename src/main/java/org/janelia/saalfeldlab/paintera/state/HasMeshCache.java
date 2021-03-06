package org.janelia.saalfeldlab.paintera.state;

import java.util.function.Predicate;

public interface HasMeshCache<T>
{

	void invalidateAll();

	default void invalidateMatching(final Predicate<T> filter)
	{
		throw new UnsupportedOperationException("Not implemented yet. Requires updates on imglib2 cache first");
	}

}
