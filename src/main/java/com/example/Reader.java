package com.example;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

public class Reader extends Thread 
{
    final Manager.Context context;

    public Reader(Manager.Context c)
    {
        this.context = c;
    }

    @Override
    public void run()
    {
        try
        {
            // Open stream
            AudioInputStream in = AudioSystem.getAudioInputStream(context.file);
            
            // Parse format
            AudioFormat format = in.getFormat();
            int bytesPerFrame = format.getFrameSize();
            if (bytesPerFrame == AudioSystem.NOT_SPECIFIED)
            {
                bytesPerFrame = 1;
            }
            final int bitsPerSample = format.getSampleSizeInBits();
            final int bytesPerSample = bitsPerSample / 8;
            // final int maxValue = (1 << bitsPerSample) - 1;

            // Allocate intermediate buffer (1/2 size of context's buffer)
            final int readSize = context.chunkSize * context.numChunks;
            final byte[] buffer = new byte[bytesPerFrame * readSize];

            int numBytesRead = 0;
            int position = 0;
            int previous = 0;
            while (context.isRunning())
            {
                // Read data
                if ((numBytesRead = in.read(buffer)) == -1)
                {
                    break;
                }

                final int channel = 0; // Use channel #0
                for (int i = 0; i < numBytesRead; i += bytesPerFrame)
                {
                    // Interpret bytes into sample
                    int sample = 0;
                    for (int j = 0; j < bytesPerSample; j++)
                    {
                        int shift = j * 8;
                        if (format.isBigEndian())
                        {
                            shift = (bytesPerSample - j) * 8;
                        }
                        int index = i + bytesPerSample * channel + j;
                        sample |= (buffer[index] & 0xFF) << shift;
                    }
                    context.buffer[position] = (double)sample;
                    position = (position + 1) % context.buffer.length;

                    // Signal worker if we have enough data
                    if ((position > previous ? position : context.buffer.length + position) - previous == readSize)
                    {
                        context.signalWorker(previous = position);
                    }
                }
            }

            // Process left-over data
            if (position != previous)
            {
                context.signalWorker(position);
            }

            // Tell worker to quit
            context.signalWorker(-1);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
