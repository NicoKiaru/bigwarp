package bdv.img;

import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.render.DefaultMipmapOrdering;
import bdv.viewer.render.MipmapOrdering;
import bigwarp.BigWarpExporter;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Wrapped2DTransformAs3D;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

public class WarpedSource < T > implements Source< T >, MipmapOrdering
{

	public static < T > SourceAndConverter< T > wrap( final SourceAndConverter< T > wrap, final String name, int ndims )
	{
		return new SourceAndConverter< T >(
				new WarpedSource< T >( wrap.getSpimSource(), name ),
				wrap.getConverter(),
				wrap.asVolatile() == null ? null : wrap( wrap.asVolatile(), name, ndims ) );
	}

	/**
	 * The wrapped {@link Source}.
	 */
	private final Source< T > source;

	private final String name;

	/**
	 * This is either the {@link #source} itself, if it implements
	 * {@link MipmapOrdering}, or a {@link DefaultMipmapOrdering}.
	 */
	private final MipmapOrdering sourceMipmapOrdering;

	private InvertibleRealTransform xfm;

	private boolean isTransformed;
	
	public WarpedSource( final Source< T > source, final String name )
	{
		this.source = source;
		this.name = name;
		this.isTransformed = false;

		this.xfm = null;

		sourceMipmapOrdering = MipmapOrdering.class.isInstance( source ) ?
				( MipmapOrdering ) source : new DefaultMipmapOrdering( source );
	}

	@Override
	public boolean isPresent( final int t )
	{
		return source.isPresent( t );
	}
	
	public void updateTransform( InvertibleRealTransform xfm )
	{
		this.xfm = xfm;
	}
	
	public void setIsTransformed( boolean isTransformed )
	{
		this.isTransformed = isTransformed;
	}
	
	public boolean isTransformed( )
	{
		return isTransformed;
	}

	public Source< T > getWrappedSource()
	{
		return source;
	}

	@Override
	public RandomAccessibleInterval< T > getSource( final int t, final int level )
	{
		return Views.interval(
				Views.raster( getInterpolatedSource( t, level, Interpolation.NEARESTNEIGHBOR ) ),
				estimateBoundingInterval( t, level ));
	}

	public Interval estimateBoundingIntervalWorking( final int t, final int level )
	{
		final Interval wrappedInterval = source.getSource( t, level );

		final Interval itvl;
////		System.out.println( "source nd: " + source.getSource( t, level ).numDimensions());
////		System.out.println( "source nz: " + source.getSource( t, level ).dimension( 2 ));
		boolean is2d = source.getSource( t, level ).numDimensions() == 2 || 
						source.getSource( t, level ).dimension( 2 ) == 1;
		if( is2d )
		{
			itvl = new FinalInterval(
					new long[]{ wrappedInterval.min( 0 ), wrappedInterval.min(1)},
					new long[]{ wrappedInterval.max( 0 ), wrappedInterval.max(1)});
		}
		else
			itvl = wrappedInterval;

		if( isTransformed && xfm != null)
		{
			final InvertibleRealTransform invxfm;
			if( xfm instanceof Wrapped2DTransformAs3D )
				invxfm = ((Wrapped2DTransformAs3D)xfm).transform.inverse().copy();
			else
				invxfm  = xfm.inverse().copy();


			// TODO: Continue work here
			final Interval movingWarpedInterval = BigWarpExporter.estimateBounds( invxfm, itvl );
//			final Interval movingWarpedInterval = BigWarpExporter.estimateBounds( xfm.inverse(), itvl );

//			final Interval movingWarpedInterval = BigWarpExporter.estimateBounds( invxfm, wrappedInterval );
//			System.out.println( "warped interval: " + Util.printInterval( movingWarpedInterval ));
			
			if( is2d )
				return new FinalInterval(
						new long[]{ movingWarpedInterval.min(0), movingWarpedInterval.min(1), 0 },
						new long[]{ movingWarpedInterval.max(0), movingWarpedInterval.max(1), 0 });
			else
				return movingWarpedInterval;
		}
		else
		{
			return wrappedInterval;
		}
	}

	public Interval estimateBoundingInterval( final int t, final int level )
	{
		final Interval wrappedInterval = source.getSource( t, level );

		if( isTransformed && xfm != null)
		{
			final InvertibleRealTransform invxfm = xfm.inverse().copy();
			final Interval movingWarpedInterval = BigWarpExporter.estimateBounds( invxfm, wrappedInterval );
			System.out.println( "original interval: " + Util.printInterval( wrappedInterval ));
			System.out.println( "warped interval: " + Util.printInterval( movingWarpedInterval ));
			System.out.println( " " );
			return movingWarpedInterval;
		}
		else
		{
			return wrappedInterval;
		}
	}

	@Override
	public RealRandomAccessible< T > getInterpolatedSource( final int t, final int level, final Interpolation method )
	{
		final RealRandomAccessible< T > sourceRealAccessible = source.getInterpolatedSource( t, level, method );

		if( isTransformed )
		{
			final AffineTransform3D transform = new AffineTransform3D();
			source.getSourceTransform( t, level, transform );
			final RealRandomAccessible< T > srcRaTransformed = RealViews.affineReal( source.getInterpolatedSource( t, level, method ), transform );

			if( xfm == null )
				return srcRaTransformed;
			else
				return new RealTransformRealRandomAccessible< T, RealTransform >( srcRaTransformed, xfm );
		}
		else
		{
			return sourceRealAccessible;
		}
	}

	@Override
	public void getSourceTransform( final int t, final int level, final AffineTransform3D transform )
	{
		if( isTransformed )
			transform.identity();
		else
			source.getSourceTransform( t, level, transform );

//		if( isTransformed )
//		{
//			Object a = null;
//		}
//		
//		System.out.println( "WarpedSource getSourceTransform: " + transform );
	}

	public InvertibleRealTransform getTransform()
	{
		return xfm;
	}

	@Override
	public T getType()
	{
		return source.getType();
	}

	@Override
	public String getName()
	{
		return source.getName() + "_" + name;
	}

	@Override
	public VoxelDimensions getVoxelDimensions()
	{
		return source.getVoxelDimensions();
	}

	@Override
	public int getNumMipmapLevels()
	{
		return source.getNumMipmapLevels();
	}

	@Override
	public synchronized MipmapHints getMipmapHints( final AffineTransform3D screenTransform, final int timepoint, final int previousTimepoint )
	{
		return sourceMipmapOrdering.getMipmapHints( screenTransform, timepoint, previousTimepoint );
	}
}
