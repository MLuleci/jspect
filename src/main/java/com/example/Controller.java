package com.example;

import java.io.File;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;

import com.example.FourierTask.Slice;

import javafx.event.EventHandler;
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

public class Controller implements ListChangeListener<Slice>
{
    @FXML
    private Button button;

    @FXML
    private Label label;

    @FXML
    private Pane pane;

    private Canvas canvas = new Canvas();

    @FXML
    protected void initialize() 
    {
        this.canvas.widthProperty().bind(pane.widthProperty());
        this.canvas.heightProperty().bind(pane.heightProperty());
        pane.getChildren().add(this.canvas);
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
        if (file != null)
        {
            try
            {
                // Check file format
                AudioFormat format = AudioSystem.getAudioFileFormat(file).getFormat();

                // Setup UI hooks
                FourierTask task = new FourierTask(file);
                ObservableList<Slice> list = task.getPartialResults();
                ListChangeListener<Slice> listener = this;
                list.addListener(listener);
                task.setOnCancelled(new EventHandler<WorkerStateEvent>() {
                    @Override
                    public void handle(WorkerStateEvent t) {
                        setError("Task cancelled");
                        list.removeListener(listener);
                    }
                });
                task.setOnFailed(new EventHandler<WorkerStateEvent>() {
                    @Override
                    public void handle(WorkerStateEvent t) {
                        setError("Task failed");
                        list.removeListener(listener);
                    }
                });
                task.setOnSucceeded(new EventHandler<WorkerStateEvent>() {
                    @Override
                    public void handle(WorkerStateEvent t) {
                        // Update label
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
                        list.removeListener(listener);
                    }
                });

                // Start background thread
                new Thread(task).start();
                setInfo("Loading...");

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
    public void onChanged(Change<? extends Slice> c)
    {
        final double width = canvas.getWidth();
        final double height = canvas.getHeight();

        GraphicsContext gc = this.canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, width, height);
        gc.setStroke(Color.BLACK);
        gc.setLineWidth(0);

        List<? extends Slice> slices = c.getList();
        for (int i = 0; i < slices.size(); i++)
        {
            Slice s = slices.get(i);
            final int n = s.amps.length;
            for (int j = 0; j < n; j++)
            {
                double amp = s.amps[j];

                double w = (width / s.total);
                double h = height / n;
                double x = w * i;
                double y = height - h * j;

                gc.setFill(getFill(amp));
                gc.fillRect(x, y, w, h);
            }
        }
    }
}
