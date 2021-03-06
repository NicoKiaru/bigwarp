package bigwarp.source;

import net.imglib2.AbstractRealInterval;
import net.imglib2.Interval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessibleRealInterval;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.RealType;

public class WarpMagnitudeRandomAccessibleInterval<T extends RealType<T>> extends AbstractRealInterval implements RealRandomAccessibleRealInterval<T> 
{
	
	WarpMagnitudeRandomAccess< T > ra;
	
	public WarpMagnitudeRandomAccessibleInterval( Interval interval, T t, RealTransform warp, RealTransform base )
	{
		super( interval );
		ra = new WarpMagnitudeRandomAccess< T >( new double[ interval.numDimensions() ], t, warp, base );
	}

	@Override
	public RealRandomAccess<T> realRandomAccess() {
		return ra.copy();
	}

	@Override
	public RealRandomAccess<T> realRandomAccess(RealInterval interval) {
		return realRandomAccess();
	}

//	public RealRandomAccessibleRealInterval<T> copy()
//	{
//		long[] min = new long[ this.numDimensions() ];
//		long[] max = new long[ this.numDimensions() ];
//		for ( int d = 0; d < this.numDimensions(); ++d )
//		{
//			min[ d ] = (long)Math.floor( this.realMin( d ));
//			max[ d ] = (long)Math.ceil( this.realMax( d ));
//		}
//		
//		Interval ri = new FinalInterval( min, max );
//		if( warp == null )
//		{
//			return new WarpMagnitudeRandomAccessibleInterval<T>(
//					ri, this.ra.value.copy(), null, null );
//		}
//		else
//		{
//			return new WarpMagnitudeRandomAccessibleInterval<T>( 
//				ri, this.ra.value.copy(),
//				((ThinPlateR2LogRSplineKernelTransform)warp).deepCopy(),
//				base.copy() );
//		}
////		return null;
//	}
	
}