package org.jnesb.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.jnesb.audio.AudioOutput;
import org.jnesb.bus.NesBus;
import org.jnesb.cartridge.Cartridge;
import org.jnesb.cpu.Cpu6502;
import org.jnesb.input.NesController;
import org.jnesb.input.NesController.Button;
import org.jnesb.input.NesZapper;
import org.jnesb.ppu.Ppu2C02;

public final class JavaFxNesEmulator extends Application {

    private static final int DISPLAY_SCALE = 3;
    private static final long FRAME_NANOS = 16_666_667L;

    private static NesBus sharedBus;
    private static CountDownLatch exitLatch;
    private static Path sharedRomPath;

    private NesBus bus;
    private Ppu2C02 ppu;
    private volatile boolean running;
    private Thread emulationThread;

    private Canvas canvas;
    private StackPane canvasHolder;
    private WritableImage frameImage;
    private int[] rgbBuffer;
    private NesController controller1;
    private NesZapper zapper;
    private final Map<KeyCode, Button> keyBindings = new EnumMap<>(KeyCode.class);
    private AudioOutput audioOutput;
    private Thread audioThread;
    private volatile boolean audioThreadRunning;
    private Stage primaryStage;
    private MenuBar menuBar;
    private Label footerLabel;
    private boolean paused;
    private boolean menuPaused;
    private int menuPauseDepth;

    public static void launchWith(NesBus bus, Path romPath) throws InterruptedException {
        synchronized (JavaFxNesEmulator.class) {
            if (sharedBus != null) {
                throw new IllegalStateException("Emulator already running");
            }
            sharedBus = Objects.requireNonNull(bus, "bus");
            exitLatch = new CountDownLatch(1);
            sharedRomPath = romPath;
        }
        Application.launch(JavaFxNesEmulator.class);
        exitLatch.await();
    }

    @Override
    public void start(Stage stage) {
        this.bus = Objects.requireNonNull(sharedBus, "sharedBus");
        this.primaryStage = stage;
        this.ppu = bus.ppu();
        this.controller1 = bus.controller(0);
        this.zapper = bus.zapper();
        this.rgbBuffer = new int[Ppu2C02.SCREEN_WIDTH * Ppu2C02.SCREEN_HEIGHT];
        this.frameImage = new WritableImage(Ppu2C02.SCREEN_WIDTH, Ppu2C02.SCREEN_HEIGHT);
        this.canvas = new Canvas(Ppu2C02.SCREEN_WIDTH * DISPLAY_SCALE, Ppu2C02.SCREEN_HEIGHT * DISPLAY_SCALE);
        configureDefaultKeyBindings();
        startAudioPipeline();

        canvasHolder = new StackPane(canvas);
        canvasHolder.setBackground(new Background(new BackgroundFill(Color.BLACK, null, null)));
        BorderPane root = new BorderPane();
        root.setCenter(canvasHolder);
        menuBar = buildMenuBar();
        root.setTop(menuBar);
        footerLabel = new Label(buildFooterText());
        footerLabel.setPadding(new Insets(4, 8, 4, 8));
        root.setBottom(footerLabel);
        stage.setTitle(buildWindowTitle());
        Scene scene = new Scene(root);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (handleGlobalShortcut(event)) {
                event.consume();
                return;
            }
            handleKey(event, true);
        });
        scene.addEventFilter(KeyEvent.KEY_RELEASED, event -> handleKey(event, false));
        canvas.setOnMouseMoved(this::handleMouseMove);
        canvas.setOnMouseDragged(this::handleMouseMove);
        canvas.setOnMousePressed(this::handleMousePressed);
        canvas.setOnMouseReleased(this::handleMouseReleased);
        canvas.setOnMouseExited(event -> {
            if (zapper != null) {
                zapper.aimAt(-1, -1);
            }
        });
        
        // Fix the cursor to a crosshair for better aiming with the Zapper
        // canvas.setCursor(Cursor.CROSSHAIR);
        
        stage.setScene(scene);
        stage.setResizable(false);
        stage.setOnCloseRequest(event -> running = false);
        stage.show();
        stage.fullScreenProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(() -> {
                    updateCanvasSize();
                    updateFullScreenUi();
                }));
        canvasHolder.widthProperty().addListener((obs, oldVal, newVal) -> updateCanvasSize());
        canvasHolder.heightProperty().addListener((obs, oldVal, newVal) -> updateCanvasSize());
        updateCanvasSize();
        updateFullScreenUi();

        startEmulationThread();
    }

    @Override
    public void stop() {
        stopEmulationThread();
        synchronized (JavaFxNesEmulator.class) {
            sharedBus = null;
            if (exitLatch != null) {
                exitLatch.countDown();
            }
            sharedRomPath = null;
        }
        stopAudioPipeline();
    }

    private void runLoop() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        long nextFrameTarget = System.nanoTime() + FRAME_NANOS;

        while (running) {
            bus.clock();
            if (ppu.isFrameComplete()) {
                ppu.copyScreenRgb(rgbBuffer);
                ppu.clearFrameFlag();

                int[] argbFrame = convertToArgb(rgbBuffer);
                Platform.runLater(() -> drawFrame(gc, frameImage, argbFrame));

                long now = System.nanoTime();
                if (now < nextFrameTarget) {
                    LockSupport.parkNanos(nextFrameTarget - now);
                }
                nextFrameTarget = Math.max(nextFrameTarget + FRAME_NANOS, System.nanoTime());
            }
        }
    }

    private void configureDefaultKeyBindings() {
        keyBindings.put(KeyCode.W, Button.A);
        keyBindings.put(KeyCode.X, Button.B);
        keyBindings.put(KeyCode.Q, Button.SELECT);
        keyBindings.put(KeyCode.S, Button.START);
        keyBindings.put(KeyCode.UP, Button.UP);
        keyBindings.put(KeyCode.DOWN, Button.DOWN);
        keyBindings.put(KeyCode.LEFT, Button.LEFT);
        keyBindings.put(KeyCode.RIGHT, Button.RIGHT);
    }

    private void handleKey(KeyEvent event, boolean pressed) {
        Button mapped = keyBindings.get(event.getCode());
        if (mapped != null) {
            controller1.setButton(mapped, pressed);
            event.consume();
        }
    }

    private static void drawFrame(GraphicsContext gc, WritableImage image, int[] argbPixels) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        image.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getIntArgbPreInstance(), argbPixels, 0, width);
        gc.clearRect(0, 0, gc.getCanvas().getWidth(), gc.getCanvas().getHeight());
        gc.drawImage(image, 0, 0, gc.getCanvas().getWidth(), gc.getCanvas().getHeight());
    }

    private static int[] convertToArgb(int[] rgbPixels) {
        int[] argb = Arrays.copyOf(rgbPixels, rgbPixels.length);
        for (int i = 0; i < argb.length; i++) {
            argb[i] = (0xFF << 24) | (argb[i] & 0x00FFFFFF);
        }
        return argb;
    }

    private MenuBar buildMenuBar() {
        Menu gameMenu = new Menu("Game");
        MenuItem loadGame = new MenuItem("Load Game");
        loadGame.setOnAction(event -> handleLoadGame());
        MenuItem resetGame = new MenuItem("Reset");
        resetGame.setOnAction(event -> handleResetGame());
        gameMenu.getItems().addAll(loadGame, resetGame);
        registerMenuForAutoPause(gameMenu);

        Menu displayMenu = new Menu("Display");
        MenuItem fullScreenItem = new MenuItem("Full Screen");
        fullScreenItem.setOnAction(event -> toggleFullScreen());
        displayMenu.getItems().add(fullScreenItem);
        registerMenuForAutoPause(displayMenu);

        Menu inputMenu = new Menu("Input");
        Menu configureMenu = new Menu("Configure");
        MenuItem configurePlayer1 = new MenuItem("Input 1st player");
        configurePlayer1.setOnAction(event -> showInputConfigurationDialog(0));
        MenuItem configurePlayer2 = new MenuItem("Input 2nd player");
        configurePlayer2.setOnAction(event -> showInputConfigurationDialog(1));
        configureMenu.getItems().addAll(configurePlayer1, configurePlayer2);
        inputMenu.getItems().add(configureMenu);
        registerMenuForAutoPause(inputMenu);

        Menu debugMenu = new Menu("Debug");
        MenuItem inputRegisterItem = new MenuItem("Input Register");
        inputRegisterItem.setOnAction(event -> showInputRegisterDialog());
        MenuItem cpuRegisterItem = new MenuItem("CPU register");
        cpuRegisterItem.setOnAction(event -> showCpuRegisterDialog());
        debugMenu.getItems().addAll(inputRegisterItem, cpuRegisterItem);
        registerMenuForAutoPause(debugMenu);

        return new MenuBar(gameMenu, displayMenu, inputMenu, debugMenu);
    }
    
    private String buildFooterText() {
        String gameName = sharedRomPath != null && sharedRomPath.getFileName() != null
                ? sharedRomPath.getFileName().toString()
                : "No game loaded";
        return " Game: " + gameName + " ";
    }

    private String buildWindowTitle() {
        String gameName = sharedRomPath != null && sharedRomPath.getFileName() != null
                ? sharedRomPath.getFileName().toString()
                : "No ROM";
        return "jNESb - " + gameName;
    }

    private void handleLoadGame() {
        FileChooser chooser = createRomFileChooser();
        setMenuPausedTemporarily(true);
        File selected;
        try {
            selected = chooser.showOpenDialog(primaryStage);
        } finally {
            setMenuPausedTemporarily(false);
        }
        if (selected != null) {
            loadGame(selected.toPath());
        }
    }

    private void handleResetGame() {
        if (bus != null) {
            bus.reset();
        }
    }

    private void showInputConfigurationDialog(int playerIndex) {
        NesController controller = bus.controller(playerIndex);
        Map<Button, Boolean> states = controller.snapshotButtonStates();
        StringBuilder content = new StringBuilder();
        states.forEach((button, pressed) ->
                content.append(button.name()).append(" : ").append(pressed ? "pressed" : "released").append('\n'));
        showInfoAlert("Input Configuration",
                "Player " + (playerIndex + 1) + " bindings (read-only)",
                content.toString());
    }

    private void showInputRegisterDialog() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            NesController controller = bus.controller(i);
            builder.append("Player ").append(i + 1).append('\n');
            controller.snapshotButtonStates().forEach((button, pressed) ->
                    builder.append("  ")
                            .append(button.name())
                            .append(" : ")
                            .append(pressed ? "1" : "0")
                            .append('\n'));
            builder.append('\n');
        }
        showInfoAlert("Input Register", "Controller button states", builder.toString());
    }

    private void showCpuRegisterDialog() {
        Cpu6502 cpu = bus.cpu();
        String content = "PC : $" + formatHex(cpu.pc, 4)
                + "\nA  : $" + formatHex(cpu.a, 2)
                + "\nX  : $" + formatHex(cpu.x, 2)
                + "\nY  : $" + formatHex(cpu.y, 2)
                + "\nSP : $" + formatHex(cpu.stkp, 2)
                + "\nP  : $" + formatHex(cpu.status, 2);
        showInfoAlert("CPU register", "6502 register snapshot", content);
    }

    private void showInfoAlert(String title, String header, String content) {
        Alert alert = new Alert(AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        if (primaryStage != null) {
            alert.initOwner(primaryStage);
        }
        alert.showAndWait();
    }

    private void toggleFullScreen() {
        if (primaryStage == null) {
            return;
        }
        primaryStage.setFullScreen(!primaryStage.isFullScreen());
        Platform.runLater(() -> {
            updateCanvasSize();
            updateFullScreenUi();
        });
    }

    private void showErrorAlert(String title, String header, String content) {
        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        if (primaryStage != null) {
            alert.initOwner(primaryStage);
        }
        alert.showAndWait();
    }

    private static String formatHex(int value, int width) {
        int bits = Math.min(width * 4, 16);
        int mask = (1 << bits) - 1;
        return String.format("%0" + width + "X", value & mask);
    }


    private FileChooser createRomFileChooser() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select NES ROM");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("NES ROM (*.nes)", "*.nes"));
        if (sharedRomPath != null && sharedRomPath.getParent() != null) {
            File parent = sharedRomPath.getParent().toFile();
            if (parent.exists()) {
                chooser.setInitialDirectory(parent);
            }
        }
        return chooser;
    }

    private void loadGame(Path romPath) {
        if (romPath == null) {
            return;
        }
        if (bus == null) {
            showErrorAlert("Load Game", "NES bus unavailable", "Cannot load a ROM right now.");
            return;
        }

        final Cartridge cartridge;
        try {
            cartridge = Cartridge.load(romPath);
        } catch (IOException ex) {
            showErrorAlert("Load Game Failed", "Could not load ROM",
                    "Failed to load " + romPath + ":\n" + ex.getMessage());
            return;
        }

        if (!cartridge.isImageValid()) {
            showErrorAlert("Load Game Failed", "Invalid ROM",
                    "The file \"" + romPath.getFileName() + "\" is not a valid NES ROM.");
            return;
        }

        boolean wasRunning = running && !paused;
        stopEmulationThread();
        stopAudioPipeline();
        bus.insertCartridge(cartridge);
        bus.reset();
        sharedRomPath = romPath;
        updateWindowDecorations();
        startAudioPipeline();
        if (wasRunning) {
            if (!menuPaused) {
                startEmulationThread();
            }
        }
    }

    private void updateWindowDecorations() {
        if (footerLabel != null) {
            footerLabel.setText(buildFooterText());
        }
        if (primaryStage != null) {
            primaryStage.setTitle(buildWindowTitle());
        }
        updateCanvasSize();
        updateFullScreenUi();
    }

    private void startEmulationThread() {
        if (running || bus == null) {
            return;
        }
        running = true;
        paused = false;
        emulationThread = new Thread(this::runLoop, "jNESb-Emulation");
        emulationThread.setDaemon(true);
        emulationThread.start();
    }

    private void stopEmulationThread() {
        running = false;
        paused = true;
        if (emulationThread != null && emulationThread.isAlive() && emulationThread != Thread.currentThread()) {
            try {
                emulationThread.join(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        emulationThread = null;
    }

    private void registerMenuForAutoPause(Menu menu) {
        menu.setOnShowing(event -> setMenuPausedTemporarily(true));
        menu.setOnHidden(event -> setMenuPausedTemporarily(false));
    }

    private void updateMenuPauseFlag() {
        boolean newMenuPaused = menuPauseDepth > 0;
        if (menuPaused != newMenuPaused) {
            menuPaused = newMenuPaused;
            applyPauseState();
        }
    }

    private void setMenuPausedTemporarily(boolean engage) {
        if (engage) {
            menuPauseDepth++;
        } else {
            menuPauseDepth = Math.max(0, menuPauseDepth - 1);
        }
        updateMenuPauseFlag();
    }

    private void applyPauseState() {
        boolean desiredPause = menuPaused;
        if (desiredPause && !paused) {
            paused = true;
            stopEmulationThread();
        } else if (!desiredPause && paused) {
            paused = false;
            startEmulationThread();
        }
    }

    private void startAudioPipeline() {
        if (audioOutput == null) {
            audioOutput = new AudioOutput();
            audioOutput.start();
        }
        startAudioThread();
    }

    private void stopAudioPipeline() {
        stopAudioThread();
        if (audioOutput != null) {
            audioOutput.close();
            audioOutput = null;
        }
    }

    private void startAudioThread() {
        if (audioThread != null) {
            return;
        }
        audioThreadRunning = true;
        audioThread = new Thread(this::runAudioLoop, "jNESb-Audio");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    private void stopAudioThread() {
        audioThreadRunning = false;
        if (audioThread != null) {
            audioThread.interrupt();
            try {
                audioThread.join(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
        }
    }

    private void runAudioLoop() {
        while (audioThreadRunning) {
            double sample = bus.pollAudioSample();
            if (audioOutput != null) {
                audioOutput.submitSample(sample);
            }
        }
    }

    private void handleMouseMove(MouseEvent event) {
        if (zapper == null) {
            return;
        }
        updateZapperAim(event);
    }

    private void handleMousePressed(MouseEvent event) {
        if (zapper == null || event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        zapper.setTriggerPressed(true);
        updateZapperAim(event);
    }

    private void handleMouseReleased(MouseEvent event) {
        if (zapper == null || event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        zapper.setTriggerPressed(false);
        updateZapperAim(event);
    }

    private void updateZapperAim(MouseEvent event) {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        double scaleX = width / Ppu2C02.SCREEN_WIDTH;
        double scaleY = height / Ppu2C02.SCREEN_HEIGHT;
        if (scaleX == 0 || scaleY == 0) {
            return;
        }
        double eventX = event.getX();
        double eventY = event.getY();
        int targetX = (int) Math.floor(eventX / scaleX);
        int targetY = (int) Math.floor(eventY / scaleY);
        zapper.aimAt(targetX, targetY);
    }

    private void updateCanvasSize() {
        if (canvasHolder == null || canvas == null) {
            return;
        }
        double baseWidth = Ppu2C02.SCREEN_WIDTH * DISPLAY_SCALE;
        double baseHeight = Ppu2C02.SCREEN_HEIGHT * DISPLAY_SCALE;
        boolean fullScreen = primaryStage != null && primaryStage.isFullScreen();
        if (fullScreen) {
            double availableWidth = Math.max(1.0, canvasHolder.getWidth());
            double availableHeight = Math.max(1.0, canvasHolder.getHeight());
            double scale = Math.min(
                    availableWidth / Ppu2C02.SCREEN_WIDTH,
                    availableHeight / Ppu2C02.SCREEN_HEIGHT);
            if (Double.isFinite(scale) && scale > 0.0) {
                canvas.setWidth(Ppu2C02.SCREEN_WIDTH * scale);
                canvas.setHeight(Ppu2C02.SCREEN_HEIGHT * scale);
                return;
            }
        }
        canvas.setWidth(baseWidth);
        canvas.setHeight(baseHeight);
    }

    private void updateFullScreenUi() {
        if (primaryStage == null) {
            return;
        }
        boolean fullScreen = primaryStage.isFullScreen();
        if (menuBar != null) {
            menuBar.setVisible(!fullScreen);
            menuBar.setManaged(!fullScreen);
        }
        if (footerLabel != null) {
            footerLabel.setVisible(!fullScreen);
            footerLabel.setManaged(!fullScreen);
        }
    }

    private boolean handleGlobalShortcut(KeyEvent event) {
        if (event.isControlDown()) {
            if (event.getCode() == KeyCode.Q) {
                Platform.exit();
                return true;
            }
            if (event.getCode() == KeyCode.F) {
                toggleFullScreen();
                return true;
            }
        }
        return false;
    }
}
