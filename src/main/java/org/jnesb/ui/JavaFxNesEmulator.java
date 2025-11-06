package org.jnesb.ui;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.jnesb.bus.NesBus;
import org.jnesb.input.NesController;
import org.jnesb.input.NesController.Button;
import org.jnesb.ppu.Ppu2C02;

public final class JavaFxNesEmulator extends Application {

    private static final int DISPLAY_SCALE = 3;
    private static final long FRAME_NANOS = 16_666_667L;

    private static NesBus sharedBus;
    private static CountDownLatch exitLatch;

    private NesBus bus;
    private Ppu2C02 ppu;
    private volatile boolean running;
    private Thread emulationThread;

    private Canvas canvas;
    private WritableImage frameImage;
    private int[] rgbBuffer;
    private NesController controller1;
    private final Map<KeyCode, Button> keyBindings = new EnumMap<>(KeyCode.class);

    public static void launchWith(NesBus bus) throws InterruptedException {
        synchronized (JavaFxNesEmulator.class) {
            if (sharedBus != null) {
                throw new IllegalStateException("Emulator already running");
            }
            sharedBus = Objects.requireNonNull(bus, "bus");
            exitLatch = new CountDownLatch(1);
        }
        Application.launch(JavaFxNesEmulator.class);
        exitLatch.await();
    }

    @Override
    public void start(Stage stage) {
        this.bus = Objects.requireNonNull(sharedBus, "sharedBus");
        this.ppu = bus.ppu();
        this.controller1 = bus.controller(0);
        this.rgbBuffer = new int[Ppu2C02.SCREEN_WIDTH * Ppu2C02.SCREEN_HEIGHT];
        this.frameImage = new WritableImage(Ppu2C02.SCREEN_WIDTH, Ppu2C02.SCREEN_HEIGHT);
        this.canvas = new Canvas(Ppu2C02.SCREEN_WIDTH * DISPLAY_SCALE, Ppu2C02.SCREEN_HEIGHT * DISPLAY_SCALE);
        configureDefaultKeyBindings();

        StackPane root = new StackPane(canvas);
        stage.setTitle("jNESb");
        Scene scene = new Scene(root);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> handleKey(event, true));
        scene.addEventFilter(KeyEvent.KEY_RELEASED, event -> handleKey(event, false));
        stage.setScene(scene);
        stage.setResizable(false);
        stage.setOnCloseRequest(event -> running = false);
        stage.show();

        running = true;
        emulationThread = new Thread(this::runLoop, "jNESb-Emulation");
        emulationThread.setDaemon(true);
        emulationThread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (emulationThread != null && emulationThread.isAlive() && emulationThread != Thread.currentThread()) {
            try {
                emulationThread.join(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (JavaFxNesEmulator.class) {
            sharedBus = null;
            if (exitLatch != null) {
                exitLatch.countDown();
            }
        }
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
        keyBindings.put(KeyCode.X, Button.A);
        keyBindings.put(KeyCode.Z, Button.B);
        keyBindings.put(KeyCode.A, Button.SELECT);
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
}
