package org.apache.pdfboxandroid.io;

import java.io.IOException;

public interface RandomAccessRead extends SequentialRead {
	/**
     * Returns offset of next byte to be returned by a read method.
     * 
     * @return offset of next byte which will be returned with next {@link #read()}
     *         (if no more bytes are left it returns a value >= length of source)
     *         
     * @throws IOException 
     */
    public long getPosition() throws IOException;
	
	/**
     * Seek to a position in the data.
     *
     * @param position The position to seek to.
     * @throws IOException If there is an error while seeking.
     */
    public void seek(long position) throws IOException;
    
    /**
     * The total number of bytes that are available.
     *
     * @return The number of bytes available.
     *
     * @throws IOException If there is an IO error while determining the
     * length of the data stream.
     */
    public long length() throws IOException;
}