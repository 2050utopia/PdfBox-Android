package org.apache.pdfbox.filter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Helper class to contain predictor decoding used by Flate and LZW filter.
 * To see the history, look at the FlateFilter class.
 */
public class Predictor
{
    static byte[] decodePredictor(int predictor, int colors, int bitsPerComponent, int columns, InputStream data)
            throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        if (predictor == 1)
        {
            // no prediction
            int i;
            while ((i = data.read(buffer)) != -1)
            {
                baos.write(buffer, 0, i);
            }
        }
        else
        {
            // calculate sizes
            final int bitsPerPixel = colors * bitsPerComponent;
            final int bytesPerPixel = (bitsPerPixel + 7) / 8;
            final int rowlength = (columns * bitsPerPixel + 7) / 8;
            byte[] actline = new byte[rowlength];
            byte[] lastline = new byte[rowlength];

            boolean done = false;
            int linepredictor = predictor;

            while (!done && data.available() > 0)
            {
                // test for PNG predictor; each value >= 10 (not only 15) indicates usage of PNG predictor
                if (predictor >= 10)
                {
                    // PNG predictor; each row starts with predictor type (0, 1, 2, 3, 4)
                    linepredictor = data.read();// read per line predictor
                    if (linepredictor == -1)
                    {
                        done = true;// reached EOF
                        break;
                    }
                    else
                    {
                        linepredictor += 10; // add 10 to tread value 0 as 10, 1 as 11, ...
                    }
                }

                // read line
                int i, offset = 0;
                while (offset < rowlength && ((i = data.read(actline, offset, rowlength - offset)) != -1))
                {
                    offset += i;
                }

                // do prediction as specified in PNG-Specification 1.2
                switch (linepredictor)
                {
                    case 2:// PRED TIFF SUB
                        // TODO decode tiff with bitsPerComponent != 8;
                        // e.g. for 4 bpc each nibble must be subtracted separately
                        if (bitsPerComponent != 8)
                        {
                            throw new IOException("TIFF-Predictor with " + bitsPerComponent
                                    + " bits per component not supported");
                        }
                        // for 8 bits per component it is the same algorithm as PRED SUB of PNG format
                        for (int p = 0; p < rowlength; p++)
                        {
                            int sub = actline[p] & 0xff;
                            int left = p - bytesPerPixel >= 0 ? actline[p - bytesPerPixel] & 0xff : 0;
                            actline[p] = (byte) (sub + left);
                        }
                        break;
                    case 10:// PRED NONE
                        // do nothing
                        break;
                    case 11:// PRED SUB
                        for (int p = 0; p < rowlength; p++)
                        {
                            int sub = actline[p];
                            int left = p - bytesPerPixel >= 0 ? actline[p - bytesPerPixel] : 0;
                            actline[p] = (byte) (sub + left);
                        }
                        break;
                    case 12:// PRED UP
                        for (int p = 0; p < rowlength; p++)
                        {
                            int up = actline[p] & 0xff;
                            int prior = lastline[p] & 0xff;
                            actline[p] = (byte) ((up + prior) & 0xff);
                        }
                        break;
                    case 13:// PRED AVG
                        for (int p = 0; p < rowlength; p++)
                        {
                            int avg = actline[p] & 0xff;
                            int left = p - bytesPerPixel >= 0 ? actline[p - bytesPerPixel] & 0xff : 0;
                            int up = lastline[p] & 0xff;
                            actline[p] = (byte) ((avg + (int) Math.floor((left + up) / 2)) & 0xff);
                        }
                        break;
                    case 14:// PRED PAETH
                        for (int p = 0; p < rowlength; p++)
                        {
                            int paeth = actline[p] & 0xff;
                            int a = p - bytesPerPixel >= 0 ? actline[p - bytesPerPixel] & 0xff : 0;// left
                            int b = lastline[p] & 0xff;// upper
                            int c = p - bytesPerPixel >= 0 ? lastline[p - bytesPerPixel] & 0xff : 0;// upperleft
                            int value = a + b - c;
                            int absa = Math.abs(value - a);
                            int absb = Math.abs(value - b);
                            int absc = Math.abs(value - c);

                            if (absa <= absb && absa <= absc)
                            {
                                actline[p] = (byte) ((paeth + a) & 0xff);
                            }
                            else if (absb <= absc)
                            {
                                actline[p] = (byte) ((paeth + b) & 0xff);
                            }
                            else
                            {
                                actline[p] = (byte) ((paeth + c) & 0xff);
                            }
                        }
                        break;
                    default:
                        break;
                }
                lastline = actline.clone();
                baos.write(actline, 0, actline.length);
            }
        }
        return baos.toByteArray();
    }

}
