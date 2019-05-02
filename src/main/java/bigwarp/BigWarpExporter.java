package bigwarp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import bdv.export.ProgressWriter;
import bdv.viewer.Interpolation;
import bdv.viewer.SourceAndConverter;
import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.iterator.IntervalIterator;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.MixedTransformView;
import net.imglib2.view.Views;

public abstract class BigWarpExporter <T>
{
	final protected ArrayList< SourceAndConverter< ? >> sources;

	final protected int[] movingSourceIndexList;

	final protected int[] targetSourceIndexList;
	
	protected AffineTransform3D pixelRenderToPhysical;
	
	protected AffineTransform3D resolutionTransform;
	
	protected AffineTransform3D offsetTransform;
	
	protected Interval outputInterval;

	protected Interpolation interp;
	
	protected boolean isVirtual = false;

	protected int nThreads = 1;

	protected ExportThread exportThread;

	public abstract ImagePlus export();

	protected ProgressWriter progress;

	public enum ParallelizationPolicy {
		SLICE, ITER
	};

	public ParallelizationPolicy policy = ParallelizationPolicy.ITER;

	private ImagePlus result;

	public BigWarpExporter(
			final ArrayList< SourceAndConverter< ? >> sources,
			final int[] movingSourceIndexList,
			final int[] targetSourceIndexList,
			final Interpolation interp,
			final ProgressWriter progress )
	{
		this.sources = sources;
		this.movingSourceIndexList = movingSourceIndexList;
		this.targetSourceIndexList = targetSourceIndexList;

		this.progress = progress;
		this.setInterp( interp );
		
		pixelRenderToPhysical = new AffineTransform3D();
		resolutionTransform = new AffineTransform3D();
		offsetTransform = new AffineTransform3D();
	}

	public void setInterp( Interpolation interp )
	{
		this.interp = interp;
	}
	
	public void setVirtual( final boolean isVirtual )
	{
		this.isVirtual = isVirtual;
	}

	public void setParallelizationPolicy( ParallelizationPolicy policy )
	{
		this.policy = policy;
	}

	public void setNumThreads( final int nThreads )
	{
		this.nThreads = nThreads;
	}
	
	public void setRenderResolution( double... res )
	{
		for( int i = 0; i < res.length; i++ )
			resolutionTransform.set( res[ i ], i, i );
	}
	
	/**
	 * Set the offset of the output field of view in pixels.
	 * 
	 * @param offset the offset in pixel units.
	 */
	public void setOffset( double... offset )
	{
		for( int i = 0; i < offset.length; i++ )
			offsetTransform.set( offset[ i ], i, 3 );
	}

	/**
	 * Generate the transform from output pixel space to physical space.
	 * 
	 * Call this after setRenderResolution and setOffset.  
	 */
	public void buildTotalRenderTransform()
	{
		pixelRenderToPhysical.identity();
		pixelRenderToPhysical.concatenate( resolutionTransform );
		pixelRenderToPhysical.concatenate( offsetTransform );
	}

	public void setInterval( final Interval outputInterval )
	{
		this.outputInterval = outputInterval;
	}

	public FinalInterval destinationIntervalFromLandmarks( ArrayList<Double[]> pts, boolean isMoving )
	{
		int nd = pts.get( 0 ).length;
		long[] min = new long[ nd ];
		long[] max = new long[ nd ];

		Arrays.fill( min, Long.MAX_VALUE );
		Arrays.fill( max, Long.MIN_VALUE );

		for( Double[] pt : pts )
		{
			for( int d = 0; d < nd; d++ )
			{
				if( pt[ d ] > max [ d ] )
					max[ d ] = (long)Math.ceil( pt[ d ]);
				
				if( pt[ d ] < min [ d ] )
					min[ d ] = (long)Math.floor( pt[ d ]);
			}
		}
		return new FinalInterval( min, max );
	}
	
	public static FinalInterval getSubInterval( Interval interval, int d, long start, long end )
	{
		int nd = interval.numDimensions();
		long[] min = new long[ nd ];
		long[] max = new long[ nd ];
		for( int i = 0; i < nd; i++ )
		{
			if( i == d )
			{
				min[ i ] = start;
				max[ i ] = end - 1;
			}
			else
			{
				min[ i ] = interval.min( i );
				max[ i ] = interval.max( i );
			}
		}
		return new FinalInterval( min, max );
	}

	public < T extends NumericType<T> > RandomAccessibleInterval<T> copyToImageStack( 
			final RandomAccessible< T > raible,
			final Interval itvl,
			final ImgFactory<T> factory,
			final int nThreads )
	{
		Img< T > target = factory.create( itvl );
		if( policy == ParallelizationPolicy.ITER )
			return copyToImageStackIterOrder( raible, itvl, target, nThreads, progress );
		else
			return copyToImageStackBySlice( raible, itvl, target, nThreads, progress );
		
	}

	public static < T extends NumericType<T> > RandomAccessibleInterval<T> copyToImageStackBySlice( 
			final RandomAccessible< T > raible,
			final Interval itvl,
			final ImgFactory<T> factory,
			final int nThreads,
			final ProgressWriter progress )
	{
		// create the image plus image
		Img< T > target = factory.create( itvl );
		return copyToImageStackBySlice( raible, itvl, target, nThreads, progress );
	}

	public static < T extends NumericType<T> > RandomAccessibleInterval<T> copyToImageStackBySlice( 
			final RandomAccessible< T > ra,
			final Interval itvl,
			final RandomAccessibleInterval<T> target,
			final int nThreads,
			final ProgressWriter progress )
	{
		// TODO I wish I didn't have to do this inside this method
		MixedTransformView< T > raible = Views.permute( ra, 2, 3 );

		// what dimension should we split across?
		int nd = raible.numDimensions();
		int tmp = nd - 1;
		while( tmp >= 0 )
		{
			if( target.dimension( tmp ) > 1 )
				break;
			else
				tmp--;
		}
		final int dim2split = tmp;

		final long[] splitPoints = new long[ nThreads + 1 ];
		long N = target.dimension( dim2split );
		long del = ( long )( N / nThreads ); 
		splitPoints[ 0 ] = 0;
		splitPoints[ nThreads ] = target.dimension( dim2split );
		for( int i = 1; i < nThreads; i++ )
		{
			splitPoints[ i ] = splitPoints[ i - 1 ] + del;
		}

		ExecutorService threadPool = Executors.newFixedThreadPool( nThreads );

		LinkedList<Callable<Boolean>> jobs = new LinkedList<Callable<Boolean>>();
		for( int i = 0; i < nThreads; i++ )
		{
			final long start = splitPoints[ i ];
			final long end   = splitPoints[ i+1 ];

			jobs.add( new Callable<Boolean>()
			{
				public Boolean call()
				{
					try
					{
						final FinalInterval subItvl = getSubInterval( target, dim2split, start, end );
						final IntervalView< T > subTgt = Views.interval( target, subItvl );
						long N = Intervals.numElements(subTgt);
						final Cursor< T > c = subTgt.cursor();
						final RandomAccess< T > ra = raible.randomAccess();
						long j = 0;
						while ( c.hasNext() )
						{
							c.fwd();
							ra.setPosition( c );
							c.get().set( ra.get() );

							if( start == 0  && j % 100000 == 0 )
							{
								double ratio = 1.0 * j / N;
								progress.setProgress( ratio ); 
							}
							j++;
						}
						return true;
					}
					catch( Exception e )
					{
						e.printStackTrace();
					}
					return false;
				}
			});
		}
		try
		{
			threadPool.invokeAll( jobs );
			threadPool.shutdown(); // wait for all jobs to finish

		}
		catch ( InterruptedException e1 )
		{
			e1.printStackTrace();
		}

		progress.setProgress(1.0);
		return target;
	}

	public static < T extends NumericType<T> > RandomAccessibleInterval<T> copyToImageStackIterOrder( 
			final RandomAccessible< T > raible,
			final Interval itvl,
			final ImgFactory<T> factory,
			final int nThreads,
			final ProgressWriter progress )
	{
		// create the image plus image
		Img< T > target = factory.create( itvl );
		return copyToImageStackIterOrder( raible, itvl, target, nThreads, progress );
	}

	public static < T extends NumericType<T> > RandomAccessibleInterval<T> copyToImageStackIterOrder( 
			final RandomAccessible< T > ra,
			final Interval itvl,
			final RandomAccessibleInterval<T> target,
			final int nThreads,
			final ProgressWriter progress )
	{
		
		progress.setProgress(0.0);
		// TODO I wish I didn't have to do this inside this method..
		// 	Maybe I don't have to, and should do it where I call this instead?
		MixedTransformView< T > raible = Views.permute( ra, 2, 3 );

		ExecutorService threadPool = Executors.newFixedThreadPool( nThreads );

		LinkedList<Callable<Boolean>> jobs = new LinkedList<Callable<Boolean>>();
		for( int i = 0; i < nThreads; i++ )
		{

			final int offset = i;
			jobs.add( new Callable<Boolean>()
			{
				public Boolean call()
				{
					try
					{
						IterableInterval<T> it = Views.flatIterable( target );
						final RandomAccess< T > access = raible.randomAccess();

						long N = it.size();
						final Cursor< T > c = it.cursor();
						c.jumpFwd( 1 + offset );
						for( long j = offset; j < N; j += nThreads )
						{
							access.setPosition( c );
							c.get().set( access.get() );
							c.jumpFwd( nThreads );
							
							if( offset == 0  && j % (nThreads * 100000) == 0 )
							{
								double ratio = 1.0 * j / N;
								progress.setProgress( ratio ); 
							}
						}

						return true;
					}
					catch( Exception e )
					{
						e.printStackTrace();
					}
					return false;
				}
			});
		}
		try
		{
			threadPool.invokeAll( jobs );
			threadPool.shutdown(); // wait for all jobs to finish

		}
		catch ( InterruptedException e1 )
		{
			e1.printStackTrace();
		}

		progress.setProgress(1.0);
		return target;
	}
	
	public static FinalInterval transformRealInterval( RealTransform xfm, RealInterval interval )
	{
		int nd = interval.numDimensions();
		double[] pt = new double[ nd ];
		double[] ptxfm = new double[ nd ];

		long[] min = new long[ nd ];
		long[] max = new long[ nd ];

		// transform min		
		for( int d = 0; d < nd; d++ )
			pt[ d ] = interval.realMin( d );
		
		xfm.apply( pt, ptxfm );
		copyToLongFloor( ptxfm, min );


		// transform max
		
		for( int d = 0; d < nd; d++ )
		{
			pt[ d ] = interval.realMax( d );
		}
		
		xfm.apply( pt, ptxfm );
		copyToLongCeil( ptxfm, max );
		
		return new FinalInterval( min, max );
	}
	
	public static FinalInterval transformIntervalMinMax( RealTransform xfm, Interval interval )
	{
		int nd = interval.numDimensions();
		double[] pt = new double[ nd ];
		double[] ptxfm = new double[ nd ];

		long[] min = new long[ nd ];
		long[] max = new long[ nd ];

		// transform min		
		for( int d = 0; d < nd; d++ )
			pt[ d ] = interval.min( d );
		
		xfm.apply( pt, ptxfm );
		copyToLongFloor( ptxfm, min );


		// transform max
		
		for( int d = 0; d < nd; d++ )
		{
			pt[ d ] = interval.max( d );
		}
		
		xfm.apply( pt, ptxfm );
		copyToLongCeil( ptxfm, max );
		
		return new FinalInterval( min, max );
	}
	
	public static FinalInterval estimateBounds( RealTransform xfm, Interval interval )
	{
		int nd = interval.numDimensions();
		double[] pt = new double[ nd ];
		double[] ptxfm = new double[ nd ];

		long[] min = new long[ nd ];
		long[] max = new long[ nd ];
		Arrays.fill( min, Long.MAX_VALUE );
		Arrays.fill( max, Long.MIN_VALUE );

		long[] unitInterval = new long[ nd ];
		Arrays.fill( unitInterval, 2 );
		
		IntervalIterator it = new IntervalIterator( unitInterval );
		while( it.hasNext() )
		{
			it.fwd();
			for( int d = 0; d < nd; d++ )
			{
				if( it.getLongPosition( d ) == 0 )
					pt[ d ] = interval.min( d );
				else
					pt[ d ] = interval.max( d );
			}

			xfm.apply( pt, ptxfm );

			for( int d = 0; d < nd; d++ )
			{
				long lo = (long)Math.floor( ptxfm[d] );
				long hi = (long)Math.ceil( ptxfm[d] );
				
				if( lo < min[ d ])
					min[ d ] = lo;
				
				if( hi > max[ d ])
					max[ d ] = hi;
			}
		}
		return new FinalInterval( min, max );
	}

	public static void copyToLongFloor( final double[] src, final long[] dst )
	{
		for( int d = 0; d < src.length; d++ )
			dst[ d ] = (long)Math.floor( src[d] );
	}

	public static void copyToLongCeil( final double[] src, final long[] dst )
	{
		for( int d = 0; d < src.length; d++ )
			dst[ d ] = (long)Math.floor( src[d] );
	}

	public ImagePlus exportAsynch()
	{
		exportThread = new ExportThread( this );
		exportThread.start();
		return result;
	}

	public static class ExportThread extends Thread
	{
		BigWarpExporter<?> exporter;

		public ExportThread(BigWarpExporter<?> exporter)
		{
			this.exporter = exporter;
		}

		@Override
		public void run()
		{
			try {
				long startTime = System.currentTimeMillis();
				exporter.result = exporter.export();
				long endTime = System.currentTimeMillis();

				System.out.println("export took " + (endTime - startTime) + "ms");

				if (exporter.result != null)
					exporter.result.show();

			}
			catch (final RejectedExecutionException e)
			{
				// this happens when the rendering threadpool
				// is killed before the painter thread.
			}
		}
	}

}
