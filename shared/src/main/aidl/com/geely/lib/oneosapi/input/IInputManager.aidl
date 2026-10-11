// Reverse-engineered from the head unit's com.geely.inputservice; transaction order must match.
package com.geely.lib.oneosapi.input;

import com.geely.lib.oneosapi.input.IInputListener;

interface IInputManager {
    boolean interceptKeyCode(int keyCode, String packageName);
    boolean releaseKeyCode(int keyCode, String packageName);
    void registerListener(IInputListener listener, String packageName, in int[] keyCodes);
    void unregisterListener(IInputListener listener, String packageName);
    int getControlIndex();
}
