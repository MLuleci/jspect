package com.example;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyListWrapper;

public class Worker extends Thread 
{
    final Manager.Context context;
    final ReadOnlyListWrapper<double[]> slices;

    public Worker(Manager.Context c, ReadOnlyListWrapper<double[]> s)
    {
        this.context = c;
        this.slices = s;
    }

    private static int reverse(int num, int lg_n)
    {
        int rev = 0;
        for (int i = 0; i < lg_n; i++)
        {
            if ((num & (1 << i)) != 0)
            {
                rev |= 1 << (lg_n - 1 - i);
            }
        }
        return rev;
    }

    // https://en.wikipedia.org/wiki/Cooley%E2%80%93Tukey_FFT_algorithm
    // https://cp-algorithms.com/algebra/fft.html
    private static Complex[] transform(Complex[] a)
    {
        // Compute log2(a)
        int n = a.length;
        int lg_n = 0;
        while ((1 << lg_n) < n)
        {
            lg_n++;
        }

        // Bit-reverse copy
        for (int i = 0; i < n; i++)
        {
            int r = reverse(i, lg_n);
            if (i < r) // Don't re-swap after half (incl. middle)
            {
                Complex tmp = a[i];
                a[i] = a[r];
                a[r] = tmp;
            }
        }

        for (int l = 2; l <= n; l <<= 1) // length of sub-DFT
        {
            double arg = -2 * Math.PI / l;
            Complex wl = new Complex(Math.cos(arg), Math.sin(arg));
            for (int j = 0; j < n; j += l) // index of sub-DFT
            {
                Complex w = new Complex(1);
                for (int k = 0; k < l / 2; k++)
                {
                    Complex u = a[k + j]; // E_k
                    Complex t = w.multiply(a[k + j + l / 2]); // O_k * w_k
                    a[k + j] = u.add(t); // X_k
                    a[k + j + l / 2] = u.subtract(t); // X_{k + l / 2}
                    w = w.multiply(wl); // w_{k + 1}
                }
            }
        }

        return a;
    }

    @Override
    public void run()
    {
        final int numFrames = context.fileFormat.getFrameLength();
        final int samplesPerSlice = (int)Math.ceil((double)numFrames / (double)context.pixels);

        final int n = context.chunkSize;
        int end = 0;
        int start = 0;
        int previous = 0;
        int numSamples = 0;
        int numSlices = 0;
        int numChunks = 0;
        
        // Initialise buffer
        Complex[] buffer = new Complex[n];
        for (int i = 0; i < n; i++)
        {
            buffer[i] = new Complex();
        }

        try
        {
            while (context.isRunning())
            {
                end = context.awaitWork(end);
                if (end == -1)
                {
                    return; // Reader wants us to quit
                }

                while (true)
                {
                    start = (start + 1) % context.buffer.length;
                    if (start == end)
                    {
                        start = previous;
                        break;
                    }
                    numSamples++;

                    // If we have enough samples to either:
                    // 1. Fill an entire slice
                    // 2. Fill an entire chunk
                    // Total number of frames is divided equally(-ish) between all pixels in the canvas -> slice
                    // Arbitrary size of frames used to perform FFT (to ensure sufficient samples) -> chunk
                    // Here we process whichever comes first. If slice > chunk, then perform Welch's method w/ 
                    // multiple chunks (FFTs). Otherwise, use a single FFT (w/ possible overlap) per slice.
                    boolean sliceFull = numSamples == samplesPerSlice;
                    if (numSamples % n == 0 || sliceFull)
                    {
                        previous = start;

                        // Prepare input
                        Complex[] chunk = new Complex[n];
                        for (int i = 0; i < n; i++)
                        {
                            int index = (context.buffer.length + start - n + i) % context.buffer.length;
                            double d = context.buffer[index];
                            d *= 0.53836 - 0.46164 * Math.cos(2 * Math.PI * i / n); // Hamming window
                            chunk[i] = new Complex(d);
                        }

                        // Perform FFT
                        transform(chunk);
                        numChunks++;

                        // Accumulate result
                        for (int i = 0; i < n; i++)
                        {
                            buffer[i] = buffer[i].add(chunk[i]);
                        }
                    }

                    if (sliceFull)
                    {
                        // Average multiple FFTs (if any) & reset buffer
                        double[] slice = new double[n];
                        double min = Double.POSITIVE_INFINITY;
                        double max = Double.NEGATIVE_INFINITY;
                        for (int i = 0; i < n; i++)
                        {
                            Complex c = buffer[i].divideBy(numChunks);
                            double d = 10.0 * Math.log(c.abs() / n);
                            slice[i] = d;
                            buffer[i] = new Complex();
                            min = Math.min(d, min);
                            max = Math.max(d, max);
                        }

                        // Normalise to [0, 1]
                        double range = max - min;
                        for (int i = 0; i < n; i++)
                        {
                            slice[i] = (slice[i] - min) / range;
                        }

                        // Notify UI
                        if (numSlices == context.pixels)
                        {
                            break; // Processed all slices
                        }
                        Platform.runLater(new Runnable() {
                            @Override
                            public void run() {
                                slices.get().add(slice);
                            }
                        });
                        numSlices++;
                        numSamples = 0;
                        numChunks = 0;
                    }
                }
            }
        }
        catch (InterruptedException e)
        {
            return;
        }
    }
}
