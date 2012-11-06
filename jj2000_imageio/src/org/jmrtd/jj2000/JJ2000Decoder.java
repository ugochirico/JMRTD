/* $Id: $ */

package org.jmrtd.jj2000;

import icc.ICCProfiler;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import jj2000.j2k.codestream.HeaderInfo;
import jj2000.j2k.codestream.reader.BitstreamReaderAgent;
import jj2000.j2k.codestream.reader.HeaderDecoder;
import jj2000.j2k.decoder.DecoderSpecs;
import jj2000.j2k.entropy.decoder.EntropyDecoder;
import jj2000.j2k.fileformat.reader.FileFormatReader;
import jj2000.j2k.image.BlkImgDataSrc;
import jj2000.j2k.image.Coord;
import jj2000.j2k.image.DataBlkInt;
import jj2000.j2k.image.ImgDataConverter;
import jj2000.j2k.image.invcomptransf.InvCompTransf;
import jj2000.j2k.io.RandomAccessIO;
import jj2000.j2k.quantization.dequantizer.Dequantizer;
import jj2000.j2k.roi.ROIDeScaler;
import jj2000.j2k.util.ISRandomAccessIO;
import jj2000.j2k.util.ParameterList;
import jj2000.j2k.wavelet.synthesis.InverseWT;
import colorspace.ColorSpace;
import colorspace.ColorSpace.CSEnum;

/**
 * Utility class for access to jj2000 library.
 * Tested with jj2000-5.2-SNAPSHOT.jar only.
 * 
 * @author The JMRTD team (info@jmrtd.org)
 *
 * @version $Revision: $
 */
public class JJ2000Decoder {

	private final static String[][] DECODER_PINFO = {
		{ "u", "[on|off]", "", "off" },
		{ "v", "[on|off]", "", "off" },
		{ "verbose", "[on|off]", "", "off" },
		{ "pfile", "", "", null },
		{ "res", "", "", null },
		{ "i", "", "", null },
		{ "o", "", "", null },
		{ "rate", "", "", "3" },
		{ "nbytes", "", "", "-1" },
		{ "parsing", null, "", "on" },
		{ "ncb_quit", "", "", "-1" },
		{ "l_quit", "", "", "-1" },
		{ "m_quit", "", "", "-1" },
		{ "poc_quit", null, "", "off" },
		{ "one_tp", null, "", "off" },
		{ "comp_transf", null, "", "on" },
		{ "debug", null, "", "off" },
		{ "cdstr_info", null, "", "off" },
		{ "nocolorspace", null, "", "off" },
		{ "colorspace_debug", null, "", "off" } };
	
	/**
	 * Prevent unwanted instantiation.
	 */
	private JJ2000Decoder() {
	}

	public static Bitmap decode(InputStream in) throws IOException {
		String[][] pinfo = getAllDecoderParameters();
		ParameterList defpl = new ParameterList();
		for (int i = pinfo.length - 1; i >= 0; i--) {
			if (pinfo[i][3] != null)
				defpl.put(pinfo[i][0], pinfo[i][3]);
		}
		ParameterList pl = new ParameterList(defpl);

		return decode(new ISRandomAccessIO(in), pl);
	}

	/* ONLY PRIVATE METHODS BELOW. */

	private static Bitmap decode(RandomAccessIO in, ParameterList pl) throws IOException {

		// The codestream should be wrapped in the jp2 fileformat, Read the
		// file format wrapper
		FileFormatReader fileFormatReader = new FileFormatReader(in);
		fileFormatReader.readFileFormat();
		if (!fileFormatReader.JP2FFUsed) {
			throw new IOException("Was expecting JP2 file format");
		}
		in.seek(fileFormatReader.getFirstCodeStreamPos());

		// Instantiate header decoder and read main header
		HeaderInfo headerInfo = new HeaderInfo();
		HeaderDecoder headerDecoder = null;
		try {
			headerDecoder = new HeaderDecoder(in, pl, headerInfo);
		} catch (EOFException e) {
			throw new IOException("Codestream too short or bad header, unable to decode");
		}

		int originalComponentCount = headerDecoder.getNumComps();
		/* int nTiles = */ headerInfo.siz.getNumTiles();
		DecoderSpecs decoderSpecs = headerDecoder.getDecoderSpecs();

		// Get demixed bitdepths
		int[] originalBitDepths = new int[originalComponentCount];
		for (int i = 0; i < originalComponentCount; i++) {
			originalBitDepths[i] = headerDecoder.getOriginalBitDepth(i);
		}

		BitstreamReaderAgent bitStreamReader = BitstreamReaderAgent.createInstance(in, headerDecoder, pl, decoderSpecs, pl.getBooleanParameter("cdstr_info"), headerInfo);
		EntropyDecoder entropyDecoder = headerDecoder.createEntropyDecoder(bitStreamReader, pl);

		ROIDeScaler roiDeScaler = headerDecoder.createROIDeScaler(entropyDecoder, pl, decoderSpecs);

		Dequantizer dequantizer = headerDecoder.createDequantizer(roiDeScaler, originalBitDepths, decoderSpecs);

		// full page inverse wavelet transform
		InverseWT inverseWT = InverseWT.createInstance(dequantizer, decoderSpecs);

		// resolution level to reconstruct
		int imgRes = bitStreamReader.getImgRes();
		inverseWT.setImgResLevel(imgRes);

		ImgDataConverter imgDataConverter = new ImgDataConverter(inverseWT, 0);

		InvCompTransf invCompTransf = new InvCompTransf(imgDataConverter, decoderSpecs, originalBitDepths, pl);

		// **** Color space mapping ****
		ColorSpace colorSpace = null;
		BlkImgDataSrc color = null;
		try {
			colorSpace = new ColorSpace(in, headerDecoder, pl);
			BlkImgDataSrc channels = headerDecoder.createChannelDefinitionMapper(invCompTransf, colorSpace);
			BlkImgDataSrc resampled = headerDecoder.createResampler(channels, colorSpace);
			BlkImgDataSrc palettized = headerDecoder.createPalettizedColorSpaceMapper(resampled, colorSpace);
			color = headerDecoder.createColorSpaceMapper(palettized, colorSpace);
		} catch (Exception e) {
			throw new IOException("Error processing jp2 colorspace information: " + e.getMessage());
		}

		// This is the last image in the decoding chain and should be
		// assigned by the last transformation:
		BlkImgDataSrc decodedImage = color;
		if (color == null) {
			decodedImage = invCompTransf;
		}
		int imgComponentCount = decodedImage.getNumComps();

		// **** Create image writers/image display ****

		DataBlkInt[] blk = new DataBlkInt[imgComponentCount];
		int[] imgBitDepths = new int[imgComponentCount];

		int imgWidth = decodedImage.getImgWidth();
		int imgHeight = decodedImage.getImgHeight();

		// Find the list of tile to decode.
		Coord nT = decodedImage.getNumTiles(null);

		// Loop on vertical tiles
		for (int y = 0; y < nT.y; y++) {
			// Loop on horizontal tiles
			for (int x = 0; x < nT.x; x++) {
				decodedImage.setTile(x, y);

				int width = decodedImage.getImgWidth();
				int height = decodedImage.getImgHeight();
				int ulx = decodedImage.getImgULX();
				int uly = decodedImage.getImgULY();

				for (int i = 0; i < imgComponentCount; i++) {
					blk[i] = new DataBlkInt(ulx, uly, width, height);
					blk[i].data = null;
					blk[i] = (DataBlkInt)decodedImage.getInternCompData(blk[i], i);
					imgBitDepths[i] = decodedImage.getNomRangeBits(i);
				}
			}
		}

		CSEnum colorSpaceType = colorSpace.getColorSpace();
		if (colorSpaceType.equals(ColorSpace.sRGB)) {
			return decodeSignedRGB(blk, imgWidth, imgHeight, imgBitDepths);
			/*
			 * For Android use:
			 *   return Bitmap.createBitmap(colors, 0, imgWidth, imgWidth, imgHeight, Bitmap.Config.ARGB_8888);
			 * 
			 * For J2SE use:
			 *   BufferedImage image = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_ARGB);
			 *   image.setRGB(0, 0, imgWidth, imgHeight, colors, 0, imgWidth);
			 *   return image;
			 */			
		} else if (colorSpaceType.equals(ColorSpace.GreyScale)) {
			/* NOTE: Untested */
			return decodeGrayScale(blk, imgWidth, imgHeight, imgBitDepths);

			/* For Android use:
			 *   return Bitmap.createBitmap(colors, 0, imgWidth, imgWidth, imgHeight, Bitmap.Config.ARGB_8888);
			 *   
			 * For J2SE use:
			 *   BufferedImage image = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_BYTE_GRAY);
			 *   image.setRGB(0, 0, imgWidth, imgHeight, colors, 0, imgWidth);
			 *   return image;
			 */
		}
		throw new IOException("Unsupported color space type.");
	}

	/**
	 * Decodes n-bit signed to 8-bit unsigned.
	 * 
	 * @param blk
	 * @param depths
	 * @return
	 */
	private static Bitmap decodeSignedRGB(DataBlkInt[] blk, int width, int height, int[] depths) {
		if (blk == null || blk.length != 3) {
			throw new IllegalArgumentException("Was expecting 3 bands");
		}
		if (depths == null || depths.length != 3) {
			throw new IllegalArgumentException("Was expecting 3 bands");
		}
		if (depths[0] != depths[1] || depths[1] != depths[2] || depths[2] != depths[0]) {
			throw new IllegalArgumentException("Different depths for bands");
		}

		int depth = depths[0];

		int[] rData = blk[0].getDataInt();
		int[] gData = blk[1].getDataInt();
		int[] bData = blk[2].getDataInt();

		if (rData.length != gData.length || gData.length != bData.length || bData.length != rData.length) {
			throw new IllegalArgumentException("Different dimensions for bands");
		}

		int[] pixels = new int[rData.length];
		//		int minR = Integer.MAX_VALUE, maxR = Integer.MIN_VALUE;
		//		int minG = Integer.MAX_VALUE, maxG = Integer.MIN_VALUE;
		//		int minB = Integer.MAX_VALUE, maxB = Integer.MIN_VALUE;

		for (int j = 0; j < rData.length; j++) {

			/* Signed values, should be in [-128 .. 127] (for depth = 8). */
			int r = rData[j];
			int g = gData[j];
			int b = bData[j];

			/* Determine min and max per band. For debugging. Turns out values outside [-128 .. 127] are possible in samples!?! */
			//			if (r < minR) { minR = r; } if (r > maxR) { maxR = r; }
			//			if (g < minG) { minG = g; } if (g > maxG) { maxG = g; }
			//			if (b < minB) { minB = b; } if (b > maxB) { maxB = b; }

			pixels[j] = JJ2000Util.signedComponentsToUnsignedARGB(r, g, b, depth);		}
		Bitmap bitmap = new Bitmap(pixels, width, height, 24, -1, true, 3);
		return bitmap;
	}

	/**
	 * Decodes 8-bit gray scale to 8-bit unsigned RGB.
	 * 
	 * @param blk
	 * @param depths
	 * @return
	 */
	private static Bitmap decodeGrayScale(DataBlkInt[] blk, int width, int height, int[] depths) {
		if (blk.length != 1) {
			throw new IllegalArgumentException("Was expecting 1 band");
		}
		if (depths.length != 1) {
			throw new IllegalArgumentException("Was expecting 1 band");
		}
		int[] data = blk[0].getDataInt();
		int[] pixels = new int[data.length];
		for (int j = 0; j < data.length; j++) {
			pixels[j] = 0xFF000000 | ((data[j] & 0xFF) << 16) | ((data[j] & 0xFF) << 8) | (data[j] & 0xFF);
		}
		Bitmap bitmap = new Bitmap(pixels, width, height, 24, -1, true, 3);
		return bitmap;
	}

	private static String[][] getAllDecoderParameters() {
		List<String[]> pl = new ArrayList<String[]>();

		String[][] str = BitstreamReaderAgent.getParameterInfo();
		if (str != null) {
			for (int i = str.length - 1; i >= 0; i--) {
				pl.add(str[i]);
			}
		}
		str = EntropyDecoder.getParameterInfo();
		if (str != null) {
			for (int i = str.length - 1; i >= 0; i--) {
				pl.add(str[i]);
			}
		}
		str = ROIDeScaler.getParameterInfo();
		if (str != null) {
			for (int i = str.length - 1; i >= 0; i--) {
				pl.add(str[i]);
			}
		}
		str = Dequantizer.getParameterInfo();
		if (str != null) {
			for (int i = str.length - 1; i >= 0; i--) {
				pl.add(str[i]);
			}
		}
		str = InvCompTransf.getParameterInfo();
		if (str != null) {
			for (int i = str.length - 1; i >= 0; i--) {
				pl.add(str[i]);
			}
		}
		str = HeaderDecoder.getParameterInfo();
		if (str != null) {
			for (int i = str.length - 1; i >= 0; i--) {
				pl.add(str[i]);
			}
		}
		str = ICCProfiler.getParameterInfo();
		if (str != null) {
			for (int i = str.length - 1; i >= 0; i--) {
				pl.add(str[i]);
			}
		}
		str = DECODER_PINFO;
		if (str != null) {
			for (int i = str.length - 1; i >= 0; i--) {
				pl.add(str[i]);
			}
		}
		str = new String[pl.size()][4];
		if (str != null) {
			for (int i = str.length - 1; i >= 0; i--) {
				str[i] = (String[]) pl.get(i);
			}
		}

		return str;
	}
}
