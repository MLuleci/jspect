package com.example;

import java.io.File;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;

import javafx.beans.property.ReadOnlyListProperty;
import javafx.beans.property.ReadOnlyListWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class Manager 
{
    private Thread reader;
    private Thread worker;
    private Context context;
    private ReadOnlyListWrapper<double[]> slices
        = new ReadOnlyListWrapper<>(FXCollections.observableArrayList());

    public class Context
    {
        // Constants
        public final File file;
        public final AudioFileFormat fileFormat;
        public final AudioFormat audioFormat;
        public final int pixels;
        public final int chunkSize = 1024;
        public final int numChunks = 32; // Chunks to pre-load

        // Common state
        public final double[] buffer = new double[chunkSize * (numChunks * 2 + 1)];
        
        // Internal state
        private boolean isRunning = true;
        private int position = 0;
        private boolean workerDone = true;
        private final Lock readerMutex = new ReentrantLock();
        private final Lock workerMutex = new ReentrantLock();
        private final Condition notFull = readerMutex.newCondition(); 
        private final Condition notEmpty = workerMutex.newCondition();

        public Context(File file, AudioFileFormat fileFormat, int pixels)
        {
            this.file = file;
            this.fileFormat = fileFormat;
            this.audioFormat = fileFormat.getFormat();
            this.pixels = pixels;
        }

        public synchronized void setRunning(boolean value)
        {
            isRunning = value;
        }

        public synchronized boolean isRunning()
        {
            return isRunning;
        }

        public void signalWorker(int position) throws InterruptedException
        {
            // Wait until worker is done
            readerMutex.lock();
            while (!workerDone)
            {
                notFull.await();
            }
            this.workerDone = false;
            readerMutex.unlock();

            // Assign more work
            workerMutex.lock();
            this.position = position;
            notEmpty.signal();
            workerMutex.unlock();
        }

        public int awaitWork(int previous) throws InterruptedException
        {
            // Allow reader to continue
            readerMutex.lock();
            this.workerDone = true;
            notFull.signal();
            readerMutex.unlock();

            // Wait for new work
            workerMutex.lock();
            while (previous == this.position)
            {
                notEmpty.await();
            }
            previous = this.position; // temp. storage
            workerMutex.unlock();

            return previous;
        }
    }

    public ObservableList<double[]> getSlices() 
    {
        return slices.get();
    }

    public ReadOnlyListProperty<double[]> partialResultsProperty()
    {
        return slices.getReadOnlyProperty();
    }

    
    private void join(Thread t)
    {
        if (t == null)
        {
            return;
        }

        final long startTime = System.currentTimeMillis();
        final long patience = 30 * 1000;
        try
        {
            while (t.isAlive())
            {
                t.join(1000);
                if ((System.currentTimeMillis() - startTime) > patience && t.isAlive())
                {
                    t.interrupt();
                    t.join();
                }
            }
        }
        catch (InterruptedException e)
        {
            t.interrupt();
        }
    }

    public void start(File file, AudioFileFormat format, int pixels)
    {
        slices.clear();
        context = new Context(file, format, pixels);
        reader = new Reader(context);
        worker = new Worker(context, slices);

        reader.start();
        worker.start();
    }

    public void stop()
    {
        if (context != null)
        {
            context.setRunning(false);
        }

        this.join(reader);
        this.join(worker);
    }
}
