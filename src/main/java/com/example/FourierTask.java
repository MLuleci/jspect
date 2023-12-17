package com.example;

import java.io.File;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyListProperty;
import javafx.beans.property.ReadOnlyListWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;

public class FourierTask extends Task<ObservableList<FourierTask.Slice>>
{
    public class Slice
    {
        public final long index;
        public final long total; // number of slices
        public final double[] freq;
        public final double[] amps;
        public final double duration; // seconds

        public Slice(Complex[] coeff, double duration, long index, long total)
        {
            int n = coeff.length / 2;
            this.duration = duration;
            this.index = index;
            this.total = total;
            this.freq = new double[n];
            this.amps = new double[n];
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;

            // Compute frequency and amplitude
            for (int k = 0; k < n; k++)
            {
                freq[k] = k / duration;
                amps[k] = 10.0 * Math.log(coeff[k].abs() / n);
                min = Math.min(amps[k], min);
                max = Math.max(amps[k], max);
            }

            // Normalise to [0, 1]
            double range = max - min;
            for (int i = 0; i < n; i++)
            {
                amps[i] = (amps[i] - min) / range;
            }
        }
    }

    private final File file;
    private ReadOnlyListWrapper<Slice> partialResults 
        = new ReadOnlyListWrapper<>(FXCollections.observableArrayList());
    
    public FourierTask(File f)
    {
        this.file = f;
    }

    public final ObservableList<Slice> getPartialResults() 
    {
        return partialResults.get();
    }

    public final ReadOnlyListProperty<Slice> partialResultsProperty()
    {
        return partialResults.getReadOnlyProperty();
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
    public ObservableList<Slice> call() throws Exception
    {
        // Open file stream
        updateMessage("Reading...");
        AudioInputStream in = AudioSystem.getAudioInputStream(file);
        
        // Parse audio format
        AudioFormat format = in.getFormat();
        int bytesPerFrame = format.getFrameSize();
        if (bytesPerFrame == AudioSystem.NOT_SPECIFIED)
        {
            bytesPerFrame = 1;
        }
        final int numChannels = format.getChannels();
        final int bytesPerSample = format.getSampleSizeInBits() / 8;
        final long numFrames = in.getFrameLength();
        final double frameRate = (double)format.getFrameRate();
        final int sliceSize = 1024;
        final long numSlices = Math.ceilDiv(numFrames, sliceSize);
        
        final int numBytes = sliceSize * bytesPerFrame;
        byte[] audioBytes = new byte[numBytes];
        int sliceIndex = 0;
        int numBytesRead = 0;
        int numFramesRead = 0;
        int totalFramesRead = 0;
        while ((numBytesRead = in.read(audioBytes)) != -1)
        {
            if (isCancelled())
            {
                break;
            }

            numFramesRead = numBytesRead / bytesPerFrame;
            totalFramesRead += numFramesRead;

            // Group samples by channel
            int[][] channels = new int[numChannels][numFramesRead];
            int frame = 0;
            for (int i = 0; i < numBytesRead; i += bytesPerFrame)
            {
                int channel = 0;
                for (int j = 0; j < bytesPerFrame; j += bytesPerSample)
                {
                    int sample = 0;
                    for (int k = 0; k < bytesPerSample; k++)
                    {
                        int shift = k * 8;
                        if (format.isBigEndian())
                        {
                            shift = (bytesPerSample - k) * 8;
                        }

                        int index = i + j + k;
                        sample |= (audioBytes[index] & 0xFF) << shift;
                    }
                    channels[channel][frame] = sample;
                    channel++;
                }
                frame++;
            }
        
            // Construct (padded) input
            final int paddedSize = 1 << (int)(Math.ceil(Math.log(numFramesRead) / Math.log(2)));
            Complex[] samples = new Complex[paddedSize];
            for (int i = 0; i < numFramesRead; i++)
            {
                samples[i] = new Complex(channels[0][i]);
            }

            for (int i = numFramesRead; i < paddedSize; i++)
            {
                samples[i] = new Complex(0);
            }

            // Apply Fourier transform (in-place)
            transform(samples);
            double duration = (double)numFramesRead / frameRate;
            Slice s = new Slice(samples, duration, sliceIndex++, numSlices);

            // Update UI
            Platform.runLater(new Runnable() {
                @Override
                public void run() {
                    partialResults.get().add(s);
                }
            });
            updateProgress(totalFramesRead, numFrames);
        }
        updateMessage(String.format("Read %d frames", totalFramesRead));

        return this.partialResults.get();
    }
}