package org.jnesb.state;

/**
 * Interface for components that can save and restore their state.
 * Used for save state functionality in the emulator.
 */
public interface Stateful {

    /**
     * Saves the current state of this component to a byte array.
     * @return byte array containing the serialized state
     */
    byte[] saveState();

    /**
     * Restores the state of this component from a byte array.
     * @param data byte array containing the serialized state
     */
    void loadState(byte[] data);

    /**
     * Returns the expected size of the state data for this component.
     * @return size in bytes
     */
    int stateSize();
}
