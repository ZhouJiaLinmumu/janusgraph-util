package janusgraph.util.batchimport.unsafe.input.reader;


import janusgraph.util.batchimport.unsafe.helps.collection.RawIterator;

import java.io.IOException;

/**
 * Joins multiple {@link CharReadable} into one. There will never be one read which reads from multiple sources.
 * If the end of one source is reached those (smaller amount of) characters are returned as one read and the next
 * read will start reading from the new source.
 *
 * Newline will be injected in between two sources, even if the former doesn't end with such. This to not have the
 * last line in the former and first in the latter to look like one long line, if reading characters off of this
 * reader character by character (w/o knowing that there are multiple sources underneath).
 */
public class MultiReadable implements CharReadable
{
    private final RawIterator<CharReadable,IOException> actual;

    private CharReadable current = EMPTY;
    private boolean requiresNewLine;
    private long previousPosition;

    public MultiReadable(RawIterator<CharReadable,IOException> readers )
    {
        this.actual = readers;
    }

    @Override
    public void close() throws IOException
    {
        closeCurrent();
    }

    private void closeCurrent() throws IOException
    {
        if ( current != null )
        {
            current.close();
        }
    }

    @Override
    public String sourceDescription()
    {
        return current.sourceDescription();
    }

    @Override
    public long position()
    {
        return previousPosition + current.position();
    }

    private boolean goToNextSource() throws IOException
    {
        if ( actual.hasNext() )
        {
            if ( current != null )
            {
                previousPosition += current.position();
            }
            closeCurrent();
            current = actual.next();
            return true;
        }
        return false;
    }

    @Override
    public SectionedCharBuffer read( SectionedCharBuffer buffer, int from ) throws IOException
    {
        while ( true )
        {
            current.read( buffer, from );
            if ( buffer.hasAvailable() )
            {
                // OK we read something from the current reader
                checkNewLineRequirement( buffer.array(), buffer.front() - 1);
                return buffer;
            }

            // Even if there's no line-ending at the end of this source we should introduce one
            // otherwise the last line of this source and the first line of the next source will
            // look like one long line.
            if ( requiresNewLine )
            {
                buffer.append( '\n' );
                requiresNewLine = false;
                return buffer;
            }

            if ( !goToNextSource() )
            {
                break;
            }
            from = buffer.pivot();
        }
        return buffer;
    }

    private void checkNewLineRequirement( char[] array, int lastIndex )
    {
        char lastChar = array[lastIndex];
        requiresNewLine = lastChar != '\n' && lastChar != '\r';
    }

    @Override
    public int read( char[] into, int offset, int length ) throws IOException
    {
        int totalRead = 0;
        while ( totalRead < length )
        {
            int read = current.read( into, offset + totalRead, length - totalRead );
            if ( read == -1 )
            {
                if ( totalRead > 0 )
                {
                    // Something has been read, but we couldn't fulfill the request with the current source.
                    // Return what we've read so far so that we don't mix multiple sources into the same read,
                    // for source traceability reasons.
                    return totalRead;
                }

                if ( !goToNextSource() )
                {
                    break;
                }

                if ( requiresNewLine )
                {
                    into[offset + totalRead] = '\n';
                    totalRead++;
                    requiresNewLine = false;
                }
            }
            else if ( read > 0 )
            {
                totalRead += read;
                checkNewLineRequirement( into, offset + totalRead - 1 );
            }
        }
        return totalRead;
    }

    @Override
    public long length()
    {
        return current.length();
    }
}