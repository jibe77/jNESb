module org.jnesb {
    requires javafx.graphics;
    requires javafx.controls;
    requires javafx.base;
    requires java.desktop;

    exports org.jnesb;
    exports org.jnesb.apu;
    exports org.jnesb.audio;
    exports org.jnesb.bus;
    exports org.jnesb.cartridge;
    exports org.jnesb.cpu;
    exports org.jnesb.input;
    exports org.jnesb.ppu;
    exports org.jnesb.ui;

    opens org.jnesb.ui to javafx.graphics;
}
