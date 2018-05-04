package org.janelia.saalfeldlab.paintera.data.meta;

import java.util.Arrays;
import java.util.function.LongFunction;

import org.janelia.saalfeldlab.paintera.control.assignment.FragmentSegmentAssignmentState;
import org.janelia.saalfeldlab.paintera.data.meta.exception.AssignmentCreationFailed;
import org.janelia.saalfeldlab.paintera.data.meta.exception.BlocksForIdCacheCreationFailed;
import org.janelia.saalfeldlab.paintera.data.meta.exception.IdServiceCreationFailed;
import org.janelia.saalfeldlab.paintera.data.meta.exception.MeshCacheCreationFailed;
import org.janelia.saalfeldlab.paintera.id.IdService;
import org.janelia.saalfeldlab.paintera.id.ToIdConverter;
import org.janelia.saalfeldlab.paintera.meshes.InterruptibleFunction;
import org.janelia.saalfeldlab.paintera.meshes.MeshGenerator.ShapeKey;

import net.imglib2.Interval;
import net.imglib2.converter.Converter;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.type.label.LabelMultisetType;
import net.imglib2.type.logic.BoolType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Pair;

public interface LabelMeta
{

	public default FragmentSegmentAssignmentState assignment() throws AssignmentCreationFailed
	{
		return assignment( new long[] {}, new long[] {} );
	}

	public FragmentSegmentAssignmentState assignment( long[] fragments, long[] segments ) throws AssignmentCreationFailed;

	public IdService idService() throws IdServiceCreationFailed;

	public default InterruptibleFunction< Long, Interval[] >[] blocksThatContainId() throws BlocksForIdCacheCreationFailed
	{
		return null;
	}

	public default InterruptibleFunction< ShapeKey, Pair< float[], float[] > >[] meshCache() throws MeshCacheCreationFailed
	{
		return null;
	}

	public default < T > ToIdConverter toIdConverter( T t ) throws IncompatibleTypeException
	{
		if ( t instanceof LabelMultisetType )
			return ToIdConverter.fromLabelMultisetType();
		else if ( t instanceof IntegerType< ? > )
			return ToIdConverter.fromIntegerType();
		else if ( t instanceof ARGBType )
			return ToIdConverter.fromARGB();
		else if ( t instanceof RealType< ? > )
			return ToIdConverter.fromRealType();
		
		
		throw new IncompatibleTypeException( t, "Supported types are: " + Arrays.asList(
				LabelMultisetType.class.getName(),
				IntegerType.class.getName(),
				ARGBType.class.getName(),
				RealType.class.getName() ) );
		
	}

	@SuppressWarnings( "unchecked" )
	public default < D > LongFunction< Converter< D, BoolType > > maskForId( D d ) throws IncompatibleTypeException
	{
		if ( d instanceof LabelMultisetType )
			return id -> ( Converter< D, BoolType > ) maskForIdLabelMultisetType( id );
		if ( d instanceof IntegerType< ? > )
			return id -> ( Converter< D, BoolType > ) maskForIdIntegerType( id );
		throw new IncompatibleTypeException( d, "Supported types are: " + Arrays.asList(
				LabelMultisetType.class.getName(),
				IntegerType.class.getName() ) );
	}

	public static Converter< LabelMultisetType, BoolType > maskForIdLabelMultisetType( final long id )
	{
		return ( s, t ) -> t.set( s.contains( id ) );
	}

	public static < I extends IntegerType< I > > Converter< I, BoolType > maskForIdIntegerType( final long id )
	{
		return ( s, t ) -> t.set( s.getIntegerLong() == id );
	}

}
