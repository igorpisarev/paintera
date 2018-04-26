package org.janelia.saalfeldlab.paintera.control.paint;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import org.janelia.saalfeldlab.paintera.SourceInfo;
import org.janelia.saalfeldlab.paintera.SourceState;
import org.janelia.saalfeldlab.paintera.data.mask.MaskInUse;
import org.janelia.saalfeldlab.paintera.data.mask.MaskInfo;
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.fx.viewer.ViewerPanelFX;
import bdv.fx.viewer.ViewerState;
import bdv.viewer.Source;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.algorithm.fill.Filter;
import net.imglib2.algorithm.neighborhood.DiamondShape;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.Type;
import net.imglib2.type.label.Label;
import net.imglib2.type.label.LabelMultisetType;
import net.imglib2.type.label.Multiset.Entry;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.util.AccessBoxRandomAccessible;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.view.Views;

public class FloodFill
{

	private static final Logger LOG = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	private final ViewerPanelFX viewer;

	private final SourceInfo sourceInfo;

	private final Runnable requestRepaint;

	private final AffineTransform3D viewerTransform = new AffineTransform3D();

	public FloodFill( final ViewerPanelFX viewer, final SourceInfo sourceInfo, final Runnable requestRepaint )
	{
		super();
		this.viewer = viewer;
		this.sourceInfo = sourceInfo;
		this.requestRepaint = requestRepaint;
		viewer.addTransformListener( t -> viewerTransform.set( t ) );
	}

	public void fillAt( final double x, final double y, final Supplier< Long > fillSupplier )
	{
		if ( sourceInfo.currentSourceProperty().get() == null )
		{
			LOG.warn( "No current source selected -- will not fill" );
			return;
		}
		final Long fill = fillSupplier.get();
		if ( fill == null )
		{
			LOG.warn( "Received invalid label {} -- will not fill.", fill );
			return;
		}
		fillAt( x, y, fill );
	}

	public void fillAt( final double x, final double y, final long fill )
	{
		final Source< ? > currentSource = sourceInfo.currentSourceProperty().get();
		final ViewerState viewerState = viewer.getState();
		if ( currentSource == null )
		{
			LOG.warn( "No current source selected -- will not fill" );
			return;
		}

		final SourceState< ?, ? > state = sourceInfo.getState( currentSource );
		if ( !state.visibleProperty().get() )
		{
			LOG.warn( "Selected source is not visible -- will not fill" );
			return;
		}

		if ( !( currentSource instanceof MaskedSource< ?, ? > ) )
		{
			LOG.warn( "Selected source is not painting-enabled -- will not fill" );
			return;
		}

		final LongFunction< ? > maskGenerator = state.maskGeneratorProperty().get();
		if ( maskGenerator == null )
		{
			LOG.warn( "Cannot generate boolean mask for this source -- will not fill" );
			return;
		}

		final MaskedSource< ?, ? > source = ( MaskedSource< ?, ? > ) currentSource;

		final Type< ? > t = source.getDataType();

		if ( !( t instanceof RealType< ? > ) && !( t instanceof LabelMultisetType ) )
		{
			LOG.warn( "Data type is not real or LabelMultisetType type -- will not fill" );
			return;
		}

		// TODO always fill at highest resolution?
//		final int level = viewerState.getBestMipMapLevel( new AffineTransform3D(), sourceInfo.currentSourceIndexInVisibleSources().get() );
		final int level = 0;
		final AffineTransform3D labelTransform = new AffineTransform3D();
		final int time = viewerState.timepointProperty().get();
		source.getSourceTransform( time, level, labelTransform );

		final RealPoint rp = setCoordinates( x, y, viewer, labelTransform );
		final Point p = new Point( rp.numDimensions() );
		for ( int d = 0; d < p.numDimensions(); ++d )
		{
			p.setPosition( Math.round( rp.getDoublePosition( d ) ), d );
		}

		LOG.debug( "Filling source {} with label {} at {}", source, fill, p );
		final Scene scene = viewer.getScene();
		final Cursor previousCursor = scene.getCursor();
		try
		{
			if ( t instanceof LabelMultisetType )
			{
				fillMultiset(
						( MaskedSource ) source,
						time,
						level,
						fill,
						p,
						new RunAll( requestRepaint, () -> scene.setCursor( Cursor.WAIT ) ),
						new RunAll( requestRepaint, () -> scene.setCursor( previousCursor ) ) );
			}
			else
			{
				fill(
						( MaskedSource ) source,
						time,
						level,
						fill,
						p,
						new RunAll( requestRepaint, () -> scene.setCursor( Cursor.WAIT ) ),
						new RunAll( requestRepaint, () -> scene.setCursor( previousCursor ) ) );
			}
		}
		catch ( final MaskInUse e )
		{
			LOG.warn( e.getMessage() );
			return;
		}

	}

	private static RealPoint setCoordinates(
			final double x,
			final double y,
			final ViewerPanelFX viewer,
			final AffineTransform3D labelTransform )
	{
		return setCoordinates( x, y, new RealPoint( labelTransform.numDimensions() ), viewer, labelTransform );
	}

	private static < P extends RealLocalizable & RealPositionable > P setCoordinates(
			final double x,
			final double y,
			final P location,
			final ViewerPanelFX viewer,
			final AffineTransform3D labelTransform )
	{
		location.setPosition( x, 0 );
		location.setPosition( y, 1 );
		location.setPosition( 0, 2 );

		viewer.displayToGlobalCoordinates( location );
		labelTransform.applyInverse( location, location );

		return location;
	}

	private static < T extends RealType< T > > void fill(
			final MaskedSource< T, ? > source,
			final int time,
			final int level,
			final long fill,
			final Localizable seed,
			final Runnable doWhileFilling,
			final Runnable doWhenDone ) throws MaskInUse
	{
		final MaskInfo< UnsignedLongType > maskInfo = new MaskInfo<>( time, level, new UnsignedLongType( fill ) );
		final RandomAccessibleInterval< UnsignedByteType > mask = source.generateMask( maskInfo );
		final AccessBoxRandomAccessible< UnsignedByteType > accessTracker = new AccessBoxRandomAccessible<>( Views.extendValue( mask, new UnsignedByteType( 1 ) ) );
		final Thread t = new Thread( () -> {
			net.imglib2.algorithm.fill.FloodFill.fill(
					source.getDataSource( time, level ),
					accessTracker,
					seed,
					new UnsignedByteType( 1 ),
					new DiamondShape( 1 ),
					makeFilter() );
			final Interval interval = accessTracker.createAccessInterval();
			LOG.debug( "Applying mask for interval {} {}", Arrays.toString( Intervals.minAsLongArray( interval ) ), Arrays.toString( Intervals.maxAsLongArray( interval ) ) );
		} );
		t.start();
		new Thread( () -> {
			while ( t.isAlive() && !Thread.interrupted() )
			{
				try
				{
					Thread.sleep( 100 );
				}
				catch ( final InterruptedException e )
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				LOG.debug( "Updating current view!" );
				doWhileFilling.run();
			}
			doWhenDone.run();
			if ( !Thread.interrupted() )
			{
				source.applyMask( mask, accessTracker.createAccessInterval() );
			}
		} ).start();
	}

	private static void fillMultiset(
			final MaskedSource< LabelMultisetType, ? > source,
			final int time,
			final int level,
			final long fill,
			final Localizable seed,
			final Runnable doWhileFilling,
			final Runnable doWhenDone ) throws MaskInUse
	{

		final RandomAccessibleInterval< LabelMultisetType > data = source.getDataSource( time, level );
		final RandomAccess< LabelMultisetType > dataAccess = data.randomAccess();
		dataAccess.setPosition( seed );
		final long seedLabel = getArgMaxLabel( dataAccess.get() );
		if ( !Label.regular( seedLabel ) )
		{
			LOG.warn( "Trying to fill at irregular label: {} ({})", seedLabel, new Point( seed ) );
			return;
		}

		final MaskInfo< UnsignedLongType > maskInfo = new MaskInfo<>( time, level, new UnsignedLongType( fill ) );
		final RandomAccessibleInterval< UnsignedByteType > mask = source.generateMask( maskInfo );
		final AccessBoxRandomAccessible< UnsignedByteType > accessTracker = new AccessBoxRandomAccessible<>( Views.extendValue( mask, new UnsignedByteType( 1 ) ) );
		final Thread t = new Thread( () -> {
			net.imglib2.algorithm.fill.FloodFill.fill(
					data,
					accessTracker,
					seed,
					new UnsignedByteType( 1 ),
					new DiamondShape( 1 ),
					makeFilterMultiset( seedLabel ) );
			final Interval interval = accessTracker.createAccessInterval();
			LOG.debug( "Applying mask for interval {} {}", Arrays.toString( Intervals.minAsLongArray( interval ) ), Arrays.toString( Intervals.maxAsLongArray( interval ) ) );
		} );
		t.start();
		new Thread( () -> {
			while ( t.isAlive() && !Thread.interrupted() )
			{
				try
				{
					Thread.sleep( 100 );
				}
				catch ( final InterruptedException e )
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				LOG.debug( "Updating current view!" );
				doWhileFilling.run();
			}
			doWhenDone.run();
			if ( !Thread.interrupted() )
			{
				source.applyMask( mask, accessTracker.createAccessInterval() );
			}
		} ).start();
	}

	private static < T extends Type< T > > Filter< Pair< T, UnsignedByteType >, Pair< T, UnsignedByteType > > makeFilter()
	{
		final UnsignedByteType zero = new UnsignedByteType( 0 );
		// first element in pair is current pixel, second element is reference
		return ( p1, p2 ) -> p1.getB().valueEquals( zero ) && p1.getA().valueEquals( p2.getA() );
	}

	private static Filter< Pair< LabelMultisetType, UnsignedByteType >, Pair< LabelMultisetType, UnsignedByteType > > makeFilterMultiset( final long id )
	{
		final UnsignedByteType zero = new UnsignedByteType( 0 );
		// first element in pair is current pixel, second element is reference
		return ( p1, p2 ) -> p1.getB().valueEquals( zero ) && p1.getA().contains( id );
	}

	public static class RunAll implements Runnable
	{

		private final List< Runnable > runnables;

		public RunAll( final Runnable... runnables )
		{
			this( Arrays.asList( runnables ) );
		}

		public RunAll( final Collection< Runnable > runnables )
		{
			super();
			this.runnables = new ArrayList<>( runnables );
		}

		@Override
		public void run()
		{
			this.runnables.forEach( Runnable::run );
		}

	}

	public static long getArgMaxLabel( final LabelMultisetType t )
	{
		long argmax = Label.INVALID;
		long max = 0;
		for ( final Entry< net.imglib2.type.label.Label > e : t.entrySet() )
		{
			final int count = e.getCount();
			if ( count > max )
			{
				max = count;
				argmax = e.getElement().id();
			}
		}
		return argmax;
	}

}