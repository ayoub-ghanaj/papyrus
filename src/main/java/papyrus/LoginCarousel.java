package papyrus;

import javafx.animation.*;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import java.util.*;

/** A local, accessible image carousel. All animation stays on the JavaFX thread. */
final class LoginCarousel extends StackPane {
    private final List<Image> images;
    private final ImageView front = new ImageView();
    private final ImageView back = new ImageView();
    private final Label title = new Label();
    private final Label description = new Label();
    private final List<ToggleButton> dots = new ArrayList<>();
    private final Timeline timer;
    private final I18n i18n;
    private int index;
    private boolean paused;
    private FadeTransition fade;
    private final Button pause = new Button("Ⅱ");

    LoginCarousel(I18n i18n) {
        this.i18n = i18n;
        images = List.of("library", "desk", "alcove").stream().map(name -> new Image(Objects.requireNonNull(
                getClass().getResourceAsStream("/papyrus/carousel/" + name + ".png")))).toList();
        getStyleClass().add("login-carousel"); setMinWidth(320); setPrefWidth(530); setMinHeight(420);
        for (ImageView view : List.of(front, back)) { view.fitWidthProperty().bind(widthProperty()); view.fitHeightProperty().bind(heightProperty()); }
        Rectangle clip = new Rectangle(); clip.widthProperty().bind(widthProperty()); clip.heightProperty().bind(heightProperty()); clip.setArcWidth(28); clip.setArcHeight(28); setClip(clip);
        Region shade = new Region(); shade.getStyleClass().add("carousel-shade"); shade.setMouseTransparent(true);
        title.getStyleClass().add("carousel-title"); description.getStyleClass().add("carousel-description"); title.setWrapText(true); description.setWrapText(true);
        VBox captions = new VBox(12, title, description); captions.setMaxHeight(Region.USE_PREF_SIZE); captions.setPadding(new Insets(30, 30, 78, 30)); captions.setMouseTransparent(true);
        StackPane.setAlignment(captions, Pos.BOTTOM_LEFT);
        HBox navigation = new HBox(8); navigation.setAlignment(Pos.CENTER_LEFT); navigation.setMaxHeight(Region.USE_PREF_SIZE); navigation.setPadding(new Insets(0, 25, 20, 25));
        ToggleGroup group = new ToggleGroup();
        for (int slide = 0; slide < images.size(); slide++) {
            int selected = slide;
            ToggleButton dot = new ToggleButton(); dot.getStyleClass().add("carousel-dot"); dot.setToggleGroup(group); dot.setAccessibleText(i18n.text("slide") + " " + (slide + 1));
            dot.setOnAction(event -> { show(selected, true); restart(); }); dots.add(dot); navigation.getChildren().add(dot);
        }
        Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS);
        Button previous = new Button("‹"); previous.setAccessibleText(i18n.text("previousSlide")); previous.setOnAction(event -> { show(index - 1, true); restart(); });
        Button next = new Button("›"); next.setId("next-slide"); next.setAccessibleText(i18n.text("nextSlide")); next.setOnAction(event -> { show(index + 1, true); restart(); });
        for (Button button : List.of(previous, next, pause)) button.getStyleClass().add("carousel-control");
        pause.setOnAction(event -> { paused = !paused; if (paused) timerPause(); else restart(); updatePause(); });
        navigation.getChildren().addAll(space, previous, pause, next); StackPane.setAlignment(navigation, Pos.BOTTOM_LEFT);
        getChildren().addAll(back, front, shade, captions, navigation);
        widthProperty().addListener((o, a, b) -> crop()); heightProperty().addListener((o, a, b) -> crop());
        timer = new Timeline(new KeyFrame(Duration.seconds(7), event -> show(index + 1, true))); timer.setCycleCount(Animation.INDEFINITE);
        show(0, false); updatePause(); timer.play();
    }

    int slideCount() { return images.size(); }
    int currentSlide() { return index; }
    private void timerPause() { timer.pause(); }
    private void restart() { if (!paused) timer.playFromStart(); }
    private void updatePause() { pause.setText(paused ? "▶" : "Ⅱ"); pause.setAccessibleText(i18n.text(paused ? "playSlideshow" : "pauseSlideshow")); }
    private void show(int requested, boolean animate) {
        if (fade != null) fade.stop();
        back.setImage(front.getImage()); front.setImage(images.get(Math.floorMod(requested, images.size()))); index = Math.floorMod(requested, images.size());
        front.setOpacity(animate ? 0 : 1); crop();
        title.setText(i18n.text("carouselTitle" + index)); description.setText(i18n.text("carouselDescription" + index));
        dots.get(index).setSelected(true);
        if (animate) { fade = new FadeTransition(Duration.millis(700), front); fade.setToValue(1); fade.play(); }
    }
    private void crop() {
        double width = Math.max(1, getWidth()), height = Math.max(1, getHeight());
        for (ImageView view : List.of(front, back)) {
            Image image = view.getImage(); if (image == null) continue;
            double ratio = width / height;
            if (image.getWidth() / image.getHeight() > ratio) {
                double cropWidth = image.getHeight() * ratio; view.setViewport(new Rectangle2D((image.getWidth() - cropWidth) / 2, 0, cropWidth, image.getHeight()));
            } else {
                double cropHeight = image.getWidth() / ratio; view.setViewport(new Rectangle2D(0, (image.getHeight() - cropHeight) / 2, image.getWidth(), cropHeight));
            }
        }
    }
    void dispose() { timer.stop(); if (fade != null) fade.stop(); }
}
