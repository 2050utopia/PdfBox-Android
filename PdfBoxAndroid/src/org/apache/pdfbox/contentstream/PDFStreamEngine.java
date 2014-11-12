package org.apache.pdfbox.contentstream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorProcessor;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import android.graphics.PointF;

/**
 * Processes a PDF content stream and executes certain operations.
 * Provides a callback interface for clients that want to do things with the stream.
 * 
 * @author Ben Litchfield
 */
public class PDFStreamEngine
{
	private static final Log LOG = LogFactory.getLog(PDFStreamEngine.class);

	private final Map<String, OperatorProcessor> operators = new HashMap<String, OperatorProcessor>();

	private Matrix textMatrix;
	private Matrix textLineMatrix;
	protected Matrix subStreamMatrix = new Matrix();

	private final Stack<PDGraphicsState> graphicsStack = new Stack<PDGraphicsState>();

	private PDResources resources;
	private PDPage currentPage;
	private boolean isProcessingPage;

	// skip malformed or otherwise unparseable input where possible
	private boolean forceParsing;

	/**
	 * Creates a new PDFStreamEngine.
	 */
	public PDFStreamEngine()
	{
	}

	/**
	 * Indicates if force parsing is activated.
	 * 
	 * @return true if force parsing is active
	 */
	public boolean isForceParsing()
	{
		return forceParsing;
	}

	/**
	 * Enable/Disable force parsing.
	 * 
	 * @param forceParsingValue true activates force parsing
	 */
	public void setForceParsing(boolean forceParsingValue)
	{
		forceParsing = forceParsingValue;
	}

	/**
	 * Register a custom operator processor with the engine.
	 * 
	 * @param operator The operator as a string.
	 * @param op Processor instance.
	 * @deprecated Use {@link #addOperator(OperatorProcessor)} instead
	 */
	@Deprecated
	public void registerOperatorProcessor(String operator, OperatorProcessor op)
	{
		op.setContext(this);
		operators.put(operator, op);
	}

	/**
	 * Adds an operator processor to the engine.
	 *
	 * @param op operator processor
	 */
	public final void addOperator(OperatorProcessor op)
	{
		op.setContext(this);
		operators.put(op.getName(), op);
	}

	/**
	 * Initialises the stream engine for the given page.
	 */
	private void initPage(PDPage page)
	{
		if (page == null)
		{
			throw new IllegalArgumentException("Page cannot be null");
		}
		currentPage = page;
		graphicsStack.clear();
		graphicsStack.push(new PDGraphicsState(page.getCropBox()));
		textMatrix = null;
		textLineMatrix = null;
		resources = null;
	}

	/**
	 * This will initialise and process the contents of the stream.
	 *
	 * @param page the page to process
	 * @throws IOException if there is an error accessing the stream
	 */
	public void processPage(PDPage page) throws IOException
	{
		initPage(page);
		if (page.getStream() != null)
		{
			isProcessingPage = true;
			processStream(page);
			isProcessingPage = false;
		}
	}

	/**
	 * Shows a transparency group from the content stream.
	 *
	 * @param form transparency group (form) XObject
	 * @throws IOException if the transparency group cannot be processed
	 */
	public void showTransparencyGroup(PDFormXObject form) throws IOException
	{
		showForm(form);
	}

	/**
	 * Shows a form from the content stream.
	 *
	 * @param form form XObject
	 * @throws IOException if the form cannot be processed
	 */
	public void showForm(PDFormXObject form) throws IOException
	{
		processChildStream(form);
	}

	/**
	 * Process a child stream of the current page. For use with #processPage(PDPage).
	 *
	 * @param contentStream the child content stream
	 * @throws IOException if there is an exception while processing the stream
	 */
	public void processChildStream(PDContentStream contentStream) throws IOException
	{
		if (currentPage == null)
		{
			throw new IllegalStateException("No current page, call " +
					"#processChildStream(PDContentStream, PDPage) instead");
		}
		processStream(contentStream);
	}

	// todo: a temporary workaround for tiling patterns (overrides matrix and bbox)
	public final void processChildStreamWithMatrix(PDTilingPattern contentStream, PDPage page,
			Matrix matrix, PDRectangle bbox) throws IOException
	{
		initPage(page);

		// transform ctm
		Matrix concat = matrix.multiply(getGraphicsState().getCurrentTransformationMatrix());
		getGraphicsState().setCurrentTransformationMatrix(concat);

		processStream(contentStream, bbox);
		currentPage = null;
	}

	/**
	 * Process a child stream of the given page. Cannot be used with #processPage(PDPage).
	 *
	 * @param contentStream the child content stream
	 * @throws IOException if there is an exception while processing the stream
	 */
	protected void processChildStream(PDContentStream contentStream, PDPage page) throws IOException
	{
		if (isProcessingPage)
		{
			throw new IllegalStateException("Current page has already been set via " +
					" #processPage(PDPage) call #processChildStream(PDContentStream) instead");
		}
		initPage(page);
		processStream(contentStream);
		currentPage = null;
	}

	/**
	 * Process a content stream.
	 *
	 * @param contentStream the content stream
	 * @throws IOException if there is an exception while processing the stream
	 */
	private void processStream(PDContentStream contentStream) throws IOException
	{
		processStream(contentStream, null);
	}

	/**
	 * Process a content stream.
	 *
	 * @param contentStream the content stream
	 * @param patternBBox fixme: temporary workaround for tiling patterns
	 * @throws IOException if there is an exception while processing the stream
	 */
	private void processStream(PDContentStream contentStream, PDRectangle patternBBox) throws IOException
	{
		// resource lookup: first look for stream resources, then fallback to the current page
		PDResources parentResources = resources;
		PDResources streamResources = contentStream.getResources();
		if (streamResources != null)
		{
			resources = streamResources;
		}
		else
		{
			resources = currentPage.getResources();
		}

		// bounding box (for clipping)
		PDRectangle bbox = contentStream.getBBox();
		if (patternBBox  !=null)
		{
			bbox = patternBBox;
		}
		if (contentStream != currentPage && bbox != null)
		{
			//            Area clip = new Area(new GeneralPath(new Rectangle(bbox.createDimension()))); TODO
			//        	clip.transform(getGraphicsState().getCurrentTransformationMatrix().createAffineTransform());
			saveGraphicsState();
			//            getGraphicsState().intersectClippingPath(clip);
		}

		// fixme: stream matrix
		Matrix oldSubStreamMatrix = subStreamMatrix;
		subStreamMatrix = getGraphicsState().getCurrentTransformationMatrix();

		List<COSBase> arguments = new ArrayList<COSBase>();
		PDFStreamParser parser = new PDFStreamParser(contentStream.getContentStream(), forceParsing);
		try
		{
			Iterator<Object> iter = parser.getTokenIterator();
			while (iter.hasNext())
			{
				Object token = iter.next();
				if (token instanceof COSObject)
				{
					arguments.add(((COSObject) token).getObject());
				}
				else if (token instanceof Operator)
				{
					processOperator((Operator) token, arguments);
					arguments = new ArrayList<COSBase>();
				}
				else
				{
					arguments.add((COSBase) token);
				}
			}
		}
		finally
		{
			parser.close();
		}

		if (contentStream != currentPage && bbox != null)
		{
			restoreGraphicsState();
		}
		
		// restore page resources
		resources = parentResources;

		// fixme: stream matrix
		subStreamMatrix = oldSubStreamMatrix;
	}

	/**
	 * Called when the BT operator is encountered. This method is for overriding in subclasses, the
	 * default implementation does nothing.
	 *
	 * @throws IOException if there was an error processing the text
	 */
	public void beginText() throws IOException
	{
		// overridden in subclasses
	}

	/**
	 * Called when the ET operator is encountered. This method is for overriding in subclasses, the
	 * default implementation does nothing.
	 *
	 * @throws IOException if there was an error processing the text
	 */
	public void endText() throws IOException
	{
		// overridden in subclasses
	}

	/**
	 * Called when a string of text is to be shown.
	 *
	 * @param string the encoded text
	 * @throws IOException if there was an error showing the text
	 */
	public void showTextString(byte[] string) throws IOException
	{
		showText(string);
	}

	/**
	 * Called when a string of text with spacing adjustments is to be shown.
	 *
	 * @param array array of encoded text strings and adjustments
	 * @throws IOException if there was an error showing the text
	 */
	public void showTextStrings(COSArray array) throws IOException
	{
		PDTextState textState = getGraphicsState().getTextState();
		float fontSize = textState.getFontSize();
		float horizontalScaling = textState.getHorizontalScaling() / 100f;
		boolean isVertical = textState.getFont().isVertical();

		for (COSBase obj : array)
		{
			if (obj instanceof COSNumber)
			{
				float tj = ((COSNumber)obj).floatValue();

				// calculate the combined displacements
				float tx, ty;
				if (isVertical)
				{
					tx = 0;
					ty = -tj / 1000 * fontSize;
				}
				else
				{
					tx = -tj / 1000 * fontSize * horizontalScaling;
					ty = 0;
				}

				applyTextAdjustment(tx, ty);
			}
			else if(obj instanceof COSString)
			{
				byte[] string = ((COSString)obj).getBytes();
				showText(string);
			}
			else
			{
				throw new IOException("Unknown type in array for TJ operation:" + obj);
			}
		}
	}

	/**
	 * Applies a text position adjustment from the TJ operator. May be overridden in subclasses.
	 *
	 * @param tx x-translation
	 * @param ty y-translation
	 */
	protected void applyTextAdjustment(float tx, float ty) throws IOException
	{
		// update the text matrix
		textMatrix.concatenate(Matrix.getTranslatingInstance(tx, ty));
	}

	/**
	 * Process text from the PDF Stream. You should override this method if you want to
	 * perform an action when encoded text is being processed.
	 *
	 * @param string the encoded text
	 * @throws IOException if there is an error processing the string
	 */
	protected void showText(byte[] string) throws IOException
	{
		PDGraphicsState state = getGraphicsState();
		PDTextState textState = state.getTextState();

		// get the current font
		PDFont font = textState.getFont();
		if (font == null)
		{
			LOG.warn("No current font, will use default");
			font = PDFontFactory.createDefaultFont();
		}

		float fontSize = textState.getFontSize();
		float horizontalScaling = textState.getHorizontalScaling() / 100f;
		float charSpacing = textState.getCharacterSpacing();

		// put the text state parameters into matrix form
		Matrix parameters = new Matrix(
				fontSize * horizontalScaling, 0, // 0
				0, fontSize,                     // 0
				0, textState.getRise());         // 1

		// read the stream until it is empty
		InputStream in = new ByteArrayInputStream(string);
		while (in.available() > 0)
		{
			// decode a character
			int before = in.available();
			int code = font.readCode(in);
			int codeLength = before - in.available();
			String unicode = font.toUnicode(code);

			// Word spacing shall be applied to every occurrence of the single-byte character code
			// 32 in a string when using a simple font or a composite font that defines code 32 as
			// a single-byte code.
			float wordSpacing = 0;
			if (codeLength == 1 && code == 32)
			{
				wordSpacing += textState.getWordSpacing();
			}

			// text rendering matrix (text space -> device space)
			Matrix ctm = state.getCurrentTransformationMatrix();
			Matrix textRenderingMatrix = parameters.multiply(textMatrix).multiply(ctm);

			// get glyph's position vector if this is vertical text
			// changes to vertical text should be tested with PDFBOX-2294 and PDFBOX-1422
			if (font.isVertical())
			{
				// position vector, in text space
				Vector v = font.getPositionVector(code);

				// apply the position vector to the horizontal origin to get the vertical origin
				textRenderingMatrix.translate(v);
			}

			// get glyph's horizontal and vertical displacements, in text space
			Vector w = font.getDisplacement(code);

			// process the decoded glyph
			showGlyph(textRenderingMatrix, font, code, unicode, w);

			// calculate the combined displacements
			float tx, ty;
			if (font.isVertical())
			{
				tx = 0;
				ty = w.getY() * fontSize + charSpacing + wordSpacing;
			}
			else
			{
				tx = (w.getX() * fontSize + charSpacing + wordSpacing) * horizontalScaling;
				ty = 0;
			}

			// update the text matrix
			textMatrix.concatenate(Matrix.getTranslatingInstance(tx, ty));
		}
	}

	/**
	 * Called when a glyph is to be processed.This method is intended for overriding in subclasses,
	 * the default implementation does nothing.
	 *
	 * @param textRenderingMatrix the current text rendering matrix, T<sub>rm</sub>
	 * @param font the current font
	 * @param code internal PDF character code for the glyph
	 * @param unicode the Unicode text for this glyph, or null if the PDF does provide it
	 * @param displacement the displacement (i.e. advance) of the glyph in text space
	 * @throws IOException if the glyph cannot be processed
	 */
	protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, String unicode,
			Vector displacement) throws IOException
	{
		// overridden in subclasses
	}

	/**
	 * This is used to handle an operation.
	 * 
	 * @param operation The operation to perform.
	 * @param arguments The list of arguments.
	 * @throws IOException If there is an error processing the operation.
	 */
	public void processOperator(String operation, List<COSBase> arguments) throws IOException
	{
		Operator operator = Operator.getOperator(operation);
		processOperator(operator, arguments);
	}

	/**
	 * This is used to handle an operation.
	 * 
	 * @param operator The operation to perform.
	 * @param arguments The list of arguments.
	 * @throws IOException If there is an error processing the operation.
	 */
	protected void processOperator(Operator operator, List<COSBase> arguments) throws IOException
	{
		String name = operator.getName();
		OperatorProcessor processor = operators.get(name);
		if (processor != null)
		{
			processor.setContext(this);
			processor.process(operator, arguments);
		}
		else
		{
			unsupportedOperator(operator, arguments);
		}
	}

	/**
	 * Called when an unsupported operator is encountered.
	 *
	 * @param operator The unknown operator.
	 * @param arguments The list of arguments.
	 */
	protected void unsupportedOperator(Operator operator, List<COSBase> arguments) throws IOException
	{
		// overridden in subclasses
	}

	/**
	 * Pushes the current graphics state to the stack.
	 */
	public void saveGraphicsState()
	{
		graphicsStack.push(graphicsStack.peek().clone());
	}

	/**
	 * Pops the current graphics state from the stack.
	 */
	public void restoreGraphicsState()
	{
		graphicsStack.pop();
	}

	/**
	 * @return Returns the size of the graphicsStack.
	 */
	public int getGraphicsStackSize()
	{
		return graphicsStack.size();
	}

	/**
	 * @return Returns the graphicsState.
	 */
	public PDGraphicsState getGraphicsState()
	{
		return graphicsStack.peek();
	}

	/**
	 * @return Returns the textLineMatrix.
	 */
	public Matrix getTextLineMatrix()
	{
		return textLineMatrix;
	}

	/**
	 * @param value The textLineMatrix to set.
	 */
	public void setTextLineMatrix(Matrix value)
	{
		textLineMatrix = value;
	}

	/**
	 * @return Returns the textMatrix.
	 */
	public Matrix getTextMatrix()
	{
		return textMatrix;
	}

	/**
	 * @param value The textMatrix to set.
	 */
	public void setTextMatrix(Matrix value)
	{
		textMatrix = value;
	}

	/**
	 * Returns the subStreamMatrix.
	 */
	protected Matrix getSubStreamMatrix()
	{
		return subStreamMatrix;
	}

	/**
	 * Returns the stream' resources.
	 */
	public PDResources getResources()
	{
		return resources;
	}

	/**
	 * Returns the current page.
	 */
	public PDPage getCurrentPage()
	{
		return currentPage;
	}

	/**
	 * use the current transformation matrix to transformPoint a single point.
	 *
	 * @param x x-coordinate of the point to be transformPoint
	 * @param y y-coordinate of the point to be transformPoint
	 * @return the transformed coordinates as Point2D.Double
	 */
	public PointF transformedPoint(double x, double y)
	{
		float[] position = { (float)x, (float)y };
		getGraphicsState().getCurrentTransformationMatrix().createAffineTransform().mapPoints(position);
		return new PointF(position[0], position[1]);
	}

	/**
	 * use the current transformation matrix to transformPoint a PDRectangle.
	 * 
	 * @param rect the PDRectangle to transformPoint
	 * @return the transformed coordinates as a GeneralPath
	 */
	//    public GeneralPath transformedPDRectanglePath(PDRectangle rect)
	//    {
	//        float x1 = rect.getLowerLeftX();
	//        float y1 = rect.getLowerLeftY();
	//        float x2 = rect.getUpperRightX();
	//        float y2 = rect.getUpperRightY();
	//        Point2D p0 = transformedPoint(x1, y1);
	//        Point2D p1 = transformedPoint(x2, y1);
	//        Point2D p2 = transformedPoint(x2, y2);
	//        Point2D p3 = transformedPoint(x1, y2);
	//        GeneralPath path = new GeneralPath();
	//        path.moveTo((float) p0.getX(), (float) p0.getY());
	//        path.lineTo((float) p1.getX(), (float) p1.getY());
	//        path.lineTo((float) p2.getX(), (float) p2.getY());
	//        path.lineTo((float) p3.getX(), (float) p3.getY());
	//        path.closePath();
	//        return path;
	//    }TODO

	// transforms a width using the CTM
	protected float transformWidth(float width)
	{
		Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
		float x = ctm.getValue(0, 0) + ctm.getValue(1, 0);
		float y = ctm.getValue(0, 1) + ctm.getValue(1, 1);
		return width * (float)Math.sqrt((x * x + y * y) * 0.5);
	}
}
