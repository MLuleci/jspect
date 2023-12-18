package com.example;

import java.io.File;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;


import javafx.event.EventHandler;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.WorkerStateEvent;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

public class Controller implements ListChangeListener<double[]>
{
    @FXML
    private Button button;

    @FXML
    private Label label;

    @FXML
    private Pane pane;

    private Canvas canvas = new Canvas();
    private Manager manager = new Manager();
    private File file;

    @FXML
    protected void initialize() 
    {
        this.canvas.widthProperty().bind(pane.widthProperty());
        this.canvas.heightProperty().bind(pane.heightProperty());
        pane.getChildren().add(this.canvas);
        
        // Re-start manager on canvas resize
        Controller controller = this;
        canvas.widthProperty().addListener(new ChangeListener<Number>() {
            final Timer timer = new Timer();
            TimerTask task = null;

            @Override
            public void changed(ObservableValue<? extends Number> observable, Number oldValue, Number newValue)
            {
                // Cancel old task
                if (task != null)
                {
                    task.cancel();
                }

                // Schedule new task
                task = new TimerTask()
                {
                    @Override
                    public void run() {
                        Platform.runLater(new Runnable() {
                            @Override
                            public void run() {
                                loadFile(controller.file);
                            }
                        });
                    }
                };
                timer.schedule(task, 200);
            }
        });
    }

    @FXML
    private void handleButtonAction(ActionEvent event)
    {
        Stage stage = (Stage) pane.getScene().getWindow();
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open audio file");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("All Audio", "*.*"),
            new FileChooser.ExtensionFilter("WAV", "*.wav")
        );
        File file = fileChooser.showOpenDialog(stage);
        loadFile(file);
    }

    @FXML
    private void handleDragDetected(MouseEvent event)
    {
        pane.startDragAndDrop(TransferMode.ANY);
        event.consume();
    }

    @FXML
    private void handleDragOver(DragEvent event)
    {
        if (event.getGestureSource() != pane && event.getDragboard().hasFiles())
        {
            event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
        }
        event.consume();
    }

    @FXML
    private void handleDragDropped(DragEvent event)
    {
        Dragboard db = event.getDragboard();
        boolean success = false;
        if (db.hasFiles())
        {
            for (File file : db.getFiles())
            {
                if ((success = loadFile(file)))
                {
                    break;
                }
            }
        }

        event.setDropCompleted(success);
        event.consume();
    }

    private void setError(String message)
    {
        label.setText(message);
        label.setTextFill(Color.RED);
    }

    private void setInfo(String message)
    {
        label.setText(message);
        label.setTextFill(Color.BLACK);
    }

    private boolean loadFile(File file)
    {
        this.file = file;
        manager.stop();
        if (file != null)
        {
            try
            {
                // Check file format
                AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(file);
                
                // Start background threads
                manager.start(file, fileFormat, (int)canvas.getWidth());
                manager.getSlices().addListener(this);
                
                // Update label
                AudioFormat format = fileFormat.getFormat();
                StringBuilder sb = new StringBuilder();
                sb.append(file.getName() + ": ");
                sb.append(format.getEncoding());
                sb.append(" ");
                sb.append(format.getSampleRate());
                sb.append(" Hz, ");
                sb.append(format.getSampleSizeInBits());
                sb.append(" bit, ");
                switch (format.getChannels())
                {
                    case 1:
                        sb.append("mono");
                        break;
                    case 2:
                        sb.append("stereo");
                        break;
                    default:
                        sb.append(format.getChannels());
                        sb.append(" channels");
                        break;
                }
                setInfo(sb.toString());

                return true;
            }
            catch (Exception ex)
            {
                ex.printStackTrace();
                setError(ex.getMessage());
            }
        }
        else
        {
            setInfo("No file selected");
        }

        return false;
    }

    // https://octave.sourceforge.io/octave/function/hot.html
    private Color getFill(double i)
    {
        int n = (int)Math.floor(i * 256.0); // [0, 255]
        double r = Math.clamp(1.0/96.0 * n, 0.0, 1.0);
        double g = Math.clamp(1.0/96.0 * (n - 96), 0.0, 1.0);
        double b = Math.clamp(1.0/63.0 * (n - 192), 0.0, 1.0);
        return new Color(r, g, b, 1);
    }

    @Override
    public void onChanged(Change<? extends double[]> change)
    {
        final double width = canvas.getWidth();
        final double height = canvas.getHeight();

        GraphicsContext gc = this.canvas.getGraphicsContext2D();
        change.next();

        // List was cleared
        if (change.wasRemoved())
        {
            gc.clearRect(0, 0, width, height);
            return;
        }

        // Slices were added
        ObservableList<? extends double[]> list = change.getList();
        for (int i = change.getFrom(); i < change.getTo(); i++)
        {
            double[] slice = list.get(i);
            int n = slice.length / 2;
            double h = n / height;
            for (int j = 0; j < n; j++)
            {
                gc.setFill(getFill(slice[j]));
                gc.fillRect(i, height - j * h, 1, h);
            }
        }
    }
}
