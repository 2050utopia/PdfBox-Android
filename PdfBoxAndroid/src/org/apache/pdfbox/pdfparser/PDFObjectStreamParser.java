package org.apache.pdfbox.pdfparser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;

/**
 * This will parse a PDF 1.5 object stream and extract all of the objects from the stream.
 *
 * @author <a href="mailto:ben@benlitchfield.com">Ben Litchfield</a>
 * @version $Revision: 1.6 $
 */
public class PDFObjectStreamParser extends BaseParser
{
    /**
     * Log instance.
     */
    private static final Log LOG =
        LogFactory.getLog(PDFObjectStreamParser.class);

    private List<COSObject> streamObjects = null;
    private List<Long> objectNumbers = null;
    private COSStream stream;

    /**
     * Constructor.
     *
     * @param strm The stream to parse.
     * @param doc The document for the current parsing.
     * @throws IOException If there is an error initializing the stream.
     */
    public PDFObjectStreamParser(COSStream strm, COSDocument doc) throws IOException
    {
        super(strm.getUnfilteredStream());
        setDocument(doc);
        stream = strm;
    }

    /**
     * This will parse the tokens in the stream.  This will close the
     * stream when it is finished parsing.
     *
     * @throws IOException If there is an error while parsing the stream.
     */
    public void parse() throws IOException
    {
        try
        {
            //need to first parse the header.
            int numberOfObjects = stream.getInt( "N" );
            objectNumbers = new ArrayList<Long>( numberOfObjects );
            streamObjects = new ArrayList<COSObject>( numberOfObjects );
            for( int i=0; i<numberOfObjects; i++ )
            {
                long objectNumber = readObjectNumber();
                long offset = readLong();
                objectNumbers.add( objectNumber);
            }
            COSObject object = null;
            COSBase cosObject = null;
            int objectCounter = 0;
            while( (cosObject = parseDirObject()) != null )
            {
                object = new COSObject(cosObject);
                object.setGenerationNumber( COSInteger.ZERO );
                if (objectCounter >= objectNumbers.size())
                {
                    LOG.error("/ObjStm (object stream) has more objects than /N " + numberOfObjects);
                    break;
                }
                COSInteger objNum =
                    COSInteger.get( objectNumbers.get( objectCounter).intValue() );
                object.setObjectNumber( objNum );
                streamObjects.add( object );
                if(LOG.isDebugEnabled())
                {
                    LOG.debug( "parsed=" + object );
                }
                objectCounter++;
            }
        }
        finally
        {
            pdfSource.close();
        }
    }

    /**
     * This will get the objects that were parsed from the stream.
     *
     * @return All of the objects in the stream.
     */
    public List<COSObject> getObjects()
    {
        return streamObjects;
    }
}
