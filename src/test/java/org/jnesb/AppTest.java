package org.jnesb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

final class AppTest {

    @Test
    void mainRunsWithoutException() {
        assertDoesNotThrow(() -> App.main(new String[0]));
    }
}
