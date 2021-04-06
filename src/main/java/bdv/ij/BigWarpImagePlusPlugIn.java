package bdv.ij;

import java.io.File;
import java.io.IOException;

import org.janelia.saalfeldlab.n5.N5DatasetDiscoverer;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5TreeNode;
import org.janelia.saalfeldlab.n5.ij.N5Factory;
import org.janelia.saalfeldlab.n5.ij.N5Importer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.metadata.MultiscaleMetadata;
import org.janelia.saalfeldlab.n5.metadata.N5ImagePlusMetadata;
import org.janelia.saalfeldlab.n5.metadata.N5Metadata;
import org.janelia.saalfeldlab.n5.metadata.PhysicalMetadata;
import org.janelia.utility.ui.RepeatingReleasedEventsFixer;

import bdv.ij.util.ProgressWriterIJ;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.RandomAccessibleIntervalSource;
import bdv.viewer.Source;
import bigwarp.BigWarp;
import bigwarp.BigWarp.BigWarpData;
import bigwarp.BigWarpInit;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.PlugIn;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.XmlIoSpimData;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedIntType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

/**
 * ImageJ plugin to show the current image in BigDataViewer.
 *
 * @author John Bogovic &lt;bogovicj@janelia.hhmi.org&gt;
 * @author Tobias Pietzsch &lt;tobias.pietzsch@gmail.com&gt;
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class BigWarpImagePlusPlugIn implements PlugIn
{

    private ImagePlus movingIp;
    private ImagePlus targetIp;

	public static void main( final String[] args ) throws IOException
	{
		new ImageJ();
		IJ.run("Boats (356K)");
		new BigWarpImagePlusPlugIn().run( null );
	}

	@Override
	public void run( final String arg )
	{
		if ( IJ.versionLessThan( "1.40" ) ) return;

		// don't need any open windows if we're using N5
        final int[] ids = WindowManager.getIDList();

        // Find any open images
        final int N = ids == null ? 0 : ids.length;

        final String[] titles = new String[ N + 1 ];
        for ( int i = 0; i < N; ++i )
        {
            titles[ i ] = ( WindowManager.getImage( ids[ i ] ) ).getTitle();
        }
        titles[ N ] = "<None>";

        // Build a dialog to choose the moving and fixed images
        final GenericDialogPlus gd = new GenericDialogPlus( "Big Warp Setup" );

        gd.addMessage( "Image Selection:" );

		ImagePlus currimg = WindowManager.getCurrentImage();
        String current = titles[ N ];
		if ( currimg != null )
		{
			current = currimg.getTitle();
		}

        gd.addChoice( "moving_image", titles, current );
        if( titles.length > 1 )
        	gd.addChoice( "target_image", titles, current.equals( titles[ 0 ] ) ? titles[ 1 ] : titles[ 0 ] );
        else 
        	gd.addChoice( "target_image", titles, titles[ 0 ] );

        gd.addMessage( "\nN5/Zarr/HDF5/BDV-XML" );
        gd.addDirectoryOrFileField( "Moving", "" );
        gd.addStringField( "Moving dataset", "" );
        gd.addDirectoryOrFileField( "Target", "" );
        gd.addStringField( "Target dataset", "" );

        gd.addMessage( "" );
        gd.addFileField( "Landmarks file", "" );
        gd.addCheckbox( "Apply transform from landmarks", true );

        gd.showDialog();

        if (gd.wasCanceled()) return;

		final int mvgImgIdx = gd.getNextChoiceIndex();
		final int tgtImgIdx = gd.getNextChoiceIndex();
		movingIp = mvgImgIdx < N ? WindowManager.getImage( ids[ mvgImgIdx ]) : null;
		targetIp = tgtImgIdx < N ? WindowManager.getImage( ids[ tgtImgIdx ]) : null;

		final String mvgRoot = gd.getNextString();
		final String mvgDataset = gd.getNextString();
		final String tgtRoot = gd.getNextString();
		final String tgtDataset = gd.getNextString();

		final String landmarkPath = gd.getNextString();
		final boolean applyTransform = gd.getNextBoolean();

		// build bigwarp data
		BigWarpData< ? > bigwarpdata = BigWarpInit.initData();
		int id = 0;
		if ( movingIp != null )
		{
			id += BigWarpInit.add( bigwarpdata, movingIp, id, 0, true );
		}

		SpimData movingSpimData = null;
		if ( !mvgRoot.isEmpty() )
		{
			movingSpimData = BigWarpInit.addToData( bigwarpdata, true, id, mvgRoot, mvgDataset );
			id++;
		}

		if ( targetIp != null )
		{
			id += BigWarpInit.add( bigwarpdata, targetIp, id, 0, false );
		}

		if ( !tgtRoot.isEmpty() )
		{
			BigWarpInit.addToData( bigwarpdata, false, id, tgtRoot, tgtDataset );
			id++;
		}
		bigwarpdata.wrapUp();

        // run BigWarp
        try
        {
        	new RepeatingReleasedEventsFixer().install();
			final BigWarp<?> bw = new BigWarp<>( bigwarpdata, "Big Warp",  new ProgressWriterIJ() );

			if( landmarkPath != null && !landmarkPath.isEmpty())
			{
				bw.loadLandmarks( landmarkPath );

				if ( applyTransform )
					bw.setIsMovingDisplayTransformed( applyTransform );
			}

			if( movingSpimData != null )
				bw.setMovingSpimData( movingSpimData, new File( mvgRoot ));

			bw.getViewerFrameP().getViewerPanel().requestRepaint();
			bw.getViewerFrameQ().getViewerPanel().requestRepaint();
			bw.getLandmarkFrame().repaint();
		}
        catch (final SpimDataException e)
        {
			e.printStackTrace();
			return;
		}

	}

}
