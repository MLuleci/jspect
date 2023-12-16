package com.example;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class App extends Application {

    private static Scene scene;

    @Override
    public void start(Stage stage) throws IOException
    {
        scene = new Scene(loadFXML("app"), 640, 480);
        stage.setScene(scene);
        stage.setTitle("jspect");
        stage.show();
    }

    private static Parent loadFXML(String fxml) throws IOException
    {
        FXMLLoader loader = new FXMLLoader(App.class.getResource(fxml + ".fxml"));
        return loader.load();
    }

    public static void main(String[] args)
    {
        Application.launch(args);
    }
}