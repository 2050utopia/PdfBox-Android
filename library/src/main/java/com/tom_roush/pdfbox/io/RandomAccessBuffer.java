package com.tom_roush.pdfbox.io;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * An implementation of the RandomAccess interface to store a pdf in memory.
 * The data will be stored in chunks organized in an ArrayList.
 *
 */
public class RandomAccessBuffer implements RandomAccess, Closeable, Cloneable
{
    // default chunk size is 1kb
    private static final int DEFAULT_CHUNK_SIZE = 1024;
    // use the default chunk size
    private int chunk_size = DEFAULT_CHUNK_SIZE;
    // list containing all chunks
    private List<byte[]> bufferList = null;
    // current chunk
    private byte[] currentBuffer;
    // current pointer to the whole buffer
    private long pointer;
    // current pointer for the current chunk
    private long currentBufferPointer;
    // size of the whole buffer
    private long size;
    // current chunk list index
    private int bufferListIndex;
    // maximum chunk list index
    private int bufferListMaxIndex;

    /**
     * Default constructor.
     */
    public RandomAccessBuffer()
    {
        // starting with one chunk
        bufferList = new ArrayList<byte[]>();
        currentBuffer = new byte[chunk_size];
        bufferList.add(currentBuffer);
        pointer = 0;
        currentBufferPointer = 0;
        size = 0;
        bufferListIndex = 0;
        bufferListMaxIndex = 0;
    }

    /**
     * Create a random access buffer using the given byte array.
     *
     * @param input the byte array to be read
     */
    public RandomAccessBuffer(byte[] input)
    {
        // this is a special case. The given byte array is used as the one
        // and only chunk.
        bufferList = new ArrayList<byte[]>(1);
        chunk_size = input.length;
        currentBuffer = input;
        bufferList.add(currentBuffer);
        pointer = 0;
        currentBufferPointer = 0;
        size = chunk_size;
        bufferListIndex = 0;
        bufferListMaxIndex = 0;
    }

    /**
     * Create a random access buffer of the given input stream by copying the data.
     *
     * @param input the input stream to be read
     * @throws IOException if something went wrong while copying the data
     */
    public RandomAccessBuffer(InputStream input) throws IOException
    {
        this();
        byte[] byteBuffer = new byte[8192];
        int bytesRead = 0;
        while ((bytesRead = input.read(byteBuffer)) > -1)
        {
            write(byteBuffer, 0, bytesRead);
        }
        seek(0);
    }

    @Override
    public RandomAccessBuffer clone()
    {
        RandomAccessBuffer copy = new RandomAccessBuffer();

        copy.bufferList = new ArrayList<byte[]>(bufferList.size());
        for (byte [] buffer : bufferList)
        {
            byte [] newBuffer = new byte [buffer.length];
            System.arraycopy(buffer,0,newBuffer,0,buffer.length);
            copy.bufferList.add(newBuffer);
        }
        if (currentBuffer!=null)
        {
            copy.currentBuffer = copy.bufferList.get(copy.bufferList.size()-1);
        }
        else
        {
            copy.currentBuffer = null;
        }
        copy.pointer = pointer;
        copy.currentBufferPointer = currentBufferPointer;
        copy.size = size;
        copy.bufferListIndex = bufferListIndex;
        copy.bufferListMaxIndex = bufferListMaxIndex;

        return copy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException
    {
        currentBuffer = null;
        bufferList.clear();
        pointer = 0;
        currentBufferPointer = 0;
        size = 0;
        bufferListIndex = 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear()
    {
        bufferList.clear();
        currentBuffer = new byte[chunk_size];
        bufferList.add(currentBuffer);
        pointer = 0;
        currentBufferPointer = 0;
        size = 0;
        bufferListIndex = 0;
        bufferListMaxIndex = 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void seek(long position) throws IOException
    {
        checkClosed();
        pointer = position;
        // calculate the chunk list index
        bufferListIndex = (int) (position / chunk_size);
        currentBufferPointer = position % chunk_size;
        currentBuffer = bufferList.get(bufferListIndex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getPosition() throws IOException
    {
       checkClosed();
       return pointer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read() throws IOException
    {
        checkClosed();
        if (pointer >= this.size)
        {
            return -1;
        }
        if (currentBufferPointer >= chunk_size)
        {
            if (bufferListIndex >= bufferListMaxIndex)
            {
                return -1;
            }
            else
            {
                currentBuffer = bufferList.get(++bufferListIndex);
                currentBufferPointer = 0;
            }
        }
        pointer++;
        return currentBuffer[(int)currentBufferPointer++] & 0xff;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(byte[] b, int offset, int length) throws IOException
    {
        checkClosed();
        if (pointer >= this.size)
        {
            return 0;
        }
        int maxLength = (int) Math.min(length, this.size-pointer);
        long remainingBytes = chunk_size - currentBufferPointer;
        if (remainingBytes == 0)
        {
            nextBuffer();
            remainingBytes = chunk_size;
        }
        if (maxLength >= remainingBytes)
        {
            // copy the first bytes from the current buffer
            System.arraycopy(currentBuffer, (int)currentBufferPointer, b, offset, (int)remainingBytes);
            currentBufferPointer += remainingBytes;
            int newOffset = offset + (int)remainingBytes;
            long remainingBytes2Read = length - remainingBytes;
            // determine how many buffers are needed to get the remaining amount bytes
            int numberOfArrays = (int) remainingBytes2Read / chunk_size;
            for (int i=0;i<numberOfArrays;i++)
            {
                nextBuffer();
                System.arraycopy(currentBuffer, 0, b, newOffset, chunk_size);
                newOffset += chunk_size;
                currentBufferPointer = chunk_size;
            }
            remainingBytes2Read = remainingBytes2Read % chunk_size;
            // are there still some bytes to be read?
            if (remainingBytes2Read > 0)
            {
                nextBuffer();
                System.arraycopy(currentBuffer, 0, b, newOffset, (int)remainingBytes2Read);
                currentBufferPointer = remainingBytes2Read;
            }
        }
        else
        {
            System.arraycopy(currentBuffer, (int)currentBufferPointer, b, offset, maxLength);
            currentBufferPointer += maxLength;
        }
        pointer += maxLength;
        return maxLength;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long length() throws IOException
    {
        checkClosed();
        return size;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(int b) throws IOException
    {
        checkClosed();
        // end of buffer reached?
        if (currentBufferPointer >= chunk_size)
        {
            if (pointer + chunk_size >= Integer.MAX_VALUE)
            {
                throw new IOException("RandomAccessBuffer overflow");
            }
            expandBuffer();
        }
        currentBuffer[(int)currentBufferPointer++] = (byte)b;
        pointer++;
        if (pointer > this.size)
        {
            this.size = pointer;
        }
        // end of buffer reached now?
        if (currentBufferPointer >= chunk_size)
        {
            if (pointer + chunk_size >= Integer.MAX_VALUE)
            {
                throw new IOException("RandomAccessBuffer overflow");
            }
            expandBuffer();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(byte[] b, int offset, int length) throws IOException
    {
        checkClosed();
        long newSize = pointer + length;
        long remainingBytes = chunk_size - currentBufferPointer;
        if (length >= remainingBytes)
        {
            if (newSize > Integer.MAX_VALUE)
            {
                throw new IOException("RandomAccessBuffer overflow");
            }
            // copy the first bytes to the current buffer
            System.arraycopy(b, offset, currentBuffer, (int)currentBufferPointer, (int)remainingBytes);
            int newOffset = offset + (int)remainingBytes;
            long remainingBytes2Write = length - remainingBytes;
            // determine how many buffers are needed for the remaining bytes
            int numberOfNewArrays = (int) remainingBytes2Write / chunk_size;
            for (int i=0;i<numberOfNewArrays;i++)
            {
                expandBuffer();
                System
                    .arraycopy(b, newOffset, currentBuffer, (int) currentBufferPointer, chunk_size);
                newOffset += chunk_size;
            }
            // are there still some bytes to be written?
            remainingBytes2Write -= numberOfNewArrays * (long) chunk_size;
            if (remainingBytes2Write >= 0)
            {
                expandBuffer();
                if (remainingBytes2Write > 0)
                {
                    System.arraycopy(b, newOffset, currentBuffer, (int)currentBufferPointer, (int)remainingBytes2Write);
                }
                currentBufferPointer = remainingBytes2Write;
            }
        }
        else
        {
            System.arraycopy(b, offset, currentBuffer, (int)currentBufferPointer, length);
            currentBufferPointer += length;
        }
        pointer += length;
        if (pointer > this.size)
        {
            this.size = pointer;
        }
    }

    /**
     * create a new buffer chunk and adjust all pointers and indices.
     */
    private void expandBuffer()
    {
        if (bufferListMaxIndex > bufferListIndex)
        {
            // there is already an existing chunk
            nextBuffer();
        }
        else
        {
            // create a new chunk and add it to the buffer
            currentBuffer = new byte[chunk_size];
            bufferList.add(currentBuffer);
            currentBufferPointer = 0;
            bufferListMaxIndex++;
            bufferListIndex++;
        }
    }

    /**
     * switch to the next buffer chunk and reset the buffer pointer.
     */
    private void nextBuffer()
    {
        currentBufferPointer = 0;
        currentBuffer = bufferList.get(++bufferListIndex);
    }

    /**
     * Ensure that the RandomAccessBuffer is not closed
     * @throws IOException
     */
    private void checkClosed () throws IOException
    {
        if (currentBuffer==null)
        {
            // consider that the rab is closed if there is no current buffer
            throw new IOException("RandomAccessBuffer already closed");
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isClosed()
    {
    	return currentBuffer == null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEOF() throws IOException
    {
        int peek = peek();
        return peek == -1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int available() throws IOException
    {
        return (int) Math.min(length() - getPosition(), Integer.MAX_VALUE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int peek() throws IOException
    {
        int result = read();
        if (result != -1)
        {
            rewind(1);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void rewind(int bytes) throws IOException
    {
        checkClosed();
        seek(getPosition() - bytes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] readFully(int length) throws IOException
    {
        byte[] b = new byte[length];
        read(b, 0, length);
        return b;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(byte[] b) throws IOException
    {
        return read(b, 0, b.length);
    }
}
